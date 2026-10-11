# Checklist Nâng Cấp Bibot — Port từ OpenReadEra + openreader + Chuẩn UI ReadEra

> Single source cho đợt nâng cấp đọc sách Bibot.
> Nguồn: (1) so sánh `OpenReadEra/` + `openreader/` đã clone về repo,
> (2) screenshot thật ReadEra `org.readera 26.05.20+2300` chạy trên AVD `BibotTest`.
> Ngày lập: 2026-10-10. Bibot hiện tại: `com.bibot.app`, APK ~83.5MB, Compose + Go core.

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

- [ ] A2.1 Lazy page kiểu `EraPdfBridge.getPage(i,decode)+free + LRU 3–5 trang + store cap 64MB`:
  - File chạm: `core/text/BlockTextReader.kt` (đã có block 16KB/CARRY 256 — thêm `free` tường minh), `PdfToMarkdownHelper.kt`, `BookRepository.kt` (bỏ chỗ còn `readText()/readBytes()` toàn file).
- [ ] A2.2 MOBI → EPUB rồi reuse renderer (port `eramobi/EraMobiConvert.cpp` bằng `libmobi-java + java.util.zip` gói `mimetype+container.xml+part*.html`).
  - File chạm: `data/books/BookRepository.kt`, `DriveBookSource.kt`.
- [ ] A2.3 Snap config + `RenderIfDirty` (copy `ALLOWED_FONT_SIZES/INTERLINE/PARAGRAPH_MARGINS` của `eraepub`): chỉ relayout khi setting đổi, hết jank khi đổi font sách dày.
  - File chạm: `feature/book/BookScreen.kt`, `feature/story/StoryScreen.kt`.
- [ ] A2.4 Đơn vị vạn năng `Hitbox + SearchWindow ±7 từ` (clone `orebridge/StSearchUtils.h`): dùng chung cho highlight/bookmark/TTS word-sync/search preview/tap-to-define.
  - File chạm: `core/text/TextCleaner.kt`, `BookScreen/StoryScreen`; thay `position=chap*100+sent` (sai khi chương >100 câu).
- [ ] A2.5 Verify: mở sách 3–5MB không đơ; đổi font không relayout liên tục; resume nhảy đúng chương >100 câu.

### A3. P2 — Export + Player polish (học openreader)

- [ ] A3.1 Export audiobook durable (port `export-job.ts + ffmpeg-export.ts`): prefetch 4, batch sidecar 32, assemble khi audio xong (không chờ align), lỗi terminal → silence 1s + đếm `skipped`, progress đọc từ cache scope (fix `eee701c6`: không đọc session hết hạn 30p).
  - File chạm: mới `feature/export/ExportViewModel.kt` + WorkManager.
- [ ] A3.2 Gộp 2 player chồng (`ExoPlayer` vs `speakOffline Direct`) thành 1 controller; mọi màn gọi 1 hàm stop duy nhất (hết đọc chồng News/Weather/Voice bypass `activeIds`).
  - File chạm: `PlayerViewModel.kt`, `data/offline/OfflineTtsHelper.kt`, `NewsViewModel`, `WeatherScreen`, `VoiceScreen`.
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

### B2. Giai đoạn 2 — Màn About sách mới (`BookDetailScreen`)

- [ ] B2.1 Tách khỏi `BookScreen` (~770 dòng ôm đồm): màn mới hiện bìa lớn + nút `ĐỌC` nổi + nút loa (đọc từ đầu bằng pipeline hiện tại) + dãy 7 action + metadata dòng (`Tác giả / Thể loại / Ngôn ngữ / Bookmark + giờ đọc / format-size-files`).
- [ ] B2.2 `BookScreen/StoryScreen` chỉ còn nhiệm vụ render + đọc; nhận `bookId + startPos` từ Detail.
- [ ] B2.3 Verify: mở Detail <1s; bấm ĐỌC vào đúng vị trí bookmark; nút loa đọc từ đầu.

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

