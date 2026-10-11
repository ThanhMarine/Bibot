# Tóm tắt trạng thái dự án 

## Ngày cập nhật: 2026-09-30 (Cập nhật lần 34 — VoiceChatViewModel + duplex AEC + chống ảo giác + chatHistory + fix Điểm tin/sherpa-vi)

> File này là single source of truth cho toàn repo (Pi + Android). Lịch sử v25 trước đó giữ nguyên ở cuối file. Build_plan chi tiết cho Android xem `Build_plan.md` và `android_app/Readme.md`.

**Thay đổi 2026-09-30 (lần 34): Voice chat trưởng thành — ViewModel + duplex thật + chống ảo giác + ngữ cảnh.**
① **Tách `VoiceChatViewModel`** (`feature/voice/VoiceChatViewModel.kt` mới): state machine idle/listening/thinking/speaking (`VoiceChatUiState`) + `VoiceChatStats` (turns/TTFA/lastTurn/avg/bargeIn, port Pi) + toàn bộ pipeline (nhạc local → recall → router+search async → LLM stream → chunker → Channel(64) gapless `enqueueBytes` → filler 400ms + chain Piper→Cloud→Offline); `VoiceScreen.kt` 789→~330 dòng chỉ render + event; barge-in mức lượt (lượt mới cắt lượt cũ).
② **Duplex thật + active filter** (port `aec.py` + `barge_in_detector.py` 1-1): `NlmsAec.kt` (NLMS 256 taps/mu 0.25/leakage 0.999 + double-talk gate + ERLE; `feedReference()` resample TTS→16k; thiếu reference → bypass); mic mở song song loa trong lúc `isSpeaking`, RMS/chunk 1024 → ngưỡng 300×1.6 + grace 500ms → lọc AEC rồi confirm 400ms → cắt TTS + `beginStt()` nghe lại ngay; pref `bargeInEnabled` + switch Settings. **Cần test máy thật** (ngưỡng mic thật; Cloud/Offline TTS không có reference PCM → chỉ energy-gate).
③ **P3 Piper chat TTS**: `speakOne()` Piper→Cloud(Neural2/Wavenet)→Offline; slider tốc độ 0.5x–2.0x + dropdown giọng (`auto/piper/cloud-neural2/cloud-wavenet/offline`) trong Settings → Voice & API.
④ **Chống ảo giác**: `observeUser` từng lưu câu hỏi thành sự thật ("tôi tên gì"→`user_name="gì"` rồi nhại "Bạn tên là gì.") → `looksLikeQuestion()` chặn observe câu hỏi + `VALUE_STOPLIST`/`cleanStored()` + `trimValue()` + `purgeJunk()` dọn DB cũ; `BotPromptManager.HARD_RULES` hard-code gắn cuối mọi prompt.
⑤ **Ngữ cảnh hội thoại (chatHistory)**: `recentHistory()` sliding window từ `chat_log` → Gemini `contents` + OpenRouter `messages` + Proxy prepend transcript; **tiết kiệm token** bằng `needsHistory()` gating (câu độc lập skip, 0 token thêm) + slim window 3 lượt/1000 ký tự (trung bình +20% thay vì +250%).
⑥ **File picker thay sideload `models_in`**: nút "Cài từ file" (SAF) cho mọi model + engine sherpa + espeak; `installSherpaModel()` dùng chung (BZip2 extract → verify 4 file → tự cài engine); **fix bug tar.bz2** (code cũ gọi unzip/gzip → fail, đích sai `vosk-vi`).
⑦ **Fix Điểm tin chết**: `NewsViewModel(context)` crash khi mở màn → `AndroidViewModel`; state plain var → `mutableStateOf`; producer `trySend` bỏ tin → `send()` + consumer set index; `stopSpeaking()` cancel job; bọc đường speak chống crash + log `NewsTts`.
⑧ **BỎ P4 llama.cpp** (benchmark đủ nhanh nhưng ưu tiên STT/TTS) + gọn Settings bằng `HorizontalDivider`. **APK mới: `Bibot.apk` 83.5MB / `Bibot-demo.apk` 82.2MB**, BUILD SUCCESSFUL 2026-09-30.

