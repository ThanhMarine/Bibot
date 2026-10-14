package com.bibot.app.data.summary

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
import com.bibot.app.data.ads.isVipActive
import com.bibot.app.data.books.BookRepository
import com.bibot.app.data.files.FilePaths
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.data.store.BotPromptManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * B5.5: tóm tắt cấp sách chạy nền (map-reduce trong [SummaryRepository.summarizeBook]).
 * Mỗi sách 1 work duy nhất (KEEP) — mở dialog tiến trình quan sát theo tên.
 */
class SummaryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val OUT_TEXT = "summary_text"
        const val OUT_ERROR = "summary_error"
        const val PROG_DONE = "prog_done"
        const val PROG_TOTAL = "prog_total"
        fun uniqueName(bookId: String) = "bibot_summary_$bookId"

        fun schedule(context: Context, bookId: String) {
            val req = OneTimeWorkRequestBuilder<SummaryWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(uniqueName(bookId))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(bookId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, bookId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(bookId))
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
            val adsState = try {
                com.bibot.app.data.ads.AdsPreferences(ctx).adsStateFlow.first()
            } catch (_: Exception) { com.bibot.app.data.ads.AdsState() }

            // Mở prep (có sẵn từ lần đọc) hoặc build streaming như ReaderScreen.
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
            if (titles.isEmpty()) return Result.failure(workDataOf(OUT_ERROR to "empty"))
            val lang = if (book.lang == "en") "en" else "vi"
            val chapters = titles.mapIndexed { idx, t ->
                val sents = try { session.sentences(idx) } catch (_: Exception) { emptyList<String>() }
                val text = sents.joinToString(" ").take(BotPromptManager.SUMMARY_MAX_CHARS)
                Triple(idx, t, text)
            }.filter { it.third.isNotBlank() }
            try { session.close() } catch (_: Exception) {}

            val keys = SummaryRepository.LlmKeys(prefs.geminiApiKey, prefs.geminiModel, prefs.openRouterKey)
            when (val r = SummaryRepository.summarizeBook(
                ctx, bookId, book.title, chapters, lang, keys,
                isVipActive(adsState), prefs.summaryShareEnabled
            ) { done, total -> setProgressAsync(workDataOf(PROG_DONE to done, PROG_TOTAL to total)) }) {
                is SummaryRepository.Outcome.Ready -> Result.success(workDataOf(OUT_TEXT to r.text))
                is SummaryRepository.Outcome.NoCredit -> Result.failure(workDataOf(OUT_ERROR to "nocredit"))
                is SummaryRepository.Outcome.NeedKey -> Result.failure(workDataOf(OUT_ERROR to "nokey"))
                is SummaryRepository.Outcome.Failed -> Result.failure(workDataOf(OUT_ERROR to r.msg))
            }
        } catch (e: Exception) {
            Result.failure(workDataOf(OUT_ERROR to (e.message ?: "error")))
        }
    }
}
