# Checklist Nâng Cấp Bibot — Port từ OpenReadEra + openreader + Chuẩn UI ReadEra

> Single source cho đợt nâng cấp đọc sách Bibot.
> Nguồn: (1) so sánh `OpenReadEra/` + `openreader/` đã clone về repo,
> (2) screenshot thật ReadEra `org.readera 26.05.20+2300` chạy trên AVD `BibotTest`.
> Ngày lập: 2026-10-10. Bibot hiện tại: `com.bibot.app`, Compose + Go core.
> APK (cập nhật 2026-10-12): demoDebug ~58,9MB; release `Bibot.apk` 33,7MB /
> `BibotV.apk` 34MB (xem Phần J). Số ~83,5MB lúc lập là bản debug cũ.

Quy ước: `- [ ]` chưa làm, `- [x]` xong. Mỗi mục ghi file Bibot bị chạm.

---

## PHẦN A — Port list từ OpenReadEra vs openreader

### A0. Nguyên tắc (đọc trước khi code)

- [ ] Giữ điểm mạnh Bibot: voice chat (`VoiceChatViewModel`), chunker VI, chain Piper → Cloud → Offline, tốc độ 0.5–2.0x, Room + DataStore. Chỉ thay vỏ đọc sách + cache, không đập pipeline.
- [ ] Không static-link code GPL (MuPDF AGPL, DjVu GPL, CoolReader GPL trong `OpenReadEra/`). Chỉ port ý tưởng/protokol; dùng lib Apache/MIT trên Android (`PdfRenderer`, Readium/Epublib, `java.util.zip`).
- [ ] Mọi cache audio phải content-addressed (theo openreader), không dùng id tạm + TTL mù như hiện tại.

### A1. P0 — Cache + identity TTS kiểu openreader (ăn ngay, ít rủi ro)

- [x] A1.1 Port `packages/tts/src/segment-plan.ts + segments.ts + nlp.ts` sang Go chunker:
  - File chạm: `internal/chunker/chunker.go` (giữ nguyên), MỚI `internal/chunker/segments.go`
    + `segments_test.go`; Kotlin MỚI `core/tts/TtsSegments.kt` (+ `src/test/.../TtsSegmentsTest.kt`).
  - Mỗi segment có `{ordinal, key=FNV(normalizedText), locator(spineIndex/charOffset), start/endAnchor}`.
  - FNV-1a tính trên UTF-16 units (khớp JS charCodeAt; vector ASCII chuẩn + surrogate
    pin chéo Go↔Kotlin `dae009c7`). Có segmentKey → locator null (dedup chủ đích,
    khớp segments.ts). Key side-local (Go/Kotlin không chia sẻ file).
  - `ordinal` là identity duy nhất để play/seek/resume; locator/text không dùng để start playback.
- [x] A1.2 Port `settingsHash/audioContentHash`:
  - `settingsHash = SHA256(settingsCanonical + 0x00 + segmentationCanonical)`
    (voice/format/speed/language/maxBlock); `audioKey = {settingsHash}/{audioHash}.{mp3|wav}`.
  - File chạm: `data/files/FilePaths.kt` (MỚI `ttsSegmentRoot/Dir/File` durable ở
    noBackup — KHÔNG TTL, LRU 300MB riêng `cleanupSegmentCache`, móc vào `cleanupCache`);
    Room entity MỚI `tts_segment_cache` + `TtsSegmentDao` (DB v1→2, destructive migration
    có sẵn); helper MỚI `core/tts/TtsAudioCache.kt` (lookup chạm LRU / store nguyên tử
    tmp→rename + siết cap); `ReaderScreen` producer lookup-trước-synth + store
    fire-and-forget (chỉ path Pcm piper/cloud; Direct offline không cache — voice
    nằm ở engine máy). `bibot_tts_<ms>` tạm giữ cho announce/khẩn.
  - Đổi voice/speed → trúng cache cũ khi switch-back, thay vì mất cache như hiện tại.
- [x] A1.3 Chuẩn CBR theo `tts/src/audio-format.ts`: hằng đích `44.1kHz mono 128k CBR,
  16000 B/s, frame 1152 (~26.12ms)` port vào `TtsSegments` làm đích cho export (A3).
  KHÔNG transcode on-device (không ffmpeg): giữ Google MP3 (có Xing → ExoPlayer seek
  tốt) + Piper wav; KHÔNG strip Xing (strip chỉ đúng khi tự nối stream như openreader).
  - File chạm: `ui/player/PlayerViewModel.kt` → fix bug `seekForward` kẹp về -1 khi
    duration TIME_UNSET; `setSpeed` kẹp 0.5–2.0x (khớp CloudTtsBridge); `seekBack`
    vốn đã an toàn. Seek ±10s dùng ở `BibotPlayerBar`.
- [x] A1.4 Voice-resolve trước khi plan (port fix openreader `0b4d559b`):
  - MỚI `core/tts/TtsVoiceResolve.kt`: 1 chuỗi fallback duy nhất Piper→Cloud→Offline
    (giữ đúng case reward theo chương + VIP bypass); snapshot 1 lần lúc bắt đầu đọc,
    không re-plan giữa chừng; voiceId không bao giờ rỗng (bất biến có test).
  - `ReaderScreen` dùng resolver cho effect đọc; degraded (thiếu model/key) hiện tag
    `• offline` ở dòng info (thay vì im lặng fallback như trước).
  - File checklist ghi `SettingsScreen/ModelsViewModel`: ModelsViewModel đã probe
    installed trước mọi tác vụ (đủ); VoiceChatViewModel giữ logic riêng (ngoài A1).
- [x] A1.5 Verify: Go `internal/chunker` 8 test PASS; JVM `TtsSegmentsTest` 9/9 PASS
  (FNV vector + parity Go, settings tách voice/speed, dedup, resolve ma trận 8 case);
  `assembleDemoDebug + assembleCommercialDebug` SUCCESS; máy ảo BibotTest: đọc Cổ tích
  resume đúng + tag `VI • 0.88x • offline`, logcat sạch FATAL, `no_backup/.../segments/`
  đã tạo. Hạn chế trung thực: hit-cache Pcm trên máy thật cần Cloud key/Piper model
  (emulator không có) — lookup/store đã bọc try/catch, miss = luồng cũ y nguyên.

### A2. P1 — Reader engine (lazy page + convert + snap config)

- [x] A2.1 Lazy page kiểu `EraPdfBridge.getPage(i,decode)+free + LRU 3–5 trang + store cap 64MB`:
  - File chạm: `core/text/BlockTextReader.kt` (block 16KB/CARRY 256 + `free()` tường minh),
    `core/text/PdfTextCleaner.kt` (đính chính: không tồn tại file `PdfToMarkdownHelper.kt`),
    `BookRepository.kt` (đã hết `readText()/readBytes()` toàn file — verify 2026-10-12).
- [x] A2.2 MOBI → EPUB rồi reuse renderer (XONG 2026-10-13 — draft sai-format cũ đã
  viết lại theo PalmDB thật + verify máy ảo):
  `core/text/MobiReader.kt` (PalmDB BOOK/MOBI detect + PalmDoc LZ77/raw + EXTH
  503-title/100-author + từ chối rõ DRM/HuffCdic + `convertToEpub` tối thiểu để
  `EpubReader` đọc lại; Mobi6 chunk 32k, KF8 dual-format chỉ đọc phần Mobi6);
  wire: `BookRepository` (scan `.mobi/.azw/.azw3` + `iterSentences`),
  `BlockTextReader.readChapters` nhánh mobi, `PublicDriveSource.mimeFor`,
  `ReaderCollection` STORY_VI/LOCAL_PDF, `ViewScreen` (kệ + tác giả EXTH).
  Test JVM `MobiReaderTest` 10/10 (file MOBI dựng tổng hợp + PalmDoc vectors +
  convert→EpubReader roundtrip); full suite 33/33. Máy ảo: `test_mobi.mobi`
  tự dựng hiện kệ (44 sách) + mở đọc text thật.
- [x] A2.3 Snap config + `RenderIfDirty` (copy `ALLOWED_FONT_SIZES/INTERLINE/PARAGRAPH_MARGINS` của `eraepub`): chỉ relayout khi setting đổi, hết jank khi đổi font sách dày.
  - File chạm (đính chính 2026-10-12: `BookScreen/StoryScreen` đã xóa ở R0):
    `data/store/BibotPreferences.kt` (`ALLOWED_FONT_SIZES` + `readerFontSizeSp/...`),
    `feature/reader/ReaderScreen.kt` (`renderKey = derivedStateOf{...}`), UI slider ở
    `feature/settings/SettingsScreen.kt`.
- [x] A2.4 Đơn vị vạn năng `Hitbox + SearchWindow ±7 từ` + gộp Shell_Manager core (MỞ RỘNG 2026-10-13 — quyết user: tách progress khỏi UI vào manager chung; XÁC MINH CHÍNH XONG):
  struct `Hitbox/SearchWindow` + `hitboxFromCharOffset/searchWindow` đã có trong
  `core/text/TextCleaner.kt` và search toàn sách (`runSearchFullBook`) đã dùng —
  NHƯNG progress vẫn `position = chap*100+sent` (`ReaderScreen` + `BookDetailScreen`
  còn `/100`, `%100` khắp nơi; sai khi chương >100 câu như B1.5/B3.4 đã ghi nhận).
  File cũ `BookScreen/StoryScreen` đã xóa ở R0.
  - A2.4a MỚI `data/library/ReadingPosition.kt` (pure, kế `LibraryQuery`, có
    `ReadingPositionTest`): `data class ReadingPosition(chap,sent,page?,offset?)` +
    `fromLegacy(pos)` / `toLegacy()` / `isFinished(total)` / `resumeLabel()`.
    Mọi Tab/kệ kiểu ReadEra sau này dùng chung, không tự tính `/100,%100`.
  - A2.4b DB Migration 5→6 (`BibotDatabase.kt`): thêm `chapterIdx/sentenceIdx`
    vào `book_progress` (ADD COLUMN có DEFAULT, giữ `position` cũ 1 bản để rollback;
    backfill `chap=pos/100, sent=pos%100`).
  - A2.4c `LibraryManager` thêm `getPosition/savePosition/resumeSnapshot()`
    (facade suspend, không Compose) — `ReaderScreen/Detail` chỉ gọi, không tự tính;
    `LibraryQuery.selectBooks` đổi sang đọc `ReadingPosition` (sửa luôn bug B1.5
    sách dở rơi vào Đã đọc).
  - Liên hệ B1.6: `data/library` (Query+Manager) chính là phôi Shell_Manager;
    A2.4c hoàn thiện nhánh progress của nó để mọi Tab quản lý/hiển thị reuse.
  - VERIFY CUỐI: `ReadingPositionTest` 5/5 trong full JVM 69/69 + ruler 64/64 +
    Go PASS; máy ảo + máy thật sạch FATAL; APK demo 58,5MB.
- [x] A2.5 Verify chính (resume+migration — XONG):
  + Migration v5→6: cài đè giữ data, 3 dòng cũ giữ nguyên + backfill đúng
    (`3→(0,3)`, `4→(0,4)`, `112→(1,12)`), schema có `chapterIdx/sentenceIdx`.
  + Resume >100: sách test `resume150.md` (chương 150 câu) gieo legacy `pos=120`
    (code cũ đọc thành Chương 2/câu 21) → reader hiện đúng `Chapter 1/3` +
    chip `Resume sentence 121` + dialog `C1 • câu 121`; Yes → READ lưu
    `chapterIdx/sentenceIdx` thật; kill app mở lại chip giữ đúng; logcat sạch FATAL.
  + Drawer badge/kệ Đang đọc: N/A trên nav hiện tại (module library chưa vào nav) —
    logic `READING` cover bởi JVM `LibraryQueryTest` + chip resume trên máy ảo.
- [ ] A2.5b Còn mở: mở sách 3–5MB + đổi font/sách dày (chuyển sang phiên sau).

### A3. P2 — Export + Player polish (học openreader)

- [ ] A3.1 Export audiobook durable (port `export-job.ts + ffmpeg-export.ts`): prefetch 4, batch sidecar 32, assemble khi audio xong (không chờ align), lỗi terminal → silence 1s + đếm `skipped`, progress đọc từ cache scope (fix `eee701c6`: không đọc session hết hạn 30p).
  - File chạm: mới `feature/export/ExportViewModel.kt` + WorkManager.
- [x] A3.2 Gộp 2 player chồng (`ExoPlayer` vs `speakOffline Direct`) thành 1 controller; mọi màn gọi 1 hàm stop duy nhất (hết đọc chồng News/Weather/Voice bypass `activeIds`).
  - File chạm: `PlayerViewModel.kt`, `data/offline/OfflineTtsHelper.kt`, `NewsViewModel`, `WeatherScreen`, `VoiceScreen`.
  - Code 2026-10-17: ① `OfflineTtsHelper` constructor private + `shared(context)` = **1 engine TTS/process** (hết 4–5
    instance song song; bỏ `shutdown()` ở News/Voice vì engine dùng chung). ② `PlayerViewModel` = **1 controller**: sở hữu
    engine; `suspend speakOffline(text, speed, lang, mediaId)` tự `stopPlayback()` trước (2 nguồn không thể phát chồng) +
    ghi `mediaId` vào `activeIds` trong lúc đọc (đọc offline hết bypass karaoke); `stopAll()` = hàm dừng DUY NHẤT (gọi
    listener **trước** → hủy job đọc nền, rồi ExoPlayer, rồi engine TTS — coroutine dừng trước khi engine trả false nên không
    sinh lỗi "TTS offline" giả); `playUrl`/`enqueueBytes` tự cắt engine TTS; `addStopListener/removeStopListener`.
    ③ `BibotNavSuite.switchMode()` — mọi đổi màn (back/mic/settings/HomeMenu/`goHome`) đi qua `stopAll()`.
    ④ News: `bindPlayer()` + `runLocalStop/stopAudio/resetAudioLocked` + `speakViaController`; ⑤ Weather: nhận `playerVm`,
    bỏ `remember { OfflineTtsHelper }`; ⑥ Voice: `runLocalStop/stopAudio/cutAudio`, `stopChat` = 1 lệnh, `DisposableEffect`
    + listener hủy `pipelineJob` khi rời màn (job sống theo Activity); ⑦ Reader: bỏ `offlineTts` local — `stopAll`/
    `togglePlay`/`jumpNow`/`startReading`/`listenSummary` đều `playerVm.stopAll()`, Direct đọc qua controller kèm `mediaId`.
  - Verify máy ảo 2026-10-17: Weather đọc → Back = `AudioCtrl stopAll` + `dumpsys audio` 1 `state:started` → **0**
    (engine TTS dừng thật, không chỉ hủy coroutine); News đọc giữa chừng → nút Settings = `stopAll listeners=1` + job bị hủy
    (không có `speakOffline done ok=false`), quay lại News **không còn banner "TTS offline lỗi"** + nút Play reset;
    reader Direct `speakOffline ... mediaId=story_0_0_1` → pause/resume/Back đều `stopAll`; Voice vào/ra =
    `stopAll listeners=2` (News + Voice cùng đăng ký); **0 FATAL** toàn phiên.
  - Gate: `go vet` 0 + `go test` PASS + ruler 64/64 + JVM 69/69 + `assembleDemoDebug` (Bibot.apk 56MB) +
    `assembleCommercialDebug` (BibotV.apk 57,2MB) PASS.
