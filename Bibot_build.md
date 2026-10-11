# Bibot_build — Checklist Build Nhẹ & Tiết kiệm RAM cho máy yếu

> **Mục tiêu:** `Bibot.apk` (`com.bibot.app`) chạy mượt trên mobile đời cũ/yếu (1-2 GB RAM, Android 8+), thông minh đủ voice-chat + đọc sách + PDF→MD. Khai thác triệt để cơ chế **lazyload / data chunk / block loading / flip-flop** từ Pi gộc `Pi_system/pi4_xiaozhi` + **làm sạch RAM khi khởi chạy + tiết kiệm RAM + giải phóng lập tức**.
> **SDK:** `H:\androidkit` — `sdk` (`platforms/android-34`, `build-tools`, `cmdline-tools/latest`, `platform-tools`), `jdk17` (`Temurin 17.0.17`), `AndroidStudio/`, `gradle/`. **Build yêu cầu JDK 17** (`android/app/build.gradle.kts:43` `jvmTarget 17`), không dùng JDK 8 hệ thống. `local.properties` phải trỏ `sdk.dir=H:\\androidkit\\sdk`.
> **Ngày khởi tạo:** 2026-09-23 | **Cập nhật:** 2026-09-24 (quảng cáo AdMob, assembleDebug thành công, TCVN3/VNI stub, Bổ sung rác text: số trang/roman) | **Kho:** `H:\Pi4_Xiaozhi\android_app` | **Persona:** `Em là Bi` | **Single truth:** file này + `Readme.md` + `Tóm_tắt_hiện_tại.md`

---

## 0) Nguyên tắc nhẹ (áp mọi Build)

| Nguyên tắc | Quy định | Liên quan Pi |
|---|---|---|
| **APK nhẹ** | `minSdk 26` `target 34` `versionCode 1`, release `isMinifyEnabled + shrinkResources true` (11.4), `proguard-rules.pro` keep Room/Vosk/Go, `aab` release sau (cần keystore). Loại `META-INF/{AL2.0,LGPL}` đã có `build.gradle.kts:50`. Ảnh `mipmap` WebP, `assets/demo_books` thực tế 2 PDF (~6MB) + 2 MD (không bundle model 40-63 MB; xem 8.5 — đề xuất thu gọn demo). Model tải khi cần `no_backup/bibot/models` `ModelCatalog.kt:18`. 2 flavor: demo `Bibot-demo.apk` ~64MB (không Drive lib) / commercial `Bibot.apk` ~66MB. | Pi bundling `install_tts_models.sh` → Bibot lazy download |
| **RAM mục tiêu** | Idle <120 MB, Voice E2E peak <250 MB trên máy 2 GB; không giữ `Bitmap`/`ByteArray` PCM quá 1 chunk; `onTrimMemory` phải phản ứng. Đo bằng `adb shell dumpsys meminfo com.bibot.app` + Android Studio Profiler. | Pi 1 GB `audio_stream.py:808` `get_available_memory_mb()<80→gc.collect()` |
| **Không hardcode Pi path** | Mọi path qua `FilePaths.kt:22` (`filesDir/bibot`, `cacheDir/bibot`, `noBackupFilesDir/bibot/models`, `databases/bibot.db`). `cache` + `no_backup` đã exclude `backup_rules.xml`. | Pi `PROJECT_DIR/Music`, `~/.asoundrc` |
| **Offline-first nhẹ** | STT/TTS standard không key mặc định (SpeechRecognizer + Android TTS), Cloud chỉ khi có key + `priority auto`. Vosk/Piper chỉ chạy khi user tải trong ⚙️. | Pi `STT_OFFLINE_FIRST=1`, `TTS_PRIORITY` |
| **Chunk-first** | Mọi luồng dài (LLM stream, TTS PCM, PDF page, Book sentence, Music yt-dlp) đều chunk + bounded queue + spill `cache/bibot/chunks` TTL 24h LRU 200 MB `FilePaths.kt:60`. | Pi `vietnamese_chunker.py:36`, `audio_stream.py:765` Queue2 |

---

## 1) Tài nguyên Pi đã khai thác — nguồn port

Từng cơ chế Pi + vị trí port tương ứng trên Bibot (dùng để tick khi hoàn thiện).

