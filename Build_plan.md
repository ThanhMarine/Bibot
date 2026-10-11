# Build Plan — Bibot Android

> **Ngày:** 2026-09-22 | **Kho:** `root/android_app` | **APK:** `Bibot.apk` `com.bibot.app` | **Persona:** `Em là Bi`
> **Trạng thái Go:** `go vet:0` `go build:0` | **Kotlin:** scaffold + logic hybrid, chưa `assembleDebug` trên CI

---

## 1) Đánh giá hiện trạng (tổng rà soát 2026-09-22)

### 1.1 Đã hoàn thành vững (có thể build)

| Trục | File chính | Mức độ | Ghi chú |
|---|---|---|---|
| **Go core** | `go.mod:1` Go 1.22, `internal/config/config.go:30` `internal/tts/tts.go:1` `internal/chunker/chunker.go:8` `internal/llm/llm.go:11` | 90% | `CleanTTSText` full port `streaming_llm.py:143-208`, `Chunker` port `vietnamese_chunker.py:36` 4-12 từ, `BuildSystemPrompt` + `UpdateBotPrompt` + `BotPromptManager` đồng bộ, `streamGemini/streamOpenRouter` REST SSE đã xong. `Synthesize` còn stub. |
| **Android base** | `android/gradle/libs.versions.toml:1` AGP 8.5.2/Kotlin 2.0/media3 1.3.1/room 2.6.1/datastore 1.1.1 | 100% | KTS + version catalog + `gradlew` đầy đủ, `namespace com.bibot.app`, `Bibot.apk` rename `app/build.gradle.kts:50`. |
| **Storage chuẩn** | `data/files/FilePaths.kt:22` | 95% | `filesDir/bibot` (pdfs, books, state, datastore), `cache/bibot` (audio/tts, chunks, pdf/cache), `noBackup/bibot/models`, `databases/bibot.db`, `externalFiles/exports`. `ensureAll/cleanupCache(200MB/24h)` `chunkTempDir/ttsChunkFile` đã thêm. |
| **DataStore + Room** | `data/store/BibotPreferences.kt:14` `data/store/BotPromptManager.kt:11` `data/db/BibotDatabase.kt:13` | 90% | Prefs `persona/bot_prompt/style/tone/maxWords` + `voiceSpeed/sttOfflineFirst`, `BotPromptManager` template `{{persona}} {{datetime}} {{style}} {{tone}} {{maxWords}}` preview. Room 5 entity `MemoryCore/Facts/ChatLog/BookProgress/PdfJob`. Chưa wired `Firestore` sync. |
| **UI hệ thống** | `ui/theme/*`, `ui/adaptive/WindowSize.kt:11`, `ui/components/BigButton.kt:11` | 90% | `BigButton 64/72dp`, `BigIconButton 72/96dp`, `BigModeTile 96dp`, `DevicePosture` Phone/Tablet, `material3-window-size-class 1.2.1`. |
| **Main menu** | `ui/menu/HomeMenu.kt:11` + `BibotNavSuite.kt:32` | 85% | Home tinh gọn hero gradient `🎙️ Trò chuyện` 140dp + grid `Adaptive 150/180dp` 6 tile BOOK-STORY-MUSIC-WEATHER-NEWS-PDF, bottom bar chỉ `🏠/🎙️/⚙️` + `ArrowBack`. Đẹp, 1 chạm. |
| **Player** | `ui/player/PlayerViewModel.kt:16` `BibotPlayerBar.kt:36` | 85% | `AndroidViewModel` + `ExoPlayer`, handler 500ms, `seekBack/Forward 10s`, `setSpeed`, `playUrl/playBytes`, nút 56-64dp. Chưa nối TTS bytes thật từ Cloud. |
| **Bot Prompt** | `data/store/BotPromptManager.kt:11` `feature/settings/SettingsScreen.kt:11` | 90% | Mục chính thức ⚙️ → 🤖 Bot Prompt & Cá nhân hoá: persona, template multiline, style/tone dropdown, maxWords chips, preview, Lưu/Mặc định → `DataStore` + sync Go `UpdateBotPrompt` (gomobile `cmd/mobile/mobile.go:22`). `VoiceScreen.kt:22` đã dùng `buildFromPrefs`. |
| **Offline fallback** | `data/offline/OfflineSttHelper.kt:19` `OfflineTtsHelper.kt:11` | 80% | `SpeechRecognizer vi-VN` (không key) → fallback Vosk `vosk-model-small-vn-0.4` (reflection, `AudioRecord 16kHz`), `Android TTS vi-VN` → `Cloud TTS` hybrid `AUTO/OFFLINE/ONLINE`. Model chưa tải sẵn. |
| **Model catalog** | `data/offline/ModelCatalog.kt:18` `ModelDownloader.kt:18` `ModelsViewModel.kt:16` | 80% | 4 model vosk-vi/en, piper-vi/en, `DownloadState` progress/unzip/verify, `HttpURLConnection` 32KB, freeSpace check, `SettingsScreen` 📦 card. Chưa test tải thực tế ~40-63MB. |
| **Kho sách** | `assets/demo_books/` 2 PDF + 2 MD thực tế + `data/books/BookRepository.kt:18` + `DriveBookSource` 2 flavor | 95% | Demo seed `assets → files/bibot/books`, Drive commercial (pageToken loop, `getChanges`, folderId DataStore, `ENABLE_DRIVE` gate) / demo stub, `PublicUrlSource`, aggregate `StateFlow`. UI Kho sách trong Settings (Folder ID, Đồng bộ, Seed, URL). `BookScreen`/`StoryScreen` đọc thật (Channel(2), resume, heading split). |
| **Backup** | `res/xml/backup_rules.xml` + `data_extraction_rules.xml` `AndroidManifest.xml:11` | 100% | Exclude `cache/bibot`, `no_backup/bibot/models`, `external`. |
| **Temp chunk** | `FilePaths.kt:50` `chunkTempDir/ttsChunkFile/cleanupCache` + `CleanupWorker` daily | 100% | TTL 24h LRU 200MB + `WorkManager` định kỳ (`work-runtime`). |