- [ ] A3.3 Scrubber 3 lớp + Undo 10s + MediaSession (port `TTSPlayer.tsx + useMediaSession.ts`) vào `FloatingControlBar/BibotPlayerBar` (hiện `BibotPlayerBar` chết, không ai gọi — gộp làm một).
- [ ] A3.4 SmartCrop sách scan (port `orecrop/smartcrop.cpp IsRectWhite 1%`) + đọc size ảnh comic không decode full + natural-sort.
  - File chạm: `DocBrowserScreen`, `PublicDriveSource`.
- [ ] A3.5 Verify: export M4B/MP3 có chapters; stop ở mọi màn dừng hết tiếng; scrubber hiện ready-bands.

---

## PHẦN B — Nâng UI Bibot theo chuẩn ReadEra (từ screenshot máy ảo)

### B0. Đối chiếu đã xác minh trên AVD

- [x] B0.1 Library + drawer 14 mục + row sách (bìa/progress/4 action) — đã chụp `re1/re3`.
- [x] B0.2 About Document (READ vàng + loa TTS + metadata dòng) — đã chụp `re4`.
- [x] B0.3 Reader sepia + đáy `✕|⚙|x of N|‹ ›` + FAB TTS + hint center-tap — đã chụp `re7`.
- [x] B0.4 Center-tap menu (top bar 5 nút + brightness + `< to page N/CLEAR` + scrubber vạch chương) — đã chụp `re13`.
- [x] B0.5 Dialog đổi vị trí TTS (`NO/YES`) — đã chụp `re9`.
- [x] B0.6 Script chạy máy ảo `run_bibot_emulator.bat` (repo root, double-click):
  kiểm tra emulator → boot cold AVD `BibotTest` nếu chưa chạy → chờ boot ≤5 phút →
  `install -r` APK demoDebug → mở app. Đã chạy thử end-to-end (emulator sẵn:
  bỏ qua boot, cài + mở app OK). Lưu ý: file `.bat` phải CRLF; `)` trong `echo`
  nằm trong khối `(...)` phải tránh (đã vấp 1 lần).

### B1. Giai đoạn 1 — Thư viện kiểu ReadEra (thay `DocBrowserScreen`) — [x] 2026-10-11

- [x] B1.1 Drawer nav: `Đang đọc / Sách & tài liệu / Yêu thích / Đọc sau / Đã đọc / Tuyển tập /
  Tác giả / Series / Định dạng / Thư mục / Tải về / Thùng rác` (ModalNavigationDrawer,
  nhóm mở rộng + badge đếm). Lệch checklist: KHÔNG thêm cột vào bảng cũ mà bảng sidecar
  mới `library_meta (shelfFav/shelfToRead/shelfRead/trashed/lastRead/author/series)` +
  `Migration 2→3` thủ công (giữ progress/cache; AutoMigration kẹt kapt-setup nên bỏ).
  Tác giả/series parse từ EPUB OPF (`EpubReader.readMeta`, backfill 30/lần); chưa tải → "Chưa rõ".
  Thùng rác mềm: chỉ ẩn ở library (reader tabs không đổi — ghi nhận).
- [x] B1.2 Row sách: bìa (Lệch: KHÔNG thêm Coil — `LibraryCovers`: PDF render trang 0
  ~160px RGB_565 / EPUB cover decode sampled / còn lại ô chữ+emoji; LRU RAM 24) +
  tựa/tác giả/format-size + progress bar (`BookProgress`) + 4 action
  (Tải/Yêu thích/Đọc sau/⋮: đánh dấu đã đọc + thùng rác) + khôi phục/xoá hẳn trong trash.
  Không mở sách vẫn quản lý được.
- [x] B1.3 FAB `+` mở SAF (giữ picker cũ); auto-scan khi đã có quyền (silent);
  search debounce giữ nguyên + empty state hướng dẫn; bỏ `take(30)` cắt ngầm (43 sách
  hiện đủ, LazyColumn).
- [x] B1.4 Phát hiện khi verify: `BibotMode.PDF` tồn tại nhưng KHÔNG có ô nào ở Home
  (PDF mồ côi thật) → thêm ô `📄 Documents` vào grid Home; PDF nằm chung grid thư
  viện (lọc Định dạng > PDF).
- [x] B1.5 Verify máy ảo BibotTest: drawer đủ mục + badge (43/1 fav/1 finished/3 tuyển
  tập/4 định dạng); toggle fav → badge; ⋮ → trash → Books 43→42 (sqlite xác nhận
  trashed=1+shelfFav=1) → restore sạch; migration v2→v3 không crash, progress cũ còn
  (Finished badge từ row cũ); logcat sạch FATAL; APK demo 58.423.686B < 85MB.
  Hạn chế trung thực (emulator offline, không tải được PDF/EPUB): đường bitmap bìa
  PDF/EPUB + backfill author mới review-code (không crash), chưa thấy ảnh thật;
  verify lại khi có mạng. `position=chap*100+sent` cũ khiến sách dở rơi vào Đã đọc
  (ghi nhận, sửa ở B3.4/A2.4).
- [x] B1.6 GỠ TAB Documents khỏi nav (quyết user — trùng kệ đọc + Card không mở được
  sách + fav/trash chỉ Documents đọc, kệ khác không thấy): xóa tile Home +
  `BibotMode.PDF` (enum + nhánh + `inReader`) — PDF vẫn đọc được ở kệ EN Books.
  Code GIỮ lại làm module quản lý cho Shell manager: `feature/doc` → dời sang
  `feature/library` (DocBrowserScreen + LibraryCovers, nguyên logic, chưa nối lại);
  tách core `data/library/` — `LibraryQuery` (lọc/nhóm/tìm pure, có unit test
  `LibraryQueryTest` 7/7) + `LibraryManager` (facade suspend trên
  BookRepository/Room/SAF/covers, không Compose); DocBrowserScreen đã chuyển sang
  gọi core (không logic lọc/thao tác riêng). DB `library_meta` giữ nguyên (không
  migration). Verify: build + JVM 59/59, cài máy ảo, Home còn 6 tiles, không FATAL.

### B2. Giai đoạn 2 — Màn About sách mới (`BookDetailScreen`) — [x] 2026-10-11

- [x] B2.1 Tách khỏi `BookScreen` (~770 dòng ôm đồm): màn mới hiện bìa lớn + nút `ĐỌC` nổi + nút loa (đọc từ đầu bằng pipeline hiện tại) + dãy 7 action + metadata dòng (`Tác giả / Thể loại / Ngôn ngữ / Bookmark + giờ đọc / format-size-files`).
- [x] B2.2 `BookScreen/StoryScreen` chỉ còn nhiệm vụ render + đọc; nhận `bookId + startPos` từ Detail.
- [ ] B2.3 Verify: mở Detail <1s; bấm ĐỌC vào đúng vị trí bookmark; nút loa đọc từ đầu.
- [x] B2.4 Add `BibotMode.ABOUT` to navigation + `BookDetailScreen.kt` creation + `nav_about` strings (EN/VN) + wire in `BibotNavSuite.kt`.

### PHẦN MỚI — 2 Chế Độ Đọc (đính chính 2026-10-13: tách theo ĐỊNH DẠNG + TÔN TRỌNG BẢN GỐC)

> Nguyên tắc (quyết user): 2 nhóm, không phải 2 tab.
> 1. **EPUB + PDF = có format** (layout, ảnh, font nền…) → tôn trọng tác phẩm gốc:
>    xem đúng bản gốc + nghe TTS.
> 2. **MD/TXT = text thuần** → gọn nhẹ, tập trung cho TTS.

#### Mode 1: EPUB/PDF (có format) — [x] xem + TTS thật
- **Xem đúng bản gốc**: `ReaderScreen` đã có 2 VIEW TEXT/PAGE (F1.1) — PDF render
  `PdfPageView` (PdfRenderer framework + pool 3 + prefetch), EPUB spine-order
  (`EpubReader` + detect scan + cover/bìa + tác giả OPF).
- **A — tách display-faithful vs TTS-smart (xong 2026-10-13, user chọn scope A,
  verify máy ảo bằng screenshot)**: trước đây display hiện text đã smart-clean
  (ngày→chữ, `—`→`,`, `(Fig.5)`→`(hình 5)`, `[Pg 62]` đọc "P g") — sai bản gốc.
  Nay `.prep` lưu NGUYÊN BẢN (`readOriginalChapters` + `cleanForDisplay`: giữ
  chữ/số/dấu/TOC/số trang, chỉ bỏ tag/entities-rác + TCVN3 + ký tự điều khiển);
  TTS-smart chạy ở speak-time (producer: lướt phần đầu trước mốc + `ttsSpeakable`
  bỏ dòng rác + `cleanForTts` như cũ) — karaoke giữ nguyên index. `PREP_VERSION`
  3→4 (cache cũ tự rebuild; resume cũ lệch câu là chấp nhận được).
  EPUB ẩn span số trang in ở extractor (cả display lẫn TTS).
  Verify: "How it Works" hiện `April 10, 2009 [#28553]`, TOC `—` + xuống dòng
  (hết dính `managed,Limit`), TTS bấm play synth en-US, không FATAL.
- **PDF grimm10**: trang scan render ảnh đúng gốc; bug race `Current page not
  closed` (effect chính + fallback từng item render song song trên 1 PdfRenderer —
  lỗi rải rác p8/9/11/15/18/19) → serialize bằng Mutex + retry nửa chiều rộng +
  giữ aspect (bản cũ cap cao 2400 làm bẹp trang dài) + log nguyên nhân.
  Verify: lướt tới Page 18/301 render đẹp, logcat hết `PdfPageView fail`.
- **Scope B — EPUB rich, giữ nguyên bản (xong, verify screenshot máy ảo)**:
  `EpubReader.richChapterAt` (1 pass: heading H1-H3 / đoạn / caption / bảng /
  `hr` + inline đậm-nghiêng, path ảnh đã resolve) + `readImageBytes` +
  `EpubRichView` (ảnh decode local sampled ≤1200px RGB_565, LRU 8, caption
  italic giữa, highlight best-effort theo câu TTS). EPUB toggle TEXT (karaoke) ↔
  PAGE rich (thay phân trang text cũ); EPUB scan mặc định + khóa PAGE rich
  (thấy được ảnh dù không có chữ). Verify: "How it Works" hiện ảnh đầu máy hơi
  nước + caption + chữ đậm/căn giữa đúng gốc.
  Bug bắt được khi verify: key LazyColumn trùng (`rule:indexOf`, đoạn lặp) crash
  `Key "rule:11"` → key theo index.
- **Scope B — số trang (quyết user: display GIỮ, TTS KHÔNG đọc, qua rulerpack)**:
  span số trang in Gutenberg rỗng (số nằm ở attribute) nên extractor không đổi;
  rule mới `drop.pageno_inline` (rewrite scope tts, template RỖNG = xóa match —
  validator + 3 engine đều nuốt) → ruler v3 (17 rules, tests 64/64, JSON 3 bản
  tools/internal/assets đồng bộ, Go PASS, JVM pagenoInline test).
  Verify unit (display `cleanForDisplay` giữ `[Pg 62]`, apply bỏ).
- **Nghe TTS**: producer `ReaderScreen` (Piper/Cloud/Offline + `TtsVoiceResolve`).
- **Kệ sách**: kệ từng kho trong `ReaderScreen` (mở + đọc tại chỗ).
  Tab Documents đã GỠ khỏi nav (quyết user — xem `Tab Documents` dưới).
- **Status**: ✅ Đọc EN/VN (Drive, local, demo).

#### Mode 2: MD/TXT (gọn nhẹ, tập trung TTS) — [x] đọc + TTS thật (ruler v2, xem Phần H)
- **Engine**: `BlockTextReader` (16KB blocks) + `TextCleaner.cleanForTts` (+ `ReadingRules.applyIfReady` từ H2.0).
- **TTS**: producer `ReaderScreen` (Piper/Cloud/Offline + `TtsVoiceResolve`) — LƯU Ý: `TtsSegments` (A1)
  làm identity/cache audio, KHÔNG gọi `ReadingRules.apply()` (bản cũ ghi nhầm).
- **Rules**: `assets/reading-rules.json` **v2, 16 rules** (bản cũ ghi "3 rules" là v1):
  drop số trang + TOC chấm, start-marker Lời giới thiệu/Chương 1, ngày (có/không năm),
  nhiệt độ, La Mã (đơn + khoảng, có đuôi chống tái-match), Fig/No theo lang, params chunker.
- **Wire thật**: `ReadingRules.init()` ở `BibotApp.onCreate` → `applyIfReady` trong
  `cleanForTts` + vòng dòng `BlockTextReader` thử ruler trước (fallback code cứng).
- **Status**: ✅ Đọc EN/VN (Drive, local, demo).

#### Tab "Xem" (VIEW) — [x] ĐÃ GỠ (2026-10-13): TRÙNG CHỨC NĂNG, không phải chế độ đọc
- Đối chiếu code: mọi khả năng của `ViewScreen.kt` đã có ở tab khác, sâu hơn —
  kệ sách ⊂ (Documents + kệ `ReaderScreen`), chương text ⊂ render BROWSE của
  `ReaderScreen` (cùng `BlockTextReader.readChapters` + ruler), `PdfPageView` là cùng
  1 component `ReaderScreen` đang dùng, TTS xem trước ⊂ pipeline TTS `ReaderScreen`
  (Piper/Cloud/Offline + resume + bookmark + search — View không có).
