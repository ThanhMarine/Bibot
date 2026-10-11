# Biên bản phiên làm việc 2026-10-11 — Bibot Android: phân tích ReadEra + nguyên tắc đọc sách

> Tài liệu bàn giao để bắt đầu code trong phiên mới. Đọc kèm
> `Checklist_Upgrade_Bibot.md` (kế hoạch sống, Phần A–F).

## 1. Mục tiêu phiên

1. Cài ReadEra gốc lên máy ảo, mổ UI + logic render để học cho Bibot.
2. So sánh `OpenReadEra` (C++ native) vs `openreader` (TypeScript) đã clone → port list.
3. Bổ sung "nguyên tắc đọc sách" vào pipeline TTS (ngày tháng, nhiệt độ, La Mã,
   skip TOC/số trang, mốc bắt đầu đọc) + Touch_point (chạm để neo điểm đọc).

## 2. Việc đã xong + bằng chứng

### 2.1 Máy ảo + ReadEra (BibotTest, `org.readera 26.05.20+2300`)

- Cài via Aurora Store (F-Droid APK) vì Play/APKPure/Uptodown chặn direct download.
- File test tự sinh (`gen_readtest.py` còn trong `%TEMP%\opencode`):
  `text_sample.pdf` 62kB (text + ảnh bìa nhúng + TOC), `scan_sample.pdf` 853kB
  (6 trang ảnh), `big_sample.pdf` 2.2MB (1200 trang TV có dấu), `war_worlds.epub` 1.8MB
  — cả 4 nằm ở `/sdcard/Download/` trên máy ảo.
- Kết quả đo: PDF text/scan render bitmap cuộn liên tục như sách thật; file 1200 trang
  hiện `1 of 1200` ngay, RAM TOTAL ~114–122MB (không tăng theo số trang); About Document
  dùng **SHA-1 file, không copy file**; external data chỉ 32K (render in-memory).
- Mổ APK: `assets/era{pdf,epub,djvu,mobi,comic}/` + `lib/x86_64/libera*.so` —
  khớp 1-1 source `OpenReadEra/` đã clone (StBridge, lazy `getPage+free`).

### 2.2 Nguyên tắc đọc sách — code đã sửa (chi tiết ở checklist Phần D)

| File | Thêm gì |
|---|---|
| `android/.../core/text/TextCleaner.kt` | `readDates` (`31/12`, `31-12-2026`, `mùng` ngày 1–10), `normalizeDegrees` (`25°C`→`25 độ C`), keyword `phụ lục`, map `IIX→8`, guard `SOLO_TOKEN_LETTERS` (fix bug `độ Chôm nay` do vá chữ chẻ ăn token `C/X/B`) |
| `android/.../core/text/BlockTextReader.kt` | Bỏ dòng số trang/mục-lục-dòng; buffer phần mở đầu 64KB, gặp mốc (Lời giới thiệu/Chương 1) thì bỏ, không mốc thì đọc như cũ; EPUB bỏ spine bìa/mục lục trống |
| `internal/tts/tts.go` | Parity Go (giữ chữ số cho engine): `readDatesGo`, `normalizeDegreesGo`, `readRomanGo`, cùng guard solo-token |
| `internal/tts/tts_test.go` (mới) | Test 3 hàm + integration `CleanTTSText` |

Verify đã chạy: `go vet ./internal/... ./cmd/...` 0, `go build` pass,
`go test ./internal/tts/` **4/4 PASS**, 40/40 check regex Kotlin (mirror Python —
**Kotlin chưa biên dịch local, bắt buộc `assembleDemoDebug` trước khi release**).

### 2.3 Quyết định kiến trúc đã chốt (checklist Phần F)

- **R0 trước mọi thứ**: gộp `BookScreen` (768 dòng) + `StoryScreen` (784 dòng, 380 dòng
  trùng nguyên văn ≈48%) thành `ReaderScreen` + `enum ReaderCollection
  { EN, STORY_VI, COTICH, THAMKHAO, LOCAL_PDF }` (Tham khảo = thêm folder ID, 0 màn mới).
- **E1.1 `PdfPageView`** thuộc nhịp **B3** (chrome center-tap menu): VIEW thuần
  (`PdfRenderer` framework = +0 lib), 2 chế độ TEXT/PAGE, dùng chung cho PDF/Tham khảo/
  fallback scan/thumb/preview. Budget APK ≤85MB.
- **Touch_point (F2)**: mode BROWSE (chỉ render, không chunking/TTS) → long-press neo
  `ReadAnchor` → mode READ chunking lazy từ anchor; anchor lưu vào `BookProgress`
  (+`fileHash` E1.3); đổi anchor hỏi dialog kiểu ReadEra.
- Thứ tự: `R0 → A1 → B1 → B3+E1.1 → B2 → A2 → B4 → A3`.
- Đánh đổi đã ghi nhận: phân số `3/5` đọc thành ngày; dòng số đứng riêng trong thơ bị bỏ;
  `B ệnh`/`D ịch` không vá (giữ token); ISO `2026-05-10` giữ nguyên.

## 3. Trạng thái môi trường (phiên mới cần biết)

- Emulator `BibotTest` (Pixel 7, API 34, x86_64) — lần trước start bằng
  `emulator.exe -avd BibotTest`; `ANDROID_HOME=H:\androidkit\sdk`.
- Máy ảo còn: ReadEra + Aurora + Bibot cũ + 4 file test trong `/sdcard/Download/`. File: Viẹt Nam Sử Lược _ Trần Trọng Kim.pdf (2.131.331 bytes, khớp bản gốc) → /sdcard/Books/ trên máy ảo.
- Repo **không phải git** (`fatal: not a git repository`) — chưa có version control;
  nên `git init + commit` trước khi code R0.
- Script tạm còn dùng được: `%TEMP%\opencode\gen_readtest.py`,
  `regex_check.py` (40 case), `findrows.py` + `adb input tap` theo bounds uiautomator.

## 4. Việc tiếp theo (theo thứ tự)

1. `git init` + commit baseline (an toàn trước refactor).
2. **R0**: gộp 2 màn đọc → `ReaderScreen` + `ReaderCollection` (F0.1–F0.4).
3. Build `assembleDemoDebug` (đồng thời kiểm chứng code Kotlin Phần D) + test máy ảo.
4. A1 → B1 → B3+E1.1 (kèm Touch_point F2) → … theo C5.