### 1.2 Nửa vời / Stub (cập nhật 2026-09-24)

- **Go `internal/tts/tts.go:214` `Synthesize`**, `internal/stt/stt.go`, `internal/pdf/pdf.go`, `internal/memory/memory.go` — chỉ stub, chưa gọi `cloud.google.com/go/texttospeech` / `Document AI`. (Kotlin đã có `MemoryRepository` lite + `PdfTextCleaner` riêng.)
- **Android `feature/*` screens** — đã nối thật: Book/Story (Channel(2) + resume + ExoPlayer), Music (lệnh thoại + tìm local, bỏ mock), Weather (cache 30p), News (cache 30p + clean), PDF (ML Kit page-window + resume).
- **Cloud wiring** — `LlmBridge.generateStreamRest` Kotlin đã REST streaming Gemini/OpenRouter, Go `internal/llm` cũng REST, nhưng chưa có `GOOGLE_APPLICATION_CREDENTIALS` flow cho Cloud STT/TTS, chưa test quota.
- **Firestore sync** — `libs.versions.toml` đã có `firebase-firestore/auth` nhưng chưa code repo sync `MemoryCore/Facts`.

### 1.3 Rủi ro / Thiếu (cập nhật 2026-09-24)

- Chưa `assembleDebug` trên CI (thiếu SDK local), chưa test Vosk/Piper tải thực tế ~40-63MB, Drive `GoogleSignIn` chưa có `requestConsent` flow đầy đủ.
- `VoiceScreen` đã phát TTS sau LLM streaming (chunk + `speakChunks` + `RECORD_AUDIO` runtime); Voice `speakChunks` vẫn sequential (chưa drain theo STATE_READY).
- `cleanupCache` đã scheduled `WorkManager` daily + `onTrimMemory`; release cần keystore để verify `aab`.

---

## 2) Kế hoạch Build tiếp theo

### Build 7 — TTS/STT Cloud hoàn thiện + Voice E2E (ưu tiên 1, 2-3 ngày) ✅ **XONG 2026-09-22**

- **Go:** Implement `tts.Synthesize` gọi `cloud.google.com/go/texttospeech` (WaveNet `vi-VN-Neural2-A`, 22050Hz) + fallback `android TTS` đã có. `stt` gọi Cloud STT v2 nếu có key.
- **Kotlin:** `VoiceScreen` sau `LlmBridge` → chunk `BotPromptManager` → `HybridTtsManager.speak` → `PlayerViewModel.playBytes`. Nối `SttBridge` → `LlmBridge` → `TTS` full loop.
- **Verify:** `go vet/build:0` + manual test STT vi-VN (không key) → LLM mock → TTS offline, rồi với Gemini key → stream thật.

### Build 8 — Book/Story Reader (ưu tiên 1, 2 ngày) ✅ **XONG (mở rộng 2026-09-23/24)**

- **UI:** `BookScreen` list `BookRepository.books` (local + Drive), `StoryScreen` đọc `vi/*.md` heading Chương/Phần/Hồi, `pdf` → `Document AI` stub. `BookProgress` Room `BOOK_SAVE_EVERY=8`, Channel(2) flip-flop producer/consumer, resume announce, `STORY_PLAYBACK_SPEED 0.88`.
- **Seed:** demo thực tế `en/` 2 PDF + `vi/` 2 MD (thay kế hoạch Gutenberg cũ).
- **Mở rộng 2026-09-25 — chống đơ + điều khiển đọc:**
  - **Lazy Block Loading** `core/text/BlockTextReader.kt` (stream 16KB/64KB, không `readText()`), load/tách chương/tách câu trên IO, render `LazyColumn` — `Bibot_build.md §2.4`, test JVM **64/64 PASS**.
  - **Rev/Next = "search #"** `jumpChapterReq/jumpSeq` + `delayUntilDone` → ⏭ sang chương kế, ⏮ về đầu chương/chương trước, item cũ bị bỏ — `Bibot_build.md §2.7`.
  - **PAUSE2SEC → khoảng lặng 2s** thật, marker không bao giờ tới engine TTS — `Bibot_build.md §2.5`.
  - **Model offline cài không cần mạng** — 2026-09-25 sideload thư mục `models_in` + nút “Cài từ file sideload”; **2026-09-30 file picker SAF thay thế** (nút “Cài từ file” từng model/engine/espeak, staged* deprecated) + fix sherpa-vi tar.bz2 (nhánh riêng, verify 4 file, tự cài engine) — `Bibot_build.md §2.8` (2.8.7).