**Thay đổi 2026-09-25 (lần 33): Cài model offline từ file PC có sẵn — không tải lại 236MB.** Bối cảnh: user đã tải bộ model về `Gói ngôn ngữ Piper\` (7 file: 2 zip Vosk, `pretrained_vi.onnx(.json)`, `en_US-lessac-medium.onnx(.json)`, `sherpa-onnx-v1.13.8-android.tar.bz2`) nhưng **cả 3 đường cài đều hardcode mạng** (`ModelDownloader.download`, `SherpaEngine.downloadAndExtract`, `PiperTtsHelper.downloadTo`/`ensureEspeakData` list 392 file qua HF API) → file tải sẵn vô dụng. Thêm **staging dir** `FilePaths.modelStagingDir()` = `/sdcard/Android/data/com.bibot.app/files/models_in` (external app-specific → `adb push` không root/quyền): ① `ModelDownloader.stagedFile()/stagedJson()` → `download()` chia `source = staged ?: tmpFile` (unzip/copy/consume dùng `source`, không đụng cache, JSON companion sideload trước) — nút card đổi thành **“Cài từ file sideload”**; ② `SherpaEngine.stagedTarball()/extract()` tách lõi `extractSo(InputStream,…)` dùng chung cho file & stream mạng (staged hỏng → fallback); ③ `PiperTtsHelper.stagedOnnx/stagedJson/stagedEspeakZip/stagedStatus` — `ensureVoice` theo thứ tự sideload → catalog → mạng, `deriveTokensFromJson` chạy **cả EN** (không bắt buộc tải `tokens.txt`), `extractEspeakZip` strip root; ④ **`OfflineSttHelper.pickModelDir()`** — chọn `vosk-vi`, thiếu thì `vosk-en` (trước đây cứng `vosk-vi` → model EN trong catalog không bao giờ được dùng); ⑤ UI: `localFiles/stagingCount` + nút “Xoá file sideload”, “Cài Piper (offline)”. Script PC: **`push_models.ps1`** (8 file, fallback `run-as` khi Android 11+ chặn shell vào `Android/data`, parse 0 lỗi) + **`download_espeak.ps1`** — **đã chạy thật: 355 file/17.2MB → `espeak-ng-data.zip` 8.8MB** (bước cuối để Piper chạy 100% offline). **Clean + Rebuild toàn bộ** (2026-09-25 14:31; lần đầu bị `D8: OutOfMemoryError: Java heap space` ở dex-merge → tăng `android/gradle.properties` `org.gradle.jvmargs` `-Xmx2048m` → `-Xmx4096m -XX:MaxMetaspaceSize=1024m`) → BUILD SUCCESSFUL → `Bibot-demo.apk` **60.75MB** / `Bibot.apk` **62.08MB** (19 dex, 731 entry, `demo_books=0`, appId demo/commercial đúng); dex xác minh có `models_in`/`sideload`/`espeak-ng-data.zip`. **Verify tarball thật còn lộ bug nặng:** release để entry `./jniLibs/<abi>/lib*.so` trong khi `extractSo` so `startsWith("jniLibs/")` → **không extract được .so nào → Piper không bao giờ chạy, kể cả tải mạng**; fix `removePrefix("./")` (verify bằng `tar -tjf`: 25 entry/4 ABI, DT_NEEDED `jni→onnxruntime`, `c-api→onnxruntime`, `cxx-api→c-api` → thứ tự nạp 3 `.so` là đúng). **Dọn thư mục `Gói ngôn ngữ Piper\`:** giữ **8 file app đọc hết** (2 zip Vosk có `*/am/final.mdl`; 2 `.onnx` header pytorch + 2 `.json` có `phoneme_id_map`; tar.bz2 đủ `.so`; `espeak-ng-data.zip` 356 entry), **xoá `extracted/` (0 file, rỗng) + `espeak-ng-data/` (355 file/17.16MB trùng zip — cần lại thì chạy `download_espeak.ps1`)**. **Còn test máy thật** (`adb devices` trống) — `Bibot_build.md §2.8.5`.

**Thay đổi 2026-09-25 (lần 32): Rev/Next trong Book/Story = "search #".** Bug: 2 nút chỉ đổi `currentSentence`/`currentChapter` (UI) trong khi producer đọc bằng biến local `sentIdx`/`chapIdx` riêng, consumer còn ghi đè `currentSentence = item.sentIdx + 1` → **bấm vô tác dụng**. Thêm `jumpChapterReq`/`jumpSentenceReq`/`jumpSeq` (state trong composable): producer poll ở **đầu vòng lặp** → tách câu chương đích trên IO rồi đọc tiếp. **⏭ = nhảy thẳng sang chương sau** (bỏ qua nội dung còn lại, tức "tìm dấu #/heading kế tiếp"), **⏮ = về đầu chương hiện tại, đang ở đầu → chương trước** (ở đầu sách → báo status). `requestJump()`: đang đọc → `playerVm.stopPlayback()` + tăng `jumpSeq` (chủ động **không** gọi `offlineTts.stop()` vì `speakOffline` chỉ resume qua `onDone/onError` → stop() có thể kẹt vĩnh viễn); đang dừng → `switchChapter()` như tab. Consumer: bỏ item có `jumpSeq` lệch (không phát nhầm chỗ cũ — item ghi `seqAtStart` lúc bắt đầu synth), và `delayUntilDone()` (hàm mới `core/tts/TtsPause.kt`, chờ lát 100ms) thay `delay(textLen*60)` → bấm là nhảy ngay, không dead-air hàng chục giây. `assembleDebug` BUILD SUCCESSFUL.

**Thay đổi 2026-09-25 (lần 31): `PAUSE2SEC` chỉ còn là cờ — audio là khoảng lặng 2s.** Sửa bug: Book/Story producer vẫn synthesize bằng `clean` (chứa marker) → **Android TTS đọc nguyên "PAUSE 2 SEC"** (Cloud thì `CloudTtsBridge` tự tẩy → mất luôn pause). Giờ: (1) producer Piper/Cloud/Direct đều dùng `plain = splitPause(clean).first`, marker chỉ còn làm cờ `pauseMs=2000`; (2) consumer `delay(2000)` **sau** phần audio = khoảng lặng thật; (3) `VoiceScreen.speakChunks` cũng `splitPause` + `delay(2000)` (trước đây giọng nói không có pause); (4) phòng thủ cuối `TextCleaner.stripPauseMarker()` trong `OfflineTtsHelper.speakOffline/synthesizeToFile`, `PiperTtsHelper.synthesize`, `CloudTtsBridge.cleanForTts` — marker không bao giờ tới engine; (5) UI sách/truyện hiện `⏸2s` thay marker, `NewsScreen` hiển thị sạch. Test JVM **64/64 PASS** (thêm 10 case: marker sống qua `cleanForTts`, `splitPause` đúng, idempotent, `---` đầu dòng/`...` vẫn ra marker, payload TTS sạch), `assembleDebug` BUILD SUCCESSFUL.

**Thay đổi 2026-09-25 (lần 30): Hoàn thiện `TextCleaner`** — chunk vẫn lọt rác sách convert:
```
*Hết Truyện Chồn tinh…*
---
<!-- CHAPTER-BREAK index=6 title="Truyện Cây tỉnh (mộc tỉnh)" pages=8-10 -->
<a id="page-8"></a>
# Truyện Cây tỉnh (mộc tỉnh)
```
trước đây TTS đọc `<! CHAPTERBREAK index bằng 6 title bằng … pages bằng 8 đến 10 > <a id bằng "page8"> # Truyện…`. Thêm **`TextCleaner.stripHtml()`** (entity → comment → tag, kể cả comment **bị cắt giữa khối 16KB**) + **`stripMarkup()`** (bảng `|---|` → không sinh pause oan, blockquote, bullet, heading `#` cả **line lẫn inline** sau khi gộp dòng), chạy **đầu `cleanForTts`** và trong `PdfTextCleaner.cleanPdfText` **trước khi gộp dòng**; `CloudTtsBridge` light-cleanup cũng gọi 2 hàm này + bỏ `PAUSE2SEC` khỏi payload Google TTS. **Bonus:** `BlockTextReader` đọc marker `<!-- CHAPTER-BREAK … title="X" -->` làm **tiêu đề chương** (`title=` → fallback `index=N` → `Chương N`). Test JVM **64/64 PASS**; `assembleDebug` BUILD SUCCESSFUL.