- [ ] B4.1 Mục lục từ heading đã parse (`##/Chương`); quotes/bookmark bảng Room mới, gom 1 chỗ như ReadEra.
- [ ] B4.2 Search trong sách: `BlockTextReader` + highlight `SearchWindow ±7 từ` (A2.4), nút Forward/Back giữa kết quả.
- [ ] B4.3 Verify: sách 500 chương vẫn liệt kê + nhảy nhanh; quote lưu note + mở lại đúng chỗ.

### B5. Giai đoạn 5 — Tóm tắt AI (Bonus/VIP, Cloud AI)

> Entry ở Detail (B2) + cần hạ tầng chương (B4) nên làm sau B4; xong trước A3 để
> export audiobook có thể kèm track tóm tắt. Tốn token Cloud thật → gating là
> yêu cầu bắt buộc, không phải optional.

- [ ] B5.1 Scope v1 (chống phình): đúng 2 mức — tóm tắt **Chương** + tóm tắt **Sách**
  (map-reduce: tóm tắt từng chương rồi dồn thành tóm tắt sách). KHÔNG làm mức
  "truyện trong sách", KHÔNG hỏi-đáp, KHÔNG trợ lý đọc sách.
- [ ] B5.2 Ống LLM: reuse `core/llm/LlmBridge.kt` (Gemini `streamGenerateContent` +
  fallback OpenRouter); key riêng `geminiApiKey` (≠ key TTS), model theo
  `geminiModel`; prompt tóm tắt tiếng Việt versioned trong `BotPromptManager`.
- [ ] B5.3 Cache (triết lý A1): tóm tắt theo content-hash chương + model; Room bảng
  mới `chapter_summary (chapterHash PK, bookId, model, promptVer, text, updatedAt)`.
  Cap input: tối đa N chương/lần + cắt ngắn ký tự/chương (chặn đốt token sách dày).
- [ ] B5.4 Gating (reuse, không dựng hệ thứ hai): VIP mở full; non-VIP xem rewarded
  ad lấy lượt (mirror `rewardedCloudChapters`, key riêng `rewarded_summary`, trần 9).
  Mất key/offline → ẩn/disable nút + báo rõ (triết lý A1.4: probe trước, không mất
  vị trí đọc). Hết lượt → chặn + mời xem ad.
- [ ] B5.5 UI: nút `Tóm tắt` ở Detail (B2) + mục trong màn B4; đủ 4 trạng thái C2
  (loading/empty/error+Retry/content); kết quả đọc được bằng chain TTS hiện có
  (`Nghe tóm tắt`); tóm tắt cấp sách chạy nền bằng WorkManager.
- [ ] B5.7 Pipeline tóm tắt dùng chung (giảm token toàn cục): key chia sẻ =
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
- [ ] B5.6 Verify: spot-check tay 3 sách (sử Việt, cổ tích, EN) — đúng ý chính,
  tên/sự kiện chính có mặt, không bịa; mở lại chương đã tóm tắt → hiện ngay từ
  cache (không gọi mạng); hết key/offline → nút disable, không crash, giữ vị trí
  đọc; non-VIP hết lượt → chặn đúng, xem ad xong được lượt; sách 500 chương không
  đốt token cả sách một lần; máy B mở sách máy A đã tóm tắt → hiện ngay từ
  `wiki_summaries` (0 token, check logcat/quota); tắt chia sẻ trong Settings →
  không upload nhưng vẫn đọc được; hết key/offline vẫn đọc wiki đã cache;
  C4 chung (2 flavor + máy ảo).

### B6. Tối ưu vùng hiển thị + liền mạch đọc (review screenshot 5 màn, 2026-10-11) — [x]

> Làm ngay sau B3 (nhỏ, không đảo roadmap). Vấn đề thấy trên ảnh: double top-bar
> (app 52dp + reader), bottom nav + banner đè màn đọc, hàng brightness cố định,
> nút Lock lạ (code hiện tại KHÔNG còn — ảnh build cũ, không cần xóa),
> chunk ngắn + tin bị cắt 200 ký tự.

