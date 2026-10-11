package com.bibot.app.data.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(
    val id: String, // hash đường dẫn / "m<id>" MediaStore
    val title: String,
    val artist: String,
    val durationMs: Long,
    val path: String,
    val mtime: Long,
    /** content:// MediaStore → app nhạc mặc định phát trực tiếp; null = file riêng của app (chỉ phát trong app). */
    val mediaUri: String? = null
) {
    val isPlayableExternally: Boolean get() = !mediaUri.isNullOrBlank()
}

/**
 * 2.2.6 Music lazy index — port `music.py:66 build_music_index`.
 * Quét filesDir/bibot/music (app-specific, không cần quyền), metadata qua MediaMetadataRetriever
 * (thay `mutagen`, không thêm dep). Cache `.index` JSON {version, generatedAt, dirMtime, tracks[]} —
 * chỉ rebuild khi mtime thư mục đổi (port `_INDEX_MTIME`). Không giữ index RAM > vài KB (đọc khi cần).
 *
 * 12.x: thêm `scanDeviceMusic` (MediaStore — toàn bộ nhạc trên máy, cần READ_MEDIA_AUDIO)
 * + `allTracks` gộp 2 nguồn làm thư viện chính cho module Tìm nhạc.
 */
object MusicIndex {
    private const val VERSION = 1
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "mid", "midi")

    fun indexFile(context: Context): File = File(FilePaths.musicDir(context), ".index")

    /** Quyền đọc thư viện nhạc theo SDK (33+ là READ_MEDIA_AUDIO, dưới đó là READ_EXTERNAL_STORAGE). */
    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /**
     * Toàn bộ nhạc trên máy qua MediaStore (nguồn chính — app nhạc mặc định cũng thấy đúng danh sách).
     * Không có quyền → trả rỗng (không crash). Chạy IO, list vài trăm mục vẫn <50ms.
     */
    suspend fun scanDeviceMusic(context: Context): List<Track> = withContext(Dispatchers.IO) {
        if (!hasAudioPermission(context)) return@withContext emptyList()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val out = mutableListOf<Track>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val title = c.getString(iTitle)?.takeIf { it.isNotBlank() }
                        ?: continue
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    out += Track(
                        id = "m$id",
                        title = title,
                        artist = c.getString(iArtist)?.takeIf { it.isNotBlank() } ?: "Unknown",
                        durationMs = c.getLong(iDur).coerceAtLeast(0L),
                        path = c.getString(iPath) ?: "",
                        mtime = 0L,
                        mediaUri = uri.toString()
                    )
                }
            }
        } catch (_: Exception) { /* không có quyền / MediaStore hỏng → bỏ, giữ nguồn app-dir */ }
        out
    }

    /** Thư viện gộp: nhạc trên máy (MediaStore) + file riêng của app, khử trùng theo path. */
    suspend fun allTracks(context: Context): List<Track> {
        val device = try { scanDeviceMusic(context) } catch (_: Exception) { emptyList() }
        val app = try { scanMusic(context) } catch (_: Exception) { emptyList() }
        if (device.isEmpty()) return app
        val seen = device.mapNotNull { it.path.lowercase().takeIf { p -> p.isNotBlank() } }.toSet()
        return device + app.filter { it.path.lowercase() !in seen }
    }

    suspend fun scanMusic(context: Context, force: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        val dir = FilePaths.musicDir(context)
        val dirMtime = try {
            dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
        } catch (_: Exception) { 0L }
        val idx = indexFile(context)
        if (!force && idx.exists()) {
            try {
                val cached = readIndex(idx)
                if (cached != null && cached.first == dirMtime) return@withContext cached.second
            } catch (_: Exception) { /* index hỏng → quét lại */ }
        }
        val tracks = try {
            dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in AUDIO_EXTS }
                .map { readTrack(it) }.toList()
        } catch (_: Exception) { emptyList() }
        try { writeIndex(idx, dirMtime, tracks) } catch (_: Exception) {}
        tracks
    }

    private fun readTrack(f: File): Track {
        var title = f.nameWithoutExtension
        var artist = "Unknown"
        var dur = 0L
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { dur = it }
        } catch (_: Exception) { /* giữ fallback tên file */ }
        finally { try { mmr.release() } catch (_: Exception) {} }
        return Track(
            id = f.absolutePath.hashCode().toString(),
            title = title, artist = artist, durationMs = dur,
            path = f.absolutePath, mtime = f.lastModified()
        )
    }

    private fun readIndex(idx: File): Pair<Long, List<Track>>? {
        return try {
            val root = JSONObject(idx.readText())
            if (root.optInt("version", -1) != VERSION) return null
            val dirMtime = root.optLong("dirMtime", -1L)
            val tracks = mutableListOf<Track>()
            val arr = root.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tracks += Track(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist", "Unknown"),
                    durationMs = o.optLong("durationMs", 0L),
                    path = o.optString("path"),
                    mtime = o.optLong("mtime", 0L)
                )
            }
            // Chỉ dùng cache khi file còn tồn tại (tránh entry ma)
            val valid = tracks.filter { it.path.isNotBlank() && File(it.path).exists() }
            if (valid.size != tracks.size) null else Pair(dirMtime, valid)
        } catch (_: Exception) { null }
    }

    private fun writeIndex(idx: File, dirMtime: Long, tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject()
                .put("id", t.id).put("title", t.title).put("artist", t.artist)
                .put("durationMs", t.durationMs).put("path", t.path).put("mtime", t.mtime))
        }
        idx.writeText(JSONObject()
            .put("version", VERSION)
            .put("generatedAt", System.currentTimeMillis())
            .put("dirMtime", dirMtime)
            .put("tracks", arr).toString())
    }
}