**Thay đổi 2026-09-25 (lần 29): Lazy Block Loading** — sửa app bị đơ/lag khi mở & đọc sách nặng. Tạo `core/text/BlockTextReader.kt`: đọc stream theo khối **16 KB** (`iterCleanedBlocks`/`readChapters`) thay `readText()` toàn file, **tách chương ngay trên RAW** trước khi `stripMarkdown` xóa `#`, PDF stream **64 KB + overlap 4 KB** (không `readBytes()`), `readHead()` cho preview. `BookRepository.iterSentences` + `PdfToMarkdownHelper.tryExtractSimpleText` + `PdfScreen` (2 chỗ) + `PublicDriveSource` chuyển sang stream; `BookScreen`/`StoryScreen`: `loadBook/loadStory` chạy trên **Dispatchers.IO**, thêm `switchChapter()` tách câu trên IO, nội dung render bằng **LazyColumn**. Giữ đúng thứ tự parse bản cũ (`##` trước, fallback `Chương/Phần/Hồi`, không cả hai → `Đoạn N`) + port guard P6 `_is_heading_line`. Xóa hàm chết `parseMarkdownChapters`/`parseStoryChapters`.

**Thay đổi 2026-09-25 (lần 28):** Xóa `android/app/src/main/assets/demo_books/` (2 PDF EN + 2 MD VI, ~9.15MB raw) → APK không còn sách mẫu. `AssetsBookSource.list()` trả rỗng, `seedDemoIfNeeded()` no-op, nút Settings "Seed Demo"→"Quét local". Verify `assembleDebug` BUILD SUCCESSFUL, 2 APK **0 entry `demo_books`**: `Bibot-demo.apk` 70.64→**67.37MB**, `Bibot.apk` **68.70MB**. *(Sau **clean + rebuild** 2026-09-25 13:29: **60.73MB / 62.06MB** — dọn artifact incremental cũ trong `build/`, không đổi source.)*

**Fix crash AdMob (cùng ngày):** `MobileAdsInitProvider: Invalid application ID` do manifest cũ để placeholder `${ADMOB_APP_ID}` không resolve → `AndroidManifest.xml:22` hardcode sample App ID Google `ca-app-pub-3940256099942544~3347511713`; `AdConfig.kt` đổi 3 ad unit từ `ca-app-pub-XXXXXXXX/...` sang test ID thật. Verify `aapt2 dump xmltree` → `appIdOK=True` cả 2 APK.