- [x] B6.1 Ẩn chrome app trong mode đọc: `BibotNavSuite` ẩn topBar/bottomBar/Rail khi
  `BOOK/STORY/COTICH/PDF` (back bằng nút reader) + ẩn `AdsBanner` trong 4 mode này
  (true full-screen kiểu ReadEra; đánh đổi doanh thu banner lúc đọc — giữ
  interstitial/rewarded). Thêm nút back cho `DocBrowserScreen` (kẻo kẹt thư viện).
  Verify máy ảo: reader top-bar bắt đầu y=168 sát status bar, hết nav/banner;
  về Home hiện lại; logcat sạch FATAL.
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

---

## PHẦN C — Chất lượng chung (áp cho mọi giai đoạn)

- [ ] C1 Nút phone/tablet ≥48dp cho hành động chính (`BigButton 64/72dp` mở rộng khỏi Voice); chữ phụ ≥12sp; emoji có `contentDescription` cho TalkBack.
- [ ] C2 Mọi màn có 4 trạng thái: loading / empty / error + Retry / content. Không ghi đè 1 chuỗi `status?`.
- [ ] C3 Không `readText()` toàn file; mọi list dùng `LazyColumn`; tab chương dài có search.
- [ ] C4 Mỗi giai đoạn xong: `go vet` 0 + `assembleDemoDebug + assembleCommercialDebug` pass + test máy ảo (BibotTest) với sách 1.8MB/2545 trang.
- [ ] C5 Thứ tự làm (đã chèn R0 + E1.1 + B5 + B6, xem Phần F):
  `R0 → A1 → B1 → B3+E1.1 → B6 → B2 → A2 → B4 → B5 → A3`.
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
- [ ] D1.7 Verify máy thật: sách Việt có trang bìa + mục lục chấm + `Trang x` footer
  → TTS vào thẳng Lời giới thiệu/Chương 1, không đọc số trang.

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
- [ ] D2.6 Verify máy thật: nghe `31/12`, `31-12-2026`, `1.440 đồng`, `12:30`.

### D3. Nhiệt độ (`normalizeDegrees` 2 bên) + vá chữ chẻ — [x]

- [x] D3.1 `25°C`/`25-30°C` → `25 độ C`/`25-30 độ C` (rồi `dashBetween` → `25 đến 30 độ C`);
  `30độ` dính → `30 độ`. Chạy trước `dashBetween`, sau `readDates`.
- [x] D3.2 BUG PHÁT HIỆN KHI TEST: quy tắc vá chữ chẻ `M èo→Mèo` ăn luôn
  `độ C hôm nay` → `độ Chôm nay` (lỗi cũ, cả 2 bên). Fix: giữ nguyên token
  chữ đơn `A-F/K/O/X/Y` (`vitamin C`, `tia X quang`, `điểm B`) — `SOLO_TOKEN_LETTERS`.
- [ ] D3.3 Verify máy thật: `25-30 độ C`, `vitamin C mỗi ngày`, `M èo` (PDF kern).

### D4. Số La Mã (`readRomanNumerals` + Go `readRomanGo`) — [x]

- [x] D4.1 Thêm từ khóa `phụ lục`: `Phụ lục VII` → `phụ lục bảy`.
  Có sẵn: `Chương X` → 10, `thế kỷ XX` → 20, khoảng `XI-XIX`.
- [x] D4.2 Phi chuẩn sách cũ `Phần IIX` → `phần tám` (map `IIX→8`; sai khác như
  `IIII` vẫn giữ nguyên). Go giữ chữ số (`Phần 8`).
- [x] D4.3 Chỉ sau từ khóa nên `Louis XIV`, `I` đứng riêng giữ nguyên.
- [ ] D4.4 Verify máy thật: `Chương X`, `thế kỷ XX`, `Phụ lục VII`, `Phần IIX`.

### D5. Verify code (đã chạy)

- [x] D5.1 `go vet ./internal/... ./cmd/...` 0 + `go build` pass +
  `go test ./internal/tts/` 4/4 PASS (`internal/tts/tts_test.go` mới).
