# Bibot — Android App (Pi4 XiaoZhi → Mobile)

> **APK:** `Bibot.apk` | **Package:** `com.bibot.app` | **Persona:** `Em là Bi`
> **Kho xử lý:** `root/android_app` (thay thế `windows_app/`) | **Mô hình:** Go online + Google Services + Hybrid offline fallback

---

## 1) Tổng quan

Bibot là bản di động hoá của Pi4 XiaoZhi (`Tóm_tắt_hiện_tại.md:9-21`, `REPO_SYSTEM_PROMPT.md:31-44`) — bỏ phụ thuộc Pi 4/ALSA/offline heavy, chuyển sang **online-first trên Android, tối ưu mobile**, khai thác Google service, fallback offline khi mất mạng.

| Thuộc tính | Pi gốc | Bibot Android |
|---|---|---|
| TTS | PhatJKK in-process + Piper + Edge (22050Hz) `services/config.py:142` | **Cloud TTS** WaveNet/Neural2 vi-VN + en-US → fallback **`Android TTS` on-device** (`data/offline/OfflineTtsHelper.kt:11`) |
| STT | Sherpa Zipformer offline-first `config.py:99` | **`Android SpeechRecognizer` standard (không cần key)** → fallback **Vosk small** `data/offline/OfflineSttHelper.kt:19` |
| LLM | OpenRouter `nex-n2.5-mini:free` + Gemini fallback `config.py:71` | **Gemini 2.5/3.6 Flash** `internal/llm/llm.go:11` REST streaming + OpenRouter fallback; mock khi chưa có key |
| PDF→MD | `pdf_to_md.py` 3-tier + Tesseract `windows_app/pdf_engine.py:1` | **`ML Kit Text Recognition` on-device** + `Document AI` REST stub (`PdfToMarkdownHelper.kt`) |
| Doc Browser | — | **`DocBrowserScreen.kt`** — SAF file browser, search, add→Sách, xoá (demo replaces PDF→MD) |
| Audio | `audio_streamer.c:27` ALSA 8192/2048 + flip-flop `audio_stream.py:765` | `ExoPlayer` + `MediaSession` (`ui/player/PlayerViewModel.kt:16` AndroidViewModel, handler 500ms) |
| Trí nhớ 3 tầng | `conversation_memory.py:1-637` JSONL | `Room DB bibot.db` (`data/db/BibotDatabase.kt:13`) + `DataStore` + `Firestore` sync |
| Storage | `~/.asoundrc`, `/home/...` hardcode | **Chuẩn Android** `data/files/FilePaths.kt:22` (`filesDir/bibot`, `cacheDir/bibot`, `noBackupFilesDir/bibot/models`, `databases/bibot.db`) |
| Model | `scripts/install_tts_models.sh` bundling | **Tải khi cần** `data/offline/ModelCatalog.kt:18`/`ModelDownloader.kt:18` trong ⚙️ Cài đặt |
| Kho sách | `audiobookshelf/books` + `windows_app/pdf_inbox` | **3 tầng** `assets/demo_books/` (demo) + **Drive** `data/books/DriveBookSource.kt:13` (official) + public URL `BookRepository.kt:18` |
| Windows | `windows_app/app.py:25` Tkinter | **Xoá** — thay bằng Bibot Compose |

**Nguyên tắc:** Code base Pi ~52 `services/*.py` → port trọn sang Go, không giữ nhánh server. Mọi mode chạy trực tiếp trên device. STT/TTS standard chạy **không cần API key** (dùng Google Play Services + Android TTS), LLM mới cần key.

---

## 2) Thoại & Persona — Bot Prompt chính thức

- **Mục chính thức trong ⚙️ Cài đặt → 🤖 Bot Prompt & Cá nhân hoá** (`data/store/BotPromptManager.kt:11` + `BibotPreferences.kt:14` + `SettingsScreen.kt:11`): user tự đổi xưng danh (mặc định **"Em là Bi"**), template, phong cách, tông giọng, độ dài.
- **Template:** `Bạn là Bi, trợ lý giọng nói Bibot. Luôn xưng "{{persona}}", trả lời tiếng Việt, dưới {{maxWords}} từ, {{style}}, {{tone}}, không markdown/link/emoji trong TTS. Chỉ dùng web search cho thời tiết/giá/tin tức. Ngày/giờ hiện tại: {{datetime}}.`
  Placeholder `{{persona}} {{datetime}} {{style}} {{tone}} {{maxWords}}` — lưu `DataStore bot_prompt/style/tone/max_words`, Go `internal/config/config.go:51` `BuildSystemPrompt()` + `UpdateBotPrompt()` + `cmd/mobile/mobile.go:22` `UpdateBotPrompt`/`GetSystemPrompt` cho `gomobile`.