- Đã xóa: `feature/reader/ViewScreen.kt` + `BibotMode.VIEW` (enum + nhánh nav) +
  tile grid Home. Không đụng `MobiReader`/`EpubReader`/`PdfPageView` (dùng chung).
- `nav_view` trong strings để lại (không ảnh hưởng build).
- Lịch sử trung thực: 2026-10-12 View là mock tĩnh → 2026-10-13 đã wire sách thật
  (verify máy ảo: kệ 44 sách, `Linh Nam Chích Quái.md` + `test_mobi.mobi` đọc được) →
  cùng ngày xác nhận trùng lặp nên gỡ luôn thay vì giữ 2 đường làm một việc.

#### Ruler là engine dùng chung cho text đã-clean (không phải renderer ảnh) — không cần repo ngoài

### B3. Giai đoạn 3 — Chrome màn đọc (sửa `ReaderScreen`) — [x] 2026-10-11

- [x] B3.1 Full-screen khi đọc, ẩn nav; chạm giữa toggle menu (top bar `back / TTS /
  search / mục lục / ⚙` + brightness trong app + đáy `x of N + ‹ ›`). Mới
  `feature/reader/ReaderChrome.kt` (chung cho TEXT/PAGE). Brightness = slider đổi
  `window.screenBrightness` (null = hệ thống, trả về khi rời màn). Search là nút
  placeholder báo "B4" (search thật ở B4.2, không làm giả).
- [x] B3.2 Bỏ pill `FloatingControlBar` ở màn đọc (component giữ cho module khác),
  thay bằng FAB TTS tròn góc phải; thêm lịch sử chương (`‹ về trang N / XÓA`,
  tối đa 10); scrubber toàn chiều rộng + vạch chương (`ChapterTicks` từ chapters,
  cap 400 vạch); ‹ › đổi chương.
- [x] B3.3 Dialog `Đổi vị trí — đọc tiếp từ đây? (Không/Có)` khi đổi chương/câu lúc
  đang READ (TOC/scrubber/‹ ›/nhảy chương; nhảy ±câu trong chương đi thẳng).
  Verify máy ảo: TOC → chương 5 → dialog → Có → đọc tiếp + dialog tự đóng
  (từng sót bug dialog dính — đã sửa `pendingJump=null` trong `jumpNow`).
- [x] B3.4 Một nửa (ghi nhận): lưu progress MỖI CÂU (bỏ `% SAVE_EVERY`, sqlite chứng
  minh position 402 = chương 4 câu 2); back về list (Detail chưa có — B2 nối sau).
  Chưa làm: `position` Hitbox/xpointer (đợi A2.4; `chap*100+sent` cũ giữ nguyên).
- [x] B3.5 Verify máy ảo BibotTest: chrome ẩn/hiện bằng chạm giữa khi READ, đọc tiếp
  không che chữ (karaoke chạy, FAB Pause); TOC nhảy chương hỏi dialog; anchor +
  progress sống qua install -r. Chưa đo 30 phút (ghi nhận test dài ở B4).
- [x] B3.6/G1 Khắc phục crash xoay ngang-dọc (2026-10-11): tái hiện trên máy ảo —
  FATAL trong `AdsBannerComposable` (`update` gọi `setAdSize` sau `loadAd`, AdMob
  cấm; kèm `defaultDisplay` deprecated). Sửa: key `AndroidView` theo
  (widthDp, landscape) từ `LocalConfiguration` → xoay dựng AdView mới đúng size;
  `onRelease destroy()`; bỏ `defaultDisplay`. Verify: xoay list + reader cả 2 chiều,
  không FATAL, giữ vị trí/chrome (Chapter 1/14, karaoke tiếp tục).

### B4. Giai đoạn 4 — Mục lục / Quotes / Bookmark / Search

- [x] B4.1 Mục lục từ heading đã parse (`##/Chương`); quotes/bookmark bảng Room mới, gom 1 chỗ như ReadEra.
- [x] B4.2 Search trong sách: `BlockTextReader` + highlight `SearchWindow ±7 từ` (A2.4), nút Forward/Back giữa kết quả.
- [ ] B4.3 Verify: sách 500 chương vẫn liệt kê + nhảy nhanh; quote lưu note + mở lại đúng chỗ.

### B5. Giai đoạn 5 — Tóm tắt AI (Bonus/VIP, Cloud AI)

> Entry ở Detail (B2) + cần hạ tầng chương (B4) nên làm sau B4; xong trước A3 để
> export audiobook có thể kèm track tóm tắt. Tốn token Cloud thật → gating là
> yêu cầu bắt buộc, không phải optional.

- [x] B5.1 Scope v1 (XONG 2026-10-14, chống phình): đúng 2 mức — tóm tắt **Chương** +
  tóm tắt **Sách** (map-reduce trong `SummaryRepository.summarizeBook`, cap
  `MAX_BOOK_CHAPTERS=20`). KHÔNG mức "truyện trong sách", KHÔNG hỏi-đáp.
- [x] B5.2 Ống LLM (XONG 2026-10-14): reuse `LlmBridge.generateStreamRest`
  (Gemini→Proxy→OpenRouter) qua `SummaryRepository.collectLlm` (gom stream + timeout
  120s + loại fallback persona); key riêng `geminiApiKey` (≠ key TTS), model theo
  `geminiModel`; prompt versioned `SUMMARY_SYS_V1/SUMMARY_PROMPT_VER=1` trong
  `BotPromptManager` (+ cap `SUMMARY_MAX_CHARS=6000`, output 512).
- [x] B5.3 Cache (XONG 2026-10-14): Room `chapter_summary` (DB v6→7,
  `MIGRATION_6_7` — bài học: DDL KHỚP BYTE Room codegen, không DEFAULT, index dạng
  `index_<bảng>_<cột>`; đồng thời sửa tên index 4→5 cho đường v4→v7) + cap
  6000 ký tự/chương + 20 chương/lần. Bắt được bug thật: migration sai tên index →
  Room từ chối (đã sửa, verify sqlite trên máy ảo).
- [x] B5.4 Gating (XONG 2026-10-14): VIP full; non-VIP `rewarded_summary` (+3/ad,
  trần 9, trừ 1/call); mất key/offline → Gate báo rõ (giữ vị trí đọc); hết lượt →
  chặn + mời xem ad (xem xong tự retry). Verify máy ảo end-to-end (dưới).