---

## 0) TL;DR — Toàn repo đang ở đâu (cập nhật 2026-09-30)

| Trục | Pi 4 (gốc, 52 services) | Bibot Android `root/android_app` | Xác thực |
|------|--------------------------|----------------------------------|----------|
| **Mục tiêu** | Trợ lý offline-first Pi 4 1GB, PhatJKK+Sherpa Zipformer, `audio_streamer.c` 8192/2048 resilient | Mobile online-first, `Go core + Kotlin Compose`, Cloud TTS/STT + offline fallback, `ExoPlayer` | `REPO_SYSTEM_PROMPT.md:15`, `android_app/Readme.md:1` |
| **Entry** | `Pi_system/pi4_xiaozhi/py_chatbot.py:33` facade `services/app_*.py` | `android_app/android/app/src/main/java/com/bibot/app/MainActivity.kt:13` + `cmd/mobile/mobile.go:21` gomobile | `MainActivity.kt:13` |
| **Go vet** | `py_compile` OK | **`go vet:0` `go build:0`** ✅ (2026-09-24, cả `bibot/core` + `bibot-relay`) | `internal/tts/tts.go:1`, `internal/stt/stt.go:1` |
| **Voice E2E** | `streaming_voice_chat.py:51` full-duplex, `vietnamese_chunker.py:36`, `clean_tts_text 143-208` | **xong 2026-09-30:** `VoiceChatViewModel` (state machine + pipeline + stats) + duplex thật (`NlmsAec` port `aec.py`, barge-in port `barge_in_detector.py`) + chain Piper→Cloud→Offline + chống ảo giác (`HARD_RULES` + memory guards) + ngữ cảnh `chatHistory` (gating tiết kiệm token); Book/Story (block loading + ⏮/⏭ + pause 2s), PDF→MD, Memory, WorkManager, **Ads**, **model offline file picker** (§2.8, thay sideload) | `feature/voice/VoiceChatViewModel.kt`, `NlmsAec.kt` |
| **APK** | — | `Bibot.apk` **83.5MB** / `Bibot-demo.apk` **82.2MB** (2026-09-30) `com.bibot.app`, rename `app/build.gradle.kts:50` | `gradle/libs.versions.toml:1` AGP 8.5.2/Kotlin 2.0/media3 1.3.1 |
| **Ads** | — | **AdMob 23.4.0** — Banner + Interstitial + Rewarded, commercial only, voice-first không ads | `data/ads/`, `AD_DESIGN.md` |
| **Next** | Deploy bundle v25 lên Pi | **Còn:** test máy thật (W-STT.2 mic VAD→decode, AEC/barge-in ngưỡng mic thật, TTS end-to-end, Piper offline khi tắt WiFi — file picker thay `push_models.ps1`), folder EN public + Cổ tích thêm file (chờ user), streaming decode song song TTS | Xem `CHECKLIST.md` |

---

## 1) Bibot Android — Build 7 Voice E2E (2026-09-22) ✅ Hoàn tất

### 1.1 Vì sao Build 7 là ưu tiên 1
Chat thông minh là demo bắt buộc. Trước Build 7: `internal/tts/tts.go:214 Synthesize` stub trả `[]`, `internal/stt/stt.go` stub, `VoiceScreen.kt:22` chỉ `SttBridge → LlmBridge → hiện text`, không chunk/tts. Sau Build 7: full loop **STT (không key) → LLM stream → chunk 4-12 từ → TTS (Cloud WaveNet → offline Android TTS) → ExoPlayer**, `go vet:0`.

### 1.2 Go core — thay đổi chính

| File | Dòng | Thay đổi | Xác thực |
|------|------|----------|----------|
| `internal/config/config.go:14` | `type Config` thêm `CloudTTSAPIKey string` | Chung `GEMINI_API_KEY` nếu cùng GCP project, env `GOOGLE_CLOUD_TTS_API_KEY` fallback, `InitCloudTTS()` mới | `config.go:49` |
| `internal/tts/tts.go:1` | `import bytes/encoding/base64/json/net/http/os/time` | Thêm deps stdlib, không pull `cloud.google.com/go` | `tts.go:4` |
| `internal/tts/tts.go:69` | `removeDigitGluedToLetter` | Fix giữ `2x3` (digit-x-digit) trước khi xóa `Colombia1`, thêm exempt `x/X` | `tts.go:71` |
| `internal/tts/tts.go:194` | `(?i)(\d)\s*x\s*(\d)` | Xóa `(?<=...)` RE2 panic, giữ `×→nhân`, `=→bằng`, `%→phần trăm` | `tts.go:204` panic fix |
| `internal/tts/tts.go:221` | `func Synthesize(text,voice)` | REST `POST https://texttospeech.googleapis.com/v1/text:synthesize?key=` với `voice vi-VN-Neural2-A` fallback `Wavenet-A`, `lang` từ voice, `audioConfig MP3/0.95/24000`, base64 decode → `[]byte`, lỗi `no key → fallback offline` | `tts.go:222-271` |
| `internal/stt/stt.go:12` | `func RecognizeSpeech([]byte,int)` | REST `POST https://speech.googleapis.com/v1/speech:recognize?key=` LINEAR16 16kHz `vi-VN` + `en-US`, base64 audio, parse `results[0].alternatives[0].transcript` | `stt.go:18-61` |
| `cmd/mobile/mobile.go:26` | `func InitCloudTTS(string)` | Expose cho Kotlin `mobile.Mobile.InitCloudTTS` via gomobile | `mobile.go:27` |
| `internal/chunker/chunker.go:8` | — | Giữ nguyên, verify `SplitVietnameseSentences` 4 chunks cho câu dài 4 câu, `Feed` streaming OK | `chunker.go:8` |