- **Tuỳ biến:** Persona `Em là Bi` / tên riêng, Style `friendly/formal/humorous/concise/storyteller`, Tone `warm/professional/playful/calm`, MaxWords `20/30/50/80/120` (`FilterChip`), preview 280 ký tự live. Nút **Lưu** → `DataStore` + sync Go, **Mặc định** khôi phục `DEFAULT_TEMPLATE`.
- **Sử dụng:** `VoiceScreen.kt:22` `BotPromptManager.buildFromPrefs(prefsState)` làm `systemPrompt` cho `LlmBridge.generateStreamRest`; fallback `BuildConfig.BIBOT_PERSONA` nếu chưa lưu.
- **Giọng đọc:** `tts.CleanTTSText` (port đầy `TP.HCM→TP HCM`, `1.440→1440`, `3,5→phẩy`, `100-120→đến`, `<think>`...) giữ emoji/markdown chỉ hiện UI, TTS sạch.
- **Chunker:** `internal/chunker/chunker.go:8` port `vietnamese_chunker.py:36` `4-12 từ / 120 chars`.

---

## 3) Giao diện — Google Emoji + Touch + Player chuẩn đồng bộ

### 3.1 Google Emoji
- **Font:** `Noto Color Emoji` via `androidx.emoji2:emoji2` + Play EmojiCompat. Không dùng OEM.
- **Quy ước:** Menu label có emoji tiền tố (đồng bộ `services/app_menus/*`):
  | Mode | Emoji | Label |
  |---|---|---|
  | 1 Sách EN | 📚 | Sách tiếng Anh |
  | 2 Kể chuyện VI | 📖 | Kể chuyện |
  | 3/4 Nhạc | 🎵 | Nhạc / Ngẫu nhiên |
  | 5 Voice Chat | 🎙️ | Trò chuyện |
  | 6 Thời tiết | ⛅ | Thời tiết |
  | 7 Tin tức | 📰 | Tin tức |
  | 14 PDF→MD | 📄→📝 | PDF sang Markdown |
  | Cài đặt | ⚙️ | Cài đặt + 📦 Mô hình offline |
  TTS `CleanTTSText` bỏ emoji nên không ảnh hưởng audio.

### 3.2 Main Menu tinh gọn + Nút bấm to (phone/tablet)
- **Home dashboard** `ui/menu/HomeMenu.kt` — mặc định `currentMode=null` (không phải VOICE). Hero gradient `🎙️ Trò chuyện` 140dp + grid `GridCells.Adaptive 150dp phone / 180dp tablet` 6 tile (`BOOK/STORY/MUSIC/WEATHER/NEWS/PDF`) bo 20dp, emoji 26sp trong vòng tròn pastel, `FilledTonalButton` Cài đặt 56dp footer. Một chạm tới mọi mode, không nhồi 8 tab.
- **Nav tinh gọn** `BibotNavSuite.kt:32` `CenterAlignedTopAppBar` + `NavigationBar` chỉ 3 item `🏠 Trang chủ / 🎙️ Trò chuyện / ⚙️ Cài đặt`, back `ArrowBack` khi trong mode. `Home` là single source, các mode khác chỉ hiện khi chọn.
- **Nút to:** `ui/components/BigButton.kt` — `BigButton` 64/72dp, `BigIconButton` FAB 72/96dp, `BigModeTile` 96dp, `hapticFeedback`, `semantics Role.Button`. Player `BibotPlayerBar.kt:36` ⏮/⏯/⏭ 56-64dp. `ui/adaptive/WindowSize.kt` `DevicePosture` + `material3-window-size-class 1.2.1`.
- **VoiceScreen:** `VoiceScreen.kt:22` `BigIconButton` mic `fabSize(tablet)` + `BigButton` "Chạm để nói" 64/72dp.