- [x] B5.5 UI (XONG 2026-10-14 — LỆCH: Detail B2 vẫn mock chưa nối nên entry đặt ở
  `ReaderScreen`: icon tóm tắt từng dòng TOC + nút `Tóm tắt cả sách` ở header TOC
  (kèm tiến độ `Đã tóm tắt n/N`); dialog `SummaryDialog` đủ 4 trạng thái C2;
  `Nghe tóm tắt` bằng chain TTS (offline-first, Cloud nếu có key); cấp sách chạy
  `SummaryWorker` (1 work/sách, poll tiến trình vào dialog).
- [x] B5.7 Pipeline tóm tắt dùng chung (CODE XONG 2026-10-14, chạy local-only đến khi
  có Firebase — `FirestoreSync.getWikiSummary/putWikiSummary/bumpWikiHits` + thứ tự
  local → wiki → LLM → local + upload trong `SummaryRepository`; first-writer-wins;
  `summaryShareEnabled` (mặc định bật — UI toggle đã có ở `SettingsScreen`
  (`summary_share`/`summary_share_sub` EN/VI, sau cụm paragraph-margin) + setter
  `setSummaryShareEnabled` đã có sẵn; còn nợ test opt-out trên máy) +
  KHÔNG BAO GIỜ upload nguyên văn): key chia sẻ =
  content-hash chương + model + promptVer (kế thừa B5.3/A1) → document ID cố định
  trong collection PUBLIC mới `wiki_summaries/{hash}_{model}_{promptVer}`
  (khác `bibot/{uid}/...` per-user hiện tại). Thứ tự đọc: cache local → Firestore
  public → miss mới gọi LLM → ghi local + upload. Upload mặc định các tóm tắt do
  máy mình sinh (ngắn, phái sinh) + nút tắt trong Settings; KHÔNG BAO GIỜ upload
  nguyên văn sách. First-writer-wins + `updatedAt`; rules: public read, auth write,
  giới hạn size vài KB + rate-limit theo user. Mỗi chương chỉ 1 người trả token
  đầu tiên, N người sau đọc free (đếm `hits` để đo).
- [ ] B5.8 Wiki book_reader tự động: từ dữ liệu chia sẻ dựng trang wiki mỗi sách —
  metadata + mục lục chương (B4) + tóm tắt từng chương (v1 CHỈ summaries, chưa quotes).
  Trong app trước (tab Wiki ở Detail B2), web public để sau. Đủ N% chương có summary
  → gắn nhãn "wiki hoàn chỉnh"; thiếu → hiện tiến độ + nút "đóng góp" (sinh tóm tắt
  chương thiếu). Ghi nhãn "AI tóm tắt, có thể sai" + nguồn model/ver; prompt mới
  (promptVer khác) không đè bản cũ; nút báo xấu → ẩn chờ duyệt. Tuân thủ gỡ nội
  dung khi khiếu nại bản quyền.
  LUẬT CÔNG BẰNG 2026-10-15 (user yêu cầu "ai share thì người đó được nhận"):
  wiki-read giờ gate bởi `shareEnabled` — TẮT = không gửi, cũng không đọc chùa cache
  cộng đồng (bỏ qua wiki, đi thẳng LLM/NoCredit); BẬT = được đọc free + badge.
  Sub text `summary_share_sub` EN/VI đã ghi luật. VERIFY LIVE máy ảo BibotTest: share
  OFF → Khe Sanh ch (local đã xóa) Failed/NoCredit dù wiki có bản (không đọc chùa);
  share ON → wiki-hit "PART IV — THE SO-CALLED SIEGE BEGINS" + badge "From community
  cache (0 tokens)", turns giữ 3, DB row `chapterIdx=2, shared=1, fromShared=1`.
  (Wiki máy thật là idx khác do split epub khác nhau — hash khác, miss đúng.)
  Log chẩn đoán `BibotWiki` (lookup share/id, hit/len, error) giữ lại trong code.
  TRẠNG THÁI 2026-10-14: B5.8-minimal — dialog gắn nhãn "AI tóm tắt, có thể sai" +
  badge "Từ cache cộng đồng (0 token)" khi đọc wiki + tiến độ `Đã tóm tắt n/N` ở
  header TOC (đóng vai trò % wiki + nút "đóng góp" đúng nghĩa); promptVer tách key
  nên prompt mới không đè bản cũ. Tab Wiki riêng trong Detail + nút báo xấu + web
  public để sau (Detail còn mock). Rules Firestore (`wiki_summaries`: public read,
  auth write, size vài KB) ĐẶT TAY ở Firebase console — chưa làm (Firebase
  `FIREBASE_*` đang trống nên lớp share tự skip, app chạy local-only bình thường).
- [ ] B5.6 Verify (GẦN XONG 2026-10-14 đêm, máy ảo BibotTest, demoDebug 58.6MB rebuild có
  B6.17/B6.18): wiki-hit dialog "From community cache (0 tokens)" + nhãn "AI summary,
  may be wrong", không trừ lượt; book-level NoCredit upfront (Khe Sanh 7ch cần 8, có 3 —
  không đốt dở); Worker chạy thật + SUCCESS (test_mobi mobi 1ch: 3→1 lượt, 2 hàng local
  fresh + upload shared=1, giữ vị trí đọc, logcat sạch FATAL); NeedKey gate sau xóa key
  ("No AI key/offline", KHÔNG trừ lượt, Lĩnh Nam md VI); spot-check 3 sách (Khe Sanh
  epub 7ch / test_mobi 1ch / Lĩnh Nam 37ch • 2730 câu); toggle share ON thấy ở Settings;
  reward cloud-chapters +3 đã grant (6→9 — pipeline reward sống). Trước đó (đầu phiên):
  TOC icon + dialog NoCredit → Test Ad → retry → NeedKey, không crash, giữ vị trí.
  JVM `SummaryRepositoryTest` 3/3 + full suite 69/69 + ruler 64/64 + Go PASS + máy thật
  sạch FATAL. Còn nợ (cần nhập lại key + hết cooldown 30'): opt-out OFF→shared=0 /
   ON→shared=1 trên chương mới, reward +3 summary turns, C4 2 flavor, cap 500 chương (code
   đã có    `MAX_CHAPTERS=500`, còn nợ test sách dày).
  VERIFY 2026-10-15 (luật công bằng + opt-out live, máy ảo): xem LUẬT CÔNG BẰNG ở B5.7.
  Opt-out OFF→không đọc wiki (đúng luật mới) / ON→wiki-hit shared=1 trên chương mới —
  XONG live. Còn nợ: reward +3 summary turns live (nút Watch ad trước đó im lặng; đã
  sửa `AdManager.loadRewarded` DEBUG dùng TEST_REWARDED_ID tránh no-fill + thêm Toast
  `ads_cooldown`/`ads_loading` + log `BibotReward` ở `watchAdForSummary` — chưa verify
  lại live), C4 2 flavor, A2.5b font slider + sách dày.
  VERIFY 2026-10-14 (Khe Sanh epub, máy thật + máy ảo): máy thật tóm tắt Chương 1 OK
  → doc wiki `{hash}_gemini-3-5-flash-lite_v1` (chapterTitle/hits/lang/model/promptVer/
  text/updatedAt đủ 7 field); máy ảo đọc ké FREE không mất lượt + `hits` 1→2
  (`bumpWikiHits`). Chữ `lang` lạ trên console là Google dịch tự động, không phải bug.
  Lệch "máy ảo 1 chương cả sách" đã rõ: file epub trên máy ảo hỏng/khác bản (zip lỗi
  → spine rỗng → 1 chương) — xóa + tải lại đúng file là đủ Chương + tóm tắt OK.
- [x] B5.9 Đấu nối Firebase `bibot-wiki` (XONG 2026-10-14 — project user tạo mới):
  Auth ẩn danh Enabled; Firestore `asia-southeast1` production + Rules đã Publish
  (file `firestore.rules` mới ở repo root: `bibot/{uid}` auth-owner, `wiki_summaries`
  public-read/auth-write ≤8KB); 2 app Android `com.bibot.app` (Bibot) +
  `com.bibot.app.vip` (BibotV, nickname console tùy ý) — App ID
  `1:62701146933:android:d4ea976099f1701743727c`; `build.gradle.kts` đọc
  `bibot.firebase.projectId/appId/apiKey` từ `local.properties` (gitignore) → env CI
  → rỗng (=offline), key Android `AIza...SA1M`; key Browser dự phòng khi 403.
  Verify: `BuildConfig.java` demoDebug có đủ 3 giá trị; release song song
  `Bibot.apk` 33.7MB + `BibotV.apk` 34MB (`Bibot.aab` 22.2MB + `BibotV.aab` 22.5MB),
  `apksigner verify` cả 2 exit 0 (giữ version 1.0.2/code 3 — bản test, chưa bump);
  demoDebug cài máy ảo `install -r` Success, logcat sạch FATAL.
  Lệch build đã sửa: `java` trên PATH máy là Java 8 (HotSpot 25) → Gradle 8.7 vỡ;
  chốt máy bằng `~/.gradle/gradle.properties` (`org.gradle.java.home=H:/androidkit/jdk17`,
  ngoài repo) — `./gradlew --version` báo `JVM 17.0.17`.

### B6. Tối ưu vùng hiển thị + liền mạch đọc (review screenshot 5 màn, 2026-10-11) — [x]

> Làm ngay sau B3 (nhỏ, không đảo roadmap). Vấn đề thấy trên ảnh: double top-bar
> (app 52dp + reader), bottom nav + banner đè màn đọc, hàng brightness cố định,
> nút Lock lạ (code hiện tại KHÔNG còn — ảnh build cũ, không cần xóa),
> chunk ngắn + tin bị cắt 200 ký tự.

- [x] B6.1 Ẩn chrome app trong mode đọc: `BibotNavSuite` ẩn topBar/bottomBar/Rail khi
  `BOOK/STORY/COTICH/PDF` (back bằng nút reader) + **khôi phục `AdsBanner` ở mode đọc** (không ẩn banner, vẫn hiển thị banner ở đáy màn đọc) + **giữ `AdsRewarded` bonus cho Cloud TTS** + **xoá hoàn toàn phase-based interstitial ads** (xoá `PhaseTracker`, `phaseTracker.reset()`, `adBreakActive`, logic ngắt 8-10' + interstitial + bonus trước ad). Giữ `AdsRewarded` bonus cho Cloud TTS chapter (demo) + VIP bypass. Thêm nút back cho `DocBrowserScreen` (kẻo kẹt thư viện).
- [x] B6.15 Top_bar thống nhất (yêu cầu user 2026-10-13, verify máy ảo bằng screenshot):
  BỎ nút Home (bottom `NavigationBar` + `NavigationRail` tablet + header Home) —
  Back nằm góc trái Top_bar (mode → về Home; Home → thoát app như Back hệ thống);
  BỎ nút Chat/Mic + Settings ở header — thay bằng icon tương đương góc phải Top_bar
  (hiện cả ở Home). Kệ sách `ReaderScreen` (BOOK/STORY/COTICH) thêm hàng Back về Home
  (`onBackHome`) + system-back ở kệ → Home (trước đó kẹt mode đọc vì chỉ còn đường back
  về list). Mode đọc giữ full-screen (ẩn Top_bar app, back bằng nút reader).
  Verify máy ảo: reader top-bar bắt đầu y=168 sát status bar, hết nav; banner hiển thị ở đáy; không còn ngắt quảng cáo interstitial giữa chừng khi đọc; logcat sạch FATAL.
  B6.15b: kệ EN Books hiện 2 vạch loading khi kệ trống (đầu list `if (isLoading)` +
  cuối list `if (isLoading && books.isEmpty())`) → bỏ vạch cuối, giữ status text.
  Soát hết app: các màn khác mỗi loader riêng (story 1, Documents 3 loader khác nhau,
  News/Voice/Weather/Settings mỗi màn 1, ReaderChrome 1 vạch progress) — không trùng.
  Verify máy ảo (cold start → kệ đang tải): đúng 1 `ProgressBar`.
- [x] B6.16 Nâng cấp EPUB rich + zoom (yêu cầu user 2026-10-13, verify máy ảo):
  1. Zoom ảnh: bấm ảnh EPUB → dialog fullscreen + pinch (`detectTransformGestures`
     + `graphicsLayer`, 0 lib); PDF double-tap trang → cùng dialog (bitmap pool).
     Bắt được 2 bug khi verify: (a) key LazyColumn trùng crash `Key "rule:11"` →
     key theo index; (b) dialog PDF đen thui — bitmap `PdfRenderer` TRONG SUỐT
     (không nền giấy) nên trên nền đen tưởng mất ảnh → nền trắng sau ảnh là hiện
     giấy (list trước đó "thấy" nhờ watermark xuyên qua).
  2. Style prefs đầy đủ trong rich: `fontSize` + `interline` → lineHeight +
     `paragraphMargin` → cách khối (ngang TEXT view).
  3. Karaoke chính xác: truyền `sentences` + `currentSentence`, match có thứ tự
     từ khối câu trước (`findSentenceBlock`, có unit test) — vẫn mức khối/câu
     (engine TTS không trả timestamp từng từ).
  Verify: ảnh đầu máy + caption, zoom pinch dialog, highlight khối khi đọc,
  PDF zoom trang 1, JVM + ruler + Go xanh, logcat không FATAL mới.
- [x] B6.2 Gọn menu reader: brightness vào popup nút ⚙ (bỏ hàng cố định);
  popup gồm toggle Xem chữ/Xem trang + slider + Auto. Giữ 5 nút top-bar theo ReadEra.
  (Popup mới, không bấm thử từng control — slider/window-attr là API chuẩn; ghi nhận.)
- [x] B6.3 Điều hướng chạm: vuốt ngang đổi chương (dialog khi READ, thẳng khi dừng —
  verify: swipe trái ở cuối → "Already at the last chapter", swipe phải → Chương 14
  + hàng lịch sử `‹ Back to page 15 / Clear`); popup hint lần đầu mỗi máy
  (verify: hiện sau mở sách, `Đã hiểu` → prefs 24→50B, mở lại không hiện).
- [x] B6.4 Chunking sách 50–70 từ (quyết định user): `BookPrepStore` dùng
  `VietnameseChunker(maxWords=60, maxChars=300)` tại 2 điểm build `.sent`/fallback +
  `PREP_VERSION 2→3` (verify máy ảo: sách rebuild êm `77 sentences` thay vì 83,
  resume đúng Chương 15). Giữ default chunker (voice chat Go/mobile không đổi).
  Đánh đổi: ít lần synth + liền mạch, latency chunk và độ hạt karaoke giảm nhẹ.
- [x] B6.5 Tin tròn block: `NewsItem.description` full (bỏ `take(200)` — verify live:
  Tuổi Trẻ về 270 ký tự không cắt) + UI tap-to-expand (verify bounds 126→168px
  sau 1 tap); `mergeTts` hưởng ké. JVM test RSS bỏ (XmlPullParser không chạy trên
  JVM thường — ghi nhận), thay bằng verify máy ảo.
- [x] B6.6 Verify: uiautomator so vùng text (hết ~200dp chrome thừa), swipe + dialog
  (B3.3 đã cover khi READ), hint 1 lần, prep rebuild, tin full + expand,
  2 flavor BUILD SUCCESSFUL + JVM 18/18 (9 TextRules + 9 TtsSegments) + Go PASS,
  logcat sạch FATAL. APK demo 58.534.751B (~55,8MB) < 85MB.
  (Cập nhật 2026-10-12 sau H2.0: JVM 23/23 = 5 ReadingRules + 9 TextRules + 9 TtsSegments;
  APK demoDebug 58.881.939B, vẫn < 85MB.)
- [x] B6.7 Thanh chỉ báo mảnh 3dp sát dưới top-bar (hiển thị-only, scrubber đáy giữ
  nguyên điều khiển): verify screenshot máy ảo thấy vệt xanh full-width
  (uiautomator không expose node vẽ — check bằng ảnh). +0 text, không che nội dung.
- [x] B6.8 Dời cụm điều hướng chương lên top (dưới vệt xanh), đáy chỉ còn FAB —
  tránh hẳn vùng banner quảng cáo: verify screenshot (top: top-bar + vệt + ‹ scrubber
  ticks ›, đáy sạch). FAB hạ từ 190dp → 32dp.
- [x] B6.9 TEMP (test aid): nút Back hệ thống trong màn đọc → về list (ưu tiên đóng
  dialog/sheet trước). Verify máy ảo (`keyevent 4` từ reader → list Cổ tích, không
  crash). B2 sẽ đổi thành về Detail — nhớ gỡ TEMP khi đó.
- [x] B6.10 Gọt cụm chương còn 52dp (1 dòng text + vạch xanh 3dp chạm-nhảy + 2 mũi tên):
  bỏ slider/ticks/dòng engine/lịch sử UI (trùng nội dung, rộng). Verify screenshot
  (`Chapter 1/15` + vạch, đáy sạch). `chrome_chapter_of` mới (2 locale).
- [x] B6.12 Gọt tiếp theo review landscape (user): bar chương còn 44dp —
  1 dòng text + 2 mũi tên (bỏ luôn vạch scrub riêng, chỉ còn 1 vạch top);
  landscape gom top + chương vào 1 hàng 48dp (`ReaderCompactBar`).
  Verify screenshot portrait + máy ảo (không FATAL).
- [x] B6.13 Nút Search tích hợp tìm thật trong chương (trước B4.2): thanh kết quả +
  ↑ ↓ + đếm `n/m`, highlight câu trúng + tự cuộn (verify máy ảo: `trong` → 1/1).
  B4.2 mở rộng toàn sách + SearchWindow sau. Nút Contents đã link TOC sheet
  (verify từ B3, dùng lại).
- [x] B6.14 Xoá phase-based interstitial ad system (code 2026-10-12): xoá `ReadingPhaseTracker` class
  (xóa file `PhaseTracker.kt`), xoá `phaseTracker` instance + `phaseTracker.reset()` calls,
  xoá `adBreakActive` state, xoá logic ngắt 8-10' + `phaseTracker.shouldBreakAtBoundary()` +
  `showInterstitial()`/`loadInterstitial()` trong reader + bonus trước ad +
  `adBreakActive`/`adPreloaded`/`lastAdBreakMs`/`lastAdBreakChap` state (+ `bonusChapter`
  trong `AdCopy`, giờ không caller). Giữ `AdsRewarded` bonus cho Cloud TTS chapter (demo) +
  VIP bypass. Giữ `AdsInterstitialHelper` cho các màn khác (Home, Settings, etc.).
  Verify code+build: grep 0 còn reference phase trong reader; `compileDemoDebugKotlin`
  + `assembleDemoDebug` SUCCESS (APK 58.881.939B). Cài máy ảo BibotTest thành công
  (`install -r`, `com.bibot.app` v1.0.2). Còn nợ: walkthrough UI trên máy ảo cho đúng
  bản này (đọc liền mạch không ngắt + rewarded Cloud TTS + interstitial màn khác).
- [x] B6.11 Hint 1 lần: bấm ngoài dialog cũng lưu cờ (trước đây chạm trượt → nag-loop).
  Đính chính: `backup_rules` LOẠI TRỪ HẾT (`datastore/` + `bibot.db` + `bibot/`) —
  gỡ app là sạch thật, không còn gì để khôi phục. Ghi nhận cũ "backup khôi phục prefs"
  là sai (lần đó do bấm trượt nút). Không đổi `backup_rules` (vướng VIP grace).
  Hack time +40d (`date 112012002025`, `auto_time 0` rồi khôi phục): Drive chết
  (SSL, dự kiến) nhưng sách local vẫn liệt kê/đọc/neo/karaoke bình thường, FAB →
  confirm → READ + persist anchor, không FATAL EXCEPTION nào của app (1 SIGSEGV là
  WebView GPU process hệ thống). Trên máy ảo không có VIP cục bộ
  (`bibot_ads.preferences_pb` chưa từng tồn tại) nên không trigger được UX hết hạn —
   phần đó thuộc verify relay E2E của phiên kia.
- [x] B6.18 Lề hẹp + căn đều kiểu ReadEra (yêu cầu user 2026-10-14, theo ảnh mẫu
  ReadEra nền giấy — VERIFY MÁY 2026-10-14 đêm, demoDebug 58.6MB):
  TEXT + EPUB rich lề ngang `16dp→10dp` (chữ sát mép 2 bên, thấy rõ ở Lĩnh Nam/Khe Sanh/
  How it Works); PAGE rich (`How it Works` Ch.2): đậm/nghiêng/căn giữa giữ đúng gốc,
  `April 10, 2009 [#28553]` hiện nguyên bản, đoạn body b metals đều 2 biên (Justify);
  PDF (`grimm10` Page 1/301): render đủ trang ngay, khung hẹp + viền tờ giấy mảnh,
  tách khỏi nền trống đồng. FAB B6.17 + resume chip + banner cùng hiện, không che chữ,
  logcat sạch FATAL. Giữ `MaterialTheme` (nền giấy sepia để ở E2.4).
- [x] B6.17 Cụm FAB một tay ở màn đọc (yêu cầu user 2026-10-14, code xong — còn nợ verify máy):
  thêm nút Back nằm trên nút Play (`ReaderScreen.kt:1571-1597`, `Column` +
  `SmallFloatingActionButton` Back + `FloatingActionButton` Play/Pause, cùng kiểu FAB
  tròn); Back = `stopAll()` + về kệ (`selectedBook = null`) hoặc Home (`onBackHome`)
  khi đang ở kệ — trùng hành vi nút Back top-bar + Back hệ thống; nâng cụm lên giữa
  màn hình phải (Play cách đáy ~30% `screenHeightDp`, Back ~40% nhờ FAB + gap 12dp —
  tự co theo phone/tablet, dọc/ngang); nền trong mờ alpha 0.55
  (`surfaceVariant` cho Back, `primary` cho Play — đồng bộ `FloatingControlDefaults.BackgroundAlpha`)
   để chữ trôi bên dưới vẫn đọc được khi cuộn. VERIFY MÁY 2026-10-14 đêm (demoDebug rebuild
   58.6MB): cụm Back+Play hiện giữa-phải màn đọc, nền trong mờ thấy chữ bên dưới, Back về
   kệ/Home đúng, Play/Pause đọc tiếp ngay; không che chữ khi cuộn (Khe Sanh/Lĩnh Nam/
   test_mobi + grimm10 PDF). BỔ SUNG 2026-10-15 (yêu cầu user, VERIFY MÁY cùng ngày):
   2 nút CÙNG UI (2 `FloatingActionButton` 56dp, cùng nền primary alpha 0.55 — bỏ
   `SmallFloatingActionButton` lệch cỡ/màu cũ), gap `12dp→170dp` (≈2.7cm: 160dp = 1 inch)
   cho không gian 1 tay linh hoạt; Lĩnh Nam Ch.3/37 hiện 2 nút đồng nhất cách nhau ~450px.
   BỔ SUNG 2026-10-15 (yêu cầu user "kéo thấp xuống", VERIFY uiautomator cùng ngày):
   bỏ Column chung → 2 FAB ĐỊNH VỊ ĐỘC LẬP: Back `align(CenterEnd)` = giữa màn hình,
   Play `align(BottomEnd)` đáy `screenHeight*0.20−28dp` = giữa khoảng dưới; đo thực tế
   Back [919,1104][1044,1229] tâm 48.6%, Play [919,1691][1044,1816] tâm 73%, cách nhau
   ~462px (≈2.8cm), chữ đọc xuyên nền mờ (English Intermediate Ch.1/5).
- [x] B6.19 Tùy chọn nền màn đọc (yêu cầu user 2026-10-14 — CODE + VERIFY MÁY 2026-10-17,
  demoDebug 58,7MB): mặc định giữ nền Trống đồng Đông Sơn (`TrongDongWatermark.kt`);
  thêm option nền đơn sắc — màu LẤY TỪ palette watermark (trung bình pixel
  `bg_trong_dong.png` = `#B38541`, blend đúng alpha watermark 0.45 light / 0.38 dark lên
  `surface` → ra đúng tông nền đang thấy, KHÔNG chọn màu mới); setting DataStore
  `reader_background` = `drum`/`solid` (`PrefKeys.READER_BACKGROUND` + `setReaderBackground`
  trong `BibotPreferences.kt`); chọn ở popup ⚙ màn đọc — `ReaderSettingsPopup` thêm 2 mục
  tick ✓ (`chrome_background_drum/_solid`, EN+VI).
  File chạm: MỚI `feature/reader/ReaderBackground.kt` (`readerSolidBackground()` +
  `ReaderBackgroundLayer` vẽ ở ROOT `BibotNavSuite` TRƯỚC Scaffold trong suốt → phủ luôn
  statusBar/navBar, gate `inReader` nên các màn khác vẫn trống đồng); `BibotNavSuite.kt`
  (đọc `BibotPrefs` + gọi layer), `ReaderChrome.kt`, `ReaderScreen.kt`, strings 2 locale.
  Áp dụng: TEXT + EPUB rich + PDF khung cùng hưởng (PDF giữ viền tờ giấy B6.18); kệ sách
  trong mode đọc cũng hưởng (cùng layer).
  KÈM SỬA: nút ⚙ portrait hết `enabled = pageMode` — sách MD/TXT nay đổi được
  brightness + nền (trước bị khóa; chuyển Xem chữ/trang vẫn tự disabled trong popup).
  Verify máy ảo BibotTest: popup hiện `Background: Drum pattern` (✓) / `Background: Solid
  color` → bấm Solid → nền tan đặc phủ full màn kể cả 2 dải system bar, chữ vẫn đọc rõ;
  `force-stop` + mở lại → VẪN Solid (persist qua DataStore), `Chapter 1/41` giữ nguyên;
  bấm Drum → nền trống đồng hiện lại ngay; logcat sạch FATAL. Verify khác: JVM 69/69,
  ruler 64/64, `go vet` 0 + `go test` PASS, `assembleDemoDebug` + `assembleCommercialDebug`
  SUCCESS (C4 2 flavor).

---

## PHẦN C — Chất lượng chung (áp cho mọi giai đoạn)

- [ ] C1 Nút phone/tablet ≥48dp cho hành động chính (`BigButton 64/72dp` mở rộng khỏi Voice); chữ phụ ≥12sp; emoji có `contentDescription` cho TalkBack.
- [ ] C2 Mọi màn có 4 trạng thái: loading / empty / error + Retry / content. Không ghi đè 1 chuỗi `status?`.
- [ ] C3 Không `readText()` toàn file; mọi list dùng `LazyColumn`; tab chương dài có search.
- [ ] C4 Mỗi giai đoạn xong: `go vet` 0 + `assembleDemoDebug + assembleCommercialDebug` pass + test máy ảo (BibotTest) với sách 1.8MB/2545 trang.
- [ ] C5 Thứ tự làm (đã chèn R0 + E1.1 + B5 + B6, xem Phần F):
  `R0 → A1 → B1 → B3+E1.1 → B6 → B2 → A2(+Shell_Manager core ở A2.4) → B4 → B5 → A3`.
  Mỗi bước build được APK, không phá voice chat. B6 là polish từ review screenshot,
  làm ngay sau B3 (nhỏ, không đảo roadmap).

---

## PHẦN D — Nguyên tắc đọc sách (đã triển khai 2026-10-11)

Thứ tự đọc chuẩn: **tựa sách/tác giả** (đọc từ metadata — UI/Detail, không đọc trong nội dung)
→ **bỏ** mục lục/header/footer/số trang/phần mở đầu liệt kê số
→ đọc **Lời giới thiệu/Lời dẫn** → bắt đầu **Phần 1/Chương 1/Tập 1**
→ các phần tiếp theo → hết sách.

### D1. Skip + mốc bắt đầu (`BlockTextReader.kt`) — [x]

- [x] D1.1 Bỏ dòng số trang đứng riêng mọi lúc/mọi mode: `12`, `- 12 -`, `Trang 12`, `[12]`
  (`isPageNumberLine` + `dropPageNumberLines` trong `cleanBlock` — chặn cả PDF dồn trang).
- [x] D1.2 Bỏ dòng mục lục chấm lãnh đạo: `Chương 1 .... 5` (`tocLeaderRegex`).
- [x] D1.3 Buffer phần mở đầu (tối đa 64KB), gặp mốc thì bỏ hết, đọc từ mốc
  (`pendingFront` + `discardFront`). Không thấy mốc → đọc từ đầu như cũ (không nuốt sách).
- [x] D1.4 Mốc = lời giới thiệu/dẫn/tựa/mở đầu/Prologue **hoặc** chương 1
  (`Chương 1`, `Phần I`, `Tập 1`, `Truyện thứ nhất` — `isReadingStartMarker`).
  Không áp cho mode PARAGRAPH (không heading chuẩn → chỉ bỏ dòng số trang).
- [x] D1.5 EPUB: bỏ spine trống có tiêu đề bìa/mục lục/bản quyền
  (`epubSkippableTitleRegex`; spine nav/`linear=no` vốn đã bỏ).
- [x] D1.6 Đánh đổi đã ghi nhận: dòng số đứng riêng trong NỘI DUNG (thơ, `(1984)`)
  cũng bị bỏ; sách không mốc chuẩn thì phần đầu vẫn đọc như cũ.
- [x] D1.7 Verify máy thật (XONG 2026-10-14, Xiaomi + offline voice): sách `dverify.md`
  (bìa `12`/`- 7 -`/`Trang 3` + mục lục chấm + NXB) → chunk đầu là Lời giới thiệu,
  không đọc số trang (log BibotDbg + tai user).

### D2. Ngày tháng (`TextCleaner.readDates` + Go `readDatesGo`) — [x]

- [x] D2.1 `31/12` → `ngày ba mươi mốt tháng mười hai`;
  `31-12-2026`/`31/12/2026` → thêm `năm hai nghìn hai mươi sáu`;
  ngày 1–10 có `mùng` (`ngày mùng năm`). Có sẵn `ngày` thì không lặp.
- [x] D2.2 Quy ước phân biệt: **không-năm chỉ nhận `/`** (`31/12` = ngày);
  **`-` không năm là khoảng** (`100-120` → `đến`, giữ hành vi cũ).
- [x] D2.3 Validate ngày 1–31/tháng 1–12/năm 1000–2999 (2 số → 2000+)
  nên thoát: `1.440`, `3,5`, `12:30`, `2026-05-10` (ISO giữ nguyên).
- [x] D2.4 Go giữ chữ số cho engine (`ngày 31 tháng 12 năm 2026`); Kotlin đọc chữ.
- [x] D2.5 Đánh đổi đã ghi nhận: phân số `3/5` → đọc thành ngày (trước đây `3 5` cũng sai);
  ISBN/front-matter nhiều số được D1.3 bỏ trước nên không tới đây.
- [x] D2.6 Verify máy thật (XONG 2026-10-14): `31/12`→*ngày ba mươi mốt tháng mười
  hai*, `31-12-2026`→*…năm hai nghìn hai mươi sáu*, `1.440 đồng`→*1440 đồng*
  (thoát đúng), `12:30` giữ (log + tai).

### D3. Nhiệt độ (`normalizeDegrees` 2 bên) + vá chữ chẻ — [x]

- [x] D3.1 `25°C`/`25-30°C` → `25 độ C`/`25-30 độ C` (rồi `dashBetween` → `25 đến 30 độ C`);
  `30độ` dính → `30 độ`. Chạy trước `dashBetween`, sau `readDates`.
- [x] D3.2 BUG PHÁT HIỆN KHI TEST: quy tắc vá chữ chẻ `M èo→Mèo` ăn luôn
  `độ C hôm nay` → `độ Chôm nay` (lỗi cũ, cả 2 bên). Fix: giữ nguyên token
  chữ đơn `A-F/K/O/X/Y` (`vitamin C`, `tia X quang`, `điểm B`) — `SOLO_TOKEN_LETTERS`.
- [x] D3.3 Verify máy thật (XONG 2026-10-14): `25-30 độ C`→*25 đến 30 độ C*,
  `25°C`→*25 độ C*, `vitamin C` giữ, `M èo`→*Mèo* (log + tai).

### D4. Số La Mã (`readRomanNumerals` + Go `readRomanGo`) — [x]

- [x] D4.1 Thêm từ khóa `phụ lục`: `Phụ lục VII` → `phụ lục bảy`.
  Có sẵn: `Chương X` → 10, `thế kỷ XX` → 20, khoảng `XI-XIX`.
  (Vá H2.0: đuôi single `([^A-Za-z0-9À-ɏḀ-ỹ]|$)` chặn tái-match "m" trong output
  của range — xem chi tiết ở H2.0.)
- [x] D4.2 Phi chuẩn sách cũ `Phần IIX` → `phần tám` (map `IIX→8`; sai khác như
  `IIII` vẫn giữ nguyên). Go giữ chữ số (`Phần 8`).
- [x] D4.3 Chỉ sau từ khóa nên `Louis XIV`, `I` đứng riêng giữ nguyên.
- [x] D4.4 Verify máy thật (XONG 2026-10-14): `Phụ lục VII`→*bảy*, `thế kỷ XX`→
  *hai mươi*, `Phần IIX`→*tám*, `Fig. 5`→*hình 5*, `No. 12`→*số 12*,
  `thứ XVIII` giữ (đúng D4.3); nit duy nhất "bảy dính tóm" là prosody engine,
  text đúng (log + tai user).

### D5. Verify code (đã chạy)

- [x] D5.1 `go vet ./internal/... ./cmd/...` 0 + `go build` pass +
  `go test ./internal/tts/` 4/4 PASS (`internal/tts/tts_test.go` mới).
- [x] D5.2 Pattern check cho regex Kotlin (mirror Python).
  (Cập nhật 2026-10-12: kotlinc/gradle build đã chạy local bình thường;
  thay bằng `compile_rules.py` 61/61 + JVM `ReadingRulesTest` 5/5 đọc JSON thật.)
- [x] D5.3 Lưu ý build: `go build ./...` từ gốc hiện fail ở `OpenReadEra/libwebp/swig`
  (code clone third-party, có từ trước — build theo package `internal/... cmd/...`).
- [x] D5.4 Chạy `./gradlew :app:assembleDemoDebug` khi có máy build (Kotlin chưa biên dịch local).
  Xong 2026-10-11 (R0): `assembleDemoDebug` + `assembleCommercialDebug` đều
  BUILD SUCCESSFUL (chỉ warning deprecation có sẵn); `go vet` 0 + `go test ./internal/tts/` PASS.

### D6. Gom chunk trong ngoặc/trích dẫn + đọc Fig./No. (2026-10-11) — [x]

- [x] D6.1 Chunker không ngắt giữa `(...)/[...]/ {...}/"..."`: `insideBracketsOrQuote`
  (depth + straight-quote toggle + `“ ”`) giữ chunk cho đủ đoạn; ép yield khi quá dài
  (vượt 8 từ / gấp đôi ký tự) để không kẹt. Cả `VietnameseChunker.kt` + Go `chunker.go`.
- [x] D6.2 Viết tắt trích dẫn cuối chunk không phải hết câu (`endsWithCiteAbbrev`):
  `fig/no/st/mr/mrs/ms/dr/prof/vs/etc/ie/eg/am/pm/tr/tp/nxb/p/pp/ch/sec/vol/ed` —
  chặn chẻ `(Fig.` | `5)`. Cả 2 phía.
- [x] D6.3 Đọc chữ theo ngôn ngữ (`readFigureRefs`, bước 3d sau La Mã, trước
  abbrevDot/numDot): `Fig. 5` → `hình 5` (VI) / `figure 5` (EN);
  `No. 12` → `số 12`/`number 12`. Dấu chấm BẮT BUỘC (không ăn `no 5` tiếng Anh).
  `cleanForTts` thêm param `lang="vi"` (tương thích caller cũ);
  `ReaderScreen` truyền `readLang`; Go `Synthesize` suy lang từ prefix voice.
- [x] D6.4 Verify: Go `TestEndsWithCiteAbbrev/InsideBracketsOrQuote/FeedKeepsParenthetical/
  ReadFigureRefsGo` PASS; JVM `TextRulesTest` (chunk whole/quote/abbrev/fig vi-en) 9/9
  cùng `TtsSegmentsTest` 9/9 PASS; máy ảo đọc Ngưu Lang có `(1989...)`/`mùng bảy`
  tự nhiên (spot-check dòng `(âm lịch)` không bị chẻ).
  (Cập nhật 2026-10-12: TextRules 8/8 cũ giờ là 9/9 sau thêm `bookChunking_60words`.)

### D7. Đề mục dòng ngắn không keyword (2026-10-11) — [x]

- [x] D7.1 `detectStoryHeading` thêm fallback dòng ngắn: ≤60 ký tự, ≤10 từ, không kết
  dấu câu (`.!?…:;,)]}'"”’`), mở đầu hoa/số, dòng sau tồn tại + mở đầu hoa/số
  (kế thừa guard P6 chống wrap), loại số trang/`mục lục`/chấm lãnh đạo TOC.
  Áp dụng SAU khi đã vào sách (đề mục phần đầu chưa mốc vẫn buffer theo D1 —
  `Tác giả/NXB` không thành chương). Thơ đối thoại ngắn hoa-đầu có thể tách nhầm
  (ghi nhận): hậu quả nhẹ (1 mục TOC + ngắt 450ms), `MAX_CHAPTERS` chặn.
- [x] D7.2 Verify: JVM `TextRulesTest` — `Tiểu sử tác giả` tách chương sau `Chương 1`;
  âm tính (wrap thường/số trang/mục lục/kết hai chấm) giữ 1 chương; `Chương 1`
  keyword không regression. Kotlin-only (Go không có heading logic — ghi nhận).

---

## PHẦN E — Logic render ReadEra trên máy ảo (đã đo 2026-10-11) → học cho Bibot

Máy ảo `BibotTest`, ReadEra `26.05.20+2300`, file test tự sinh:
`text_sample.pdf` (text + ảnh bìa nhúng + TOC, 62kB),
`scan_sample.pdf` (6 trang ảnh 150dpi, 853kB),
`big_sample.pdf` (1200 trang text TV có dấu, 2.2MB), `war_worlds.epub` (1.8MB).

### E0. Bằng chứng đã xác minh (screenshot + dumpsys + mổ APK)

- [x] E0.1 Library hiện thumbnail trang 1 cho MỌI loại (PDF raster / EPUB cover) + đếm
  theo format (`PDF: 3`, `EPUB: 1`) + banner `New documents: 3 / SHOW`.
- [x] E0.2 PDF text render như sách thật: ảnh bìa nhúng full màu + TOC chấm lãnh đạo +
  chữ Việt có dấu chuẩn (font nhúng), cuộn dọc liên tục qua ranh giới trang.
- [x] E0.3 PDF scan render ảnh trang phủ sepia, cuộn liên tục trang 1→2; RAM TOTAL ~103MB
  cho file 853kB (bitmap là phần nặng, không phải text).
- [x] E0.4 File 1200 trang: mở vài giây, `1 of 1200` hiện ngay (đếm từ trailer, không
  parse hết); RAM TOTAL ~114–122MB — không tăng theo số trang.
- [x] E0.5 About Document: preview full trang 1 + bookmark % + giờ đọc +
  **file identifier SHA-1** + đường dẫn gốc (`File #1 .../Download/x.pdf`, KHÔNG copy
  file vào app) + `Add a review`.
- [x] E0.6 Mổ APK: `assets/erapdf|eraepub|eradjvu|eramobi|eracomic/` +
  `lib/x86_64/libera{pdf,epub,djvu,mobi,comic}.so` — khớp 1-1 source `OpenReadEra/`
  (StBridge, lazy `getPage+free`, Hitbox). External data chỉ 32K → render in-memory,
  không cache đĩa.

### E1. Bài học port sang Bibot — [x] E1.1/E1.2/E1.3/E1.5 nhịp B3 (E1.4 khung, E1.6 một phần)

- [x] E1.1 `PdfPageView` (`feature/reader/`, VIEW thuần, không logic TTS):
  `PdfRenderer` framework (+0 lib) + pool 3 bitmap + prefetch ±1 TUẦN TỰ
  (từng race prefetch song song — PdfRenderer không thread-safe, đã sửa) +
  `onVisiblePage/onPageCount`. Dùng cho PDF local/Tham khảo/EN-PDF.
- [x] E1.2 `pageCount` có ngay sau open (không inflate), đáy hiện `Trang x/N` ngay.
  Thumb library đã có từ B1 (`LibraryCovers`, render 72dpi tương đương).
- [x] E1.3 SHA-1 streaming file (`sha1File`, cap 64MB) lưu vào `BookProgress.fileHash`
  (sqlite chứng minh 40 hex); neo/anchor không phụ thuộc path. Re-key progress theo
  hash để BỎ path dời sang A2/B4 (ghi nhận).
- [x] E1.4 Khung xong: scan → ép PAGE + khóa TEXT + disable loa + báo `reader_pdf_scanned`.
  Chưa thấy máy thật (không có PDF scan nào offline để thử — ghi nhận verify ở B4).
- [x] E1.5 Lệch so với bản gốc: PdfRenderer framework BẮT BUỘC ARGB_8888 (RGB_565 ném
  exception — đã thử và sửa cả `PdfPageView` lẫn `LibraryCovers`); bù bằng pool 3
  trang + recycle khi evict. Không thêm `inBitmap` (API render tự quản).
- [ ] E1.6 Verify máy ảo: `big_sample.pdf`/`scan_sample.pdf` là file của phiên cũ,
  máy hiện tại offline + không còn file đó — thay bằng PDF 3 trang tự sinh
  (`mkpdf.py`): render đủ 3 trang, `Trang 1/3` ngay, long-press neo đúng tỉ lệ.
  Còn nợ: file 1200 trang + RAM TOTAL + scan thật (ghi nhận sang B4 khi có mạng).

### E2. Yêu cầu VIEW nguyên bản đạt chuẩn ReadEra/OpenReader (audit 2026-10-14)

> Kết luận audit: mới đạt ~60% phần XEM. `OpenReadEra/` (`erapdf/eraepub/orecrop...`)
> chỉ nằm trong repo, CHƯA wire (`app/build.gradle.kts` không có `externalNativeBuild`;
> NDK chỉ cho sherpa/vosk/onnx). MuPDF-GPL loại có chủ ý (`ReadAnchor.kt:14`).
> PDF hiện render ảnh (`PdfPageView` + `PdfRenderer`) nhưng không text-layer theo trang
> → TTS PAGE phải map tỉ lệ ước lượng (`ReadAnchor.kt:10-13`, `ReaderScreen.kt:679-692`).
> EPUB rich mới subset tags, chưa CSS/table/footnote, chưa phân trang thật.

- [ ] E2.1 PDF — text-layer theo trang (hết map tỉ lệ):
  `BlockTextReader.iterPdfText` flow-based → trích text kèm `pageIdx`
  (tự làm per-page hoặc wire `erapdf` qua JNI/FIFO khi cho phép GPL);
  `ReadAnchor PAGE {page,offset từ}` chính xác, bỏ chữ "khoảng X%".
  File chạm: `core/text/BlockTextReader.kt`, `feature/reader/ReadAnchor.kt`,
  `feature/reader/ReaderScreen.kt` (`enterRead/startPlayFlow`).
- [ ] E2.2 PDF — cho đọc TEXT + karaoke với PDF chữ:
  hiện `ReaderScreen.kt:330` ép `isPdf → PAGE`; ReadEra cho cả 2.
  Thêm toggle thật TEXT (câu/karaoke/search) ↔ PAGE (ảnh) cho PDF text-readable;
  chỉ khóa PAGE khi `isScannedPdf`.
- [ ] E2.3 PDF — select/copy/search/TOC chuẩn ReadEra:
  text selection + copy/dictionary trên PAGE, search highlight trong PAGE
  (hiện search chỉ trên `sentences TEXT`), TOC từ outline PDF
  (hiện PDF = 1 chương duy nhất), reflow-text option, crop margin
  (port `orecrop/smartcrop.cpp IsRectWhite 1%` — chung với A3.4).
- [ ] E2.4 PDF — modes hiển thị: fit-width/fit-page, single/continuous, dual-page,
  rotation, night/sepia recolor (hiện chỉ brightness `window.screenBrightness`),
  annotation/highlight persist theo `fileHash` (E1.3).
- [ ] E2.5 EPUB — full rich + phân trang thật:
  mở rộng `EpubReader.richParse` (CSS, font-family/màu, `table/ul-ol/footnote/dropcap`),
  giữ `readImageBytes` sampled; phân trang theo font (paginated, tính % trang chuẩn)
  thay `LazyColumn` continuous; search highlight trong rich (hiện chỉ TEXT prep);
  EPUB scan có OCR-option hoặc giữ khóa PAGE + báo rõ.
- [ ] E2.6 Verify E2: PDF 1200 trang mở vài giây + `x of N` ngay + RAM không tăng theo
  trang (đo `dumpsys meminfo` như E0.4); PDF scan sepia cuộn liên tục; EPUB 1.8MB
  ảnh/caption/bold chuẩn gốc; TTS từ PAGE neo đúng câu (không lệch %); C4 2 flavor PASS.

---

## PHẦN F — Vị trí E1.1 + kiến trúc reuse cho menu Đọc Sách (gọn, không nặng code)

Đo thật: `StoryScreen` 784 dòng vs `BookScreen` 768 dòng — **380 dòng trùng nguyên văn
(≈48%)**, còn lại song sinh (producer `Channel(2)`, `jumpSeq`, pause, prepSession…);
`BookScreen` còn import `findNextHeading` từ `feature.story` (rối chéo).
Menu hiện tại: BOOK (EN) → `BookScreen`; STORY (Kể chuyện/Sử Việt) + COTICH (Cổ tích)
→ `StoryScreen(collection=...)`; **Tham khảo chưa tồn tại** (sẽ là collection mới, 0 màn mới).
ReadEra thành phẩm ~50MB (base 11.7MB + split abi); Bibot 83MB — E1.1 không được thêm
lib nặng nào (`PdfRenderer` là framework = 0 byte; thumb dùng Coil đã có trong catalog
libs hoặc `PdfRenderer` render thu nhỏ, không thêm Glide/MuPDF/Readium AAR).

### F0. R0 — Gộp màn đọc trước mọi thứ (điều kiện để E1.1 không phải wire 3 lần)

- [x] F0.1 Mới `feature/reader/ReaderScreen(book: BookMeta, collection: ReaderCollection)`
  (tổng quát hóa từ `BookScreen`, giữ nguyên luồng producer `Channel(2)` + PrepSession;
  heading chuyển về `feature/reader/ReaderHeadings.kt` — xóa import chéo `feature.story`).
  Xong 2026-10-11: sinh bằng script `gen_reader.py` (44 điểm thay thế đều khớp count +
  check leftover). (Đính chính 2026-10-12: "phase ads" trong luồng đã XÓA ở B6.14;
  state vẫn `remember` trong composable — hoist sang `ReaderViewModel` hẹn ở B3/G1.2
  CHƯA làm, không tồn tại file `ReaderViewModel.kt`.)
- [x] F0.2 Mới `enum ReaderCollection { EN, STORY_VI, COTICH, THAMKHAO, LOCAL_PDF }`:
  mỗi loại chỉ là config `{headerEmoji, fixedLang, adsEnabled, allowManage,
  useStoryStrings, fallbackTitle, audioPrefix}` + `filter()` giữ đúng hành vi cũ
  (EN: ẩn `vi+pdrive`; STORY_VI: `storyOk`; COTICH: `collection==cotich`).
  Tham khảo = thêm `ThamkhaoBooksCatalog.FOLDER_ID` (đang trống → `listAll` bỏ qua,
  0 màn mới); `PublicDriveSource` map folder → `collection=thamkhao`.
- [x] F0.3 Xóa `BookScreen.kt` (768 dòng) + `StoryScreen.kt` (784 dòng), `BibotNavSuite`
  trỏ 3 mode về `ReaderScreen` (BOOK→EN, STORY→STORY_VI, COTICH→COTICH).
  Verify máy ảo BibotTest 2026-10-11 (uiautomator): EN hiện `📚 EN Books` + 8 sách +
  nút Refresh/Scan/URL; Storytelling 16 truyện 📖+Tell; Cổ tích 19 truyện 🧚+Tell;
  mở truyện đã tải → reader đúng (tab Đoạn, câu VI, `Loaded 15 chapters • 83 sentences
  • 0.88x`, resume `Chapter 14/15`, `VI • 0.88x`). Không crash (logcat sạch FATAL).
- [x] F0.4 Size check: R0 bớt ~1500 dòng code trùng. `Bibot.apk` demoDebug sau R0 =
  58.353.752 bytes (~55,7MB) — dưới budget 85MB (F1.4), +0 lib.

### F1. Vị trí E1.1 trong kế hoạch — [x] đúng thiết kế (1 lệch vị trí file)

- [x] F1.1 `ReaderScreen` 2 chế độ VIEW chung 1 chrome — `TEXT` + `PAGE` — toggle ở nút ⚙
  (chỉ bật với PDF; scan khóa PAGE). PDF mặc định PAGE; EPUB/MD/TXT mặc định TEXT;
  scan ép PAGE + disable loa. Đúng kế hoạch.
- [x] F1.2 `PdfPageView` VIEW thuần: `PdfRenderer` + pool 3 + prefetch ±1 + báo
  `onVisiblePage/onPageCount` — KHÔNG logic TTS (controller đọc text prep qua mapping).
  Lệch: file đặt ở `feature/reader/` (không phải `ui/reader/`) để gần `ReaderScreen`;
  chưa dùng cho thumb library/preview About (B1 `LibraryCovers` làm việc đó).
  Lệch quan trọng: PAGE→text KHÔNG qua `iterPdfText` theo trang được (trích text PDF
  là flow-based, không biên trang) → anchor PAGE map TỈ LỆ sang câu, UI ghi rõ
  "đọc từ khoảng X%"; map chính xác cần engine text theo trang (MuPDF — GPL, loại).
- [x] F1.3 Thứ tự giữ nguyên C5 (`B3+E1.1` xong, tiếp B2).
- [x] F1.4 Size: +0 lib. APK demo 59.070.876B (~56,3MB) < 85MB (tăng ~0,7MB so với R0
  do chrome+PAGE — dưới budget). RAM `big_sample` dời E1.6 (ghi nhận trên).

### F2. Touch_point — lướt xem trước, chạm để neo điểm đọc — [x] (F2.4 rút gọn trung thực)

- [x] F2.1 `ReaderScreen` 2 mode: **BROWSE** mặc định (render + câu/chương load sẵn để
  hiển thị, KHÔNG producer TTS — effect gate `readModeState != READ`); **READ** chỉ
  bật khi user neo điểm. Lệch nhỏ: TEXT vẫn load sentences để hiển thị (không thì
  trắng trang — hiển thị ≠ chunking/TTS).
- [x] F2.2 Chạm câu (BROWSE) → marker; long-press/FAB `▶ Đọc từ đây` → hộp xác nhận →
  `ReadAnchor`. TEXT `{chapterIdx, sentIdx}` chính xác; PAGE `{pageIndex}` + map tỉ lệ.
- [x] F2.3 `BookProgress` + `anchorPage/anchorOffset/readMode/fileHash` (Migration 3→4,
  ADD COLUMN có DEFAULT — giữ dữ liệu; sqlite chứng minh `1|0|read|9b6e…`).
  Bookmark/progress cũ giữ nguyên.
- [x] F2.4 Rút gọn trung thực: KHÔNG build window riêng quanh anchor (prep `.sent` đã
  là seek ngẫu nhiên rẻ — build window mới là trùng lắp); TTS chain đọc tiếp từ anchor
  qua `enterRead` + `jumpSeq` hủy item cũ. PAGE dùng prep 1-chương + map tỉ lệ.
- [x] F2.5 Đổi anchor khi đang READ → dialog B3.3 (`Đổi vị trí — đọc tiếp từ đây?`).
- [x] F2.6 Verify máy ảo: BROWSE vuốt không TTS (effect gate); long-press trang 2/3 →
  "reads from about 50%" → READ karaoke đúng; kill app (install -r giữ data) mở lại →
  resume chip + anchor cũ (`savedAnchor`); logcat sạch FATAL.

---

## PHẦN G — Crash xoay màn hình + chunking ngoặc + đọc đề mục (mới)

### G1. Crash xoay màn hình + hoist ViewModel (đính chính 2026-10-12)

- [x] G1.1 Nguyên nhân THẬT đã tìm ra ở B3.6 (giả thuyết race `prepSession`/`Channel(2)` ở
  đây là SAI): FATAL nằm trong `AdsBannerComposable` (`update` gọi `setAdSize` sau
  `loadAd`, AdMob cấm) — đã sửa bằng key `AndroidView` theo (widthDp, landscape).
  KHÔNG dùng `android:configChanges` (phá resources/locale).
- [ ] G1.2 Hoist state đọc (`prepSession`, `jumpSeq`, producer job, `currentChapter/Sentence`)
  vào `ReaderViewModel` + `SavedStateHandle` (CHƯA làm — không tồn tại file này; state vẫn
  `remember`, B3.6 đã đủ để hết crash nên hoist thành hardening, không còn là fix crash).
- [ ] G1.3 Verify máy ảo (ma trận xoay): xoay khi IDLE / đang READ TTS / đang BROWSE /
  giữa dialog đổi anchor → không FATAL, đọc tiếp đúng câu
  (logcat + `dumpsys meminfo` không leak ExoPlayer). (Bỏ case "khi QC interstitial" —
  interstitial trong reader đã xóa ở B6.14.)

### G2. Nguyên tắc gom chunk cho đoạn trong ngoặc (...)/{...}/[...]/"..." (XONG lõi ở D6 — đính chính 2026-10-12)

- [x] G2.1 Bug `DELIM` tách `(at Fig. 5)` ngay sau `Fig.` — ĐÃ FIX ở D6.1
  (`insideBracketsOrQuote` depth + quote-toggle + ép yield khi quá dài, cả 2 phía).
- [x] G2.2 Chuẩn hoá đọc — XONG 1 PHẦN ở D6.3 + H2.0 (`Fig.`→`hình`/`figure`,
  `No.`→`số`/`number` theo `fixedLang`, rule `fig.vi/en + no.vi/en` trong ruler).
  CÒN LẠI (chưa làm): `e.g.`→`ví dụ`, `i.e.`→`nghĩa là`, `vs.`→`so với`,
  `etc.`→`vân vân` (+EN); vỏ ngoặc ngoài → dấu phẩy; bỏ ngoặc rỗng `()`.
- [x] G2.3 Mirror Go — XONG ở D6 (`internal/chunker`: `insideBracketsOrQuote` +
  `endsWithCiteAbbrev`; `readFigureRefsGo` theo lang) + `go test` case mới.
  Params chunker đã khai báo ở rule `chunk.brackets` (H2.0); cắm `chunkGuard()` vào
  chunker còn ở H2.3.
- [ ] G2.4 Verify: `(from 1989 to 2011)`, `(see Fig. 5)`, `"trích dẫn dài..."` không bao giờ
  bị chẻ làm đôi (unit đã cover parenthetical/quote/Fig ở cả 2 phía); nghe VI/EN mỗi loại
  3 câu ngoặc (còn nợ verify tai nghe); `go test` + `compile_rules.py` pass.

### G3. Nhận diện đề mục + đọc có ngắt câu

- [x] G3.1 Quy tắc đề mục — XONG ở D7 (`detectStoryHeading` ≤120 ký tự + fallback dòng ngắn
  D7 `SHORT_HEADING_MAX_CHARS=60`, ≤10 từ, không dấu câu cuối, mở đầu HOA/số + guard
  chống wrap) + `mdHeading` (`##`) + `CHAPTER-BREAK title=` (`chapterBreakTitle`).
  EPUB `h1-h3`/spine title: spine đã có ở `EpubReader.chapters`, tách `h1-h3` trong spine
  chưa làm (ghi nhận).
- [ ] G3.2 Hành vi mới: producer phát **tiêu đề chương là chunk đầu tiên kèm pause**
  (`pauseAfterMs` = `BOOK_PAUSE_BETWEEN_MS`, hoặc marker `⏸` như pause 2s) rồi mới tới
  câu nội dung; karaoke highlight tab tương ứng. Hiện tại title chỉ hiện UI, TTS bỏ qua.
- [ ] G3.3 Verify máy ảo: mở sách bất kỳ → audio đầu tiên là tên chương + khoảng lặng
  rõ rệt trước câu đầu; chương `##`, `Chương X`, `Part One`, `h2` EPUB đều đọc.

---

## PHẦN H — Module "Reading Rules" mới: rulepack con người/AI cùng sửa được, runtime nhẹ, reuse mọi nơi

Vấn đề: quy tắc đọc (D1–D4, G2–G3) đang là regex cứng rải trong `TextCleaner.kt`,
`BlockTextReader.kt`, `tts.go` — muốn sửa 1 quy tắc phải đụng code 2 ngôn ngữ + sợ
regression. Giải pháp: **1 rulepack khai báo duy nhất, 2 dạng file** —
`reading-rules.ruler` (tác giả sửa, cú pháp YAML) + `reading-rules.json` (máy đọc,
sinh tự động lúc build). **Loại `.db`/SQLite**: nặng, cần driver, Go không có sẵn —
JSON thắng về nhẹ + đa nền tảng 0-dependency.

### H0. Đặc tả rulepack — [x] khóa + chạy được 2026-10-11

- [x] H0.1 Schema mỗi rule: `{id, kind, scope, lang, pattern(s), template?, params?,
  priority, tests[]}` (`validate` gộp vào hook tên: `day/month/year/num/roman`).
  `kind` = `rewrite` | `drop_line` | `start_marker` | `split_guard`.
  `priority` = thứ tự pipeline. File: `tools/rules/reading-rules.ruler`
  (v1 3 rule lúc khóa; nay v2 16 rules — xem H2.0).
- [x] H0.2 Test nhúng trong ruler (cả giữ nguyên) — `compile_rules.py` chạy toàn bộ,
  thay `regex_check.py` rời rạc. (v1: 19/19; v2: 61/61.)
- [x] H0.3 Giao Java∩RE2∩Python: script từ chối lookaround/atomic/possessive/backref/
  named (đã bắt bug thật khi dev). `version` int + `checksum` sha256 trong JSON
  (v1 1006 bytes; v2 ~4,4KB, trần 30KB). `split_guard` đã có rule mẫu `chunk.brackets`
  từ H2.0 (lệch cũ "chưa có rule mẫu" đã xong).

### H1. Scaffold + 3 rule pilot — [x] chạy được (H1.3 dời H2)

- [x] H1.1 `tools/rules/reading-rules.ruler` (v1 3 rule pilot, nay v2 16 rules) +
  `compile_rules.py` (YAML→JSON + tests + đo size) + `RULES_AUTHORING.md` (§4 quy trình
  publish từ H2.0). Lệch: script mirror 1 bản JSON sang `internal/rules/reading-rules.json`
  (do `go:embed` cấm `..`); bản assets copy tay theo §4.
- [x] H1.2 Loader Kotlin `core/text/ReadingRules.kt` (0 lib mới:
  `parse/load/apply/isDropLine/isStartMarker`, hook dùng `TextCleaner` sẵn có) +
  JSON ở `android/app/src/main/assets/reading-rules.json`.
  (Đính chính 2026-10-12: parser đã BỎ `org.json` → `MiniJson` thuần vì stub Android
  crash trên JVM thường; JSON v2 ~4,4KB.)
  Loader Go `internal/rules/` (`//go:embed` + stdlib, engine + hook + `viWords`
  port từ Kotlin) + `rules_test.go` 5 test PASS. Kotlin build local đã chạy bình thường.
  Ghi nhận bug đã sửa khi verify: Go `FindAllStringSubmatchIndex` trả -1 với group
  optional (đã fix + Kotlin an toàn sẵn); quirk `mười mốt` parity Kotlin (giữ theo H2.0,
  không phải "fix sang H2" nữa — test khóa hành vi ở cả 3 engine); Go `hook("",…)`
  trim mất đuôi (đã fix H2.0).
- [x] H1.3 1 entry reuse duy nhất (XONG 1 PHẦN ở H2.0, còn lại ở H2.1/H2.3): đọc sách đã gọi
  (`ReadingRules.init()` ở `BibotApp` + `applyIfReady` trong `cleanForTts` + vòng dòng
  `BlockTextReader`); voice chat / news / weather CHƯA gọi (giữ hàm cũ).

### H2. Migrate dần, mỗi bước xóa code cứng (D1–D4, G2–G3 → ruler)

- [x] H2.0 Rulepack v2 (2026-10-12): 16 rules / 61 tests nhúng PASS — thêm
  `date.full(9)`, `temp.celsius/fahrenheit/stuck(12-14)`, `roman.range(29)`,
  `fig.vi/en + no.vi/en(31-32)`, `drop.toc_leader(1)`, `start.intro + start.first_chapter(0)`,
  `chunk.brackets` params flat. JSON ~4.3KB (trần 30KB), 3 bản đồng bộ
  (tools/internal/assets). Wire Kotlin: `ReadingRules.init()` ở `BibotApp.onCreate` +
  `applyIfReady` trong `cleanForTts` (hàm cứng thành no-op/fallback, chưa xóa) +
  vòng dòng `BlockTextReader` thử ruler trước. GIỮ code có lý do ghi trong `note:`:
  `readPageNumbers` (cần lookahead, ngoài giao 3 engine), `dropPageNumberLines` vòng
  khối (chỉ bỏ số trang, TOC để vòng dòng). Test mới `ReadingRulesTest` đọc JSON thật
  (chống drift) + `TestApplyV2` Go. `PREP_VERSION` giữ 3 (pipeline clean chưa đổi
  output — ruler trùng hành vi cũ; đổi output mới bump).
  Bug thật lòi ra khi verify (vá đồng bộ 4 nơi): `roman.single` tái-match chữ "m"
  trong output của `roman.range` ("mười…" — `\b` ASCII ở Java/Go coi "m|ư" là biên,
  Python-unicode che mất nên test cũ vẫn xanh): đuôi single đổi `([^A-Za-z0-9À-ɏḀ-ỹ]|$)`
  + giữ `{3}` ở cả `.ruler`/Kotlin/Go; Go `hook("",…)` sửa trả nguyên văn (đừng trim
  mất dấu cách đuôi). Parser Kotlin bỏ `org.json` (stub crash trên JVM thường) →
  `MiniJson` thuần (~90 dòng, đủ schema sinh bởi script, 0 dep). Verify: compile
  61/61 + Go `-count=1` 3 package + JVM 23/23 (5 ReadingRules + 9 TextRules +
  9 TtsSegments) + `assembleDemoDebug` SUCCESS, APK 58.881.939B (+107B).
- [x] H2.1 Xóa hàm cứng dates/degrees/roman/figure (XONG 2026-10-14 — máy thật Xiaomi,
  D1.7/D2.6/D3.3/D4.4 đạt cả text lẫn tai, trừ 1 nit prosody engine "bảy dính tóm"):
  xóa `readDates/readDay/normalizeDegrees/readRomanNumerals/readFigureRefs` +
  regex trong `TextCleaner.kt` (caller duy nhất là `cleanForTts`, test chuyển sang
  integration qua pipeline); `romanToInt/numberToVietnameseWords/validRomanRegex` GIỮ
  (hook ruler dùng); `readPageNumbers` GIỮ (lookahead ngoài giao).
  Go KHÔNG xóa (ghi nhận trung thực: `readDatesGo/...` là live path của
  `CleanTTSText`, không có code chết — "tts_test.go trùng" không đúng kiến trúc Go).
  Test: `TextRulesTest.figureRefs` chuyển integration + `ReadingRules.initForTest/
  resetForTest` mới (nạp JSON thật trên JVM). Verify: JVM 66/66 + ruler 64/64 +
  Go vet 0 + test PASS + máy thật đọc lại `dverify.md` ra text Y HỆT 14/14 câu
  (log BibotDbg pid mới), sạch FATAL. Nit prosody "bảy+tóm dính" là engine, không code.
- [x] H2.2 `BlockTextReader` chỉ còn state machine buffer/discard (XONG audit 2026-10-14 —
  KHÔNG xóa fallback, H2.1 khóa đến khi có máy thật): vòng dòng/vòng khối/speak-time
  đều ruler-first (`isDroppableLine`→drop.page_number/toc_leader,
  `isReadingStartMarker`→start.intro/first_chapter, `cleanBlock`→`cleanForTts`→
  `applyIfReady` trước `readDates/Degrees/Roman/FigureRefs`); giữ code có lý do:
  `readPageNumbers` (lookahead ngoài giao), `dropPageNumberLines` vòng khối,
  `epubSkippableTitleRegex` (điều kiện kép blank+title gắn spine — extract hàm pure
  mới `isSkippableEpubTitle` + `BlockTextReaderTest` 2 test, thay 2 call-site
  EPUB/MOBI), heading/marker/chunker-guard cấu trúc. Verify: ruler 64/64 + Go
  vet 0 + test PASS + JVM 66/66 (64 cũ + 2 mới) + `assembleDemoDebug` SUCCESS.
  BỔ SUNG sau H2.1 (2026-10-14): fallback đã xóa thật nên H2.2 nay đúng theo chữ —
  `BlockTextReader` còn state machine buffer/discard + code cấu trúc
  (heading/marker/spine/PDF) + 2 hàm GIỮ có lý do (`readPageNumbers`,
  `dropPageNumberLines`); pattern đọc sách 100% qua rulepack.
- [x] H2.3 Chunker đọc params (XONG 2026-10-13): `VietnameseChunker` nhận
  `guard: ChunkGuard = ReadingRules.chunkGuard()` (snapshot lúc dựng; mọi `split()`
  dựng mới nên luôn tươi) — `insideBracketsOrQuote` đọc `bracketsOpen/Close/quotes`,
  overrun đọc `overrunWords/overrunCharsMult`, cite-check đọc `citeAbbrev` (rỗng →
  fallback `TextCleaner.endsWithCiteAbbrev` cũ; overload mới nhận tập rule).
  Chưa init → default = hành vi cứng cũ (parity khóa bởi `chunkGuard_matchesHardcode`
  + `VietnameseChunkerTest` 5 test mới). Full JVM 38/38.
  Đề mục đọc-pause (G3) giữ code (cần `pauseAfterMs` ở producer, chưa vào ruler).
- [ ] H2.4 Mỗi H2.x xong: `go test`, `compile_rules.py` (thay `regex_check`),
  APK không tăng quá 0.2MB (JSON nhúng v2 ~4.3KB).

### H3. Cập nhật bởi người/AI + OTA nhẹ

- [ ] H3.1 Quy trình: sửa `.ruler` → chạy `compile_rules.py` (sinh JSON + test xanh) →
  commit cả 2 file (JSON là artifact có version, review được diff). CI chặn pattern
  ngoài giao + test đỏ.
- [ ] H3.2 OTA tùy chọn: tải JSON mới về `filesDir` (verify checksum + `version` lớn
  hơn), fallback JSON nhúng khi lỗi — không cần update APK để sửa cách đọc.
- [ ] H3.3 Verify cuối: người không biết code thêm được 1 rule theo `RULES_AUTHORING.md`
  (VD: `e.g.`→`ví dụ`); AI đọc `.ruler` đề xuất rule mới từ log sách lỗi; máy ảo nghe
  đúng sau OTA mà không cài lại APK.

---

## PHẦN I — VIP verify server-side + grace offline (xong relay 2026-10-11)

Vấn đề: secret HMAC nướng trong APK (`BuildConfig.VIP_HMAC_SECRET`) — R8 không giấu
được string constants, lộ secret = tự đúc mã VIP. Obfuscation không cứu được.
Fix đúng: secret ở server, app chỉ gửi code (chi tiết `relay/VIP_VERIFY.md`).

### I1. Relay — [x] xong + verify thật

- [x] I1.1 `POST /v1/vip/verify` (`relay/main.go`): port 1-1 `VipCode.kt`+`vip_batch.py`
  (base32-custom/HMAC6/payload) + entitlement ký bind code↔máy + `serverTime` chống
  vặn đồng hồ + quota 10/ngày/máy + anti-share (`VIP_MAX_DEVICES=3`) + thu hồi batch
  (`VIP_REVOKED_BATCHES`). Toàn stdlib, `go vet` + `go test` 3/3 PASS (vector mã thật
  batch 9) + curl E2E qua HTTP thật (`valid:true`, entitlement có `sig`).
  E2E hết-hạn 2026-10-11 (máy ảo không có VIP local nên không trigger UX tại chỗ —
  verify ở relay thay): mã hết hạn → `valid:false "ma het han"`; batch 7 + env revoke
  → `valid:false "ma bi thu hoi"`; mã batch 9 → `valid:true tier=1`. App nhận invalid
  cho mã đang lưu thì clearVip + hiện `vip_expired_back` (I2.1 đã code).
- [x] I1.2 Cấu hình: `BIBOT_VIP_SECRET` (+`GEMINI_API_KEY` sẵn có) qua Secret Manager,
  KHÔNG commit git — kể cả repo `meme` private (repo đó chỉ chứa file non-secret:
  Dockerfile, script deploy). Lệnh deploy + curl mẫu đã cập nhật `relay/README.md`.

### I2. App (Kotlin) — [x] code xong 2026-10-11 (chờ assembleDebug kiểm chứng)

- [x] I2.1 `data/ads/VipVerifier.kt` (mới, HttpURLConnection + org.json, 0 dep):
  `redeem()` online POST relay → lưu entitlement + `last_check=serverTime` +
  `max_seen` → `setVip` (giữ cộng dồn monthly theo cap cũ, tuyệt đối nên nhập lại
  không cộng oan); mã lưu mà server báo invalid (hết hạn/thu hồi) → clear ngay.
  Offline: tin entitlement cũ nếu đúng mã + tháng (còn hạn & trong grace 7 ngày) /
  lifetime (recheck 30 ngày) + `now >= max_seen - 24h`; đổi mã → NeedOnline.
  Dev fallback giữ khi chưa cấu hình relay + build có secret (debug).
  DataStore thêm `VIP_ENT/VIP_ENT_TIER/VIP_LAST_CHECK/VIP_MAX_SEEN/VIP_CODE_HASH`
  + `save/load/clearVipAuth`, `touchVipMaxSeen`.
- [x] I2.2 Release ngừng nướng secret: `VIP_HMAC_SECRET` chuyển khỏi `defaultConfig`
  sang `debug` (có secret) / `release` (rỗng). `SettingsScreen` VipContent chuyển sang
  `VipVerifier.redeem` (bỏ verify local + bỏ chặn nhập lại, giữ `markVipCodeUsed`
  audit); thêm strings `vip_grace_offline/vip_need_online/vip_shared_warn` (en+vi).
  Gate không đổi: mọi chỗ đọc `isVipActive(adsState)` từ DataStore (`setVip` đã ghi).
- [ ] I2.3 Phase 2: enforce Play Integrity (`integrityToken` hiện log-only) + quota relay
  theo entitlement thay vì deviceId trần.
- [ ] I2.4 Verify build + máy ảo: `assembleDemoDebug` pass; nhập mã thật (dev relay local
  hoặc staging) → VIP on; tắt mạng → grace giữ VIP; vặn đồng hồ ngược >24h → rớt;
  đổi mã khác offline → NeedOnline; release APK `strings` không còn secret.
  Bug đã bắt khi user test: ô nhập `.take(32)` cắt mã 35 ký tự (thiếu 3) → nới `.take(40)`.
  Verify máy ảo 2026-10-11 (dev fallback, chưa có relay staging): mã đúng → VIP 1 tháng;
  tắt mạng → giữ 1 tháng (grace); mã sai → GIỮ VIP cũ (đúng thiết kế, chống typo mất
   quyền); không crash. Còn nợ: relay thật staging, vặn đồng hồ, `strings` release.

## PHẦN J - Quy trình ra bản mới (release/upgrade) + quy ước tên file - [x] chốt 2026-10-12

### J0. Quy ước tên file CHUẨN (bắt buộc mọi bản, không đặt tên có version)

| Flavor | Package | APK (cài trực tiếp) | AAB (nộp store) |
|---|---|---|---|
| Demo (Free, có Ads) | `com.bibot.app` | `Bibot.apk` | `Bibot.aab` |
| VIP (đặc biệt, không Ads) | `com.bibot.app.vip` | `BibotV.apk` | `BibotV.aab` |

- Gradle TỰ xuất đúng tên này, không đổi tay:
  `android/app/build.gradle.kts` → `applicationVariants.all { outputFileName =
  flavorName == "demo" ? "Bibot.apk" : "BibotV.apk" }` + task `renameReleaseBundles`
  (copy `app-*-release.aab` → `Bibot.aab` / `BibotV.aab`).
- Outputs gốc: `app/build/outputs/apk/<flavor>/release/Bibot*.apk`,
  `app/build/outputs/bundle/<flavor>Release/Bibot*.aab`.
- Đóng gói `H:\Pi4_Xiaozhi\dist\vX.Y.Z-demo\`: COPY GIỮ NGUYÊN TÊN, version nằm ở
  tên THƯ MỤC. CẤM tên kiểu `Bibot-v1.0.1-demo.apk` (đã mắc 1 lần ở v1.0.0/v1.0.1,
  sửa xong 2026-10-12).
- Nguyên tắc build song song (chốt 2026-10-12): mỗi bản release build CẢ 2 APK —
  `Bibot.apk` trước, `BibotV.apk` ngay sau — để cài cùng máy test song song
  (khác package `com.bibot.app` / `com.bibot.app.vip`, khác label "Bibot" /
  "Bibot VIP" nên không đè nhau). File VIP test để riêng `dist\vX.Y.Z-vip\`,
  KHÔNG public khi chưa có lệnh riêng (chỉ demo + `Bibot.aab` được public).

### J1. Checklist từng bước ra bản mới

- [ ] J1.1 Tăng version trong `android/app/build.gradle.kts` (`defaultConfig`):
  `versionCode` +1, `versionName` theo semver. Commercial tự thêm suffix `-vip`.
- [ ] J1.2 Build song song: `./gradlew :app:assembleDemoRelease`
  rồi `:app:assembleCommercialRelease :app:renameReleaseBundles`
  (task sau kéo cả 2 `bundle*Release`). Kết quả đủ bộ 4 file:
  `Bibot.apk` + `Bibot.aab` + `BibotV.apk` + `BibotV.aab`. Kiểm tra BUILD SUCCESSFUL.
- [ ] J1.3 Verify outputs: `output-metadata.json` đúng code/name; `apksigner verify`
  cả 2 APK phải exit 0; đúng chữ ký `CN=Bibot App` (không dùng `bibot-test.keystore`);
  `aapt2 dump badging` check package (`com.bibot.app` / `com.bibot.app.vip`) +
  label ("Bibot" / "Bibot VIP") + versionCode đồng đều 2 flavor.
- [ ] J1.4 Đóng dist mới: `dist\vX.Y.Z-demo\` copy `Bibot.apk` + `Bibot.aab`
  (GIỮ NGUYÊN TÊN) + viết `SHA256.txt` (`Get-FileHash -Algorithm SHA256`) +
  ghi chú test (`GHI-CHU-TEST.md`: có gì mới, cần test gì, cài đè giữ data);
  `dist\vX.Y.Z-vip\` copy `BibotV.apk` + `SHA256.txt` riêng (test nội bộ).
- [ ] J1.5 Cập nhật tài liệu: `data_to_CH Play\store-info.txt` (bản/code/đường dẫn),
  `README-Bibot.md` (số version, dung lượng), `Policy.md` + `privacy-policy.md`
  trên GitHub nếu đổi thu thập dữ liệu/quyền (hiện tại email thống nhất
  `bibotvadmin@gmail.com`, URL raw `.../Bibot/main/privacy-policy.md`).
- [ ] J1.6 Test máy thật trước khi public: cài đè lên bản cũ (giữ data), đọc
  TXT/MD + PDF text, Voice Chat, Tin tức/Thời tiết, check nhãn menu "Tài liệu",
  PDF scan xác nhận đọc không ra (đúng thiết kế, không phải bug).
- [ ] J1.7 Public: up `Bibot.apk` + `SHA256.txt` + ghi chú lên GitHub Releases
  (tag `vX.Y.Z-demo`); beta qua Firebase App Distribution; store hãng + chợ APK
  theo `store-info.txt`. Không public `BibotV.*` khi chưa có lệnh.

### J2. Lịch sử áp dụng

- [x] v1.0.1 (code 2, 2026-10-10): vá đọc PDF FlateDecode (`BlockTextReader`),
  gỡ ML Kit OCR + `PdfScreen`, nhãn "Tài liệu". APK 64MB → 33.5MB,
  AAB 36MB → 22MB. Chuẩn hóa tên dist về `Bibot.apk`/`Bibot.aab` 2026-10-12.
- [x] v1.0.2 (code 3, 2026-10-12): bộ song song đầu tiên — `Bibot.apk` 33.7MB
  (`com.bibot.app`, "Bibot") + `BibotV.apk` 34MB (`com.bibot.app.vip`,
  "Bibot VIP"), `Bibot.aab` 22.3MB + `BibotV.aab` 22.5MB. Verify aapt +
  apksigner cả 2 APK. Dist tách `v1.0.2-demo\` (public được) / `v1.0.2-vip\`
  (test nội bộ).

### J3. Trạng thái phiên hiện tại (chốt theo user)
- XONG: A2.4/A2.5 xác minh chính, H2.1/H2.2, D1.7–D4.4, B5.1–B5.5/B5.7, B6.17 (cụm FAB
  một tay — VERIFY MÁY 2026-10-14 đêm),
  B6.18 (lề hẹp + Justify + viền 0.4dp — code xong, thấy lề hẹp trên máy, còn nợ đo kỹ),
  B5.9 (Firebase `bibot-wiki` đấu nối + release song song verify ký),
  wiki 2 máy (Khe Sanh 2026-10-14: máy thật trả token → máy ảo đọc free, `hits` 1→2),
  B6.19 (nền màn đọc drum/đơn sắc — CODE + VERIFY MÁY 2026-10-17, xem B6.19),
  C4 2 flavor (2026-10-17: `go vet` 0 + JVM 69/69 + ruler 64/64 + `assembleDemoDebug`
  58,7MB + `assembleCommercialDebug` PASS + máy ảo sạch FATAL),
  A3.2 (gộp 2 player chồng → 1 controller `PlayerViewModel` + `stopAll()` duy nhất +
  engine TTS singleton — CODE + VERIFY MÁY 2026-10-17, xem A3.2).
- CÒN MỞ: B5.6 (còn reward +3 summary turns live — cần nhập lại key + hết cooldown),
  B5.8, A2.5b (font/sách dày), A3.1/A3.3–A3.5 (export audiobook, scrubber+MediaSession,
  SmartCrop), E2 (view nguyên bản), G1.2/G1.3,
  G2.2 (e.g./i.e./vs./etc.), G3.2/G3.3 (title chương đọc-pause), H3 (OTA rulepack),
  I2.3/I2.4 (Play Integrity + verify relay thật), J1 (release mới).
- VERIFY CUỐI (2026-10-17): JVM 69/69, ruler 64/64, Go PASS, demoDebug 58,7MB rebuild +
  commercialDebug PASS, release cũ Bibot 33.7MB/BibotV 34MB (ký release, apksigner 0),
  máy ảo BibotTest (AVD path mới: chạy emulator với `ANDROID_AVD_HOME=H:\androidkit\.android\avd`)
  + máy thật sạch FATAL.
- NỢ SANG PHIÊN SAU: reward +3 summary turns live (hết cooldown), A2.5b, rồi A3 còn lại
  (A3.1/A3.3–A3.5); release mới (J1.1 bump version) khi user lệnh.