- **Mở rộng 2026-09-30 — voice chat:** tách `VoiceChatViewModel` (state machine + pipeline + stats) khỏi `VoiceScreen`; duplex thật (`NlmsAec` port `aec.py` + barge-in monitor port `barge_in_detector.py`, pref `bargeInEnabled`); chain Piper→Cloud→Offline + slider 0.5x–2.0x + dropdown giọng; chống ảo giác (`HARD_RULES` + memory guards) + ngữ cảnh `chatHistory` (gating tiết kiệm token); fix Điểm tin chết (AndroidViewModel + auto-read Channel); BỎ P4 llama.cpp — chi tiết `CHECKLIST.md` + `Tóm_tắt_hiện_tại.md` lần 34.

### Build 8b — Music/Weather/News (ưu tiên 2, cùng ngày) ✅ **XONG (mở rộng 2026-09-24)**

- **Music (hướng mới — bỏ applet player):** lệnh thoại `parseMusicCommand` local-first → `MusicIndex` → Intent app ngoài; `MusicScreen` thành view tìm tay.
- **Weather:** Open-Meteo + cache 30p + `disconnect()` trong `finally` (HCMC; Hanoi để sau).
- **News:** Gemini Flash REST + cache 30p + headline qua `cleanForTts`. Cần Gemini API Key từ Settings.

**Verify:** `go vet:0`, all screens use `LaunchedEffect` + coroutines for API calls, `HttpURLConnection` for network (no new deps).

### Build 9 — PDF→MD + Drive polish (ưu tiên 2, 2 ngày) ✅ **XONG (mở rộng 2026-09-24)**

- **PDF:** ML Kit on-device page-window 1 trang + append incremental + resume `PdfJob` + MIN_FREE_MEM, `Document AI` REST stub. `pdfChunkDir` spill.
- **Drive:** 2 flavor build-time (demo stub / commercial full): `list()` pageToken 100/trang, `download()` alt=media + close `finally`, `getChanges()` incremental, folderId/pageToken qua DataStore, gate `ENABLE_DRIVE`.
- **Settings:** `BookLibrarySection()` — Sign-In, Folder ID, incremental sync, link download (mục Drive ẩn ở bản demo).
- **Verify:** demo PDF `%PDF-1.4/1.7` valid, `assembleDemoDebug + assembleCommercialDebug` BUILD SUCCESSFUL.

### Build 10 — Firestore sync + Memory 3 tầng (ưu tiên 2, 1-2 ngày) — memory lite ✅ 2026-09-24, còn Firestore

- Đã có: `MemoryRepository` (observe regex + `recallAnswer` trước LLM + logTurn), `memoryContextLine()` để sẵn.
- Còn lại: wire `MemoryCore/Facts/ChatLog` ↔ `Firestore` (user=`FirebaseAuth`), sync offline-first qua `WorkManager`.

### Build 11 — WorkManager + Polish (ưu tiên 3, 1 ngày) ✅ **XONG 2026-09-24**

- `CleanupWorker` daily, `StrictMode` debug + LeakCanary, tablet `NavigationRail`, release diet (minify/shrink/resConfigs/abiFilters), `RECORD_AUDIO` runtime, flavors demo/commercial. Còn lại: verify `aab` khi có keystore.

---

## 3) Thứ tự thực hiện (khuyên)

1. **Build 7** (Voice E2E) — demo trò chuyện thông minh bắt buộc có.
2. **Build 8** (Book/Story) — demo 2 PDF + 2 MD thực tế đọc được.
3. **Build 9** (PDF→MD + Drive) — official kho sách (commercial).
4. Build 10 (Firestore) + 2.6.4 (relay) song song.

Mỗi build xong: `go vet ./... && go build ./...` `0`, `./gradlew :app:assembleDemoDebug :app:assembleCommercialDebug` ra `Bibot-demo.apk`/`Bibot.apk`, test 3 case `persona Em là Bi` / STT không key (+hộp quyền mic) / TTS offline, cập nhật `Readme.md` §10 + `Bibot_build.md`.

---

## 4) Checklist trước Build 10

- [x] `FilePaths.ensureAll()` + `seedDemo()` on `MainActivity.onCreate` (Build 7 đã thêm)
- [x] Demo books thực tế `en/` 2 PDF + `vi/` 2 MD (thay kế hoạch Gutenberg cũ)
- [x] Build 9 PDF→MD + Drive complete (2 flavor)
- [x] `assembleDemoDebug + assembleCommercialDebug` BUILD SUCCESSFUL (JDK 17 + `ANDROID_HOME=H:\androidkit\sdk`)
- [x] `WorkManager` dependency đã thêm (Build 11 xong)

> File này là single source cho kế hoạch build tiếp theo. Cập nhật sau mỗi build.