### 3.3 Player tiêu chuẩn — đồng bộ mọi mode
Mọi mode audio (1,2,3,4,5,7) dùng **chung 1 `BibotPlayerBar`**:
```
┌─────────────────────────────────────────────┐
│  [⏮] [⏯/⏸] [⏭]   ──●──────────  01:23/04:10 │  ← pinned bottom
│  Tốc độ: 0.85x  [0.75x|0.85x|1.0x|1.25x]  🔊 │
└─────────────────────────────────────────────┘
```
- **Component:** `ui/player/BibotPlayerBar.kt:10` `viewModel: PlayerViewModel = viewModel()`; `PlayerViewModel.kt:16` `AndroidViewModel` sở hữu `ExoPlayer`, listener `isPlaying/buffering`, `Handler 500ms` sync `currentPosition/duration`, `seekBack/Forward 10s`, `setSpeed` via `PlaybackParameters`, `playUrl/playBytes` (cache `audio/tts`).
- **Speed:** Map `TTS_PLAYBACK_SPEED=0.85` / `STORY 0.88` `config.py:153` → `ExoPlayer.setPlaybackParameters(Speed)`.
- **Queue:** Flip-flop `Queue(maxsize=2)` Pi mô phỏng bằng `Media3` double-buffer.
- **Book/Story có hàng điều khiển riêng** (không dùng `BibotPlayerBar`, vì không seek theo thời lượng mà theo **câu/chương**): `[⏮] [⏸/⏯] [⏭]` trong Card cuối màn đọc.
  - **⏭ Next = "search #"**: `requestJump(cur+1)` → producer tách câu chương kế trên IO rồi đọc tiếp, bỏ qua phần còn lại.
  - **⏮ Rev**: đang ở giữa chương → **đầu chương hiện tại**; đã ở đầu → **đầu chương trước** (ở đầu sách → báo status).
  - **⏸/⏯**: `isReading` false/true → `offlineTts.stop()` + `playerVm.stopPlayback()`.
  - Không chết dở: item âm thanh cũ mang `jumpSeq` bị consumer bỏ, `delayUntilDone` cắt delay "chờ phát xong" (lát 100ms) → bấm là nhảy ngay. Chi tiết `Bibot_build.md §2.7`.

---

## 4) Kiến trúc Go + Android + Data

```
android_app/
├── go.mod                          # module bibot/core Go 1.22
├── cmd/mobile/mobile.go            # gomobile bind → bibot.aar (LLMStream/TTSSynthesize/PDFConvert/MemoryRecall/ChunkerSplit)
├── internal/
│   ├── config/config.go            # port config.py:1-222 + BuildSystemPrompt VI + datetime Thứ Hai, 19/09...
│   ├── llm/llm.go                  # answerDatetimeLocal + streamGemini/streamOpenRouter REST SSE + isThinkingLeak
│   ├── tts/tts.go                  # CleanTTSText full port 143-208 + Synthesize stub
│   ├── chunker/chunker.go          # VietnameseTextChunker + StreamingAccumulator
│   ├── stt/stt.go | memory/ | pdf/ | player/
├── android/                        # Kotlin + Compose
│   ├── gradle/libs.versions.toml   # AGP 8.5.2, Kotlin 2.0.0, Compose BOM 2024.06, media3 1.3.1, room 2.6.1, datastore 1.1.1, vosk 0.3.45, drive v3, work-runtime, leakcanary (debug)
│   ├── app/build.gradle.kts        # namespace com.bibot.app, minSdk 26, target 34, kapt room; flavors demo (ENABLE_DRIVE=false, Bibot.apk) / commercial (ENABLE_DRIVE=true, BibotV.apk); google-drive/api-client chỉ commercialImplementation; release minify+shrink
│   ├── app/src/main/assets/demo_books/  # Demo bundle (không cần Drive)
│   │   ├── en/{Learn English.pdf 2.8MB (%PDF-1.4), Learn English Now.pdf 3.2MB (%PDF-1.7)}
│   │   └── vi/{Dai-viet-su-ky-toan-thu.md 3.2MB, Linh Nam Chich Quai 353KB}
│   ├── app/src/main/res/xml/backup_rules.xml + data_extraction_rules.xml # exclude cache/no_backup
│   └── app/src/main/java/com/bibot/app/
│       ├── ui/components/BigButton.kt      # BigButton/BigIconButton/BigModeTile 64-96dp, haptic, tablet
│       ├── ui/adaptive/WindowSize.kt       # DevicePosture Phone/Tablet, fabSize/bigButtonHeight
│   ├── data/files/FilePaths.kt         # chuẩn Android paths (files/bibot, no_backup/models, databases/bibot.db)
│       ├── data/store/BibotPreferences.kt  # DataStore Preferences (thay .env) + bot_prompt/style/tone/maxWords
│       ├── data/store/BotPromptManager.kt  # DEFAULT_TEMPLATE + STYLE/TONE options + build() preview + HARD_RULES chống ảo giác (2026-09-30)
│       ├── data/db/BibotDatabase.kt        # Room bibot.db (MemoryCore/Facts/ChatLog/BookProgress/PdfJob)
│       ├── data/books/                     # Kho sách 3 tầng
│       │   ├── BookRepository.kt           # AssetsBookSource + PublicUrlSource aggregate + seedDemo()
│   │   └── DriveBookSource.kt          # 2 bản theo flavor: src/commercial (full: pageToken loop 100/page, getChanges, folderId/pageToken qua DataStore) / src/demo (stub vô hiệu hoá, cùng API)
│       ├── data/offline/                   # Hybrid fallback
│       │   ├── OfflineSttHelper.kt         # Vosk reflection + AudioRecord 16kHz + HybridSttManager + Sherpa Zipformer vi (2026-09-29)
│       │   ├── OfflineTtsHelper.kt         # Android TTS vi-VN + HybridTtsManager AUTO/OFFLINE/ONLINE
│       │   ├── ModelCatalog.kt             # vosk-vi/en, piper-vi/en, sherpa-vi-zipformer (2026-09-30)
│       │   └── ModelDownloader.kt          # HttpURLConnection + unzip + verify
│       ├── feature/settings/
│       │   ├── SettingsScreen.kt           # Persona/API key + 📦 Mô hình offline + 📚 Kho sách (Drive/Link/Demo)
│       │   └── ModelsViewModel.kt          # downloadStates/freeSpace
│       ├── core/{audio/SttBridge,LlmBridge} # SpeechRecognizer vi-VN + Gemini/OpenRouter REST streaming + chatHistory (2026-09-30)
│       └── ui/{player,menu,theme}
└── .env.example
```

