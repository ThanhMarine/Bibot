package com.bibot.app.feature.export

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bibot.app.core.text.BlockTextReader
import com.bibot.app.core.text.BookPrepStore
import com.bibot.app.data.books.BookRepository
import com.bibot.app.data.files.FilePaths
import com.bibot.app.data.offline.OfflineTtsHelper
import com.bibot.app.data.store.BibotPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * A3.1/A3.5 Export audiobook durable — proper WAV + chapter markers (.cue) + ready for M4B.
 * - Single WAV header, all audio data concatenated (not raw WAV concatenation)
 * - Chapter start times tracked for .cue file (chapter markers)
 * - Error terminal → silence 1s + count skipped
 * - Progress read from cache scope (fix expired session 30min)
 * - A3.5: export M4B/MP3 có chapters; scrubber ready-bands (later)
 */
class ExportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val OUT_PATH = "export_path"
        const val OUT_ERROR = "export_error"
        const val PROG_DONE = "prog_done"
        const val PROG_TOTAL = "prog_total"
        const val PROG_SKIPPED = "prog_skipped"
        fun uniqueName(bookId: String) = "bibot_export_$bookId"

        fun schedule(context: Context, bookId: String) {
            val req = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(uniqueName(bookId))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(bookId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, bookId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(bookId))
        }

        fun getProgress(context: Context, bookId: String): androidx.work.WorkInfo? {
            return WorkManager.getInstance(context.applicationContext)
                .getWorkInfoByIdLiveData(WorkManager.getInstance(context.applicationContext)
                    .getWorkInfosByTagLiveData(uniqueName(bookId)).value?.firstOrNull()?.id!!).value
        }
    }

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure(workDataOf(OUT_ERROR to "nobook"))
        return try {
            val ctx = applicationContext
            val repo = BookRepository(ctx)
            try { repo.refreshAll() } catch (_: Exception) {}
            val book = repo.books.value.firstOrNull { it.id == bookId }
                ?: return Result.failure(workDataOf(OUT_ERROR to "notfound"))
            val file = book.localFile?.takeIf { it.exists() }
                ?: return Result.failure(workDataOf(OUT_ERROR to "nodata"))

            val prefs = BibotPreferences(ctx).prefsFlow.first()
            val speed = prefs.voiceSpeed
            val lang = if (book.lang == "en") "en" else "vi"

            // Open prep (cached from reading) or build streaming
            val prepDir = FilePaths.prepDir(ctx)
            val heading = BlockTextReader.Heading.MARKDOWN_OR_STORY
            val opened = try { BookPrepStore.open(prepDir, bookId, file, heading) } catch (_: Exception) { null }
            val session = if (opened != null) {
                val sent = try { BookPrepStore.openSentences(opened) } catch (_: Exception) { null }
                BookPrepStore.PrepSession.of(opened, sent)
            } else {
                val built = try {
                    BookPrepStore.build(prepDir, bookId, file, heading, book.title)
                } catch (_: Exception) { null }
                if (built != null) BookPrepStore.PrepSession.of(built, null)
                else {
                    val chaps = try {
                        BlockTextReader.readOriginalChapters(file, book.title, heading)
                    } catch (_: Exception) { emptyList() }
                    BookPrepStore.PrepSession.inMemory(chaps.map { it.title }, chaps.map { it.content })
                }
            }
            val titles = session.titles
            if (titles.isEmpty()) {
                try { session.close() } catch (_: Exception) {}
                return Result.failure(workDataOf(OUT_ERROR to "empty"))
            }

            // Export directory: external exports visible to user (SAF)
            val exportDir = FilePaths.externalExportDir(ctx)
                ?: return Result.failure(workDataOf(OUT_ERROR to "noexportdir"))
            val safeTitle = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val timestamp = System.currentTimeMillis()
            val wavFile = File(exportDir, "${safeTitle}_$timestamp.wav")
            val cueFile = File(exportDir, "${safeTitle}_$timestamp.cue")

            // Initialize offline TTS
            val offlineTts = OfflineTtsHelper.shared(ctx)
            if (!offlineTts.isAvailable()) {
                val ready = com.bibot.app.ui.player.PlayerViewModel(ctx as android.app.Application).run {
                    var initReady = false
                    offlineTts.ensureInit { initReady = true }
                    kotlinx.coroutines.delay(2000)
                    initReady || offlineTts.isAvailable()
                }
                if (!ready) {
                    try { session.close() } catch (_: Exception) {}
                    return Result.failure(workDataOf(OUT_ERROR to "tts_not_ready"))
                }
            }

            var skipped = 0
            val totalChapters = titles.size
            setProgressAsync(workDataOf(PROG_DONE to 0, PROG_TOTAL to totalChapters, PROG_SKIPPED to 0))

            // A3.5: Track chapter start positions (in samples) for .cue file
            val sampleRate = 44100
            val bytesPerSample = 2 // 16-bit
            val channels = 1
            val bytesPerSecond = sampleRate * bytesPerSample * channels
            var totalSamplesWritten = 0L
            val chapterCuePoints = mutableListOf<ChapterCue>()

            // Initialize output stream with placeholder header (will rewrite at end)
            val outputStream = FileOutputStream(wavFile)
            val dataStartPos = 44 // WAV header size (we'll rewrite)
            outputStream.write(ByteArray(44)) // placeholder header

            try {
                for (chapIdx in titles.indices) {
                    if (isStopped) break

                    val sents = try { session.sentences(chapIdx) } catch (_: Exception) { emptyList<String>() }
                    if (sents.isEmpty()) continue

                    // Record chapter start time (before synthesizing first sentence)
                    val chapterStartSamples = totalSamplesWritten
                    chapterCuePoints.add(ChapterCue(
                        index = chapIdx + 1,
                        title = titles[chapIdx],
                        startSamples = chapterStartSamples,
                        sampleRate = sampleRate
                    ))

                    for (sentIdx in sents.indices) {
                        if (isStopped) break
                        val text = sents[sentIdx]
                        if (text.isBlank()) continue

                        val wavFileTmp = try {
                            withContext(Dispatchers.IO) {
                                offlineTts.synthesizeToFile(text, speed)
                            }
                        } catch (e: Exception) {
                            null
                        }

                        if (wavFileTmp != null && wavFileTmp.exists()) {
                            try {
                                val bytes = wavFileTmp.readBytes()
                                // Skip WAV header (44 bytes) of each synthesized file
                                val audioData = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes
                                outputStream.write(audioData)
                                totalSamplesWritten += (audioData.size / 2 / channels).toLong() // 16-bit = 2 bytes per sample
                                wavFileTmp.delete()
                            } catch (_: Exception) {
                                skipped++
                                val silenceSamples = writeSilenceSamples(outputStream, 1000, sampleRate, bytesPerSample, channels)
                                totalSamplesWritten += silenceSamples
                            }
                        } else {
                            skipped++
                            val silenceSamples = writeSilenceSamples(outputStream, 1000, sampleRate, bytesPerSample, channels)
                            totalSamplesWritten += silenceSamples
                        }
                    }

                    setProgressAsync(workDataOf(
                        PROG_DONE to (chapIdx + 1),
                        PROG_TOTAL to totalChapters,
                        PROG_SKIPPED to skipped
                    ))
                }
            } finally {
                try { outputStream.close() } catch (_: Exception) {}
                try { session.close() } catch (_: Exception) {}
            }

            // Rewrite WAV header with correct sizes
            rewriteWavHeader(wavFile, totalSamplesWritten, sampleRate, bytesPerSample, channels)

            // Write .cue file with chapter markers
            writeCueFile(cueFile, safeTitle, chapterCuePoints)

            if (!wavFile.exists() || wavFile.length() == 0L) {
                return Result.failure(workDataOf(OUT_ERROR to "nooutput"))
            }

            Result.success(workDataOf(
                OUT_PATH to wavFile.absolutePath,
                PROG_SKIPPED to skipped,
                "cue_path" to cueFile.absolutePath
            ))
        } catch (e: Exception) {
            Result.failure(workDataOf(OUT_ERROR to (e.message ?: "error")))
        }
    }

    /** Chapter cue point for .cue file generation */
    private data class ChapterCue(
        val index: Int,
        val title: String,
        val startSamples: Long,
        val sampleRate: Int
    ) {
        fun toCueTime(): String {
            val totalSeconds = startSamples.toDouble() / sampleRate
            val hours = (totalSeconds / 3600).toInt()
            val minutes = ((totalSeconds % 3600) / 60).toInt()
            val seconds = (totalSeconds % 60).toInt()
            val frames = ((totalSeconds - hours * 3600 - minutes * 60 - seconds) * 75).toInt() // 75 frames/sec
            return String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }
    }

    /** Write silence samples directly to output stream, return sample count written */
    private fun writeSilenceSamples(
        outputStream: FileOutputStream,
        durationMs: Int,
        sampleRate: Int,
        bytesPerSample: Int,
        channels: Int
    ): Long {
        val samples = (sampleRate * durationMs / 1000).toLong()
        val byteCount = samples * bytesPerSample * channels
        val silence = ByteArray(byteCount.toInt())
        try {
            outputStream.write(silence)
        } catch (_: Exception) {}
        return samples
    }

    /** Rewrite WAV header at file start with correct data size */
    private fun rewriteWavHeader(
        wavFile: File,
        totalSamples: Long,
        sampleRate: Int,
        bytesPerSample: Int,
        channels: Int
    ) {
        val dataSize = totalSamples * bytesPerSample * channels
        val totalSize = 36 + dataSize
        val ts = totalSize.toInt()

        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
        header[4] = (ts and 0xFF).toByte(); header[5] = ((ts shr 8) and 0xFF).toByte()
        header[6] = ((ts shr 16) and 0xFF).toByte(); header[7] = ((ts shr 24) and 0xFF).toByte()
        header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
        // fmt chunk
        header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0 // PCM
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xFF).toByte(); header[25] = ((sampleRate shr 8) and 0xFF).toByte()
        header[26] = ((sampleRate shr 16) and 0xFF).toByte(); header[27] = ((sampleRate shr 24) and 0xFF).toByte()
        val byteRate = sampleRate * bytesPerSample * channels
        header[28] = (byteRate and 0xFF).toByte(); header[29] = ((byteRate shr 8) and 0xFF).toByte()
        header[30] = ((byteRate shr 16) and 0xFF).toByte(); header[31] = ((byteRate shr 24) and 0xFF).toByte()
        val blockAlign = (bytesPerSample * channels).toShort()
        val ba = blockAlign.toInt()
        header[32] = (ba and 0xFF).toByte(); header[33] = ((ba shr 8) and 0xFF).toByte()
        header[34] = (bytesPerSample * 8).toByte(); header[35] = 0
        // data chunk
        val ds = dataSize.toInt()
        header[36] = 'd'.toByte(); header[37] = 'a'.toByte(); header[38] = 't'.toByte(); header[39] = 'a'.toByte()
        header[40] = (ds and 0xFF).toByte(); header[41] = ((ds shr 8) and 0xFF).toByte()
        header[42] = ((ds shr 16) and 0xFF).toByte(); header[43] = ((ds shr 24) and 0xFF).toByte()

        try {
            FileOutputStream(wavFile).use { it.write(header) }
        } catch (_: Exception) {}
    }

    /** Write .cue file with chapter markers for audiobook navigation */
    private fun writeCueFile(cueFile: File, title: String, cues: List<ChapterCue>) {
        try {
            FileOutputStream(cueFile).use { fos ->
                OutputStreamWriter(fos, "UTF-8").use { writer ->
                    writer.write("PERFORMER \"$title\"\n")
                    writer.write("TITLE \"$title\"\n")
                    writer.write("FILE \"${cueFile.nameWithoutExtension}.wav\" WAVE\n")
                    for (cue in cues) {
                        writer.write("  TRACK ${"%02d".format(cue.index)} AUDIO\n")
                        writer.write("    TITLE \"${cue.title.replace("\"", "\\\"")}\"\n")
                        writer.write("    INDEX 01 ${cue.toCueTime()}:00\n")
                    }
                }
            }
        } catch (_: Exception) {}
    }
}