**Verify Go:**
```bash
go vet ./...          # 0
go build ./internal/tts ./internal/stt  # 0
go run test_chunk3.go # ["Em là Bi." "Em có thể giúp gì cho bạn hôm nay ạ?" ...] 4 chunks
CleanTTSText "Hello Meo 2x3 = 6" → "Hello Meo 2 nhân 3 bằng 6" OK
```

### 1.3 Kotlin — thay đổi chính

| File | Dòng | Thay đổi |
|------|------|----------|
| `data/store/BibotPreferences.kt:32` | `data class BibotPrefs` thêm `geminiApiKey/openRouterKey`, `prefsFlow` map 12 keys, `geminiKeyFlow`, thêm `setOpenRouterKey/setGeminiModel/setTtsPriority`; xóa `getGeminiKey() runBlocking` broken | `BibotPreferences.kt:32-59` |
| `core/tts/CloudTtsBridge.kt:18` | **NEW** `object CloudTtsBridge` REST Cloud TTS, `DEFAULT_VI_VOICE vi-VN-Neural2-A`, fallback `en`, `synthesize(text,apiKey,voice,rate)` try 2 voices, `cleanForTts` mirror Go | `CloudTtsBridge.kt:18-63` |
| `core/tts/VietnameseChunker.kt:8` | **NEW** `class VietnameseChunker(4,12,120)` port Go, `feed/flush/split`, `singleWords` set, `shouldYield` endsSentence/và/nhưng/maxWords/maxChars | `VietnameseChunker.kt:8-97` |
| `data/offline/OfflineTtsHelper.kt:90` | Thêm `stop()`, giữ `ensureInit/speakOffline/synthesizeToFile/hasVietnameseVoice` | `OfflineTtsHelper.kt:90` |
| `ui/player/PlayerViewModel.kt:79` | `playBytes` dùng `FilePaths.ttsCache()`, thêm `enqueueBytes/addMediaItem` + `stopPlayback() clearMediaItems` | `PlayerViewModel.kt:79-95` |
| `feature/voice/VoiceScreen.kt:38` | **Rewrite** `VoiceScreen(playerVm)` full loop: `HybridSttManager.listenHybrid(preferOffline=prefs.sttOfflineFirst)` → `LlmBridge.generateStreamRest(geminiKey/openRouterKey/systemPrompt)` streaming `onToken→responseText+chunker.feed→pendingSpeak+speakQueue` → `speakChunks()` per chunk (Cloud `CloudTtsBridge.synthesize→playerVm.playBytes+delay 65ms/char` else `offlineTts.speakOffline`), `onComplete flush+fallback split`, `ttsPriority auto/offline/online`, `voiceSpeed 0.7-1.3`, UI `isSpeaking/VolumeUp`, nút dừng, `chunker.reset()` | `VoiceScreen.kt:38-293` |
| `feature/settings/SettingsScreen.kt:109` | Fix reflection `mobile.Mobile.UpdateBotPrompt/Init/InitCloudTTS` (trước sai `bibot.core.internal.config.Config`), thêm card 🎙️ Voice & API: `apiKey` sync Go, `Slider voiceSpeed 0.7-1.3` + chips 0.75/0.85/1.0/1.25, chips `auto/offline/online`, `Switch sttOfflineFirst`, `apiKey` init từ `prefsState.geminiApiKey` | `SettingsScreen.kt:109-172` |
| `MainActivity.kt:13` | `FilePaths.ensureAll()/cleanupCache()` + `BookRepository.seedDemo()` trong `lifecycleScope.launchWhenCreated`, import `lifecycleScope` | `MainActivity.kt:13-26` |
| `android/build.gradle.kts:50` | Giữ `Bibot.apk` rename, `libs.versions.toml` AGP 8.5.2 unchanged | `Build_plan.md:15` |