**Vì sao Go + Kotlin:** Go gom `.aar` (LLM/chunker/memory), UI Kotlin Compose Material3/Player/Emoji.

### Google Services khai thác

| Service | Dùng cho | API | Cần key? |
|---|---|---|---|
| `Gemini API` | LLM chính | `generativelanguage.googleapis.com/v1beta` (model `gemini-3.6-flash`, thinking 128) | Có (`GEMINI_API_KEY`) |
| `OpenRouter` | Fallback LLM | `openrouter.ai/api/v1/chat/completions` SSE | Có (tùy) |
| `Android SpeechRecognizer` | STT standard | `android.speech.SpeechRecognizer` `vi-VN` | **Không** |
| `Vosk` | STT offline | `org.vosk` `vosk-model-small-vn-0.4.zip` | **Không** (tải khi cần) |
| `Android TextToSpeech` | TTS standard | `android.speech.tts.TextToSpeech` | **Không** |
| `Cloud TTS` | TTS WaveNet | `cloud.google.com/go/texttospeech` | Có (fallback offline) |
| `DocBrowser (SAF)` | Duyệt file local, thêm vào Sách | `androidx.documentfile` + `FilePaths` | **Không** |
| `Room + DataStore` | Persistence | `androidx.room:room-* 2.6.1`, `datastore-preferences 1.1.1` | **Không** |
| `Firestore + Auth` | Memory sync | `firebase-firestore/auth 25.0.0` | Có (tùy) |

---

## 5) Data Store & Đường dẫn chuẩn Android

**FilePaths.kt:22** (`Context.filesDir/cacheDir/noBackupFilesDir`):

| Pi path | Android path | API |
|---|---|---|
| `Pi_system/state/*.jsonl` | `filesDir/bibot/state/` | `stateDir()` |
| `windows_app/pdf_inbox/md_outbox` | `filesDir/bibot/pdfs/inbox\|outbox` | `pdfInbox/outbox()` |
| `Pi_system/models/...` `piper/...` | `noBackupFilesDir/bibot/models/vosk-vi/` + `piper-vi.onnx` | `voskModelDir()/piperModelFile()` (no_backup = không backup Drive, tiết kiệm quota) |
| `cache` PCM | `cacheDir/bibot/audio/tts/offline_*.wav` | `ttsCache()` |
| **Temp chunk** `audio_stream.py:765` Queue2 + `chunker` buffer | `cacheDir/bibot/chunks/llm_*.tmp` + `audio/tts/chunk_*.wav` + `pdfs/cache/chunks/` | `chunkTempDir()/ttsChunkFile()/llmChunkFile()/pdfChunkDir()` **TTL 24h, LRU 200MB** |
| `mode14_state.db` | `databases/bibot.db` | `roomDbFile()` |
| `.env` | `filesDir/bibot/datastore/bibot.preferences_pb` | `BibotPreferences` DataStore |

- **DataStore** `BibotPreferences.kt:13` `preferencesDataStore(name="bibot")`: `persona`, `geminiModel`, `ttsPriority auto/offline/online`, `voiceSpeed 0.85f`, `sttOfflineFirst`, `ttsOfflineFallback`, `language vi`, `driveFolderId`. Thay `services/config.py` env + `SharedPreferences` cũ.
- **Room** `BibotDatabase.kt:13` `bibot.db` 5 entity: `MemoryCore` (Tier0 bất tử), `MemoryFact` (Tier1 count+TTL), `ChatLog` (Tier2 xoay 500), `BookProgress` (`BOOK_SAVE_EVERY=8`), `PdfJob` (`pending/done`), `fallbackToDestructiveMigration`, singleton `get(context)`.
- **External export** `getExternalFilesDir("exports")` cho user SAF (không cần `READ_MEDIA` từ Android 10 với app-specific).

