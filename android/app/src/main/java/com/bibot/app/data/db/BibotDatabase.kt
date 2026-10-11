package com.bibot.app.data.db

import android.content.Context
import androidx.room.*

/**
 * Room DB — thay Pi state/ *.jsonl + *.db
 * Đường dẫn: /data/data/com.bibot.app/databases/bibot.db (Context.getDatabasePath)
 * Entities port từ: conversation_memory.py (3 tầng), book_reader.py, pdf_to_md.py
 */
@Entity(tableName = "memory_core") // Tier0 bất tử — chỉ ghim tay
data class MemoryCore(
    @PrimaryKey val key: String, // ví dụ "user_name", "allergy"
    val value: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_facts") // Tier1 FACTS — TTL, n=3 gợi ý ghim
data class MemoryFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val count: Int = 1, // n lặp
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_log") // Tier2 LOG xoay 500
data class ChatLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // user/assistant
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "book_progress") // Port book_reader.py BOOK_SAVE_EVERY=8
data class BookProgress(
    @PrimaryKey val bookId: String, // hash path
    val path: String,
    val position: Int, // câu index
    val total: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pdf_jobs") // Port mode14_state.db
data class PdfJob(
    @PrimaryKey val pdfHash: String,
    val pdfName: String,
    val status: String, // pending/processing/done/error
    val outPath: String? = null,
    val pageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// A1.2: sidecar per-segment cho audio cache content-addressed (port openreader
// sidecar per-ordinal duration/align/audioKey/status). audioHash = identity duy
// nhất (dedup cùng text ở nhiều vị trí); settingsHash tách theo voice/speed.
@Entity(tableName = "tts_segment_cache")
data class TtsSegmentCache(
    @PrimaryKey val audioHash: String,
    val settingsHash: String,
    val segmentKey: String = "",
    val docId: String = "",
    val ext: String = "mp3", // mp3 (cloud) | wav (piper)
    val bytesLen: Long = 0,
    val durationMs: Long? = null, // đo sau khi phát (ExoPlayer), null = chưa biết
    val status: String = "ready", // ready
    val createdAt: Long = System.currentTimeMillis(),
    val lastHitAt: Long = System.currentTimeMillis()
)

@Dao
interface MemoryCoreDao {
    @Query("SELECT * FROM memory_core") suspend fun all(): List<MemoryCore>
    @Query("SELECT * FROM memory_core WHERE `key`=:k LIMIT 1") suspend fun get(k: String): MemoryCore?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: MemoryCore)
    @Query("DELETE FROM memory_core WHERE `key`=:k") suspend fun delete(k: String)
}

@Dao
interface MemoryFactDao {
    @Query("SELECT * FROM memory_facts ORDER BY updatedAt DESC LIMIT :limit") suspend fun recent(limit: Int = 30): List<MemoryFact>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: MemoryFact)
    @Query("DELETE FROM memory_facts WHERE id=:id") suspend fun delete(id: Long)
    @Query("DELETE FROM memory_facts WHERE updatedAt < :cutoff") suspend fun prune(cutoff: Long)
}

@Dao
interface ChatLogDao {
    @Query("SELECT * FROM chat_log ORDER BY timestamp DESC LIMIT :limit") suspend fun recent(limit: Int = 500): List<ChatLog>
    @Query("SELECT * FROM chat_log ORDER BY timestamp ASC LIMIT :limit") suspend fun oldest(limit: Int): List<ChatLog>
    @Insert suspend fun insert(e: ChatLog)
    @Query("DELETE FROM chat_log WHERE id IN (SELECT id FROM chat_log ORDER BY timestamp ASC LIMIT :n)") suspend fun deleteOldest(n: Int)
    @Query("SELECT COUNT(*) FROM chat_log") suspend fun count(): Int
    // 10.3: mốc merge 2 chiều — chỉ upsert log mới hơn max local (tránh tải lại 500 row)
    @Query("SELECT MAX(timestamp) FROM chat_log") suspend fun maxTimestamp(): Long?
    @Query("SELECT * FROM chat_log WHERE timestamp > :ts ORDER BY timestamp ASC LIMIT 200") suspend fun since(ts: Long): List<ChatLog>
}

@Dao
interface BookProgressDao {
    @Query("SELECT * FROM book_progress WHERE bookId=:id LIMIT 1") suspend fun get(id: String): BookProgress?
    @Query("SELECT * FROM book_progress") suspend fun all(): List<BookProgress>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: BookProgress)
}