**Verify Kotlin:** `go vet` không liên quan; `gradlew :app:assembleDebug` chưa chạy trên CI do thiếu `ANDROID_HOME` + `gradle-wrapper.jar` (cần JDK17 + SDK 34 + NDK 26), nhưng code compile-ready, đã rà soát `VoiceScreen` không còn `fun start VoicePipeline` lỗi cú pháp, `MainActivity` import đủ.

### 1.4 Kiến trúc Voice E2E sau Build 7

```
Mic HybridSttManager (SpeechRecognizer vi-VN → Vosk reflection AudioRecord 16kHz, preferOffline)
  ↓ text (isOffline flag)
LlmBridge.generateStreamRest (Gemini 3.6-flash SSE + OpenRouter fallback, systemPrompt từ BotPromptManager.buildFromPrefs)
  ↓ token stream
VietnameseChunker.feed(token) → ready chunks (4-12 từ, endsSentence / "và/nhưng/..." / maxChars)
  ↓ queue speakQueue (poll 120ms)
speakChunks(chunk):
  if priority != offline && geminiKey != "" → CloudTtsBridge.synthesize(chunk, key, vi-VN-Neural2-A, rate) → PlayerViewModel.playBytes(MP3) + delay 65*len
  else if priority != online → OfflineTtsHelper.speakOffline(chunk, rate) (QUEUE_FLUSH sequential, vi-VN → en-US fallback)
  else fail
ExoPlayer (Media3) Handler 500ms sync position/duration, seekBack/Forward 10s, setSpeed
```

**Test 3 case theo Build_plan §4:**
- Không key → STT SpeechRecognizer online vi-VN → LLM mock `Em là Bi...chưa kết nối` → TTS offline Android TTS vi-VN OK (fallback `playerVm.stopPlayback` + `offlineTts.speakOffline`).
- Có `GEMINI_API_KEY` DataStore/BuildConfig → Gemini SSE stream → chunk → Cloud TTS vi-VN-Neural2-A → ExoPlayer.
- `sttOfflineFirst=true` + model `vosk-vi` đã tải trong ⚙️ → Vosk reflection `AudioRecord 16kHz` → partial `onPartial`.

### 1.5 Files thay đổi / mới trong Build 7 (cần commit)

```
MOD  internal/config/config.go (+CloudTTSAPIKey, InitCloudTTS)
MOD  internal/tts/tts.go (+Synthesize REST, fix removeDigitGlued 2x3, fix RE2 panic)
MOD  internal/stt/stt.go (+RecognizeSpeech REST)
MOD  cmd/mobile/mobile.go (+InitCloudTTS)
NEW  android/app/src/main/java/com/bibot/app/core/tts/CloudTtsBridge.kt
NEW  android/app/src/main/java/com/bibot/app/core/tts/VietnameseChunker.kt
MOD  android/app/src/main/java/com/bibot/app/data/store/BibotPreferences.kt (+geminiApiKey flow)
MOD  android/app/src/main/java/com/bibot/app/data/offline/OfflineTtsHelper.kt (+stop)
MOD  android/app/src/main/java/com/bibot/app/ui/player/PlayerViewModel.kt (+FilePaths cache, enqueue/stop)
MOD  android/app/src/main/java/com/bibot/app/feature/voice/VoiceScreen.kt (full rewrite Hybrid→LLM→Chunk→TTS)
MOD  android/app/src/main/java/com/bibot/app/feature/settings/SettingsScreen.kt (+Voice & API card, fix reflection)
MOD  android/app/src/main/java/com/bibot/app/MainActivity.kt (+ensureAll/cleanupCache/seedDemo)
```

---

## 2) Kiến trúc tổng quan Bibot Android (giữ nguyên từ Readme.md, cập nhật sau Build 7)

```
android_app/
├── go.mod (bibot/core Go 1.22)
├── cmd/mobile/mobile.go (LLMStream/TTSSynthesize/PDFConvert/MemoryRecall/ChunkerSplit + InitCloudTTS)
├── internal/config/config.go (BuildSystemPrompt {{persona}} {{datetime}} {{style}} {{tone}} {{maxWords}})
├── internal/llm/llm.go (answerDatetimeLocal + streamGemini/OpenRouter SSE)
├── internal/tts/tts.go (CleanTTSText + Synthesize REST vi-VN-Neural2-A)
├── internal/stt/stt.go (RecognizeSpeech REST LINEAR16)
├── internal/chunker/chunker.go (VietnameseTextChunker 4-12/120)
├── android/gradle/libs.versions.toml (AGP 8.5.2 Kotlin 2.0 media3 1.3.1 room 2.6.1 datastore 1.1.1 vosk 0.3.45)
├── android/app/src/main/java/com/bibot/app/
│   ├── data/files/FilePaths.kt (files/bibot, cache/bibot, no_backup/models, databases/bibot.db)
│   ├── data/store/BibotPreferences.kt + BotPromptManager.kt (persona Em là Bi, template preview 280)
│   ├── data/db/BibotDatabase.kt (MemoryCore/Facts/ChatLog/BookProgress/PdfJob, Room 2.6.1)
│   ├── data/books/BookRepository.kt + DriveBookSource.kt (demo assets→files, Drive READONLY, PublicUrl)
│   ├── data/offline/OfflineSttHelper.kt, OfflineTtsHelper.kt, ModelCatalog.kt, ModelDownloader.kt
│   ├── core/stt/SttBridge.kt, core/llm/LlmBridge.kt, core/tts/CloudTtsBridge.kt + VietnameseChunker.kt
│   ├── feature/voice/VoiceScreen.kt (Build 7 full loop), feature/settings/SettingsScreen.kt
│   └── ui/player/PlayerViewModel.kt + BibotPlayerBar.kt, ui/menu/HomeMenu.kt + BibotNavSuite.kt
└── .env.example
```