### Temp chunk data & Cải tiến

- **Nguồn:** `Chunker.buffer` (`internal/chunker/chunker.go:8`), `streaming_llm` token buffer, TTS Queue 2 spill (`audio_stream.py:765` → `ExoPlayer` double-buffer), `pdf` page chunks. RAM chính, spill ra file khi >512KB hoặc cần resume Story (`BOOK_SAVE_EVERY=8`).
- **Đường dẫn:** `chunkTempDir()` → `cache/bibot/chunks/llm_*.tmp`, `ttsChunkFile(id)` → `cache/bibot/audio/tts/chunk_*.wav`, `pdfChunkDir()` → `cache/bibot/pdfs/cache/chunks/`. TTL 24h, không backup.
- **Cleanup:** `FilePaths.cleanupCache(maxMb=200, ttlHours=24)` — xoá file >24h, LRU nếu vượt 200MB, trả về `Cleaned N files, freed X KB`. Gọi `ensureAll()` + `cleanupCache()` onStart và `WorkManager` 1 lần/ngày. Hiện tại `go vet:0`, `cache` + `no_backup` đã exclude trong `res/xml/backup_rules.xml` + `data_extraction_rules.xml` (khai `<exclude domain="file" path="bibot/cache/"/>` + `no_backup/`), `AndroidManifest.xml` `fullBackupContent` + `dataExtractionRules` trỏ tới 2 file.
- **Đánh giá:** Cấu trúc hiện tại đúng chuẩn scoped storage, tách `files` (giữ) vs `cache` (xoá) vs `no_backup` (model lớn). Cải tiến đã thêm chunk dir riêng + backup rules + LRU/TTL, tránh đầy disk trên tablet 1GB. Nếu cần share sách ra File Manager, mirror `files/bibot/books` → `externalFiles/exports`.

### Kho sách — Demo + Drive + Link (tối ưu)

**Vị trí menu:** **⚙️ Cài đặt → 📚 Kho sách** (cùng trang với 📦 Mô hình offline, `SettingsScreen.kt:11`).

| Tầng | Nguồn | File | Khi dùng |
|---|---|---|---|
| **Demo** | ~~`assets/demo_books/` (bundle APK)~~ **ĐÃ GỠ 2026-09-25** (−9.15MB raw, APK −3.3MB) | Không còn file bundle — `AssetsBookSource` tự list nếu dev thêm lại file vào `assets/demo_books/{en,vi}/` | Kho sách giờ lấy từ Public Drive + URL + local scan (`scanLocalBooks`) |
| **Official Drive** | **Google Drive của bạn** — chỉ flavor commercial (`ENABLE_DRIVE=true`; demo ẩn mục này) | Folder `BibotBooks` (Folder ID lưu DataStore) scope readonly, `files.list q="'$folderId' in parents and trashed=false"` loop pageToken 100/trang, `getChanges()` incremental | Bản chính thức — `src/commercial/.../DriveBookSource.kt` via `GoogleAccountCredential` + `NetHttpTransport`; `download()` alt=media + close trong `finally` |
| **Public URL** | Link internet bất kỳ | `PublicUrlSource` `downloadFromUrl(url)` | User dán link `https://.../sach.pdf` trong Kho sách |

- **Tối ưu:** `BookRepository.kt:18` `BookSource` interface + aggregate `books: StateFlow<List<BookMeta>>` (`isDownloaded`, `displaySize`), cache `FilePaths.pdfInbox/audioBooks`, `scanLocalBooks()`, chỉ list metadata (100/page + pageToken loop), download `alt=media` 32KB buffer, `drive_folder_id`/`drive_page_token` lưu DataStore (`BibotPreferences`), gate `BuildConfig.ENABLE_DRIVE`.
- **Gradle:** `libs.versions.toml` thêm `google-api-services-drive v3-rev20241014`, `google-api-client-android 2.7.2`, `play-services-auth 21.2.0`, `work-runtime 2.9.0`, `leakcanary 2.14` (debugOnly); drive/api-client là `commercialImplementation`, còn lại `implementation` chung.

### Mô hình tải khi cần (User tự tải)

**Vị trí menu:** **⚙️ Cài đặt → 📦 Mô hình offline** (`SettingsScreen.kt:11` `LazyColumn`). Không để ở NavBar chính để tránh rối.