| # | Cơ chế Pi (nguồn) | Mô tả RAM/lazyload Pi | Port Bibot | Trạng thái |
|---|---|---|---|---|
| **P1** | `services/audio_stream.py:59` `AudioStreamer` + `audio_streamer.c:27` ALSA 8192/2048 resilient, `stretch_pcm 0.5-1.5×` | PCM feeder non-blocking `select` + `write_pcm_streaming 4096` `write_timeout 2s`, `pause SIGUSR1/resume SIGUSR2/stop SIGTERM` | `ui/player/PlayerViewModel.kt:16` `ExoPlayer` + `Handler 500ms` `PlaybackParameters(speed)` + 2 pipeline book/story `get_pipeline():940` `get_story_pipeline():949` | 95% — text-queue Channel(2) đã có (8.3); còn PCM-overlap producer/consumer |
| **P2** | `services/audio_stream.py:765` `TTSAudioPipeline.speak_chunks()` flip-flop | `asyncio.Queue(maxsize=2)` producer `tts_producer` sinh PCM `await _generate_tts_piped()` + `del pcm_chunk` `buffer_queue.task_done()` immediate free, `get_available_memory<80 → gc.collect():808`, `abort_check` xả queue khi Stop, `_ffmpeg_cache.clear()` | `feature/voice/VoiceScreen.kt:79` `speakChunks()` sequential cloud→offline, `PlayerViewModel.kt:79` `playBytes/enqueueBytes` file `cache/bibot/audio/tts` | 90% — Book/Story PCM-overlap (producer IO // consumer Main, 8.3); còn Voice `speakChunks` sequential + `delay 65ms*len` (chưa drain theo STATE_READY) |
| **P3** | `services/vietnamese_chunker.py:36` `VietnameseTextChunker 4-12/120` `StreamingTextAccumulator` | `CHUNK_SPLIT_PATTERN` + `VIETNAMESE_SINGLE_WORDS` guard `last_w in single/digit`, `should_yield` theo `.!?…` `;:,` liên từ `và/nhưng` `maxWords/maxChars`, `feed() yield` + `flush()` bỏ `^\w` rác | `internal/chunker/chunker.go:24` port 1-1 + `core/tts/VietnameseChunker.kt:8` port Go, `VoiceScreen.kt:124` `chunker.reset/feed/flush` | 100% Go/Kotlin, cần dùng trong Book/Story/PDF |
| **P4** | `services/streaming_llm.py:143` `clean_tts_text` + `answer_datetime_local:247` + `StreamingLLM.stream_response:377` | `clean` bỏ markdown/URL/thinking `count \d+`, `has_sentence_end/has_clause_sep` mask `TP.HCM/1.440`, first-token timeout `LLM_FIRST_TOKEN_TIMEOUT 6s`, `query_requires_live_info` bật web search chọn lọc | `internal/tts/tts.go:195` `CleanTTSText` + `internal/llm/llm.go:62` `StreamGenerate` SSE Gemini/OpenRouter + `core/tts/CloudTtsBridge.kt:18` | 90% — cần `hasSentenceEnd` mask trong VoiceScreen |
| **P5** | `services/streaming_voice_chat.py:434` `_process_turn` + `services/conversation_memory.py:122` 3 tầng | Full-duplex STT→LLM→Chunker→TTS single PCM gen `feed_reference AEC`, mic chỉ mở khi không phát (chống ALSA heap), `gc.collect() mỗi lượt:268`, `VoiceChatStats`, memory `log_turn+observe_user` | `VoiceScreen.kt:118` `startVoicePipelineWithHybrid` `HybridSttManager→LlmBridge→Chunker→CloudTtsBridge` | 85% — đã `System.gc()` mỗi turn ở cleanup (không rely); **2026-09-30: tách `VoiceChatViewModel` (state machine + pipeline + stats) + port `NlmsAec` (`aec.py`) + barge-in monitor (`barge_in_detector.py`, mic song song loa, feed reference trước phát); còn streaming decode song song TTS — cần test máy thật** |
| **P6** | `services/book_reader.py:60` `iter_book_sentences` `read_book:140` | `yield` từng câu (không giữ PDF RAM), `pypdf PdfReader` `page_texts[] → preprocess_pdf_pages` `detect_header_footer:80`, `split_sentences`, `BOOK_PAUSE_AFTER_SENTENCES 200`, `BOOK_SAVE_EVERY 8` + `deque recent 20` `_flush_progress` atomic `tmp+replace`, `STREAM_BATCH_SIZE 8` `gc.collect()` finally | `data/db/BibotDatabase.kt:35` `BookProgress`, `data/books/BookRepository.kt:18` `FilePaths.audioBooks`, `feature/book/BookScreen.kt` `feature/story/StoryScreen.kt` | 85% — `iterSentences` Flow + saveEvery 8 + Channel(2) + resume announce đã có; còn `deque recent 20` + `tmp+replace` atomic |
| **P7** | `services/book_state.py:7` `load_progress/save_progress` | JSON `state/book_progress.json` `deque maxlen 20` `tmp.replace` atomic, `updated_at` UTC | `BibotDatabase.kt:81` `BookProgressDao get/upsert` | 70% — chưa `deque` + atomic file fallback |
| **P8** | `services/text_cleaner.py:80` `detect_header_footer` + `clean_pdf_text` | Counter `top_n 3 bottom_n 3` `HF_REPEAT_THRESHOLD 0.4`, `remove_urls/page_numbers/normalize_blanks __PAUSE2S__` | `core/text/PdfTextCleaner.kt` (detect header/footer 0.4 + urls/pageNum/blanks) dùng từng trang trong `PdfToMarkdownHelper` (9.1) | 80% — còn TCVN3/VNI convert; `internal/pdf/pdf.go:4` vẫn stub |
| **P9** | `services/pdf_to_md.py:1263` 3-tier | `Direct → TCVN3/VNI convert → OCR Tesseract DPI 200/300` `sanity_score 0.55` `has_corruption_signals`, `State DB mode14_state.db` resume, `MIN_FREE_MEM 250 MB` `MAX_PAGES_OCR 200` `NICE 19` | `feature/pdf/PdfScreen.kt` `PdfToMarkdownHelper` ML Kit on-device, `data/db/PdfJob` resume incremental + MIN_FREE_MEM, `feature/doc/DocBrowserScreen.kt` search debounce | 80% — thiếu TCVN3/VNI convert |
| **P10** | `services/music.py:99` `find_local_song/build_music_index/search_local_ranked` | `mutagen` ID3 + `.index` JSON cache `_INDEX_CACHE/_INDEX_MTIME`, `search_local_ranked` `strip_accents` + score 100/80/60, `yt-dlp flat-search` `limit 5` | `data/music/MusicIndex.kt` (index + mtime) + `MusicCommand.kt` (parse + score) + `VoiceScreen` lệnh thoại, `MusicScreen` view tìm kiếm | 90% — **quyết định 2026-09-24: bỏ applet player**, chỉ lệnh thoại local-first + Intent app ngoài; chưa quét MediaStore toàn máy |
| **P11** | `services/feed_prompt.py` `preprocess` | Tiền xử lý STT text chuẩn hoá, lọc nhiễu, trả lời cục bộ giờ/ngày/xã giao/trí nhớ/cache trước khi LLM → giảm quota + stream rỗng | `answerDatetimeLocal` + nhánh local `parseMusicCommand` + `MemoryRepository.recallAnswer` trước LLM (Voice) | 75% — còn web-search chọn lọc `query_requires_live_info` |
| **P12** | Pi RAM hygiene | `audio_stream.py:907 _ffmpeg_cache.clear()` `del pcm_chunk` `gc.collect()` `wait_for_drain 30s` `reset_streaming()`, `streaming_voice_chat.py:269 gc.collect() mỗi turn` | `FilePaths.cleanupCache 200MB/24h` + `BibotApp` onTrimMemory/onLowMemory + StrictMode + LeakCanary debug | 90% — còn `gc.collect()` mỗi voice turn như Pi |

---

## 2) Checklist RAM — làm sạch khi khởi chạy + tiết kiệm + giải phóng lập tức

### 2.1 Làm sạch khi khởi chạy (App start)

- [x] **2.1.1 `MainActivity.onCreate:15`** `FilePaths.ensureAll()` giữ sync nhanh, prune `chat_log>500` + `models_tmp` chuyển sang `BibotApp:21` `appScope.launch(IO)` — **xong 2026-09-23** `MainActivity.kt:15` `BibotApp.kt:21`.
- [x] **2.1.2 `Application.onCreate` + `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`** tạo `BibotApp : Application` `ComponentCallbacks2` `cleanupCache(150/200 MB 12/24h)` theo `isLowRam` + fix `launchWhenCreated` → `repeatOnLifecycle(State.CREATED)` `MainActivity.kt:28` — **xong**, `AndroidManifest.xml:10 android:name=.BibotApp`.
- [x] **2.1.3 `WorkManager` daily 02:00** `CleanupWorker` 1 lần/ngày `ExistingPeriodicWorkPolicy.KEEP` — **xong 2026-09-23** `libs.versions.toml:17 work 2.9.0 + work-runtime-ktx` `app/build.gradle.kts` `data/files/CleanupWorker.kt:18` `PeriodicWorkRequest 24h KEEP` LRU 150/200 MB TTL 12/24h theo `isLowRam` + dọn `models_tmp`, `BibotApp.kt:42 schedule()`.
- [x] **2.1.4 `LeakCanary` debugOnly** — **xong 2026-09-23** `libs.versions.toml: leakcanary 2.14` + `app/build.gradle.kts: debugImplementation(leakcanary-android)` (chỉ debug, không tăng APK release).

### 2.2 Tiết kiệm RAM (runtime)

- [x] **2.2.1 Lazyload Go `cmd/mobile/mobile.go:21`** — `gomobile bind` chỉ expose `LLMStream/TTSSynthesize/PDFConvert/MemoryRecall/ChunkerSplit`, không bundle ML model vào `bibot.aar` (đã đúng stdlib `net/http` không pull `cloud.google.com/go`). `go vet:0` giữ — **xong 2026-09-23** `go vet:0`.
- [x] **2.2.2 `DataStore` lazy** `BibotPreferences.kt:32` `prefsFlow` 12 keys — đã đúng `preferencesDataStore`, không `SharedPreferences` sync `runBlocking` — **xong**.
- [x] **2.2.3 `Room` paged lazy** — **xong 2026-09-24 (không cần PagingSource)**: grep xác minh không UI nào đọc bulk — `recent()` chỉ là DAO chưa ai gọi, `BookProgress` chỉ `get(id)`, prune `chat_log>500` ở `BibotApp`. Không thêm dep paging (tiết kiệm APK); khi nào có màn hình lịch sử chat mới cần Paging 3.
- [x] **2.2.4 `Book/Story` block loading** port `book_reader.py:60` `iter_book_sentences` yield — **xong 2026-09-25 (khối 16KB, không `readText()` toàn file)**: `BlockTextReader.iterCleanedBlocks/readChapters` stream + `BookRepository.iterSentences Flow<String>` (`flowOn(IO)`, PDF qua `iterPdfText` 64KB+overlap, MD/TXT qua `iterCleanedBlocks` 16KB+carry 256 ký tự); `BookScreen.loadBook` / `StoryScreen.loadStory` chạy `withContext(Dispatchers.IO)` (tách chương RAW → clean → tách câu), nội dung render `LazyColumn`, `switchChapter()` tách câu chương đích trên IO; `VoiceScreen` clean trước `chunker.feed`. Test JVM 64/64 PASS.
- [x] **2.2.5 `PDF` block loading** — **xong 2026-09-24 (gộp trong 9.1/9.3)**: `renderAndRecognize` page-window 1 trang (`withContext(IO)`, không loop sync), clean + append incremental từng trang, `PdfJob pending→processing→done/error` resume được, `pdfChunkDir` spill mỗi 5 trang, `availMem<80MB → delay 500ms + gc` trước mỗi trang.
- [x] **2.2.6 `Music` lazy index** — **xong 2026-09-23** `data/music/MusicIndex.kt: scanMusic()` quét `filesDir/bibot/music` (app-specific, không cần quyền) + metadata `MediaMetadataRetriever` (release trong `finally`), cache `.index` JSON `{version, generatedAt, dirMtime, tracks[]}` chỉ rebuild khi mtime đổi + validate file còn tồn tại; `MusicScreen` hiện mục "Nhạc trên máy" phát qua `playUrl(file://)`. Chưa làm: quét MediaStore toàn máy (cần quyền READ).
- [x] **2.2.7 `VietnameseChunker` streaming** `VietnameseChunker.kt:8` `feed/flush` đã port `P3` — dùng trong `VoiceScreen` + `Book/Story` TTS queue, tham số `4/12/120` như Pi, không tạo `StringBuilder` lớn — **xong**, `internal/chunker` + `VietnameseChunker.kt` `go vet:0`.
- [x] **2.2.8 `PlayerViewModel` ExoPlayer nhẹ** `PlayerViewModel.kt:27` `setWakeMode NONE` `setHandleAudioBecomingNoisy true` `USAGE_MEDIA CONTENT_TYPE_SPEECH` + `STATE_ENDED` auto dọn `ttsCache/bibot_tts_*` + `playBytes/enqueueBytes` **off Main** `viewModelScope.launch(IO) writeBytes → Main playUrl/addMediaItem` `prepare()` async `play()` khi `STATE_READY` — **xong 2026-09-23**.
- [x] **2.2.9 Dependency diet** — **cập nhật 2026-09-24**: thêm `work-runtime` (2.1.3/11.1) + `leakcanary` debugOnly (2.1.4); google-drive/api-client chuyển `commercialImplementation` (demo nhẹ hơn ~1.3MB); **không thêm** `play-services-speech`, `mlkit` chỉ `text-recognition`, `firebase` chỉ `firestore/auth`. `vosk-android 0.3.45` load via reflection. APK debug thực tế demo ~64MB / commercial ~66MB (mốc cũ 110MB đã lỗi thời).
- [x] **2.2.10 `onTrimMemory` + `onLowMemory`** trong `BibotApp` — **xong 2026-09-23** `BibotApp.kt:45 onTrimMemory` `COMPLETE → cleanupCache(80/12h)+gc`, `RUNNING_LOW/UI_HIDDEN → cleanupCache(80/12h)`, `onLowMemory → cleanupCache+gc`.

### 2.3 Giải phóng lập tức (immediate free)

- [x] **2.3.1 TTS PCM `del` ngay** port `audio_stream.py:895` → `PlayerViewModel.kt:34` `STATE_ENDED` `viewModelScope(IO) delete bibot_tts_* >60s` + `playBytes` `IO write → Main prepare` (bytes gốc được GC sau `emit`) `CloudTtsBridge finally disconnect()` giữ — **xong**.
- [x] **2.3.2 `Handler` leak fix** `PlayerViewModel.kt:42` `progressRunnable` chỉ tick khi `isPlaying||isBuffering` (500 ms vs 1000 ms), `onPause()/onResume()` `removeCallbacks/post` + `onCleared:102` giữ — **xong**.
- [x] **2.3.3 `VoiceScreen` `DisposableEffect:69`** đã `offlineTts.stop/shutdown playerVm.stopPlayback` — bổ sung `chunker.reset()` + `hybridStt.release()` (AudioRecord) `VoiceScreen.kt:69` + `HybridSttManager.release()` `OfflineSttHelper.kt:112` — **xong 2026-09-23**. **Mở rộng 2026-09-24 — RECORD_AUDIO runtime**: `startStt()` check `ContextCompat.checkSelfPermission` → đã cấp thì `beginStt()`, chưa thì mở hộp hệ thống (`RequestPermission`), được cấp tự nghe tiếp, từ chối báo lỗi thân thiện (`VoiceScreen.kt:212`). Chunk STT/TTS nằm hết trong sandbox (`cacheDir/filesDir/noBackup`) nên **không cần thêm quyền storage** — Manifest giữ nguyên 5 quyền.
- [x] **2.3.4 `Bitmap` PDF render** — **xong 2026-09-23** `PdfToMarkdownHelper.kt:118 page.close()` `kt:131 bitmap.recycle()` mỗi trang + `System.gc()` mỗi 3 trang, `finally` `renderer.close/pfd.close/recognizer.close()`.
- [x] **2.3.5 `LlmBridge` stream `onComplete`** — **xong 2026-09-23** `VoiceScreen.kt:193 fullBuffer.setLength(0)` + `kt:194 pendingSpeak.clear()` sau `consumerJob.join()+delay(200)`.
- [x] **2.3.6 `ModelDownloader.kt:18`** download 32 KB buffer đã reuse — thêm `tmpFile.delete()` khi `freeSpace` không đủ (xuất hiện early), `conn.disconnect()` sẽ thực thi sau khi kết nối tạo ra + `tmpFile.delete()` khi verify <500KB fail — **xong 2026-09-23** `ModelDownloader.kt:38-49,93-97`.

### 2.4 Chống đơ/lag khi nạp file (pdf, md), chunk TTS, audio streaming

> **Nguyên nhân Pi đã gặp & đã fix** (log Pi 09/2026): đọc cả PDF vào `String` → OOM, `PdfReader pages[]` giữ RAM, chunker chẻ `TP.HCM → H CM`, `1.440 → 1.4`, stream `ffmpeg` block Main, `ExoPlayer prepare()` đồng bộ, `TTS queue unbounded` → UI freeze 200-800 ms.

| Nguy cơ Bibot hiện tại | Vị trí | Cơ chế Pi đã có (port nguyên trạng) | Giải pháp Bibot — không block Main |
|---|---|---|---|
| **Đọc file md/pdf đồng bộ trên Main** `file.readText()` trong `Composable` | ~~`BookScreen.kt:108` `readText()` toàn file~~ → **đã sửa 2026-09-25**: `BookScreen.loadBook` / `StoryScreen.loadStory` chạy trên IO; `PdfScreen` preview `readText().take(n)` | `book_reader.py:60 iter_book_sentences` `yield` từng câu, `pdf_to_md.py:1254 process_pdf_to_chunks` page-window 1 trang, `services/text_cleaner.py:158 preprocess_pdf_pages` header/footer tách trước | **✅ Lazy Block Loading (xong 2026-09-25)** — `core/text/BlockTextReader.kt`: `readChapters()` đọc **stream từng dòng**, tích lũy khối **16 KB** rồi chạy `cleanBlock()` (`PdfTextCleaner.cleanPdfText` + `TextCleaner.cleanForTts` ≈25 regex × 16 KB thay vì × 3 MB), cắt ở whitespace gần nhất với **carry 256 ký tự** (URL/số không bị chia đôi), **tách chương ngay trên RAW** (TRƯỚC khi `stripMarkdown` xóa `#` — bản cũ tách sau clean nên heading biến mất → 1 chương, tách câu toàn bộ 1 lần → đơ). `iterPdfText()` đọc PDF **64 KB/round + overlap 4 KB** (không `readBytes()`), dedup match trong vùng carry. `readHead(file, n)` thay `readText().take(n)` cho preview. `resolveHeading()` prescan 1 lượt để giữ đúng thứ tự parse bản cũ. Render nội dung bằng `LazyColumn` thay `Column+verticalScroll`. |
| **Chunk TTS đồng bộ, queue tràn** `CloudTtsBridge.synthesize` await trên Main → freeze | `VoiceScreen.kt:97` `synthesize` `+ delay(65*len)` tuần tự trên Main `scope.launch`; `BookScreen.kt:146` `delay(60*len)` | `audio_stream.py:765 speak_chunks` **flip-flop `Queue(2)`** producer `tts_producer` `await _generate_tts_piped()` `buffer_queue.put(pcm)` block khi full 2, consumer `write_pcm_streaming` dòng riêng, `audio_stream.py:395 _remove_dc_and_fade` chỉ chạy trên worker, `vietnamese_chunker.py:36 min 4 max 12 maxChars 120` streaming `feed/flush` | **Flip-flop Channel(2)**: `Channel<ByteArray>(capacity=2)` hoặc `Channel<String>(2)` cho chunk text. Producer: `viewModelScope.launch(Dispatchers.IO){ chunker.feed(sentence).forEach{ chunk -> cleanForTts(chunk); ttsChannel.send(ttsBytes) } }`. Consumer: `launch(Dispatchers.Main){ for(bytes in ttsChannel) player.enqueueBytes(bytes) }`. `ttsChannel` `onUndeliveredElement` free bytes. `abort_check` (Pi `abort_check lambda`) → `isActive` + `job.cancel()` khi Back. |
| **Audio streaming prepare block** `player.prepare(); player.play()` sync IO trong `playBytes` | `PlayerViewModel.kt:79 playBytes` `f.writeBytes(bytes); playUrl(Uri.fromFile)` `prepare()` sync | `audio_stream.py:174 write_pcm_streaming` `select` non-blocking `write_timeout 2s` + `audio_streamer.c` ALSA double-buffer 8192/2048, `wait_for_drain` async | **ExoPlayer async**: `player.setMediaItem` + `player.prepare()` **không** `play()` ngay; `player.addListener(STATE_READY → play())`. Dùng `viewModelScope.launch(Dispatchers.IO){ file.write }` rồi `withContext(Main){ setMediaItem; prepare() }`. Prefetch `enqueueBytes` dùng `player.addMediaItem` (exo double-buffer sẵn) thay vì `setMediaItem` clear. `Handler 500ms` chỉ tick khi `isPlaying`. |
| **Đơ khi scan kho sách** `repo.refreshAll()` walk toàn bộ | `BookRepository.kt:145 scanLocalBooks walkTopDown` sync trên Main nếu không `withContext(IO)` | `music.py:66 build_music_index` cache `.index` + `_INDEX_MTIME` chỉ rebuild khi `mtime` đổi, `search_local_ranked` scoring không đọc file | **Lazy Index + Pagination**: `MusicIndex` / `BookIndex` JSON `cache/bibot/books/.index` `{version, mtime, tracks[]}`. `scanBooks(): Flow<BookMeta>` `withContext(IO) { walkTopDown(limit=100) }` + `PagingSource` `LazyColumn` `items(pagingItems)`. Invalidate chỉ khi `DocumentFile` `lastModified` thay đổi. |
| **Leak Job/Coroutine khi đổi mode** `LaunchedEffect(isReading)` vô hạn loop không cancel | `BookScreen.kt:157 LaunchedEffect(isReading) while(isReading) delay` | `streaming_voice_chat.py:434 _process_turn` `llm_done Event` + `barge_in` cancel `current_llm_task.cancel()` + `_cleanup()` `gc.collect()` | **Structured Cancel**: mọi `while(isReading)` lưu `Job` `val readerJob = remember{ mutableStateOf<Job?>(null)}; LaunchedEffect` `awaitCancellation` + `DisposableEffect{ onDispose{ readerJob.cancel(); offlineTts.stop(); player.stop(); scope.cancelChildren() } }`. `viewModelScope` tự cancel khi `ViewModel.onCleared`. |

**Verify chống đơ (2026-09-25) — `BlockTextReader` unit-test JVM (64/64 PASS):**
- Tách chương MD đúng như bản cũ: **chỉ `##`** (không tách theo `#`/`###`), preamble trước `##` giữ làm chương 0 (bản cũ *bỏ* — nay không mất chữ).
- 200 KB `.md` đọc theo khối → số từ clean = số từ gốc (không mất chữ khi cắt khối), `iterCleanedBlocks` stream ≥3 khối.
- PDF 300 KB có `(text) Tj` nằm **trong vùng carry 4 KB**, **vượt biên 64 KB**, và **ở cuối file** → mỗi text trúng **đúng 1 lần** (dedup overlap không trùng/không sót).
- Story: không có `##` → tách theo `Chương N`; **có `##` → chỉ tách `##`** (giống `parseStoryChapters` cũ); không cả hai → tách đoạn trống `Đoạn 1..N`.
- Guard port P6 `_is_heading_line` (`pdf_to_md.py:463`): `Phần lớn…`, `Hồi đó…`, `Mục đích…`, `Truyện, nhân hỏi…` **không** bị tách thành chương (kết thúc `.!?…:;`, từ loại dính dấu phẩy, `Hồi/Mục` không có số, dòng >120 ký tự).
- `readHead(file, 300)` trả đúng 300 ký tự (không nạp file 200 KB); file không tồn tại → 1 chương rỗng.

**Quy tắc bắt buộc (gắn lint):**
- Mọi `File.*`, `assets.open`, `PdfRenderer.render`, `ML Kit`, `Room`, `DataStore`, `HttpURLConnection` **phải** `withContext(Dispatchers.IO)` — cấm gọi trực tiếp trong `Composable`/`onClick`.
- `PlayerViewModel` không giữ `ByteArray` >1 chunk; `ttsCache` file `delete()` khi `STATE_ENDED` (Pi `del pcm_chunk`).
- `rememberCoroutineScope().launch` cho UI event, `viewModelScope` cho IO/TTS, thêm `NonCancellable` chỉ cho `cleanupCache`.

### 2.5 Loại bỏ rác text / ký hiệu — port đầy đủ từ Pi

> Đã có trong Pi, **Bibot phải dùng trước mọi `speak`/`chunker`**, nếu không TTS đọc rác `https://`, `TP.HCM → H CM`, `Colombia1`, emoji, markdown.

| Rác/Ký hiệu | Pi nguồn | Bibot hiện có | Yêu cầu bổ sung (port 1-1) |
|---|---|---|---|
| **URL / domain** `https://`, `www.`, `xxx.app/.vn/.com` | `text_cleaner.py:11 _URL_RE` + `streaming_llm.py:156 _URL_RE.sub(' ',text)` | `internal/tts/tts.go:21 urlRegex` đã 100% + `CleanTTSText` | Kotlin `core/text/TextCleaner.kt` `val URL_RE = Regex("""(?:https?|ftp)://[^\s/$.?#].[^\s]*|www\.[^\s/$.?#].[^\s]*|[a-z0-9-]+\.(?:com|vn|org|net|edu|gov|io|co|info|biz|me|xyz|top|site|online|tech|app|dev|ai)[^\s]*""")` dùng **trước** `VietnameseChunker.feed`. |
| **Link markdown + mảnh dở** `[text](url)`, `[` treo, `](https` | `streaming_llm.py:153 re.sub(r'\[([^\]]*)\]\([^)]*\)', _strip_markdown_link)` + `162 dangling` | `tts.go:19 mdLinkRegex/danglingOpen/Mid/Paren` | Thêm `Kotlin stripMarkdownLink()` giữ label nếu không phải domain (giữ `thủ công` trong `[thủ công](url)`). |
| **Số dính chữ** `Colombia1` `Giao1 thông2` (reasoning leak) | `streaming_llm.py:167 re.sub(r'(?<=[A-Za-zÀ-ỹà-ỹ])\d+(?=\s|[.,!?;:]|$)','')` + `tts.go:69 removeDigitGluedToLetter` giữ `2x3` | `tts.go:69` đã xong, Kotlin thiếu | Thêm `TextCleaner.removeDigitGluedToLetter()` Kotlin (port `isVietnameseLetter` + exempt `digit-x-digit`). |
| **Viết tắt + số chấm/phẩy** `TP.HCM→TP HCM`, `1.440→1440`, `1,440→1440`, `3,5→3 phẩy 5`, `100-120→100 đến 120` | `streaming_llm.py:169 _ABBREV_DOT_RE/_NUM_DOT_RE/_THOUSAND_COMMA/_NUM_COMMA` + `clean_pdf_text` không động vào `1.440` | `tts.go:98 replaceAbbrevDot/replaceNumDot/replaceComma/replaceDashBetweenNumbers` 100% | Kotlin `TextCleaner.normalizeNumbers()` trước `hasSentenceEnd`. |
| **Toán tử** `× → nhân`, `2x3 → nhân`, `= → bằng`, `% → phần trăm`, `/ → space` | `streaming_llm.py:179` | `tts.go:209` | Đã port Go, cần wrapper Kotlin gọi `Mobile.CleanTTSText` hoặc `TextCleaner.kt` duplicate để không phụ thuộc AAR. |
| **Chữ chẻ** `M èo → Mèo`, `H CM → HCM` | `streaming_llm.py:190 re.sub(r'\b([VI_U]) ([VI_L])')` | `tts.go:215` | Kotlin thêm regex tiếng Việt đầy đủ dấu `À-ỹ`. |
| **Header/footer + số trang + watermark** `Trang 1`, `- 1 -`, `tac gia / nxb / copyright` lặp ≥40% trang | `text_cleaner.py:62 _is_likely_header_footer`, `80 detect_header_footer Counter 0.4`, `115 remove_header_footer`, `19 _PAGE_NUM_RE` | ✅ `core/text/PdfTextCleaner.kt` đã port + gọi từng trang trong `PdfToMarkdownHelper` (9.1) và lúc load Book/Story | ✅ TCVN3 full byte-level (2026-10-10): `core/text/Tcvn3.kt` (bảng chuẩn vietunicode 74 slot + 60 cặp ghép HOA, font ABC/H-font, detect + hậu kiểm đ/ơ/ư chống phá text Latin) + `internal/pdf/pdf.go` mirror; ToUnicode CMap (Type0/Identity-H, vd Việt Nam Sử Lược) + phát hiện PDF scan Type3 (vd grimm10 → báo rõ, không đọc rác) trong `BlockTextReader`; Story đã list PDF; EPUB tương tự (`core/text/EpubReader.kt`: spine order, strip XHTML, encoding strict→Latin-1 giữ byte cho TCVN3, phát hiện EPUB ảnh, sniff lang vi/en) |
| **Blanks → pause** `___`, `---`, `...` → pause 2s | `text_cleaner.py:124 _BLANK_RE` + `audio_stream.py:544 __PAUSE2S__ → silence 2s` | ✅ Kotlin `normalizeBlanks()` → `PAUSE2SEC` (đổi từ `__PAUSE2S__` vì `__` bị `stripMarkdown` ăn mất) + `splitPause()`/`stripPauseMarker()`; **khoảng lặng 2s bằng `delay(2000)`** | ✅ **Chốt 2026-09-25** — marker **không bao giờ tới engine TTS**: (1) Book/Story producer synthesize bằng `plain` (đã bỏ marker) chứ không phải `clean` — trước đây Android TTS đọc nguyên "PAUSE 2 SEC"; (2) `pauseAfterMs=2000` → consumer `delay(2000)` **sau** phần audio; (3) `VoiceScreen.speakChunks` cũng `splitPause` + `delay(2000)`; (4) phòng thủ cuối: `stripPauseMarker()` trong `OfflineTtsHelper.speakOffline/synthesizeToFile`, `PiperTtsHelper.synthesize`, `CloudTtsBridge.cleanForTts`; (5) UI hiện `⏸2s` thay marker. Test JVM 64/64 PASS |
| **Emoji + markdown** `**`, `__`, `` ` ``, `#+ header`, `- bullet`, `()` rỗng, emoji unicode | `streaming_llm.py:195` + `tts.go:22 emojiRegex` | `tts.go:25 markdownBold/headerRegex/bulletRegex` | Kotlin `TextCleaner.stripMarkdown()` + `emojiRegex` `[\u1F600-\u1F64F...]` replace `""`. |
| **HTML/markup sách convert** `<!-- CHAPTER-BREAK index=6 title="…" pages=8-10 -->`, `<a id="page-8"></a>`, `&nbsp;`, bảng `\|---\|---\|`, `# Truyện X` (đã gộp dòng) | Pi không có (sách Pi không qua EPUB→MD) | ❌ **trước 2026-09-25 sống sót** → TTS đọc `<! CHAPTERBREAK index bằng 6 title bằng … pages bằng 8 đến 10 > <a id bằng "page8">` | ✅ **Xong 2026-09-25** — `TextCleaner.stripHtml()` (entity → comment → tag, kể cả comment **bị cắt giữa khối 16KB**) + `TextCleaner.stripMarkup()` (bảng phân cách, blockquote, bullet, heading `#` **line + inline** sau khi gộp dòng) chạy **đầu `cleanForTts`** và trong `cleanPdfText` **trước khi gộp dòng**; `CloudTtsBridge.cleanForTts` light-cleanup cũng gọi 2 hàm này + bỏ `PAUSE2SEC` trước khi gửi Google TTS. `BlockTextReader` đọc marker `CHAPTER-BREAK` làm **tiêu đề chương** (`title=` → fallback `index=N` → `Chương N`). |
| **Thinking leak** `Need under 30.`, `Count Giao1…` `<think>…</think>` | `streaming_llm.py:69 _THINKING_LEADERS` + Go `llm.go:377 isThinkingLeak` | ✅ Kotlin `TextCleaner.looksLikeThinking()` port leaders Go + `<think` + chữ-dính-số ×2; drop ở `VoiceScreen.onToken` (khỏi hiển thị + chunker) và producer Book/Story (skip câu) | Xong 2026-09-24 (giới hạn: token rời rạc chỉ bắt được đầu chunk) |
| **Dấu câu dính space** `Bồ ơi ! → Bồ ơi!` | `streaming_llm.py:205` | `tts.go:222` | Kotlin `Regex("""\s+([.,!?;:])""")` → `$1`. |

**Pipeline chuẩn Bibot (áp mọi mode trước `speak`):**
```
rawPdfOrLlmText
 → TextCleaner.stripHtml()      // <!-- CHAPTER-BREAK -->, <a id=…>, &nbsp;  (2026-09-25)
 → TextCleaner.stripMarkup()    // |---|---|, > quote, - bullet, # heading    (2026-09-25)
 → PdfTextCleaner.detectAndRemoveHeaderFooter(pages)   // PDF mới, cache hfLines
 → TextCleaner.removeUrls + removePageNumbers + normalizeBlanks
 → TextCleaner.cleanForTts()  // URL → space, markdown → space, abbrev/number, math, emoji (port tts.go:195)
 → VietnameseChunker.feed(cleanText, 4,12,120)          // chỉ sau clean, tránh chẻ TP.HCM
 → [looksLikeThinking? drop] → TTS (Cloud → Offline)
```

**Verify rác:**
- `CleanTTSText("Chào [Bibot](https://bibot.app) TP.HCM 1.440 3,5 2x3 =6 *bold* 😀") == "Chào TP HCM 1440 3 phẩy 5 2 nhân 3 bằng 6 bold"`
- `clean_pdf_text` 3 trang có `Trang 1` + header `NXB Kim Đồng` lặp → `hfLines` loại bỏ.
- **Số trang đọc thành chữ (2026-09-24)** `TextCleaner.readPageNumbers("page 456") == "trang bốn trăm năm mươi sáu"`; `Trang 21 → trang hai mươi mốt`, `page 105 → trang một trăm lẻ năm`, `page 1005 → trang một nghìn lẻ năm`; `1.440`, `3,5`, `100-120`, `homepage`, `trang 4.5` giữ nguyên (regex chỉ cụm page/trang + số nguyên đứng riêng).
- **Số La Mã đọc thành chữ (2026-09-24)** `readRomanNumerals("thế kỷ XI-XIX") == "thế kỷ mười một đến thế kỷ mười chín"`; `chương IV → chương bốn`, `kỷ XX → kỷ hai mươi`; chỉ sau từ khóa (thế kỷ/kỷ/chương/phần/hồi/tập/quyển/mục/điều/khoản) nên `Louis XIV`, `vitamin C`, `I love you` giữ nguyên; roman sai (`IIII`) giữ nguyên; validate 1..3999.
- **Rác HTML/markup sách convert (2026-09-25, test JVM 64/64 PASS)** — input thật:
  ```
  *Hết Truyện Chồn tinh (Hồ tỉnh). Bắt đầu Truyện Cây tỉnh (mộc tỉnh).*
  ---
  <!-- CHAPTER-BREAK index=6 title="Truyện Cây tỉnh (mộc tỉnh)" pages=8-10 -->
  <a id="page-8"></a>
  # Truyện Cây tỉnh (mộc tỉnh)
  ```
  → `cleanBlock()` trả `Hết Truyện Chồn tinh (Hồ tỉnh). Bắt đầu Truyện Cây tỉnh (mộc tỉnh). PAUSE2SEC Truyện Cây tỉnh (mộc tỉnh)`; không còn `CHAPTER-BREAK`/`index`/`pages`/`page-8`/`<`/`>`/`#`; `splitPause` → `needPause=true`.
- **Comment/tag bị cắt giữa khối** `…xong<!-- còn thiếu` → bỏ hết phần còn lại (không sót `<!--`); comment multi-line `<!-- a\nb -->` bị tẩy; `&nbsp;` → space (không thành U+00A0 lọt qua `\s+`).
- **Bảng markdown** `| STT | Tên |\n|---|---|\n| 1 | Mèo |` → giữ `STT Tên 1 Mèo`, **không** sinh `PAUSE2SEC` oan từ `|---|`.
- **PAUSE2SEC → khoảng lặng 2s (không để engine đọc)** — test: `"Câu một. --- Câu hai."` → `cleanForTts` giữ marker → `splitPause` trả `(plain="Câu một. Câu hai.", second=true)` → caller `delay(2000)` sau phần audio. `stripPauseMarker(pc) == splitPause.first`, idempotent; `---` đầu dòng / `...` vẫn ra marker (không bị `bulletRegex` ăn); không có marker → `second=false`; payload gửi engine `"Câu một PAUSE2SEC Câu hai"` → `"Câu một Câu hai"`.
- **Tách chương theo marker converter** file có `<!-- CHAPTER-BREAK … title="X" -->` → 2 chương, title = `X` (test cả `Heading.MARKDOWN` lẫn `MARKDOWN_OR_STORY`); marker không có `title=` → `Chương N` từ `index=`; chương nội dung sạch `<`/`>`.

### 2.6 LLM miễn phí cho user thường — không cần `GEMINI_API_KEY`

> **Hiện trạng `internal/llm/llm.go:90` + `Pi_system/services/config.py:58`**: thiếu key → mock `Em là Bi chưa kết nối` — user thường không dùng được. Mục tiêu: **user không key vẫn chat free** qua relay, không nhúng `AIza...` vào APK, không cần LLM riêng, giữ APK nhẹ.

| Phương án | Mô tả | Vị trí code | Ưu/nhược |
|---|---|---|---|
| **R1 Bibot Relay (khuyến nghị)** | App → `POST https://bibot-relay-xxx.a.run.app/v1/chat {prompt,system,deviceId}` → server inject `GEMINI_API_KEY` (Secret Manager) → `generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:streamGenerateContent` SSE → relay về app. Rate-limit `20 req/ngày/device` (`DataStore device_id` UUID). Đổi model/thinking không update APK. | `internal/config/config.go:9 +LlmProxyURL` `internal/llm/llm.go:62 StreamGenerate() answerDatetimeLocal→streamProxy→streamOpenRouter→mock` `cmd/mobile/mobile.go:21 InitProxy(url)` `core/llm/LlmBridge.kt generateStreamProxy()` `SettingsScreen.kt:144` để trống key → “Dùng Bibot Free (20 tin/ngày)” | + Key không lộ, quota tập trung `2.5-flash-lite 15/1000 RPD` free cao nhất, Cloud Run free tier 0đ. – Cần host 1 container Go. |
| **R2 Firebase AI Logic** | Dùng `firebase-vertexai` `GenerativeModel("gemini-2.5-flash-lite")` với `FirebaseAuth` anonymous, quota tính vào project Firebase Spark `1500 req/ngày`. | Thêm `firebase-vertexai` vào `libs.versions.toml:1`, `LlmBridge.kt` | + Không tự host. – APK +~1 MB, phụ thuộc Firebase. |
| **R3 OpenRouter share** | Relay share `OPENROUTER_API_KEY` `nex-n2.5-mini:free` (`OPENROUTER_FREE_CANDIDATES` `config.py:88`) fallback khi Gemini 429. | Cùng R1 nhưng `streamOpenRouter` với key chung | + Fallback rẻ. – Chất lượng thấp hơn Gemini native. |

**Pipeline chuẩn khi không key:**
```
VoiceScreen prompt → answerDatetimeLocal? local → LlmProxy.streamProxy(ProxyURL) → success? chunk→TTS : fallback streamOpenRouter(shared) → mock
```
**Checklist R1 tối thiểu:**
- [x] **2.6.1** `config.go:9` thêm `LlmProxyURL string` `InitProxy(url)`, `llm.go:62` thêm `streamProxy(url, prompt, system, onToken)` `net/http` SSE (reuse `streamGemini` parse) — **xong 2026-09-23** `go vet:0`.
- [x] **2.6.2** `LlmBridge.kt` thêm `streamProxy(proxyUrl, prompt, system, listener)` `Dispatchers.IO` `HttpURLConnection` + `generateStreamRest` thứ tự Gemini→Proxy→OpenRouter→mock — **xong**.
- [x] **2.6.3** `SettingsScreen.kt:144` để trống `apiKey` → UI hiện “Dùng Bibot Free (20 tin/ngày via relay)” / “Chưa cấu hình relay → mock” — **xong**, `VoiceScreen.kt:136` truyền `proxyUrl` từ `BuildConfig.BIBOT_LLM_PROXY_URL`.
- [x] **2.6.4** `bibot-relay` code — **xong 2026-09-24, `go vet:0` `go build:0`** `relay/main.go` stdlib-only (~170 dòng): `POST /v1/chat {prompt,system,deviceId}` → `gemini-2.5-flash-lite` `maxOutputTokens 1024` `thinkingBudget 128` SSE passthrough, quota 20/ngày/device in-memory, `GET /healthz`; `Dockerfile` + `relay/README.md` (Cloud Run asia-southeast1 + Secret Manager). Key không vào APK. **Còn lại (phía bạn):** `gcloud builds submit + run deploy`, test curl, điền URL vào `BIBOT_LLM_PROXY_URL` (build.gradle.kts:27 hiện `""`) rồi build lại 2 flavor.
- [x] **2.6.5** Offline vẫn `answerDatetimeLocal` + `OfflineTtsHelper` không cần LLM — đảm bảo 100% offline trả lời giờ/ngày — **giữ nguyên** `llm.go:65`.

**Verify không key:**
- Máy xóa `GEMINI_API_KEY` → Voice “mấy giờ rồi?” → local trả lời không relay; “kể chuyện” → relay SSE về, `Chunker` 4/12 chunk → TTS; `logcat` không lộ `AIza`.

---

### 2.7 Điều khiển đọc — ⏮ (Rev) / ⏸ (Play) / ⏭ (Next) trong Book & Story

> **Bug sửa 2026-09-25**: 2 nút ⏮/⏭ **chỉ đổi state UI** (`currentSentence`), còn producer đọc bằng biến local `sentIdx/chapIdx` riêng và consumer còn ghi đè `currentSentence = item.sentIdx + 1` → **bấm vô tác dụng**.

| Nút | Đang đọc (`isReading=true`) | Đang dừng |
|---|---|---|
| **⏭ Next** | `requestJump(cur+1, 0)` → producer tách câu **chương kế** trên IO rồi đọc tiếp, bỏ qua phần còn lại (= "tìm dấu `##`/heading kế tiếp") | `switchChapter(cur+1)` như tab |
| **⏮ Rev** | `currentSentence>0` → `requestJump(cur, 0)` = **đầu chương hiện tại**; đã ở đầu → `requestJump(cur-1, 0)` = **đầu chương trước**; ở đầu sách → báo status | y hệt |
| **⏸/⏯ Play** | `isReading=false` → `offlineTts.stop()` + `playerVm.stopPlayback()` (con `LaunchedEffect` tự cancel producer/consumer) | `isReading=true` → chạy lại từ `currentChapter/currentSentence` |

**Cơ chế nhảy có kiểm soát (không kể nhầm chỗ cũ, không dead-air):**
- `jumpChapterReq` / `jumpSentenceReq` / `jumpSeq` — `remember { mutableIntStateOf }`; producer **poll ở đầu vòng lặp** → tách câu chương đích trên IO → `sentIdx = jumpSentenceReq` → `continue`.
- Mỗi item mang `seqAtStart` (chụp lúc **bắt đầu** synth); bấm Rev/Next → `jumpSeq++` → consumer `if (item.jumpSeq != jumpSeq.intValue) continue` → **bỏ item cũ đang nằm trong `Channel(2)`**, không phát nhầm.
- `delayUntilDone(ms) { … }` (mới, `core/tts/TtsPause.kt`) thay `delay(textLen*60)` — chờ lát 100ms, cắt ngay khi có nhảy → không chờ câu dài đọc hết.
- `requestJump()` **chỉ** gọi `playerVm.stopPlayback()` — chủ động **không** gọi `offlineTts.stop()`: path `Direct` đang kẹt trong `speakOffline` chỉ resume qua `onDone/onError`, `stop()` có thể không bao giờ gọi lại → treo vĩnh viễn.
- `jumpChapterReq` reset `-1` sau khi xử lý; `< 0` = không có yêu cầu.

**Checklist:**
- [x] **2.7.1** `BookTtsItem`/`StoryTtsItem` thêm `val jumpSeq: Int` (default `0`) + stamp `seqAtStart` lúc `send` — **xong 2026-09-25**.
- [x] **2.7.2** Producer poll `jumpChapterReq` ở đầu vòng lặp (cả 2 màn) + `requestJump()` phân nhánh đọc/dừng — **xong 2026-09-25**.
- [x] **2.7.3** Consumer bỏ item lệch `jumpSeq`; `delayUntilDone` cho delay ước lượng và `pauseAfterMs` — **xong 2026-09-25**.
- [x] **2.7.4** `core/tts/TtsPause.kt` `delayUntilDone(totalMs, jumped)` — **xong 2026-09-25**, `assembleDebug` BUILD SUCCESSFUL.
- [ ] **2.7.5** **Test máy thật**: bấm ⏭ giữa câu dài → audio dừng ngay & sang chương mới; ⏮ 2 lần → đầu chương → chương trước; tab chương vẫn dừng đọc như cũ; không kẹt `speakOffline`.

---

### 2.8 Model offline — cài **không cần mạng** (sideload `models_in`; 2026-09-30: file picker SAF thay thế)

> **2026-09-25**: `ModelDownloader`/`SherpaEngine`/`PiperTtsHelper` trước đây **hardcode mạng** → 236MB model đã tải sẵn trong `Gói ngôn ngữ Piper\` (7 file) **không dùng được** (app tải lại từ đầu, mất nhiều phút & tốn bandwidth). Nay có staging dir `FilePaths.modelStagingDir()` = `/sdcard/Android/data/com.bibot.app/files/models_in` (external app-specific → `adb push` **không cần root, không cần quyền storage**) — đẩy file vào rồi bấm **Cài**, app cài thẳng, 0 request mạng.

| File trong `Gói ngôn ngữ Piper\` | Model id | Nơi cài trên máy |
|---|---|---|
| `vosk-model-small-vn-0.4.zip` (32.1MB) | `vosk-vi-small` | `no_backup/bibot/models/vosk-vi/` (unzip + flatten root) |
| `vosk-model-small-en-us-0.15.zip` (39.3MB) | `vosk-en-small` | `…/models/vosk-en/` |
| `pretrained_vi.onnx` (60.2MB) + `.json` | `piper-vi-phatjkk` | `…/models/piper-vi.onnx` + `sherpa-vi/model.onnx.json` → suy `tokens.txt` |
| `en_US-lessac-medium.onnx` (60.3MB) + `.json` | `piper-en-lessac` | `…/models/piper-en.onnx` (+ legacy copy) + `sherpa-en/` |
| `sherpa-onnx-v1.13.8-android.tar.bz2` (44MB) | engine 3 `.so` | `no_backup/bibot/sherpa/1.13.8/lib/<abi>/` |
| `espeak-ng-data.zip` (8.8MB, **mới**) | espeak data | `…/models/sherpa-espeak/` (strip root `espeak-ng-data/`) |

**Thứ tự cài trên máy:** ① (cũ) `push_models.ps1` (PC) → ② ⚙️ Cài đặt → 📦 Mô hình offline → ③ nút **“Cài Piper (offline)”** (engine → VI → EN; espeak lấy từ zip, **không** list 392 file qua HF API). **2026-09-30**: bỏ bước ① — mỗi card/model có nút **“Cài từ file”** (file picker, chọn trực tiếp trên máy) + nút **Tải** mạng; xem 2.8.7.

**Code:**
- `FilePaths.modelStagingDir()` (+ dòng trong `debugDump`); alias file theo model: `ModelInfo.stagedNames()/stagedJsonNames()` (accept `pretrained_vi.onnx`, `piper-vi.onnx`, `piper-vi-phatjkk.onnx`…).
- `ModelDownloader.stagedFile()/stagedJson()/hasLocal()`; `download()` chia `source = staged ?: tmpFile` → mọi bước install/unzip/consume dùng `source` (không copy 60MB vô cache); `.json` companion sideload trước rồi mới `downloadExtraFile`.
- `SherpaEngine.stagedTarball()/hasLocalPack()/extract()` → tách lõi `extractSo(InputStream, total, dir, onProgress)` dùng **chung** cho file tarball sideload và stream mạng (file staged hỏng → fallback mạng).
- `PiperTtsHelper.stagedOnnx()/stagedJson()/stagedEspeakZip()/stagedStatus()`; `ensureVoice` theo thứ tự **sideload → file catalog đã tải → tải mạng**; `deriveTokensFromJson` chạy cho **cả EN** (không còn bắt buộc tải `tokens.txt`); `extractEspeakZip()` giải zip + strip root.
- `OfflineSttHelper.pickModelDir()` — chọn `vosk-vi` nếu có `am/final.mdl`, không thì `vosk-en` (**trước đây cứng `vosk-vi` → model EN trong catalog không bao giờ được dùng**).
- UI: `ModelUiState.localFiles/stagingCount` + `ModelsViewModel.clearStaged()`; header có nút “Xoá file sideload”; `PiperInstallRow` hiện “File sideload sẵn: engine .so • giọng VI • …”.

**Checklist:**
- [x] **2.8.1** `modelStagingDir` + `ModelInfo.stagedNames` + `ModelDownloader` local-first (download/unzip/consume `source`) — **xong 2026-09-25**, `assembleDebug` BUILD SUCCESSFUL, dex có `models_in`/`sideload`.
- [x] **2.8.2** `SherpaEngine` giải tar.bz2 sideload + fallback mạng (tách `extractSo`) — **xong 2026-09-25**. **BUG NẶNG tìm ra khi verify tarball thật (2026-09-25):** entry trong release là `./jniLibs/<abi>/lib*.so` (tiền tố `./`) nhưng code check `entry.name.startsWith("jniLibs/…")` → **không extract được .so nào → `Thiếu .so` → Piper không bao giờ chạy, kể cả đường tải mạng**; đã fix `path = entry.name.replace('\\','/').removePrefix("./")` trước khi so prefix. (Verify bằng `tar -tjf`: 25 entry, đủ 4 ABI × `libonnxruntime`/`libsherpa-onnx-c-api`/`libsherpa-onnx-jni`; DT_NEEDED: `jni → onnxruntime`, `c-api → onnxruntime`, `cxx-api → c-api` — thứ tự nạp `onnxruntime → c-api → jni` là đúng.)
- [x] **2.8.3** `PiperTtsHelper` sideload onnx/json/espeak-zip + suy tokens EN từ `.onnx.json` — **xong 2026-09-25**.
- [x] **2.8.4** Script PC: `push_models.ps1` (8 file, fallback `run-as` cho Android 11+ chặn shell vào `Android/data`; parse 0 lỗi) + `download_espeak.ps1` — **đã chạy thật: 355 file / 17.2MB → `espeak-ng-data.zip` 8.8MB**.
- [x] **2.8.6** **Dọn thư mục `Gói ngôn ngữ Piper\` (2026-09-25)** — kiểm tra format/structure từng file trước khi xoá:
  - **Giữ 8 file (app đọc hết):** 2 zip Vosk (đều có `*/am/final.mdl`, root dir 1 cấp → `unzipWithProgress` flatten ✓), `pretrained_vi.onnx(.json)` + `en_US-lessac-medium.onnx(.json)` (header ONNX/pytorch ✓, JSON có `phoneme_id_map` ✓ → suy tokens được), `sherpa-…tar.bz2` (header `BZh` ✓, có đủ `.so`), `espeak-ng-data.zip` (`PK` ✓, 356 entry root `espeak-ng-data/` + `phondata` ✓).
  - **Đã xoá:** `extracted/` (**0 file — rỗng**, thư mục sinh nhầm bởi `extract_sherpa.py`) và `espeak-ng-data/` (**355 file/17.16MB — bản đã giải nén trùng với `espeak-ng-data.zip`**, app chỉ đọc zip; cần lại thì chạy `download_espeak.ps1`).
- [ ] **2.8.5** **Test máy thật** (hiện `adb devices` trống): bấm **“Cài từ file”** (file picker, 2026-09-30 thay sideload — `push_models.ps1` chỉ còn dự phòng) + nút tải mạng → **tắt WiFi** đọc sách bằng Piper VI/EN; xác nhận `isVoiceComplete=true`, STT sherpa-vi/Vosk offline nhận diện được.
- [x] **2.8.7** **File picker thay sideload + fix sherpa-vi tar.bz2 (2026-09-30)**: mỗi model/engine/espeak có nút **“Cài từ file”** (SAF `OpenDocument`, MIME theo loại file) — `ModelDownloader.installFromFile()` + `SherpaEngine.installEngineFromFile()` + `PiperTtsHelper.installVoiceFromFile()/installEspeakFromFile()`; code staged* deprecated (giữ tương thích). **BUG**: model sherpa-vi (`tar.bz2`) bị gọi `unzipWithProgress`/`untarGzFile` → fail + đích sai `vosk-vi` → nhánh riêng `installSherpaModel()` (BZip2 extract → verify đủ 4 file encoder/decoder/joiner/tokens → tự cài engine best-effort); `ModelCatalog.destDir/installedSize` đúng `sherpaViModelDir`. UI gọn bằng `HorizontalDivider` (thay `Divider` deprecated).

---

## 3) Checklist Build — thứ tự phát triển tiếp (nhẹ trước, thông minh sau)

### Build 7 — Voice E2E (✅ xong 2026-09-22) — giữ nền nhẹ
- [x] Go `config/llm/tts/stt/chunker` + `mobile.go` `vet:0`
- [x] `VoiceScreen.kt:38` `HybridStt→LlmBridge→Chunker→CloudTtsBridge→PlayerViewModel`
- [x] `FilePaths` `cleanupCache` `MainActivity` `ensureAll`
- [x] **RAM bổ sung:** thêm `BibotApp onTrimMemory` + `PlayerViewModel` `onPause/removeCallbacks` (2.2.10/2.3.2) — **xong 2026-09-23** `BibotApp.kt:15` `PlayerViewModel.kt:42` `gradle assembleDebug` BUILD SUCCESSFUL.

### Build 8 — Book / Story Reader (ưu tiên 1, 2 ngày) — **áp 2.4 + 2.5 chống đơ & rác**
- [x] **8.1 `BookRepository.iterSentences` lazy yield** port `P6` `iter_book_sentences` — `Flow<String>` page window 1-2 (32 KB block), `withContext(Dispatchers.IO)` mỗi block, `detect_header_footer` `remove_header_footer` `remove_urls` `normalize_blanks __PAUSE2S__` (TextCleaner 2.5), `split_sentences` regex `[.!?]` + cancel khi Back — **xong 2026-09-23** `BookRepository.kt:172` `core/text/TextCleaner.kt` `PdfTextCleaner.kt` `flowOn(IO)`.
- [x] **8.2 `BookScreen.kt` + `StoryScreen.kt`** đã fix **không block Main**: ~~`withContext(IO) file.readText`~~ → **2026-09-25** `withContext(IO) BlockTextReader.readChapters()` (stream 16KB, không `readText()`), tách chương/tách câu đều trên IO; list `BookRepository.books StateFlow` giữ; vòng đọc chạy qua `Channel(2)` producer/consumer (8.3 đã nối xong). **Resume reading**: mở lại sách/truyện có `BookProgress.position>0` → TTS announce `"Bạn đã đọc/nghe {title} tới câu X trên Y. Tiếp tục đọc/kể từ câu X+1."` rồi loop tiếp tục từ vị trí đã restore.
- [x] **8.3 Flip-flop TTS Book** port `P2` Queue2 — **xong 2026-09-23 text-queue, xong PCM-overlap 2026-09-24** `BookTtsItem`/`StoryTtsItem` sealed (`Pcm`/`Direct`) + `Channel(...,2)`, producer clean + synthesize từng câu (CloudTTS tự IO, config snapshot 1 lần/lượt, câu rác bỏ qua không chiếm slot, block send khi đầy 2) → consumer `Pcm` thì `enqueueBytes` (addMediaItem, drain nối tiếp) + delay ước lượng, `Direct` thì `speakOffline`; synthesize câu N+1 overlap lúc phát câu N; item deref ngay (immediate free), `total` hoist 1 lần; abort_check `isActive/ensureActive/isReading` + con `LaunchedEffect` nên Back/Stop auto-cancel, `finishedAll` mới báo "đọc xong". `speakSentence()`/`speak()` cũ đã xóa (resume announce dùng logic inline riêng).
- [x] **8.4 `StoryScreen` heading split** port `P6` `_is_heading_line` — **xong 2026-09-23** `StoryScreen.kt:41 parseStoryChapters` ưu tiên `##`, fallback regex `^(Chương|Chuong|Phần|Phan|Hồi|Hoi)`, `STORY_PAUSE_BETWEEN 450ms` giữ, producer `cleanForTts` mọi câu trước synthesize (2.5). **STORY_PREFER_PIPER thật — xong 2026-09-24** (đảo quyết định "không làm" nhờ tìm ra đường diet-safe): sherpa-onnx v1.13.8, KHÔNG bundle ~43MB .so (tải on-demand vào `no_backup/sherpa`, `System.load` đúng thứ tự; bindings vendored đúng tag, chỉ sửa companion loader) + `commons-compress` (~0.6MB giải tar.bz2); `SherpaEngine` + `PiperTtsHelper` (EN full pack csukuangfj; VI PhatJKK + tokens SUY TỪ `.onnx.json` đã verify 0 diff bản chuẩn; espeak-ng-data chung qua HF API) + toggle `storyPreferPiper` (mặc định bật) + nút "Cài Piper" ở Settings; producer: Piper WAV → Cloud MP3 → Direct. **Chưa kiểm chứng âm thanh — cần máy thật** (thiếu engine/voice thì rớt êm về Cloud/Android TTS).
- [x] **8.5 Seed demo** — **xong 2026-09-23; ĐÃ GỠ KHỎI APK 2026-09-25**: xóa toàn bộ `assets/demo_books/` (2 PDF EN ~6MB + 2 MD VI ~3.4MB, tổng ~9.15MB raw) theo yêu cầu "gỡ sách mẫu trong app data". Code giữ chỗ: `AssetsBookSource.list()` trả rỗng (đã bỏ fallback stub 3 sách giả), `seedDemoIfNeeded()` no-op khi assets rỗng, nút Settings "Seed Demo" → "Quét local", hint StoryScreen/Settings cập nhật. Verify: 2 APK đều **0 entry `demo_books`**, `Bibot-demo.apk` 70.64MB → **67.37MB** (`-3.27MB`), `Bibot.apk` **68.70MB**, `assembleDebug` BUILD SUCCESSFUL. Muốn thêm lại: bỏ file vào `assets/demo_books/{en,vi}/` là code tự list/seed. **(Sau clean + rebuild 2026-09-25 13:29: 60.73MB / 62.06MB — dọn artifact incremental cũ trong `build/`.)**
- **Verify:** `go vet 0`, `assembleDebug → Bibot.apk <110 MB`, `dumpsys meminfo` Book đọc 200 câu không tăng >30 MB, **UI không đơ khi mở file 5 MB md/pdf** (Cold start `iterSentences` first chunk <200 ms, frame drop <5%), `BOOK_SAVE_EVERY 8` đúng, `TextCleaner` test `Chào [...] TP.HCM 1.440` pass.
- [x] **8.6 Nút ⏮/⏭ (Rev/Next) = "search #"** — **xong 2026-09-25** (xem **§2.7**): trước đây 2 nút chỉ đổi UI → vô tác dụng; giờ `jumpChapterReq/jumpSeq` + `delayUntilDone` → ⏭ nhảy thẳng sang chương kế, ⏮ về đầu chương/chương trước, item cũ bị consumer bỏ. Xem `§2.7`.

### Build 8b — Music / Weather / News (song song, 1 ngày)
- [x] **8b.1 `MusicScreen.kt`** — **xong 2026-09-24 theo hướng mới (bỏ applet player)**: `MusicCommand.kt parseMusicCommand` (mở/phát/nghe + tên + "của" tác giả, blocklist điều khiển, bỏ dấu fuzzy) → `VoiceScreen.handleMusicCommand` local-first (1 trúng phát ngay + TTS, nhiều hỏi lại, 0 trúng → `MEDIA_PLAY_FROM_SEARCH` resolve trước + TTS) **không tốn LLM**; `MusicScreen` thành view tìm tay + "Nhạc trên máy", xóa mock SoundHelix; `MusicIndex` làm backend tìm kiếm.
- [x] **8b.2 `WeatherScreen.kt`** — **xong 2026-09-24 (HCMC + Hanoi)** `Open-Meteo` free `HttpURLConnection` không dep mới, `disconnect()` trong `finally` (bịt leak), cache `weather_{city}.json` TTL 30 phút, chọn thành phố bằng FilterChip (đổi là tải lại). **Sửa bug**: tọa độ mặc định cũ là Hà Nội nhưng nhãn ghi HCMC — nay HCMC đúng `10.8231/106.6297`.
- [x] **8b.3 `NewsScreen.kt`** — **xong 2026-09-23** `NewsApi.fetchCached` cache `cache/bibot/news.json` TTL 30p (raw text + cachedAt, mở trang đọc cache, Làm mới ép tải), `disconnect()` vào `finally` (bịt leak), headline qua `TextCleaner.cleanForTts` trong `parseNewsJson`, `LazyColumn` giữ.
- **Verify:** `Music` không giữ `index` RAM >5 MB, `Weather/News` `HttpURLConnection disconnect` + `InputStream close`.

### Build 9 — PDF→MD + Drive polish (ưu tiên 2, 2 ngày) — **áp 2.4 + 2.5 page-window & rác**
- [x] **9.1 `PdfToMarkdownHelper.kt`** — **xong 2026-09-23** page-window 1 trang `withContext(IO)`: render 1 → ML Kit → `PdfTextCleaner.cleanPdfText` từng trang → append incremental vào outbox (không gom `pages[]` RAM), `Document AI` stub giữ, `pdfChunkDir` spill mỗi 5 trang, bitmap `close/recycle` + gc/3 trang (2.3.4).
- [x] **9.2 `DriveBookSource`** — **xong 2026-09-23 (commercial only)** `list()` loop `pageToken` 100/page, `download()` alt=media + `close()` trong `finally`, `getChanges()` incremental giữ, `folderId/pageToken` migrate SharedPreferences → DataStore (`BibotPreferences.driveFolderIdFlow/drivePageTokenFlow`). **2 flavor build-time**: `demo` (`ENABLE_DRIVE=false`, stub `src/demo`, không có google-drive/api-client trong APK) / `commercial` (`ENABLE_DRIVE=true`, full `src/commercial`); `SettingsScreen` scope literal + ẩn mục Drive ở demo; `BookRepository` gate `BuildConfig.ENABLE_DRIVE`. **Mới 2026-09-24 — kho công khai "Việt Books" keyless, ổn định** (`VietBooksCatalog.kt` + `PublicDriveSource.kt`, cả 2 flavor, không tài khoản/không API key theo yêu cầu): catalog cứng 17 MD (ID trích từ embeddedfolderview, tên giữ nguyên byte UTF-8) + tải `uc?export=download` buffer 32KB + `disconnect()` finally + 1 vòng confirm-token + verify file >1KB; `source="pdrive"` về `pdfInbox` (đọc/resume như sách thường); `DRIVE_API_KEY` đã bỏ.
- [x] **9.3 `PdfJob` state DB** — **xong 2026-09-23** `pending → processing(pageCount từng trang) → done/error` (`PdfJobDao.get/upsert` đã có), resume: đọc file dở đếm `## Trang` → tiếp tục (không render lại), `MIN_FREE_MEM`: `availMem<80MB → delay 500ms + gc` trước mỗi trang; lỗi giữa chừng giữ file dở + job `error` thay vì ghi đè fallback.
- [x] **9.4 `DocBrowserScreen.kt`** — **xong 2026-09-23** SAF `DocumentFile` + `withContext(IO)` scan/copy/delete đã có; **mới**: search debounced 300ms (`LaunchedEffect delay` → `debouncedQuery`) thay vì filter mỗi keystroke.
- **Verify:** `go vet 0`, 3 PDF 10 trang ML Kit text extract đúng **không đơ UI khi render** (mở PDF 10 MB <500 ms first page), `PdfJob` resume sau kill, `cache` không vượt 200 MB, `PdfTextCleaner` header lặp loại bỏ.

### Build 10 — Memory 3 tầng + Firestore sync (ưu tiên 2, 1-2 ngày)
- [x] **10.1 `memory.go` → `MemoryRepository.kt`** — **xong 2026-09-24 (lite)** `data/memory/MemoryRepository.kt`: `observeUser` regex NAME/LIKE/DISLIKE/HOME/JOB/BIRTHDAY/REMEMBER → Room `memory_core`, `logTurn` → `chat_log` (prune 500 đã có). Chưa làm: CAT_CAPS/fact TTL/pin (T1), JSONL xoay + harvest (T2), `memory.go` Go vẫn stub.
- [x] **10.2 `recall_answer` trước LLM** — **xong 2026-09-24** `recallAnswer()` ("tôi tên gì" → "Bạn tên là An.", tên/thích/nhà/sinh nhật/nghề) return trực tiếp không tốn token; `VoiceScreen.startVoicePipelineWithHybrid` nhánh recall sau lệnh nhạc, trước LLM; log cả 2 lượt user/assistant.
- [x] **10.3 `Firestore` sync** code — **xong 2026-09-24 (còn console + test 2 máy, phía bạn)** `data/sync/FirestoreSync.kt`: init FirebaseApp thủ công từ 3 BuildConfig (không google-services plugin → build không vỡ khi thiếu json), `signInAnonymously`, merge 2 chiều theo mốc (`push` core/facts + log mới hơn `MEMORY_LAST_PUSH_TS`, `pull` upsert + log mới hơn `max(timestamp)`, batch ≤500), `observeCore` realtime (`core` only, Executor riêng); `MemorySyncWorker` 12h/khi có mạng + schedule ở `BibotApp`; `ChatLogDao.since/maxTimestamp`. Chưa cấu hình → no-op, app offline bình thường. Còn lại: bạn tạo Firebase project → bật Auth ẩn danh + Firestore → điền 3 `FIREBASE_*` vào gradle → cài 2 máy test sync + rules `auth != null`.
- **Verify:** `memory_context_line` trước `BuildSystemPrompt`, `recall "tôi tên gì"` không gọi LLM, `Firestore` 2 device sync.

### Build 11 — WorkManager + Polish nhẹ (ưu tiên 3, 1 ngày)
- [x] **11.1 `WorkManager` `CleanupWorker`** — **xong 2026-09-23 (trùng 2.1.3)** `work-runtime 2.9.0` + `CleanupWorker.kt PeriodicWorkRequest 24h KEEP` + `BibotApp.schedule()`.
- [x] **11.2 `BibotApp` `ComponentCallbacks2`** — **xong 2026-09-23** `onTrimMemory/onLowMemory` (2.2.10) + `StrictMode` debug `detectAll/detectLeakedClosableObjects/detectLeakedSqlLiteObjects` trong `BibotApp.onCreate` (chỉ `BuildConfig.DEBUG`).
- [x] **11.3 Tablet `NavigationRail`** — **xong 2026-09-23** `BibotNavSuite.kt:37` `isTablet(WindowSizeClass)` + `Row { if (tablet) NavigationRail(Home/Voice/Settings) + content weight(1f) }`, phone giữ `NavigationBar` dưới (ẩn khi tablet).
- [x] **11.4 Release diet** — **xong, đã verify `assembleDemoRelease` 2026-09-24**: R8 minify+shrink OK (demo release 43.5MB vs debug ~64MB, reflection Vosk/Room giữ qua keep rules), ký bằng `bibot-test.keystore` (TEST ONLY). Trước khi phát hành Play: tạo keystore thật, thay `signingConfigs.testRelease`. Debug không ảnh hưởng.
- **Verify:** `adb shell dumpsys meminfo` sau 10 rotate không leak, `adb backup` exclude `cache/no_backup` đúng, `assembleRelease` `aab <40 MB`.

---

## 4) Cấu hình Build với `H:\androidkit` (máy yếu dev)

```properties
# H:\Pi4_Xiaozhi\android_app\android\local.properties
sdk.dir=H\:\\androidkit\\sdk
# Dùng JDK H:\androidkit\jdk17, không dùng JDK 8 hệ thống
# Gradle JDK trong Android Studio → H:\androidkit\jdk17
```

```bash
# Kiểm tra
H:\androidkit\jdk17\bin\java.exe -version # 17.0.17 Temurin
H:\androidkit\sdk\cmdline-tools\latest\bin\sdkmanager.bat --list | findstr "platforms;android-34"
# Build
cd H:\Pi4_Xiaozhi\android_app
go vet ./... # 0
go build ./...
cd android
# Đảm bảo dùng JDK 17 cho Gradle
set JAVA_HOME=H:\androidkit\jdk17
set ANDROID_HOME=H:\androidkit\sdk
.\gradlew :app:assembleDebug --info # → android\app\build\outputs\apk\debug\Bibot.apk
.\gradlew :app:assembleRelease # cần bibot.keystore (tạo keytool -genkeypair)
adb install -r app\build\outputs\apk\debug\Bibot.apk
adb shell dumpsys meminfo com.bibot.app | findstr "TOTAL PSS"
adb logcat -s Bibot:V
```

**Heap Gradle (quan trọng khi Clean + Rebuild):** `android/gradle.properties` đang `org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m` — **tăng từ `-Xmx2048m` ngày 2026-09-25** vì `gradlew clean` rồi `assembleDebug` bị **`D8: java.lang.OutOfMemoryError: Java heap space` ở task dex-merge** (merge 19 dex của APK ~61MB cần >2GB; build incremental thì không bị). Máy 16GB RAM: giữ 4096m. Máy 4 GB: hạ `-Xmx1g` + `org.gradle.parallel=false`; `daemon false` khi RAM <2 GB.

---

## 5) Verify chung (≥10 lần như Pi `REPO_SYSTEM_PROMPT.md:110`)

> **Cập nhật 2026-09-25**: `go vet:0` `go build:0` cho cả `bibot/core` + `bibot-relay` đã xác minh. `assembleDebug` 2 flavor **BUILD SUCCESSFUL** (không có thiết bị/emulator để cài test).

| # | Test | Pass | Ghi chú RAM | Trạng thái 2026-09-25 |
|---|---|---|---|---|
| 1 | `go vet ./...` + `go build ./...` | **0** ✅ | Go stdlib, không pull TTS SDK nặng | **Đã verify 2026-09-24** |
| 1.5 | `go vet ./relay/...` + `go build ./relay/...` | **0** ✅ | Relay stdlib-only (~170 dòng) | **Đã verify 2026-09-24** |
| 2 | `assembleDebug` 2 flavor (**Clean + Rebuild 14:31 — biên dịch lại toàn bộ**) | **Thành công** ✅ | `Bibot.apk` **62.08MB** / `Bibot-demo.apk` **60.75MB** (19 dex, 731 entry, `demo_books=0`, `appIdOK=True`, dex có `models_in`/`sideload`/`espeak-ng-data.zip`/`jumpChapterReq`/`PAUSE2SEC`/`stripHtml`); lần trước 13:57: 62.15/60.82, clean 13:29: 62.06/60.73, trước clean: 68.70/67.37 | **Đã verify 2026-09-25 14:31** |
| 2.5 | `BlockTextReader` + `TextCleaner` unit-test JVM (64 assertion) | **64/64 PASS** ✅ | Stream 16 KB (md) / 64 KB (pdf), không `readText()`/`readBytes()` toàn file; lọc rác HTML/markup sách convert | **Đã verify 2026-09-25** |
| 3 | `gomobile bind` | `bibot.aar` ~2 MB | Chỉ REST SSE, không `cloud.google.com/go` | Chưa test (cần Android SDK) |
| 4 | `assembleDebug` 2 flavor | APK ~64/66MB | Thực tế <110 MB debug; release `aab` <40 MB khi có keystore | Chưa test (cần Android SDK) |
| 5 | Cài + persona `Em là Bi` không cần key | Greeting đúng | `DataStore` + `BuildSystemPrompt` | Code ready |
| 6 | STT standard không key `vi-VN` | SpeechRecognizer OK, hộp xin `RECORD_AUDIO` hiện lần đầu, từ chối báo lỗ�i thân thiện | `HybridSttManager` không giữ `AudioRecord` sau `onDispose`; `startStt→beginStt` (`VoiceScreen.kt:212`) | Code ready |
| 6b | **Vosk STT models** (vi-small/en-small) | Vosk `vosk-model-small-vn-0.4.zip` (~32MB) + `vosk-model-small-en-us-0.15.zip` (~39MB) — tải offline `no_backup/models`, giải nén tại `voskModelDir` | File `Gói ngôn ngữ Piper\` đã có 2 zip này (together 71.4MB); `ModelCatalog` đăng ký `vosk-vi-small`/`vosk-en-small` (+ `sherpa-vi-zipformer` 2026-09-30, STT chính); `OfflineSttHelper` dùng `assets/vosk/vosk-android-0.3.45.jar` reflection load; toggle `sttOfflineFirst` (Settings) | Code ready + 2 zip đã có trong `Gói ngôn ngữ Piper`; **cài không cần mạng qua file picker §2.8.7** (2026-09-30 thay sideload) — chờ test máy thật |
| 7 | TTS offline không key `vi-VN` | Android TTS OK | `OfflineTtsHelper.speakOffline QUEUE_FLUSH` + `stop()` | Code ready |
| 7b | **Piper TTS on-device** (sherpa-onnx) | Piper EN/VI WAV → Cloud MP3 → Direct Android TTS | `PiperTtsHelper.kt` (EN csukuangfj pack, VI PhatJKK + `.onnx.json` tokens suy từ), `SherpaEngine.kt` (tar.bz2 extract `no_backup/sherpa`, `System.load` 3 `.so`), `Story_Prefer_Piper` toggle (mặc định bật) + nút "Cài Piper" Settings; producer: Piper WAV→Cloud MP3→Direct. **Chưa kiểm chứng âm thanh** — cần máy thật (thiếu engine/voice rớt êm về Cloud/Android TTS). | Code ready + build verify + **2026-09-30: chat dùng chain Piper→Cloud→Offline + slider 0.5x–2.0x + dropdown giọng; cài offline qua file picker §2.8.7** (engine tarball + 2 voice + `espeak-ng-data.zip`) — chờ test máy thật |
| 8 | Voice E2E `STT→LLM mock→Chunk→TTS offline` 3 case | Full loop 4-12 chunk | `VietnameseChunker 4/12/120` + `CloudTtsBridge` `disconnect` | Code ready |
| 9 | Book 200 câu `BOOK_SAVE_EVERY 8` | `dumpsys meminfo` delta <30 MB | `iterSentences Flow` yield + `Channel(2)` flip-flop + `del pcm` | Code ready |
| 9.5 | **Rev/Next nhảy chương** (Book + Story) | ⏭ → chương kế ngay (không chờ câu xong); ⏮ → đầu chương → chương trước; item cũ bị bỏ qua | `jumpChapterReq` poll đầu vòng + `jumpSeq` stamp/loại item + `delayUntilDone` (`core/tts/TtsPause.kt`) | **Code ready 2026-09-25** — `assembleDebug` BUILD SUCCESSFUL, **cần test máy thật** |
| 10 | PDF 10 trang ML Kit | `PdfJob` pending→done, `pdfs/cache/chunks` LRU | `PdfRenderer` bitmap `close()` + `System.gc()` mỗi 3 trang | Code ready |
| 11 | Temp chunk TTL/LRU | `cleanupCache 150/200 MB 24h` `backup_rules.xml` | `cacheDir/bibot` + `no_backup/models` exclude | Code ready |
| 12 | `onTrimMemory` `TRIM_MEMORY_UI_HIDDEN` | `pause+cleanupCache 80MB` | `BibotApp ComponentCallbacks2` | Code ready |
| 13 | Model download 42MB vosk-vi `HttpURLConnection 32KB` | Progress + verify + `no_backup` | `freeSpace` check + `unzip` `delete tmp` | Code ready |
| 13.5 | **Cài model không cần mạng** (file picker; trước đó sideload `push_models.ps1` → `models_in`) | **2026-09-30**: nút “Cài từ file” từng model/engine/espeak → cài xong 0 request; “Cài Piper (offline)” xong `isVoiceComplete=true` | `ModelDownloader.installFromFile` + `SherpaEngine.installEngineFromFile` + `PiperTtsHelper.installVoiceFromFile/installEspeakFromFile` (staged* deprecated) | **Code ready 2026-09-30** — **cần test máy thật** |
| 14 | Firestore 2 device sync `MemoryCore` | Sync OK | `snapshotListener` batch `upsert` | Code ready, cần Firebase config |

---

## 6) Quy tắc cập nhật file này

- Mỗi Build xong: `go vet:0`, `assembleDebug → Bibot.apk`, `dumpsys meminfo` snapshot, tick `[x]` trong file này + cập nhật `Readme.md §10` + `Tóm_tắt_hiện_tại.md`.
- Không thêm dep mới nếu chưa cân RAM; mọi `ByteArray`/`Bitmap` >512 KB phải spill `cache` + `delete` lập tức.
- Máy yếu test thực: Samsung J2 (1.5 GB) hoặc emulator `RAM 1536 MB heap 256 MB`.

> File này là single source cho kế hoạch build nhẹ Bibot. Cập nhật sau mỗi build.

---

## 7) Remaining work — cập nhật 2026-09-25

| # | Task | Người | Trạng thái | Ghi chú |
|---|---|---|---|---|
| R1 | `go vet:0` `go build:0` toàn repo | ✅ **Đã verify** | **0** | Cả `bibot/core` + `bibot-relay` |
| R2 | `assembleDebug` 2 flavor | ✅ **Đã verify** | **Thành công** | `Bibot.apk` 62.08MB / `Bibot-demo.apk` 60.75MB — **Clean + Rebuild 14:31** (biên dịch lại toàn bộ sau khi sửa bug `./jniLibs` §2.8.2; 19 dex, dex có code mới); rebuild 13:57: 62.15/60.82, clean 13:29: 62.06/60.73, trước clean 68.70/67.37 |
| R3 | Deploy `bibot-relay` lên Cloud Run | Phía bạn | **Chưa deploy** | `gcloud builds submit + run deploy`, test curl, điền URL vào `build.gradle.kts:27` |
| R4 | Firebase config: tạo project, bật Auth ẩn danh + Firestore, điền 3 `FIREBASE_*` | Phía bạn | **Chưa config** | `build.gradle.kts:30-32` đang `""` |
| R5 | TCVN3 full byte-level convert | Đã xong 2026-10-10 | `Tcvn3.kt` + `BlockTextReader` font-aware + `pdf.go` mirror (VNI chưa — tone rời cần engine riêng) | `PdfTextCleaner.convertTcvn3VniToUtf8` |
| R6 | AdMob setup: tạo account, APP_ID + Unit IDs, thay placeholder | Phía bạn | **Code ready** | `AdConfig.kt` placeholder `ca-app-pub-XXXXXXXX`; `build.gradle.kts` test APP_ID |
| R7 | Test trên máy thật | Phía bạn | **Chưa test** | Verify `dumpsys meminfo`, `assembleDebug`, UI không đơ |
| R8 | Chuyển demo nặng sang Drive/download-on-demand | Phía bạn | **Đề xuất** | APK demo 70.6MB (có AdMob), cần tách sang `commercialImplementation` |
| R9 | Verify Sherpa/Piper audio | Phía bạn | **Chưa kiểm chứng** | `Build 8.4` Sherpa engine chưa test âm thanh trên máy thật |
| R10 | Tách `implementation(libs.admob)` → `"commercialImplementation"` | Phía bạn | **Cần làm** | Hiện tại cả 2 flavor đều có AdMob để biên dịch được |
| R11 | Cập nhật `Readme.md §10` + `Tóm_tắt_hiện_tại.md` | ✅ **Đã cập nhật** | ✅ **Xong** | Phản ánh trạng thái 2026-09-25 (lần 33: sideload model offline §2.8; lần 32: block loading + rác HTML + PAUSE2SEC + Rev/Next) |
| R12 | **Lazy Block Loading** chống đơ/lag khi nạp & đọc sách/truyện | ✅ **Xong 2026-09-25** | **Code + test JVM** | Mới tạo `core/text/BlockTextReader.kt`; `BookRepository.iterSentences`/`PdfToMarkdownHelper`/`PdfScreen`/`PublicDriveSource` đổi sang stream; `BookScreen`+`StoryScreen`: `loadBook/loadStory` chạy trên IO, `switchChapter()`, render `LazyColumn`; unit-test **64/64 PASS**; **còn test máy thật** |
| R13 | **Hoàn thiện `TextCleaner` lọc rác HTML/markup** sách convert | ✅ **Xong 2026-09-25** | **Code + test JVM** | `stripHtml()` (entity/comment/tag, kể cả comment cắt giữa khối) + `stripMarkup()` (bảng/quote/bullet/heading line+inline) chạy đầu `cleanForTts` và trong `cleanPdfText` trước khi gộp dòng; `CloudTtsBridge` light-cleanup gọi lại + bỏ `PAUSE2SEC`; `BlockTextReader` tách chương theo marker `<!-- CHAPTER-BREAK … title=… -->`. Test: sample rác của sách → còn `Hết Truyện… PAUSE2SEC Truyện Cây tỉnh (mộc tỉnh)` |
| R14 | **PAUSE2SEC → khoảng lặng 2s thật**, marker không bao giờ bị TTS đọc | ✅ **Xong 2026-09-25** | **Code + test JVM** | Sửa bug Book/Story synthesize bằng `clean` (chứa marker) → Android TTS đọc "PAUSE 2 SEC" → đổi sang `plain`; `VoiceScreen.speakChunks` thêm `splitPause` + `delay(2000)`; `stripPauseMarker()` phòng thủ trong `OfflineTtsHelper`/`PiperTtsHelper`/`CloudTtsBridge`; UI hiện `⏸2s`; `NewsScreen` hiển thị không marker. Test JVM 64/64 PASS |
| R15 | **Nút Rev/Next (⏮/⏭) trong sách & truyện thật sự có tác dụng** — "search #" | ✅ **Xong 2026-09-25** | **Code** | Trước đây 2 nút chỉ đổi `currentSentence` (UI) → loop đọc không đọc theo → **bấm vô tác dụng**. Giờ có `jumpChapterReq/jumpSentenceReq/jumpSeq`: producer poll ở đầu vòng lặp → tách câu chương đích trên IO rồi đọc tiếp; **⏭ = nhảy thẳng sang chương sau** (đi qua `##`/heading), **⏮ = về đầu chương hiện tại, đang ở đầu → chương trước**; `requestJump()` khi đang đọc dừng audio cũ (`playerVm.stopPlayback()`, **không** gọi `offlineTts.stop()` để tránh kẹt `speakOffline`), khi đang dừng thì `switchChapter()` như tab. Item mang `seqAtStart` → consumer bỏ item lệch `jumpSeq` (không kể nhầm chỗ cũ); `delayUntilDone()` (mới, `core/tts/TtsPause.kt`) cắt delay "chờ phát xong" mỗi 100ms → bấm là nhảy ngay, không dead-air. Chạy được cả 2 flavor |
| R16 | **Cài model offline từ file PC có sẵn** (sideload `models_in`, không tải lại 236MB) | ✅ **Xong 2026-09-25 (code)** | **Code + script PC** | Trước đây 3 đường tải đều hardcode mạng → 7 file trong `Gói ngôn ngữ Piper\` vô dụng. Nay: `FilePaths.modelStagingDir()` + `ModelDownloader.stagedFile` + `SherpaEngine.stagedTarball` + `PiperTtsHelper.stagedOnnx/stagedJson/stagedEspeakZip` + UI “Cài từ file sideload”/“Cài Piper (offline)” + `OfflineSttHelper` chọn được `vosk-en`; script `push_models.ps1` (fallback `run-as`) & `download_espeak.ps1` (đã chạy: `espeak-ng-data.zip` 8.8MB). **Bug nặng tìm ra khi verify:** tarball release dùng entry `./jniLibs/…` → code cũ `startsWith("jniLibs/")` không match → **Piper không bao giờ cài được (cả mạng lẫn sideload)**, đã fix `removePrefix("./")` (`SherpaEngine.extractSo`). Đã dọn thư mục model: giữ 8 file app đọc, xoá `extracted/` (rỗng) + `espeak-ng-data/` (trùng zip, 17.16MB). **Còn: test máy thật** (§2.8.5) — `adb devices` đang trống |

**Lưu ý R10:** Đã tạm chuyển `implementation(libs.admob)` (thay vì `commercialImplementation`) để cả 2 flavor đều biên dịch được vì `AdManager.kt` và `AdsPreferences.kt` trong `src/main/java/` có tham chiếu đến `com.google.android.gms.ads.*`. Khi triển khai Play Store, cần:
- Tạo source set `src/commercial/java/` cho `AdManager.kt`, `AdConfig.kt`
- Hoặc dùng `BuildConfig.ENABLE_DRIVE` để guard runtime
- Demo flavor sau đó không có AdMob dependency → APK nhẹ hơn ~3MB

---

## 8) Quảng cáo (AdMob) — Implement sẵn, cần setup

> **File thiết kế chi tiết:** `AD_DESIGN.md`

**Kiến trúc:**
- `data/ads/AdManager.kt` — Singleton AdMob init/load/show
- `data/ads/AdsPreferences.kt` — DataStore: ad-free state, click count, quota
- `ui/components/ads/AdsBannerComposable.kt` — Banner (bottom)
- `ui/components/ads/AdsInterstitialHelper.kt` — Interstitial (between modes) + Rewarded
- **Commercial only:** `"commercialImplementation"(libs.admob)` — demo không có ads

**Quy tắc:**
- Voice chat: **KHÔNG quảng cáo** (giữ voice-first)
- Banner: Home/Book/News/Weather/PDF/Music screens
- Interstitial: 1/3 lần chuyển mode, max 1/30 phút
- Rewarded: Xem ad → nhận ad-free 24h hoặc +10 tin/ngày
- RAM overhead: ~10-15MB peak (vẫn trong target <250MB)

**Đã fix crash `MobileAdsInitProvider: Invalid application ID` (2026-09-25):**
- Nguyên nhân: APK cũ khai báo `android:value="${ADMOB_APP_ID}"` nhưng không có `manifestPlaceholders` → manifest merge giữ nguyên chuỗi placeholder → SDK reject.
- Fix: `AndroidManifest.xml:22` hardcode `ca-app-pub-3940256099942544~3347511713` (sample App ID chính thức của Google). Verify bằng `aapt2 dump xmltree` trên cả 2 APK → `appIdOK=True`.
- `AdConfig.kt`: 3 ad unit `ca-app-pub-XXXXXXXX/...` → test ID thật (`/6300978111` banner, `/1033173712` interstitial, `/5224354917` rewarded) để load được ngay.

**Cần làm:**
1. Tạo AdMob account → lấy APP_ID + 3 Ad Unit IDs thật
2. Thay App ID trong `AndroidManifest.xml:24` + `build.gradle.kts:34` + 3 ID trong `AdConfig.kt`
3. Tách `implementation` → `commercialImplementation` (task R10)
4. Test `assembleCommercialDebug` trên máy thật
5. Verify `dumpsys meminfo` → Play Store

**Build 2026-09-25 (mới nhất):**
- **Clean + Rebuild 2026-09-25 13:29** (`gradlew clean` → `assembleDebug`): BUILD SUCCESSFUL → `Bibot-demo.apk` **60.73 MB** + `Bibot.apk` **62.06 MB** (hồi build incremental trước lần lượt 67.37 / 68.70 MB — **−6.6 MB do dọn artifact cũ trong `build/`**, không đổi source/dependency). Verify: `demo_books=0`, `appIdOK=True`, 19–20 dex, 6 `.so` (arm64+arm32: mlkit/vosk/datastore), **code mới nằm trong dex** (`delayUntilDone`, `jumpChapterReq`, `stripPauseMarker`, `stripMarkup`, `CHAPTER-BREAK`, `PAUSE2SEC` grep được ở `classes6/12/13.dex`).
- `assembleDebug` BUILD SUCCESSFUL → `Bibot.apk` **68.70 MB** + `Bibot-demo.apk` **67.37 MB** (đã gỡ sách mẫu −3.27 MB) — *bản incremental trước khi clean*
- Go: `go vet:0` `go build:0` cho cả `bibot/core` + `bibot-relay`
- AdMob dependency: `implementation(libs.admob)` để cả 2 flavor biên dịch được (task R10 chưa tách)

> File này là single source cho kế hoạch build nhẹ Bibot. Cập nhật sau mỗi build.