- [x] D5.2 40/40 pattern check cho regex Kotlin (mirror Python, kotlinc chưa có).
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
  ReadFigureRefsGo` PASS; JVM `TextRulesTest` (chunk whole/quote/abbrev/fig vi-en) 8/8
  cùng `TtsSegmentsTest` 9/9 PASS; máy ảo đọc Ngưu Lang có `(1989...)`/`mùng bảy`
  tự nhiên (spot-check dòng `(âm lịch)` không bị chẻ).

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
  (tổng quát hóa từ `BookScreen`, giữ nguyên luồng producer `Channel(2)` + PrepSession +
  phase ads; heading regex chuyển về `feature/reader/ReaderHeadings.kt` — xóa import
  chéo `feature.story`). Xong 2026-10-11: `ReaderScreen.kt` 821 dòng (sinh bằng
  script `gen_reader.py`, 44 điểm thay thế đều khớp count + check leftover).
  NOTE: state vẫn `remember` trong composable như cũ (không regression) — hoist sang
  `ReaderViewModel` dời sang nhịp B3 cùng Touch_point/anchor (F2 cần anchor + readMode).
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

### G1. Khắc phục crash khi xoay ngang–dọc

- [ ] G1.1 Nguyên nhân (đã xác định từ code): state đọc sách vẫn `remember` trong
  composable (F0.1 NOTE — hoist sang `ReaderViewModel` dời sang B3); xoay màn hình →
  Activity recreate → `DisposableEffect.onDispose` đóng `prepSession` trong khi producer
  `Channel(2)` + scope cũ vẫn synth → race dùng session đã đóng + ExoPlayer surface
  recreate → crash. KHÔNG dùng `android:configChanges` (phá resources/locale).
- [ ] G1.2 Fix đúng: hoist toàn bộ state đọc (`prepSession`, `jumpSeq`, producer job,
  `currentChapter/Sentence`) vào `ReaderViewModel` + `SavedStateHandle` (giữ
  chapter/sent/anchor qua recreate); `PlayerViewModel` (AndroidViewModel) giữ ExoPlayer
  sống sót; `DisposableEffect` chỉ đóng khi rời màn hình thật (không phải config change).
  Làm cùng B3 (đúng chỗ F0.1 đã hẹn).
- [ ] G1.3 Verify máy ảo (ma trận xoay): xoay khi IDLE / đang READ TTS / đang BROWSE /
  giữa dialog đổi anchor / khi QC interstitial → không FATAL, đọc tiếp đúng câu
  (logcat + `dumpsys meminfo` không leak ExoPlayer).

### G2. Nguyên tắc gom chunk cho đoạn trong ngoặc (...)/{...}/[...]/"..." (ghi chú, trích dẫn)

- [ ] G2.1 Bug đã xác định: `DELIM` của `VietnameseChunker.kt:114` tách ở `.` `;` `:` `,`
  nên `(at Fig. 5)` bị chẻ ngay sau `Fig.` (`SENT_END` khớp → yield giữa ngoặc).
  Fix: theo dõi **bracket-depth** trong `feed()` — DELIM nằm trong ngoặc (depth>0,
  kể cả `"..."` toggle) thì không yield/không tách; ngoặc đóng xong mới xét yield.
- [ ] G2.2 Chuẩn hoá đọc trong `TextCleaner` (trước chunker, theo `fixedLang` của
  `ReaderCollection` F0.2): VI — `Fig.`→`hình`, `e.g.`→`ví dụ`, `i.e.`→`nghĩa là`,
  `vs.`→`so với`, `etc.`→`vân vân`; EN — `Fig.`→`figure`, `e.g.`→`for example`…;
  số trong ngoặc theo D2 (`1989`→`một nghìn chín trăm tám mươi chín`,
  `from 1989 to 2011` giữ nguyên từ, chỉ đổi số). Vỏ ngoặc ngoài → dấu phẩy
  (`, … ,`) để TTS ngắt tự nhiên; bỏ ngoặc rỗng `()`.
- [ ] G2.3 Mirror Go (`tts.go`): guard tương đương cho `ChunkerSplit` + expand tối thiểu
  (`Fig.`→`hình`/`figure` theo tham số lang) + `go test` case mới.
- [ ] G2.4 Verify: `(from 1989 to 2011)`, `(see Fig. 5)`, `"trích dẫn dài..."` không bao giờ
  bị chẻ làm đôi; nghe VI/EN mỗi loại 3 câu ngoặc; `go test` + 40-case regex pass.

### G3. Nhận diện đề mục + đọc có ngắt câu

- [ ] G3.1 Quy tắc (khớp `detectStoryHeading` + `mdHeading` sẵn có): đề mục = chuỗi NGẮN
  (≤120 ký tự, 1 dòng), KHÔNG dấu chấm cuối (`.!?…:;`), bắt đầu HOA/số, xuống hàng ngay
  sau. Mở rộng: EPUB `h1-h3`/spine title, `CHAPTER-BREAK title=`, MD `##` (đã có).
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
  `priority` = thứ tự pipeline. File: `tools/rules/reading-rules.ruler` (v1, 3 rule).