- **Catalog** `ModelCatalog.kt:18`:
  ```
  vosk-vi-small  🎙️ STT VI  42MB  https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip  → vosk-vi/am/final.mdl
  vosk-en-small  🎙️ STT EN  40MB  vosk-model-small-en-us-0.15.zip                                  → vosk-en/am/final.mdl
  piper-vi-phatjkk 🔊 TTS VI 63MB  raw.githubusercontent.com/phatjkk/.../pretrained_vi.onnx        → piper-vi.onnx (+ .json → suy tokens.txt)
  piper-en-lessac  🔊 TTS EN 63MB  huggingface.co/rhasspy/piper-voices/.../en_US-lessac-medium.onnx (+ .onnx.json)
  ```
  `isInstalled()` check `am/final.mdl` hoặc `.onnx >1MB`, `installedSize()` walk `no_backup`.

- **Downloader** `ModelDownloader.kt:18`: `DownloadState` `Downloading% / Unzipping% / Done / Error`, `HttpURLConnection` 32KB buffer, `freeSpace` check, `verify >500KB`, `unzipWithProgress` (flatten single root `vosk-model-*/`), delete tmp. `stateFlow(id)` cho Compose observe.

- **UI** `SettingsScreen.kt` + `ModelsViewModel.kt:16`: mỗi model là `Card` có emoji/type/lang, badge `Đã cài/Chưa cài`, `LinearProgressIndicator`, nút `Tải xuống`/`Xóa`/`Thử lại`, `Dung lượng trống`, `FilePaths.debugDump`.