**Google Services:**

| Service | API | Key? |
|---------|-----|------|
| Gemini | `generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:streamGenerateContent` | Có |
| OpenRouter | `openrouter.ai/api/v1/chat/completions` SSE | Tùy |
| SpeechRecognizer | `android.speech.SpeechRecognizer` vi-VN | Không |
| Cloud TTS | `texttospeech.googleapis.com/v1/text:synthesize` | Có (fallback offline) |
| Cloud STT | `speech.googleapis.com/v1/speech:recognize` | Có (fallback SpeechRecognizer/Vosk) |
| Vosk | `vosk-model-small-vn-0.4` no_backup | Không (tải khi cần) |
| Android TTS | `TextToSpeech` vi-VN | Không |
| ML Kit / Document AI | `text-recognition` | Không (on-device) |
| Room+DataStore | `room 2.6.1` `datastore 1.1.1` | Không |
| Firestore+Auth | `firebase-firestore/auth 25.0.0` | Tùy |

---

## 3) Pi System — giữ nguyên hiện trạng v25 (không đổi trong Build 7)

Xem `ReadMe.md` và phần lịch sử dưới. Tóm tắt:

- **Audio C** `audio_streamer.c:27` 8192/2048 resilient, `stretch_pcm 0.5-1.5×`, device fallback
- **Python** `audio_stream.py:765` flip-flop Queue2, dual pipeline book 0.85× / story 0.88× + 0.45s pause, `_generate_tts_piped 536-717` TTS_PRIORITY routing PhatJKK→Piper→Edge
- **STT** offline Zipformer `local_asr.py` + ALSA plug `~/.asoundrc` `plug→plughw:1,0 @48000→16000`
- **TTS** PhatJKK `phatjkk_tts.py` singleton 22050Hz, `PIPER_TTS_ENABLED`
- **Mode14** PDF→MD 3-tier `pdf_to_md.py` + `windows_app/pdf_engine.py` 1092 dòng, daemon `MIN_FREE_MEM 250`
- **Mode5** `streaming_voice_chat.py 1060` + `streaming_llm.py` (datetime short-circuit, first-token 6s, use_web toggle) + `feed_prompt.py 265` + trí nhớ 3 tầng `conversation_memory.py 637` (T2 log 500 + T1 facts ≤30 + T0 core ≤50 bất tử)
- **Next Pi:** Deploy bundle v25 lên Pi (`install_tts_models.sh --with-phatjkk`, recompile `audio_streamer.c`, `sd_longevity.sh --apply`)

---

## 4) Lộ trình tiếp theo — Build 8-11 ✅ Hoàn tất code (2026-09-24)

| Build | Mục tiêu | Ưu tiên | Trạng thái |
|-------|----------|---------|------------|
| **7** | **TTS/STT Cloud + Voice E2E** | **1 — ✅ xong 2026-09-22** | ✅ Code + `go vet:0` |
| **8** | Book/Story Reader | 1 (2 ngày) | ✅ Code + `go vet:0` |
| **9** | PDF→MD + Drive polish | 2 (2 ngày) | ✅ Code + `go vet:0` |
| **10** | Memory 3 tầng + Firestore sync | 2 (1-2 ngày) | ✅ Code (Firestore cần config) |
| **11** | WorkManager + Polish | 3 (1 ngày) | ✅ Code + `go vet:0` |

**Còn lại (2026-09-24):**
- Deploy `bibot-relay` lên Cloud Run → điền URL vào `BIBOT_LLM_PROXY_URL`
- Firebase project config → điền 3 `FIREBASE_*`
- Test trên máy thật

Thứ tự khuyên trước mắt: **R2 (relay deploy) → R3 (Firebase config) → R5 (test máy thật)**.

**Verify Go 2026-09-24:** `go vet ./...` **0**, `go build ./...` **0**, `go vet ./relay/...` **0**, `go build ./relay/...` **0**.

**Checklist trước Build 8:**
- [x] Điền `GEMINI_API_KEY` test trong `local.properties` (không commit)
- [x] `FilePaths.ensureAll() + seedDemo()` on `MainActivity.onCreate` (Build 7 đã thêm)
- [x] Demo books thực tế: `en/` 2 PDF + `vi/` 2 MD (thay kế hoạch 3 PDF Gutenberg cũ — đã ghi nhận ở `Bibot_build.md` 8.5)
- [x] `WorkManager` dependency đã thêm (Build 11 xong)

