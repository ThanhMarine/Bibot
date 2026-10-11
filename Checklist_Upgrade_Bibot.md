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
  progress sống qua install -r. Chưa đo 30 phút/xoay máy (ghi nhận test dài ở B4).

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

---

## PHẦN C — Chất lượng chung (áp cho mọi giai đoạn)

- [ ] C1 Nút phone/tablet ≥48dp cho hành động chính (`BigButton 64/72dp` mở rộng khỏi Voice); chữ phụ ≥12sp; emoji có `contentDescription` cho TalkBack.
- [ ] C2 Mọi màn có 4 trạng thái: loading / empty / error + Retry / content. Không ghi đè 1 chuỗi `status?`.
- [ ] C3 Không `readText()` toàn file; mọi list dùng `LazyColumn`; tab chương dài có search.
- [ ] C4 Mỗi giai đoạn xong: `go vet` 0 + `assembleDemoDebug + assembleCommercialDebug` pass + test máy ảo (BibotTest) với sách 1.8MB/2545 trang.
- [ ] C5 Thứ tự làm (đã chèn R0 + E1.1 + B5, xem Phần F):
  `R0 → A1 → B1 → B3+E1.1 → B2 → A2 → B4 → B5 → A3`.
  Mỗi bước build được APK, không phá voice chat.

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