- **Sideload — cài không cần mạng (mới 2026-09-25)** `Bibot_build.md §2.8`: file model tải sẵn trên PC (folder `Gói ngôn ngữ Piper\`, 236MB) được `adb push` vào **`/sdcard/Android/data/com.bibot.app/files/models_in`** (`FilePaths.modelStagingDir()` — external app-specific, không root/quyền) rồi bấm **“Cài từ file sideload”**. Áp dụng cho cả 4 model trong catalog + **engine sherpa `.tar.bz2`** + **`espeak-ng-data.zip`** → “Cài Piper (offline)” chạy đủ engine → VI → EN **không một request nào**.
  ```powershell
  .\download_espeak.ps1   # 1 lần: tải espeak-ng-data (355 file/17.2MB) → espeak-ng-data.zip 8.8MB
  .\push_models.ps1       # adb push 8 file → models_in (fallback run-as nếu Android 11+ chặn)
  ```
  `ModelDownloader.stagedFile()` chọn nguồn `staged ?: tải mạng`; `SherpaEngine.stagedTarball()` (hỏng → fallback mạng; **fix 2026-09-25**: tarball release để `./jniLibs/…` → `extractSo` phải `removePrefix("./")` trước khi so prefix, nếu không **0 file `.so` nào được extract → Piper không bao giờ chạy**); `PiperTtsHelper.stagedOnnx/stagedJson` + `deriveTokensFromJson` chạy cho cả EN; `OfflineSttHelper.pickModelDir()` dùng `vosk-en` khi thiếu `vosk-vi`.

- **File picker thay sideload (mới 2026-09-30)**: mỗi model/engine/espeak có nút **“Cài từ file”** (Storage Access Framework, chọn trực tiếp trên máy — không cần `adb push`/`models_in` nữa; các hàm `staged*` giữ lại dạng deprecated). Riêng sherpa-vi (`tar.bz2`): nhánh riêng `installSherpaModel()` — BZip2 extract → verify đủ 4 file → **tự cài engine**; **fix bug** code cũ gọi unzip/gzip vào file bzip2 + đích sai `vosk-vi`.

---

## 6) Fallback offline STT/TTS — Hybrid

**Hoạt động không cần API key** (STT/TTS standard dùng Play Services + engine hệ thống):

```
STT: SpeechRecognizer vi-VN (online, không key) --ERROR_NETWORK/NO_MATCH(7)/SERVER--> Vosk offline (nếu đã tải)
      HybridSttManager:75 preferOffline flag từ DataStore sttOfflineFirst

TTS: Cloud TTS WaveNet (cần key) --fail/null--> Android TTS offline (vi-VN if installed else en-US)
      HybridTtsManager:84 Priority AUTO/OFFLINE/ONLINE từ ttsPriority, setSpeechRate map 0.85x
      OfflineTtsHelper:11 ensureInit, isLanguageAvailable(vi-VN), speakOffline + synthesizeToFile → cache/offline_*.wav → PlayerViewModel.playBytes
```

- Vosk cần model cài trước (tải trong ⚙️ hoặc **“Cài từ file”** — chọn zip trực tiếp trên máy, 2026-09-30 thay sideload `models_in`); nếu chưa có → skip fallback. Chọn model: có `vosk-vi` thì dùng VI, thiếu thì `vosk-en` (`OfflineSttHelper.pickModelDir`). STT offline chính là **sherpa Zipformer vi 30M int8** (độ chính xác cao, ~150ms/5s audio) — Vosk chỉ fallback.
- TTS offline giọng lấy từ `Settings → System → Text-to-speech → Preferred engine` (cài giọng vi-VN trong Google TTS).
- LLM không có standard free — khi thiếu `GEMINI_API_KEY` thì `internal/llm/llm.go:41` trả mock `Em là Bi. Hiện tại em chưa kết nối...` + `LlmBridge.generateStreamRest` fallback tương tự.

---

## 7) Build — Yêu cầu

| Công cụ | Phiên bản | Ghi chú |
|---|---|---|
| Go | ≥1.22 | `go version` |
| gomobile | latest | `go install golang.org/x/mobile/cmd/gomobile@latest` |
| Android Studio | Hedgehog+ | AGP 8.5, Gradle 8.7, `kotlin-kapt` |
| JDK | 17 | `java -version` |
| NDK | 26.1+ | SDK Manager |
| Google Cloud SDK | latest | `gcloud` cho Cloud TTS/Document AI (tùy) |

---

## 8) Build — Từng bước

### 8.1 Clone & env
```bash
git clone <repo> && cd android_app
cp .env.example .env
# Điền GEMINI_API_KEY (AI Studio) nếu dùng LLM; STT/TTS standard không cần key
# Đồng bộ Pi: GEMINI_MODEL=gemini-3.6-flash, OPENROUTER_* fallback
```
`.env` mẫu:
```env
GEMINI_API_KEY=AIza...
GEMINI_MODEL=gemini-3.6-flash
GEMINI_THINKING_BUDGET=128
OPENROUTER_API_KEY=sk-or-...
OPENROUTER_MODEL=nex-agi/nex-n2.5-mini:free
GOOGLE_APPLICATION_CREDENTIALS=./secrets/gcp.json
BIBOT_PERSONA=Em là Bi
```

### 8.2 Go core → AAR
```bash
go mod tidy
gomobile init
gomobile bind -target android -o android/app/libs/bibot.aar ./cmd/mobile
ls -lh android/app/libs/bibot.aar
```
`cmd/mobile` expose: `LLMStream(prompt, callback)`, `TTSSynthesize(text, voice)`, `PDFConvert(path)`, `MemoryRecall(q)`, `ChunkerSplit(text)`.

### 8.3 Android APK — Bibot
```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
# → android/app/build/outputs/apk/demo/debug/Bibot.apk (đổi tên sẵn via applicationVariants)
./gradlew :app:assembleRelease \
  -Pandroid.injected.signing.store.file=./bibot.keystore \
  -Pandroid.injected.signing.store.password=$STORE_PASS \
  -Pandroid.injected.signing.key.alias=bibot \
  -Pandroid.injected.signing.key.password=$KEY_PASS
```

### 8.4 Chạy
```bash
adb install -r app/build/outputs/apk/debug/Bibot.apk
adb shell am start -n com.bibot.app/.MainActivity
adb logcat -s Bibot:V
# Kiểm tra paths: adb shell run-as com.bibot.app ls -R files/bibot
```

---

## 9) Cấu hình APK Bibot

`android/app/build.gradle.kts`:
```kotlin
android {
  namespace = "com.bibot.app"
  compileSdk = 34
  defaultConfig {
    applicationId = "com.bibot.app"
    minSdk = 26; targetSdk = 34
    versionCode = 1; versionName = "1.0.0"
    buildConfigField("String", "BIBOT_PERSONA", "\"Em là Bi\"")
  }
  buildFeatures { compose = true; buildConfig = true }
}
dependencies {
  implementation("androidx.compose.material3:material3")
  implementation("androidx.media3:media3-exoplayer:1.3.1")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("androidx.room:room-runtime:2.6.1"); kapt("androidx.room:room-compiler:2.6.1")
  implementation("com.google.android.gms:play-services-speech:16.0.0")
  implementation("com.alphacephei:vosk-android:0.3.45") // optional
  implementation("com.google.firebase:firebase-firestore:25.0.0")
  implementation("androidx.emoji2:emoji2:1.4.0")
  implementation(files("libs/bibot.aar"))
}
```
`AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO"/>
```

---

## 10) Kiểm thử (≥10 lần như Pi `REPO_SYSTEM_PROMPT.md:110`)

| # | Test | Pass |
|---|---|---|
| 1 | `go vet ./...` + `go build ./...` | 0 |
| 2 | `gomobile bind` | `bibot.aar` |
| 3 | `./gradlew :app:assembleDemoDebug :app:assembleCommercialDebug` | `Bibot-demo.apk` **60.75MB** / `Bibot.apk` **62.08MB** (**Clean + Rebuild** 2026-09-25 14:31, sau fix bug `./jniLibs` §2.8.2; 13:57: 60.82/62.15) |
| 4 | Cài + persona "Em là Bi" (không cần key) | Greeting đúng |
| 5 | STT standard không key | SpeechRecognizer vi-VN OK |
| 6 | TTS standard không key | Android TTS vi-VN OK |
| 7 | Fallback Vosk/Piper (sau tải trong ⚙️) | Offline STT/TTS OK |
| 8 | Temp chunk TTL/LRU | `cleanupCache` 200MB/24h + `backup_rules.xml` |
| 9 | LLM với/without key | Gemini stream / mock fallback |
| 10 | Model download/unzip/delete | Progress + verify OK; **2026-09-25**: nguồn sideload (`models_in`); **2026-09-30**: file picker SAF thay sideload + sherpa-vi nhánh tar.bz2 riêng (verify 4 file + tự cài engine) |
| 11 | Kho sách Demo | ~~Seed `assets/demo_books/`~~ **ĐÃ GỠ 2026-09-25** — `scanLocalBooks` quét local |
| 12 | Kho sách Drive (official) | List/download từ Folder ID |
| 13 | Kho sách Link | Dán URL → tải `pdfInbox` |
| 14 | PlayerBar đồng bộ 5 mode | Seek/Speed sync |
| 15 | DataStore + Room | Persist sau restart |
| 16 | FilePaths chuẩn | `files/bibot`, `no_backup/models` |
| 17 | Mở sách/truyện 3-5MB không đơ | `BlockTextReader` stream 16KB (md) / 64KB (pdf), không `readText()`; unit-test JVM 64/64 PASS |
| 18 | Sách convert không bị TTS đọc rác HTML | `<!-- CHAPTER-BREAK -->`, `<a id=…>`, `&nbsp;`, `|---|`, `# heading` bị tẩy hết; marker `CHAPTER-BREAK` → tiêu đề chương |
| 19 | Không bao giờ nghe "PAUSE 2 SEC" | `PAUSE2SEC` chỉ là cờ → `splitPause()` tách trước mọi engine (`OfflineTtsHelper`/`PiperTtsHelper`/`CloudTtsBridge` strip phòng thủ), thực tế là `delay(2000)` = khoảng lặng 2s; UI hiện `⏸2s` |
| 20 | Nút ⏮/⏭ (Rev/Next) nhảy chương thật sự | Trước đây chỉ đổi UI → loop không theo → vô tác dụng. Giờ: ⏭ = sang chương kế (qua `##`), ⏮ = về đầu chương / chương trước; item cũ mang `jumpSeq` bị consumer bỏ, `delayUntilDone` cắt delay chờ → bấm là nhảy ngay |
| 21 | Cài model offline **không cần mạng** | **2026-09-30**: nút **Cài từ file** (file picker) cho từng model/engine/espeak, 0 request mạng; trước đó sideload `push_models.ps1` → `…/files/models_in` (giữ script PC, code staged* deprecated) |

---

## 11) Roadmap

- **v0.1** Scaffold Go + Compose + `BibotPlayerBar` + `CleanTTSText` + `Chunker` + Gemini stream "Em là Bi".
- **v0.2** DataStore/Room/FilePaths + Hybrid STT/TTS (standard không key) + ModelCatalog/Downloader trong ⚙️ + Kho sách Demo 2 MD.
- **v0.3** DriveBookSource + Public URL + BookRepository (official Drive của bạn), PDF→MD ML Kit on-device (`PdfToMarkdownHelper`), `DocBrowserScreen` (SAF browser, demo replaces PDF→MD), `BookProgress`, Firestore sync.
- **v1.0** Release `Bibot.apk` Play Store, bỏ `windows_app/` hoàn toàn.

---

## 12) Ghi chú port từ Pi

- Giữ `clean_tts_text` & `vietnamese_chunker` Go để TTS không đọc markdown/URL/thinking.
- `VOICE_JITTER_MS=300` → `ExoPlayer` buffer 300ms.
- `BOOK_SAVE_EVERY=8` → `Room book_progress`.
- `MODE14_*` → Document AI params.
- `.env` → `DataStore bibot.preferences_pb` (không hardcode `/home/...`).

> Build doc này là single source of truth cho `android_app`. Mọi thay đổi build cập nhật tại đây trước khi commit.
