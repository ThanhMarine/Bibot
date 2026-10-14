# Biên bản phiên làm việc 2026-10-13 — B6.14 walkthrough + ViewScreen wire thật + A2.2 + H2.3

> Đọc kèm `Checklist_Upgrade_Bibot.md` (Mode 2, A2.2, H2.1/H2.3 đã cập nhật trong phiên này).
> Phiên trước: `SESSION_HANDOFF_2026-10-12.md`.

## 1. Việc đã xong (commits `a7c1893`, `4eb39c1` — tree sạch)

### 1.1 Cài đè APK ruler v2 + walkthrough B6.14
- Emulator chết giữa phiên → boot lại `BibotTest` (`-no-snapshot-load`), cài đè OK.
- B6.14 giữ: Home + Reader library (8 sách) không popup ngắt; logcat banner AMP load,
  `Rewarded loaded`, KHÔNG có `Interstitial loaded/show` trong reader.

### 1.2 ViewScreen wire sách thật (user chọn wire, không gỡ) — Mode 2 [ ]→[x]
- `ViewScreen.kt` viết lại: kệ sách từ `BookRepository` (44 cuốn trên máy ảo),
  chương = `BlockTextReader.readChapters(..., MARKDOWN_OR_STORY)` (qua ruler),
  bìa `LibraryCovers`, tác giả OPF/EXTH, PAGE: PDF→`PdfPageView` thật / còn lại phân
  trang text, nút loa/play/pause đọc thử `OfflineTtsHelper`; `PAUSE2SEC` hiện "…".
- `HomeMenu.kt`: thêm tile VIEW vào grid (trước đó enum + nhánh chết, grid không có).
- Verify: `Linh Nam Chích Quái.md` (Mục lục 1 + full text), `test_mobi.mobi` tự dựng.

### 1.3 A2.2 MOBI [ ]→[x] (draft sai-format cũ viết lại theo PalmDB thật)
- `MobiReader.kt`: detect `BOOK/MOBI` + numRecords, PalmDoc header (1=raw/2=LZ77),
  MOBI header (encoding 1252/65001, exthFlags ở MOBI+0x80), EXTH (503 title/100 author),
  PalmDoc-decompress, HTML→text, từ chối rõ DRM/HuffCdic; `convertToEpub` tối thiểu
  (mimetype stored + container + OPF + XHTML) để `EpubReader` đọc lại.
- Bug tự bắt khi verify: offset EXTH `eo+8`→`eo+12`; test builder entry 7B→8B.
- Wire: `BookRepository` (scan `.mobi/.azw/.azw3` + `iterSentences`),
  `BlockTextReader.readChapters` nhánh mobi, `PublicDriveSource.mimeFor`,
  `ReaderCollection` STORY_VI/LOCAL_PDF, `ViewScreen` filter + tác giả EXTH.
- Test `MobiReaderTest` 10/10 (file MOBI dựng tổng hợp + PalmDoc vectors + roundtrip).

### 1.4 H2.3 [ ]→[x]; H2.1 giữ [ ] có lý do
- `VietnameseChunker(guard = chunkGuard())`: ngoặc/quotes/overrun/cite từ rule
  `chunk.brackets`; chưa init → default = hardcode cũ. `TextCleaner` thêm overload
  `endsWithCiteAbbrev(chunk, abbrev)`. `VietnameseChunkerTest` 5 test mới.
- Full JVM **38/38** (10 Mobi + 5 Rules + 9 TextRules + 9 TtsSegments + 5 Chunker);
  ruler `compile_rules.py` 61/61 (không drift — tree sạch sau khi chạy).
- H2.1 KHÔNG làm: điều kiện là "v2 ổn định ngoài máy thật", mới chỉ verify máy ảo.

## 2. Trạng thái repo
- APK demoDebug **58.881.879B** đã cài trên emulator-5554 (đang chạy).
- Test `.mobi` tổng hợp còn trong `files/bibot/pdfs/inbox/test_mobi.mobi` trên máy ảo
  (không trong repo — script dựng đã xóa cùng Temp; dựng lại khi cần, đừng commit rác).
- Go/`internal` không đụng (H2.3 chỉ Kotlin; chunker Go vẫn hardcode — ghi nhận).

## 3. Môi trường
- Build offline: `$env:JAVA_HOME="H:\androidkit\jdk17"`, `ANDROID_HOME=SDK`,
  `./gradlew ... --offline` trong `android/`.
- LƯU Ý build: task `compileDemoDebugKotlin` đôi khi hiện UP-TO-DATE ngay sau sửa —
  kiểm chứng bằng timestamp class/APK + `install -r` + walkthrough, đừng tin blind.