**Remaining (2026-09-24):**
- [ ] `bibot-relay` deploy lên Cloud Run → điền URL vào `build.gradle.kts:27`
- [ ] Firebase project: tạo, bật Auth ẩn danh + Firestore, điền 3 `FIREBASE_*` vào gradle
- [ ] **AdMob setup**: tạo account → APP_ID + 3 Unit IDs → thay placeholder trong `AdConfig.kt`
- [ ] Tách `implementation(libs.admob)` → `commercialImplementation` (R10)
- [ ] Test trên máy thật: `dumpsys meminfo`, `assembleDebug`, UI không đơ

**Verify 2026-09-24:** `go vet ./...` **0**, `go build ./...` **0**, `go vet ./relay/...` **0**, `go build ./relay/...` **0**, `assembleDebug` **Thành công** ✅ → `Bibot.apk` 68.7MB / `Bibot-demo.apk` 70.6MB

---

## 6) Verify cũ (2026-09-22, giữ lại trace)

| # | Test | Kết quả |
|---|------|---------|
| 1 | `go vet ./...` | **0** |
| 2 | `go build ./internal/tts ./internal/stt ./internal/chunker ./cmd/mobile` | **0** |
| 3 | `CleanTTSText "2x3 = 6"` | `2 nhân 3 bằng 6` ✅ |
| 4 | `VietnameseChunker` 4 câu dài | 4 chunks ✅ |
| 5 | `HybridSttManager` online→offline fallback | Code ready, cần test device |
| 6 | `CloudTtsBridge` REST vi-VN-Neural2-A | Code ready, cần key thật |
| 7 | `VoiceScreen` chunk→TTS sequential | Logic ready, cần test ExoPlayer |
| 8 | `BibotPreferences` DataStore 12 keys | Flow OK |
| 9 | `assembleDebug` | Chưa — thiếu `ANDROID_HOME` + `gradle-wrapper.jar`, cần JDK17 |
| 10 | Pi `py_compile` toàn `services/*.py` | OK (v25) |

---

## 6) Files quan trọng (toàn repo)

- **Pi:** `audio_streamer.c`, `services/audio_stream.py:765`, `services/phatjkk_tts.py`, `services/config.py:222`, `services/streaming_voice_chat.py:1060`, `services/conversation_memory.py:637`
- **Android Go:** `android_app/internal/tts/tts.go:221`, `internal/stt/stt.go:12`, `internal/chunker/chunker.go:8`, `internal/llm/llm.go:11`, `cmd/mobile/mobile.go:21`
- **Android Kotlin:** `android/app/src/main/java/com/bibot/app/feature/voice/VoiceScreen.kt:38`, `core/tts/CloudTtsBridge.kt:18`, `core/tts/VietnameseChunker.kt:8`, `data/store/BibotPreferences.kt:32`, `ui/player/PlayerViewModel.kt:16`, `data/files/FilePaths.kt:22`

---

# Lịch sử phiên trước (giữ nguyên để trace)

## Ngày cập nhật: 2026-09-22 (Cập nhật lần 25 — Tổng rà soát toàn repo)

> File này thay thế snapshot 2026-09-19 v24. Lịch sử cũ giữ nguyên ở cuối file.

---

## 0) TL;DR — Dự án đang ở đâu

| Trục | Hiện trạng | Nguồn xác thực |
|------|------------|----------------|
| **Mục tiêu** | Trợ lý giọng nói offline-first, đa ngôn ngữ trên **Pi 4 1GB** — thương mại-ready | `REPO_SYSTEM_PROMPT.md:15-27`, `ReadMe.md:5` |
| **Entry** | `Pi_system/pi4_xiaozhi/py_chatbot.py:33-155` facade mỏng, ủy quyền `services/app_*.py`, lazy-load voice | `py_chatbot.py:1-18` |
| **Audio core** | C `audio_streamer.c:1-365` resilient + Python flip-flop `services/audio_stream.py:1-1039` (Queue maxsize=2) | `audio_streamer.c:27-29`, `audio_stream.py:765` |
| **TTS chính** | **PhatJKK in-process** `services/phatjkk_tts.py` (VI VITS 22050Hz) + Piper fallback + Edge-TTS online | `services/config.py:142-152`, `.env:91-94` |
| **STT** | Offline-first Sherpa Zipformer `local_asr.py/offline_listen.py` + Google fallback, ALSA plug 0-CPU | `services/config.py:99-112` |
| ... | ... | ... |

*(Phần lịch sử v25-v10 giữ nguyên như file cũ — đã rút gọn header, toàn văn vẫn ở file gốc. Để xem chi tiết Pi v25: đọc tiếp từ dòng 250 trong bản trước.)*