- [x] H0.2 Test nhúng trong ruler (19 case, cả giữ nguyên) — `compile_rules.py` chạy
  toàn bộ, 19/19 PASS, thay `regex_check.py` rời rạc.
- [x] H0.3 Giao Java∩RE2∩Python: script từ chối lookaround/atomic/possessive/backref/
  named (đã bắt bug thật khi dev). `version` int + `checksum` sha256 trong JSON
  (1006 bytes). Lệch nhỏ: `split_guard` mới có trong schema + doc, chưa có rule mẫu
  (sang H2.3).

### H1. Scaffold + 3 rule pilot — [x] chạy được (H1.3 dời H2)

- [x] H1.1 `tools/rules/reading-rules.ruler` (3 rule pilot) + `compile_rules.py`
  (YAML→JSON + tests + đo size) + `RULES_AUTHORING.md`. Lệch: script còn mirror 1 bản
  JSON sang `internal/rules/reading-rules.json` (do `go:embed` cấm `..`).
- [x] H1.2 Loader Kotlin `core/text/ReadingRules.kt` (`org.json`, 0 lib mới:
  `parse/load/apply/isDropLine/isStartMarker`, hook dùng `TextCleaner` sẵn có) +
  JSON copy vào `android/app/src/main/assets/reading-rules.json` (1053 bytes).
  Loader Go `internal/rules/` (`//go:embed` + stdlib, engine + hook + `viWords`
  port từ Kotlin) + `rules_test.go` 4 test PASS. Budget giữ: JSON ~1KB (trần 30KB).
  Ghi nhận 2 bug đã sửa khi verify: Go `FindAllStringSubmatchIndex` trả -1 với group
  optional (đã fix + Kotlin an toàn sẵn); quirk `mười mốt` parity Kotlin (fix sang H2).
  Kotlin chưa biên dịch local (như D5.4) — `assembleDebug` sẽ kiểm chứng.
- [ ] H1.3 1 entry reuse duy nhất: voice chat / news / weather / đọc sách đều gọi
  `ReadingRules.apply()` — DỜI sang H2 (migrate từng bước, giữ hàm cũ làm wrapper).

### H2. Migrate dần, mỗi bước xóa code cứng (D1–D4, G2–G3 → ruler)

- [ ] H2.1 Dates/degrees/roman (D2–D4) vào ruler; xóa hàm cứng tương ứng, test cũ phải
  xanh (port case sang `tests[]` trong ruler, xóa dần `tts_test.go` trùng).
- [ ] H2.2 Drop-line + start-marker (D1) vào ruler (`kind: drop_line/start_marker`);
  `BlockTextReader` chỉ còn state machine buffer/discard (logic), pattern lấy từ ruler.
- [ ] H2.3 Chunk guard ngoặc (G2) + đọc đề mục (G3): tham số `split_guard.pairs: ()[]{}`""
  và `heading.max_chars/speak_pause_ms` vào ruler; `VietnameseChunker`/producer đọc
  tham số, không hardcode.
- [ ] H2.4 Mỗi H2.x xong: `go test`, `regex_check` thay bằng `compile_rules.py test`,
  APK không tăng quá 0.2MB (JSON nhúng ~20KB).

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