- Dump UI: `uiautomator dump` lỗi `null root` ngay sau start app → chờ 10s rồi dump lại.
- Đẩy file vào app-debug: pipe stdin qua `run-as` (`Get-Content -AsByteStream -Raw |
  adb shell "run-as com.bibot.app sh -c 'cat > ...'"`); `adb push` + `cp /sdcard` bị
  chặn quyền 2 đầu.

## 4. Việc tiếp theo (thứ tự)
1. A2.4: migrate progress `chap*100+sent` → Hitbox/xpointer (+ `BookDetailScreen`).
2. H2.2: `BlockTextReader` chỉ còn state machine buffer/discard.
3. B5/A3/C4, OTA ruler (H3.2), verify máy thật D1.7/D2.6/D3.3/D4.4 → mở khóa H2.1.
4. Xa hơn: chunker Go đọc params (H2.3 phía Go), H3.3.

## 5. Lệnh nhanh
```powershell
$env:JAVA_HOME="H:\androidkit\jdk17"; $env:ANDROID_HOME="H:\androidkit\sdk"
./gradlew :app:testDemoDebugUnitTest :app:assembleDemoDebug --offline  # JVM 38 + APK
$env:PYTHONIOENCODING="utf-8"; python tools/rules/compile_rules.py     # ruler 61
& "H:\androidkit\sdk\platform-tools\adb.exe" -s emulator-5554 install -r `
  "android\app\build\outputs\apk\demo\debug\Bibot.apk"
```

---
# Phiên tiếp theo 2026-10-13 (tối) — chốt: View/Documents gỡ tab, Top_bar, tách A, scope B+ruler v3, B6.16, .bat

Commits: `8663b6f` (gỡ View) → `6a4c5da` (Top_bar) → `d7d5b5a` (tách A, PREP v4)
→ `8f823e8` (scope B + ruler v3) → `0aecb6c` (B6.16) → `0be78c0` (1 vạch loading)
→ `2083a6a` (Documents→module) → `e2ab711` (run_bibot_emulator.bat). Tree sạch
(chỉ còn `Tóm_tắt_hiện_tại.md` của user + file handoff này, không commit).

## Việc đã xong
1. **2 chế độ đọc**: user chốt tách theo định dạng (EPUB/PDF giữ bản gốc vs MD/TXT
   gọn nhẹ) → **tab Xem TRÙNG → gỡ** (xóa file + enum + tile; MobiReader/EpubReader
   giữ). Checklist viết lại Mode 1/2.
2. **Top_bar thống nhất** (B6.15): bỏ Home/bottom-bar/Rail/header, Back trái +
   Chat/Settings phải; kệ sách thêm hàng Back về Home (kẻo kẹt mode đọc).
3. **Tách A display/TTS** (PREP v4): `.prep` nguyên bản, smart ở speak-time
   (lướt mốc + `ttsSpeakable` + `cleanForTts`), karaoke giữ index; EPUB ẩn span
   số trang; PDF fix race Mutex + retry + aspect.
4. **Scope B + ruler v3**: EPUB rich (ảnh/format/zoom/karaoke/style prefs),
   `drop.pageno_inline` template rỗng (display giữ, TTS bỏ), JSON 3 bản, version 3,
   17 rules; PDF double-tap zoom (phát hiện bitmap trong suốt → nền trắng).
5. **B6.16**: zoom dialog chung, style prefs, karaoke ordered (fix crash key trùng).
6. **Gỡ tab Documents** → giữ code làm `data/library` (Query+Manager+test 7/7) +
   `feature/library` cho Shell manager; PDF vẫn đọc ở kệ EN.
7. Lặt vặt: mục 1 `Tóm_tắt_hiện_tại.md` viết lại theo code mới; bỏ vạch loading thừa;
   `run_bibot_emulator.bat` (đã chạy thử end-to-end; nhớ CRLF + tránh `)` trong echo).
8. Verify: JVM 59/59, ruler 64/64, Go PASS, screenshot máy ảo (rich/zoom/karaoke/
   PDF p18/Home 6 tiles/1 ProgressBar), logcat không FATAL mới.

## Môi trường (bổ sung)
- Emulator CHẾT RẤT NHIỀU lần trong phiên (process biến mất, 1 lần ANR System UI).
  Không do app (không FATAL tương ứng). Boot headless `-no-window` không cứu.
  Làm việc theo đợt ngắn + screenshot/dump ngay sau mỗi thao tác.
- `run_bibot_emulator.bat` ở repo root để user tự boot/cài/mở.

## Việc tiếp theo (giữ nguyên thứ tự cũ)
1. A2.4 progress Hitbox; 2. H2.2 BlockTextReader; 3. B5/A3/C4, OTA H3.2,
   máy thật D1.7/D2.6/D3.3/D4.4 → mở H2.1; 4. Shell manager dùng `data/library`;
   5. EPUB rich: render lại phân giải cao cho zoom (đang dùng bitmap sẵn).