// B1.1: kệ sách kiểu ReadEra — sidecar theo bookId (KHÔNG sửa bảng progress cũ).
// shelfFav/shelfToRead/shelfRead: Yêu thích / Đọc sau / Đã đọc (thủ công).
// trashed: Thùng rác (ẩn ở library, chưa xoá file). author/series: parse từ EPUB
// khi đã tải (EpubReader.readMeta), sách chưa tải để trống → nhóm "Chưa rõ".
@Entity(tableName = "library_meta")
data class LibraryMeta(
    @PrimaryKey val bookId: String,
    val shelfFav: Boolean = false,
    val shelfToRead: Boolean = false,
    val shelfRead: Boolean = false,
    val trashed: Boolean = false,
    val lastRead: Long = 0L, // lần mở/tải gần nhất (ms)
    val author: String = "",
    val series: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface LibraryMetaDao {
    @Query("SELECT * FROM library_meta WHERE bookId=:id LIMIT 1") suspend fun get(id: String): LibraryMeta?
    @Query("SELECT * FROM library_meta") suspend fun all(): List<LibraryMeta>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfMissing(e: LibraryMeta)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: LibraryMeta)
    @Query("DELETE FROM library_meta WHERE bookId=:id") suspend fun delete(id: String)
    suspend fun touchOpen(id: String) {
        insertIfMissing(LibraryMeta(bookId = id, lastRead = System.currentTimeMillis()))
        stamp(id, System.currentTimeMillis())
    }
    @Query("UPDATE library_meta SET lastRead=:ts, updatedAt=:ts WHERE bookId=:id")
    suspend fun stamp(id: String, ts: Long)
    @Query("UPDATE library_meta SET shelfFav=:v, updatedAt=:ts WHERE bookId=:id")
    suspend fun setFav(id: String, v: Boolean, ts: Long = System.currentTimeMillis())
    @Query("UPDATE library_meta SET shelfToRead=:v, updatedAt=:ts WHERE bookId=:id")
    suspend fun setToRead(id: String, v: Boolean, ts: Long = System.currentTimeMillis())
    @Query("UPDATE library_meta SET shelfRead=:v, updatedAt=:ts WHERE bookId=:id")
    suspend fun setRead(id: String, v: Boolean, ts: Long = System.currentTimeMillis())
    @Query("UPDATE library_meta SET trashed=:v, updatedAt=:ts WHERE bookId=:id")
    suspend fun setTrashed(id: String, v: Boolean, ts: Long = System.currentTimeMillis())
    @Query("UPDATE library_meta SET author=:a, series=:s, updatedAt=:ts WHERE bookId=:id")
    suspend fun setMeta(id: String, a: String, s: String, ts: Long = System.currentTimeMillis())
}

@Dao
interface PdfJobDao {
    @Query("SELECT * FROM pdf_jobs ORDER BY updatedAt DESC") suspend fun all(): List<PdfJob>
    @Query("SELECT * FROM pdf_jobs WHERE pdfHash=:h LIMIT 1") suspend fun get(h: String): PdfJob?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: PdfJob)
}

@Dao
interface TtsSegmentDao {
    @Query("SELECT * FROM tts_segment_cache WHERE audioHash=:a AND settingsHash=:s LIMIT 1")
    suspend fun get(a: String, s: String): TtsSegmentCache?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: TtsSegmentCache)
    @Query("UPDATE tts_segment_cache SET lastHitAt=:ts WHERE audioHash=:a")
    suspend fun touch(a: String, ts: Long)
    @Query("SELECT COALESCE(SUM(bytesLen),0) FROM tts_segment_cache") suspend fun sumBytes(): Long
    @Query("SELECT * FROM tts_segment_cache ORDER BY lastHitAt ASC LIMIT :n")
    suspend fun oldest(n: Int): List<TtsSegmentCache>
    @Query("DELETE FROM tts_segment_cache WHERE audioHash=:a") suspend fun delete(a: String)
}

@Database(
    entities = [MemoryCore::class, MemoryFact::class, ChatLog::class, BookProgress::class, PdfJob::class, TtsSegmentCache::class, LibraryMeta::class],
    version = 3, exportSchema = false
    // B1.1: chỉ THÊM bảng library_meta → Migration thủ công (giữ progress/cache cũ).
    // (AutoMigration cần exportSchema + kapt args không tương thích setup build hiện tại.)
)
abstract class BibotDatabase : RoomDatabase() {
    abstract fun memoryCore(): MemoryCoreDao
    abstract fun memoryFact(): MemoryFactDao
    abstract fun chatLog(): ChatLogDao
    abstract fun book(): BookProgressDao
    abstract fun pdf(): PdfJobDao
    abstract fun ttsSegments(): TtsSegmentDao
    abstract fun library(): LibraryMetaDao

    companion object {
        // B1.1: v2 → v3 chỉ thêm bảng library_meta (DDL khớp Room codegen —
        // TEXT NOT NULL / INTEGER NOT NULL, PK bookId; Room validate lúc mở DB).
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_meta` (" +
                        "`bookId` TEXT NOT NULL, " +
                        "`shelfFav` INTEGER NOT NULL, " +
                        "`shelfToRead` INTEGER NOT NULL, " +
                        "`shelfRead` INTEGER NOT NULL, " +
                        "`trashed` INTEGER NOT NULL, " +
                        "`lastRead` INTEGER NOT NULL, " +
                        "`author` TEXT NOT NULL, " +
                        "`series` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`bookId`))"
                )
            }
        }

        @Volatile private var INSTANCE: BibotDatabase? = null
        fun get(context: Context): BibotDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, BibotDatabase::class.java, "bibot.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}
