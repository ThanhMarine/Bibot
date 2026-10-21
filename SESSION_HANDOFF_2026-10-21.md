# Biên bản phiên làm việc 2026-10-20/21 — G3.2/G3.3 + G2.2 + G1.3 + G1.2 + H3 + E2.1/E2.2 + E2.3(TOC + panel)

> Đọc kèm `Checklist_Upgrade_Bibot.md` (G3.2/G3.3, G2.2, G1.3, G1.2, H3, **E2.1, E2.2, E2.3(TOC
> + panel text-layer)** đã đánh dấu XONG — E2.3 chừa reflow/crop margin).
> Phiên trước: `SESSION_HANDOFF_2026-10-20.md` (B5.8 — đã commit `fb9aa5d`).
> **G3.2/G3.3 `51069bb`; G2.2 `3d8c9f1`; G1.3 `47d539a`; G1.2 `eed6cbd`; H3 `f572830`;
> E2.1+E2.2 `5c10887`; E2.3 TOC `e8a0493` — ĐÃ COMMIT**; **E2.3 (panel text-layer) CHƯA COMMIT**:

```
 M Checklist_Upgrade_Bibot.md
 M SESSION_HANDOFF_2026-10-21.md
 M android/app/src/main/java/com/bibot/app/feature/reader/PdfPageView.kt
 M android/app/src/main/java/com/bibot/app/feature/reader/ReaderChrome.kt
 M android/app/src/main/java/com/bibot/app/feature/reader/ReaderScreen.kt
 M android/app/src/main/res/values-vi/strings.xml
 M android/app/src/main/res/values/strings.xml
?? android/app/src/main/java/com/bibot/app/core/text/PdfTextSearch.kt
?? android/app/src/main/java/com/bibot/app/feature/reader/PdfTextPanel.kt
?? android/app/src/test/java/com/bibot/app/core/text/PdfTextSearchTest.kt
```

## 1. Việc đã xong

### 1.1 G3.2 — producer phát tiêu đề chương là chunk ĐẦU + pause
Quyết định thiết kế (đã chốt trong phiên):
- Title lấy từ item `isTitle=true`; **`sentIdx` = câu mục tiêu** (progress/karaoke không lệch).
- `pauseAfterMs = READER_PAUSE_BETWEEN_MS` (450L) → khoảng lặng ~630ms (450 + ~180 delay).
- **mediaId title PHẢI khác câu nội dung**: `${collection.audioPrefix}${chapIdx}_t${sentIdx}_${jumpSeq}`.
  Nếu trùng → `onMediaItemTransition` xóa nhầm id → `awaitAudioDone` thoát sớm → karaoke lệch pha.
- Không ghi `BookProgress` cho item title (`if (!item.isTitle)` quanh upsert).
- Bật announce khi: đầu lượt đọc (anchor Yes) / auto-advance sang chương mới / nhảy sang chương khác.
  **Tắt** khi đọc tiếp sau pause (cờ `suppressTitleOnStart`, đặt ở nhánh resume `startPlayFlow`)
  và khi nhảy câu **cùng** chương (`jc == chapIdx`).
- Bỏ qua: sách ≤1 chương, title rỗng hoặc >200 ký tự, nhãn chế tạo `Đoạn N`,
  title đã trùng/prefix với câu đầu (EPUB h1).

Sửa:
- `ReaderScreen.kt`: `ReaderTtsItem` (Pcm + Direct) thêm `isTitle`; local `suspend fun emitChunk(...)`
  trong producer (1 đường synth/cache duy nhất cho title lẫn câu, log
  `speak title|sent c$chapI s$sentI ruler=.. lang=.. text=..`); block announce title
  (`if (announceTitle) { ... emitChunk(..., isTitle=true, seqAtStart); continue }`); nhánh
  jump/auto-advance cập nhật `announceTitle`; consumer: `mid` tính 1 lần trước `when`
  (kiểu `_t` cho title) + bọc upsert progress bằng `if (!item.isTitle)`.
- `ReaderHeadings.kt`: `syntheticParagraphTitle` regex, `MAX_TITLE_ANNOUNCE_CHARS=200`,
  `normalizeTitleForCompare()`, `shouldAnnounceChapterTitle(title, chapterCount, firstSentence)`.
- `ReaderHeadingsTest.kt` (mới, 9 test): 1 chương / title rỗng / >200 / `Đoạn N` / trùng câu đầu /
  `##` / `Chương X` / `Part One` / h2 EPUB.

### 1.2 Fix bug CÓ SẴN lộ ra khi verify: `speakOffline` treo 90s sau `stopAll()`
- Triệu chứng: nhảy chương giữa lúc đang đọc → `AudioCtrl stopAll` → **81–90s im lặng** →
  mới thấy `speakOffline done ok=false` rồi đọc tiếp. (Pause/Back chịu chung vì cùng `stopAll()`.)
- Nguyên nhân: `OfflineTtsHelper.speakOffline()` chờ `onDone/onError` của utterance dưới
  `withTimeoutOrNull(90_000L)`; engine Android **không** gọi callback cho utterance bị
  `tts.stop()` giữa chừng → coroutine chờ hết 90s (đo 20:18:56.331 → 20:20:26.794 = 90.46s).
- Fix (`OfflineTtsHelper.kt`): field `pendingSpeakStop: AtomicReference<(() -> Unit)?>` —
  `speakOffline` đăng ký callback `safeResume(false)` trong lúc chờ (xóa trong `finally`,
  vẫn nằm trong `speakMutex` nên không xóa nhầm lần speak kế); `stop()` =
  `pendingSpeakStop.getAndSet(null)?.invoke()` **rồi** `tts?.stop()`.
- Đo lại: `speakOffline done ok=false` **28–135ms** sau `stopAll` (nhảy chương 135ms,
  pause 28ms). Không unit test được (android.speech.tts) → verify máy ảo.

### 1.3 G3.3 — verify máy ảo (sách EN `How it Works by Archibald Williams.epub`, 7 chương, engine offline, 0 FATAL)

| Nhánh | Bằng chứng logcat |
|---|---|
| Đầu lượt đọc (anchor → Có) | `20:44:54.599 speak title c2 s78 text=Chapter V.` → `20:44:54.949 speakOffline start mediaId=book_2_t78_1` (gap ~0,7s) → done → `book_2_78_1` |
| Nhảy TOC sang chương khác khi READ (B3.3 → Có) | `20:50:16.870 stopAll` → `20:50:17.005 done ok=false` (**135ms**) → `20:50:18.287 speak title c3 s0 text=Chapter XI.` → `20:50:19.821 start mediaId=book_3_t0_2` (title ~2,3s = pause) → `book_3_0_2` |
| Auto-advance (`chapIdx++`) | chương Cover rỗng → `20:52:54.113 speak title c1 s0 text=HOW IT WORKS` + `book_1_t0_3` — cùng nhánh code với đọc hết chương (dòng `announceTitle = true` + `delay(450)`) |
| Pause → resume | `20:55:31 stopAll` → `done ok=false` (28ms); resume `20:55:37.499 stopAll` → `speak sent c1 s24 ... mediaId=book_1_24_4` — **KHÔNG** có `speak title` (cờ suppress) |
| Nhảy câu CÙNG chương (Search → `goHit` → `jumpNow`) | `21:00:32.212 stopAll` → `21:00:33.004 speak sent c1 s56 mediaId=book_1_56_5` — **KHÔNG** có `speak title` (`jc == chapIdx`) |

`adb logcat` filter `FATAL EXCEPTION` = **0** trong toàn phiên; `install -r` Success.

### 1.4 G2.2 — ruler v4: viết tắt đọc RA TỪ + vỏ ngoặc ngoài → phẩy (2026-10-20, phần "CÒN LẠI" của G2.2)

Thêm **12 rule** (priority 33–36, scope `[tts]` → display giữ nguyên), `version: 3 → 4`, tổng **29 rules**:
- `abbrev.{eg,ie,vs,etc}.{vi,en}` (8 rule, p33): `e.g.`→`ví dụ`/`for example`, `i.e.`→`nghĩa là`/
  `that is`, `vs.`→`so với`/`versus`, `etc.`→`vân vân`/`and so on` — chạy **TRƯỚC** `abbrevDotRegex`
  (bước 5 `cleanForTts`) để engine không nghe "e g"/"e t c".
- `paren.comma` (p34): `(\s*)\(([^()]{1,200}?)\)(\s*[.,!?;:]?)` → `, {2}{3}`
  (nuốt space trước ngoặc → phẩy dính từ; giữ nguyên dấu câu ngay sau ngoặc).
- `paren.dup` (p35): `,` + (`\s*` + `,`)+ → `,` (nén phẩy đôi do ngoặc liền nhau sinh ra).
- `paren.leftover` (p35): `[()]` → `''` (lồng nhau / nội dung >200 ký tự chưa kịp thành phẩy
  → bỏ ký tự, GIỮ nội dung). `paren.dup` (id `d`) chạy trước `paren.leftover` (id `l`) khi cùng priority.
- `paren.start` (p36): `^[\s,]+` → `''` (phẩy/khoảng trắng đầu câu sinh ra từ ngoặc ở đầu).

JSON: sinh tại `tools/rules/reading-rules.json` + mirror `internal/rules/` + **copy tay**
sang `android/app/src/main/assets/reading-rules.json` (sha256 `f824bf137547…`). `.prep` không đổi
→ **KHÔNG** bump `PREP_VERSION` (vẫn 4, vì cleanForTts chạy speak-time).

Test cập nhật cả 3 engine: `ReadingRulesTest` count 17→29 + parity paren/abbrev;
`TextRulesTest` sửa `figureRefs_viEn` (ngoặc đã thành phẩy) + 2 test mới
(`g22_abbrevReadAsWords`, `g22_outerParensBecomeCommas` — cover `()`, lồng nhau, display không đổi);
`rules_test.go` count 29 + case vi/en mới.

Verify máy ảo (search "molecular movement" → đọc tiếp trong chương 2):
gốc `A form of injector very commonly used is Giffard's (Fig. 16).` →
logcat `BibotDbg: speak sent c1 s277 ruler=true lang=en text=A form of injector very
commonly used is Giffard's, figure 16.` (ngoặc + `Fig.` → phẩy + `figure`), **0 FATAL**.

Quan sát KHÔNG phải lỗi G2.2: `TextCleaner.kt:259` đổi em dash → phẩy (bước 10, pre-existing)
nên gốc `valves"—that is` nghe thành `valves",that is` (EPUB dùng `—` không cách).

## 1.5 G1.3 — verify ma trận xoay màn hình (2026-10-20, KHÔNG sửa code)

| Nhánh xoay (landscape↔portrait) | Bằng chứng |
|---|---|
| **READ** (đang đọc) | `BibotDbg` liên tục s311 (land) → s314–s316 (portrait) → s327–s344; KHÔNG `speak title` mới; 0 FATAL |
| **IDLE** (tạm dừng) | UI re-layout đủ 2 chiều, 0 FATAL |
| **BROWSE** (thư viện EN Books) | list re-layout đủ 2 chiều, 0 FATAL |
| **Giữa dialog anchor** (`Read from here? / C2 câu 317`) | dialog sống sót cả 2 chiều; `Yes` sau đó → `speak title c1 s316` + `speak sent c1 s317` (đọc tiếp đúng câu), 0 FATAL |

Tổng ~30 lần xoay; `adb logcat` filter `FATAL EXCEPTION` = **0**.
`dumpsys meminfo com.bibot.app` (trước/sau 24 lần xoay, có GC tự nhiên):
- `Objects`: `Activities=1`, `ViewRootImpl=1`, `AppContexts=6` — **không đổi**.
- TOTAL PSS 211→298MB **dao động** (271–298MB), Java heap 25→40→52MB — **KHÔNG** leo đơn điệu.
- `ps -A -T | grep -iE 'exo|codec'` = **0** trước & sau (path offline TTS không dựng ExoPlayer)
  → **không leak ExoPlayer**.

Quan sát phụ: `Views 112→178` / `WebViews 19→30` tăng theo đợt lúc đổi cấu hình/đợi ad nhưng
đứng yên khi đọc liên tục 150s (176–178 / 30) → nghi ad-SDK, không phải ExoPlayer.
Quan sát khác: `45°`, `1⁄500` (U+2044 fraction slash) được TTS đọc nguyên ký hiệu — pre-existing.
Mẹo thao tác: xoay bằng `adb shell settings put system accelerometer_rotation 0` rồi
`user_rotation 0|1` (đặt lại `0` sau mỗi chuỗi); dialog anchor mở bằng cách bấm pill overlay
`Read` trong reader (`ReaderScreen.kt:1863` `anchorConfirm`); dump UI helper:
`C:\Users\Thanh Marine\AppData\Local\Temp\opencode\dump.ps1`.

## 1.6 G1.2 — hoist position sang `ReaderViewModel` + `SavedStateHandle` (2026-10-21, phạm vi MINIMAL)

Quyết định (chốt qua question với người dùng): **chỉ hoist tuple position** —
`prepSession`/`jumpSeq`/producer job/`LaunchedEffect(isReading)` (pipeline TTS) **giữ nguyên
ở Compose**. Full hoist ≈ 1000–1100 dòng `ReaderScreen.kt`, rủi ro cao → từ chối.

Sửa/thêm:
- **Mới** `feature/reader/ReaderViewModel.kt`: `class ReaderViewModel(handle: SavedStateHandle)`,
  delegate `bookId` (String?), `chapter`/`sentence` (Int) đọc+ghi trực tiếp handle;
  `onPosition(...)` ghi state + handle; `clear()` chỉ set `bookId = null`.
  Constructor `(SavedStateHandle)`-only → factory mặc định của ComponentActivity tự tạo
  (repo trước đây chưa từng dùng SavedStateHandle/DI).
- `ReaderScreen.kt` 4 chỗ: (a) `val readerVm = viewModel<ReaderViewModel>(key = "reader_${collection.name}")`
  cạnh `exportVm` — key theo collection, VM sống qua Activity recreate; (b) 2 effect sau
  `ReaderHandoff.consume()`: **restore** (`loadBook(keep, readerVm.chapter, readerVm.sentence)`
  khi bookId còn tồn tại trong library; skip khi `selectedBook != null`/`books` rỗng)
  + **mirror** (ghi `(selectedBook?.id, currentChapter, currentSentence)` xuống VM);
  (c)+(d) `readerVm.clear()` ở 2 nhánh Back về shelf (callback + FAB) → không ghost reopen.
- **Mới** `ReaderViewModelTest.kt` 4 test: recreate round-trip qua cùng handle, fresh = null,
  `clear()` xóa, ghi đè. JVM tổng **84/84** (80 + 4).

Verify máy ảo (sách EN `How it Works` / `The Battle for Khe Sanh`, 0 FATAL cả 3):
| Test | Cách làm | Kết quả |
|---|---|---|
| **A** normal | vào sách → Back về shelf → Back về Home → mở app → EN Books | hiện **shelf 9 sách**, KHÔNG tự mở lại |
| **B** Activity recreate | sách đang mở (ch.2/7) → `settings put system font_scale 1.15` (configChanges **không** cover fontScale → Activity chết, `configDiffForRecreate={CONFIG_FONT_SCALE}`) | Home → EN Books → **tự mở đúng sách ch.2/7** |
| **C** process death | Home → `am kill` (pid chết hẳn, xác nhận bằng `pidof` rỗng) → `am start` → EN Books | **tự mở đúng sách ch.2/7** (SavedStateHandle) |

**Bài học thao tác:** dump UI ngay sau `am start` cold-start có thể trả về **snapshot window
cũ của task** (hiển thị đúng reader trước đó dù process đã chết) → chờ ≥25s (cold start đo được
`Displayed … +21s486ms`) rồi dump lại mới là UI thật (Home).

## 1.7 H3 — OTA rulepack + CI gate (2026-10-21)

Thiết kế (đã chốt với người dùng): **GitHub raw URL cứng**
`https://raw.githubusercontent.com/ThanhMarine/Bibot/main/tools/rules/reading-rules.json`
(repo `ThanhMarine/Bibot` PUBLIC nhưng chưa push code → 404 thật, app fallback nhúng) +
**CI "làm cả hai"** (gate cục bộ + GitHub Actions).

Sửa/thêm:
- **Mới** `core/text/RulesOta.kt`: `SOURCE_URL` (const), `MAX_BYTES=64KB`, `diskFile`
  (`filesDir/reading-rules.json`), `versionOf`/`isValidPack` (checksum + version int),
  `pickPack` (chỉ nhận nếu version **lớn hơn** pack đang dùng → chặn downgrade),
  `canonicalJson` (sort_keys đệ quy + compact), `sha256Hex`, `sync()` (đọc disk → nếu
  version cao hơn pack RAM thì `ReadingRules.reload`), `download()` (URL→bytes→parse→
  verify→ghi file, dbg `Log.d("BibotDbg", …)` gated `BuildConfig.DEBUG`).
- `ReadingRules.kt`: thêm `loadedVersion` (version pack đang nạp) + `load()` **disk-first**
  (file OTA checksum đúng + version ≥ assets → dùng; hỏng/cũ hơn → **xoá file**, dùng nhúng).
- `BibotApp.kt`: sau `ReadingRules.init` → `appScope.launch { RulesOta.sync() }` (nền, 404
  không crash).
- **Mới** `android/app/src/debug/AndroidManifest.xml`: `usesCleartextTraffic=true` (debug-only)
  để verify nội bộ qua `10.0.2.2`; release manifest không đổi.
- **Mới** `.github/workflows/rules.yml`: job `ruler` (`compile_rules.py` + `cmp` drift-check
  3 bản JSON tools/internal/assets) + job `go` (`go vet` + `go test ./internal/... ./cmd/...`),
  trigger paths `tools/rules/**`.
- `RULES_AUTHORING.md`: §0 "Gate BẮT BUỘC trước khi commit" (4 bước) + §5 "Publish qua OTA"
  (push JSON lên main = phát hành; rule hiệu lực lần mở app sau; drop_line/start_marker/
  split_guard cần prep mới).
- **Mới** `RulesOtaTest.kt` 4 test: (1) cross-check checksum với JSON thật sinh bằng
  `json.dumps(..., ensure_ascii=False, sort_keys=True)` (canonical Python); (2) gate version
  + checksum sai → reject; (3) canonical sort_keys đệ quy + escape; (4) vector sha256 chuẩn.

Verify máy ảo H3.3 (temp URL patch `http://10.0.2.2:8123` + server local python + v5 temp
rule `temp.degree.en` `(\d+…)\s*°` → `{1} degrees`, ruler 105/105 — KHÔNG pollute repo;
APK chính thức URL GitHub = 404 fallback ✓):

| Kịch bản | Kết quả |
|---|---|
| a) server v4 | `rules ota: v4 <= v4 — không đổi` |
| b) server v5 | `nhận v5 (hiện v4)` → persist `filesDir`; hiệu lực lần mở sau |
| c) relaunch | `v5 <= v5 — không đổi` (disk v5 = pack đang dùng) |
| d) TTS thật | anchor câu `In Fig. 20 … 45° …` (s315) → Yes → `speak … text=In figure 20 each crank is at **45 degrees** with the horizontal…` — **rule OTA đọc thay ký hiệu, không cài lại APK** |
| e) checksum sai | `pack sai checksum/rules — bỏ, giữ bản nhúng` |
| f) downgrade v4 | `v4 <= v5 — không đổi` |

Dọn sạch sau verify: revert `SOURCE_URL` về GitHub raw, kill server local, `rm files/reading-rules.json`
trên máy, cài lại APK chính thức → 404 fallback + 0 FATAL. **Bài học thao tác H3.3:**
- kapt Java stub xử lý `\u` trong comment KDoc → KHÔNG viết `\u00xx` (không phải hex) trong KDoc Kotlin;
  Kotlin expression-body function KHÔNG được chứa `return` (dùng block body).
- Canonical JSON Python = `json.dumps(..., ensure_ascii=False, sort_keys=True)` KHÔNG compact:
  ngăn cách `", "` / `": "`, sort_keys TOÀN BỘ map đệ quy, escape `"\/\b\f\n\r\t`, còn lại
  <0x20 → `\u00xx` hex thường — Kotlin phải khớp từng byte (test cross-check JSON thật).
- `adb shell input text` KHÔNG hỗ trợ unicode (`°` → NPE) → search trong sách không gõ được
  unicode; workaround: pull EPUB → grep `45°` (How it Works.epub 7.89MB) rồi search ASCII
  một phần câu ("pistons are able").
- Tap câu khi overlay (resume chip / hint banner) đè lên → tap "nuốt" không đổi anchor →
  swipe nội dung xuống vùng trống rồi tap lại (bounds câu sẽ đầy đủ multi-line).

## 1.8 E2.1 + E2.2 — PDF text-layer theo trang + toggle TEXT↔PAGE (2026-10-21)

E2.1 (anchor PAGE chính xác, hết map tỉ lệ "khoảng X%"):
- `BlockTextReader.kt`: data class `PdfPageText(page, text)`; `iterPdfTextPages(file):
  Sequence<PdfPageText>` — `iterPdfText` = wrapper (`map { it.text }`) nên prep/`PREP_VERSION`
  GIỮ NGUYÊN; page-tree `buildContentPageMap(raw)` (Catalog→Pages→Kids đệ quy chống cycle,
  fallback thứ tự obj number; trang không `/Contents` vẫn chiếm index; không dict trang →
  page=-1); `iterPdfInflated` gán page qua binary-search objNum (obj heads).
- **Mới** `core/text/PdfPageMap.kt`: MAGIC `PGMP`/VER1/SIG48/`MAX_PAGES` 100k; `normalize`
  (letterOrDigit+lowercase); `matchPageStarts(pages, sentences)` — sig 48 ký tự đầu trang
  tìm bằng `indexOf` trên **CONCAT** các câu đã normalize (cursor单调 tăng dần, trang trắng
  giữ cursor, concat >16MB → IntArray rỗng = fallback tỉ lệ); cache `<key>.pagemap` cạnh
  `.prep` (`load`/`save` invalidate khi srcSize/mtime đổi, **big-endian** — RandomAccessFile).
- `ReaderScreen.kt`: state `pdfPageStarts`; load flow (PDF chữ) = `load(...) ?: build(file,
  session.sentences(0), prepDir, cacheKey)`; `resolvePageSentence(page)` (map nếu có, fallback
  tỉ lệ cũ); `anchorText` PAGE → `"P4 • câu 10"` (bỏ "khoảng %") khi map có.
- Test mới 14: `BlockTextReaderPdfPagesTest` 6 (PDF fixture tự chế uncompressed: thứ tự page
  tree, parity với iterPdfText, page-dict không tree → fallback obj, không dict → -1, contents
  array, trang không Contents vẫn tính index) + `PdfPageMapTest` 8 (normalize, exact, mid-
  sentence, blank giữ cursor, monotonic, empty, cache roundtrip+invalidate, build fixture thật).

E2.2 (toggle TEXT cho PDF chữ + khóa PAGE): `viewLockedPage = (isPdf||isEpub) && scanned`
+ popup `⚙` `pageModeEnabled = (isPdfBook||isEpubBook) && !viewLockedPage`; **mở rộng
`scanned`** = `isScannedPdf` (Type3) **hoặc** PDF không trích được chữ — prep fallback đúng
1 chương rỗng (`session.titles.size == 1 && session.sentences(0).isEmpty()`) → khóa PAGE +
`scanNoTts`.

Verify máy ảo (emulator `BibotTest`/`emulator-5554`, 0 FATAL):
- Fixture `marker10.pdf` 10 trang × 3 câu (sinh bằng `make_marker_pdf.py`, xref hợp lệ) push
  vào `files/bibot/pdfs/inbox/` → EN Books → PAGE `Page 1 of 10`; `.pagemap` build 10 entry
  `starts = 0,3,6,9,…,27`; swipe tới Page 4 → Read → dialog **`P4 • câu 10`** → Yes → log
  `speak … s9 text=MARKERPAGE04 opens this page with confidence.` → s10, s11 (hết trang) →
  s12 = trang 5 (đúng tuần tự trang; bản tỉ lệ cũ sẽ ra s10 = lệch 1 câu).
- TEXT↔PAGE 2 chiều trên marker10: ⚙ → `Text view` = karaoke `Chapter 1/1` + TTS đọc tiếp
  (s23→s26), ⚙ → PAGE ngược lại.
- Fixture `scan2.pdf` (2 trang, **0 text ops**, xref hợp lệ — sinh tay 5 obj đúng thứ tự):
  mở → mặc định **PAGE `Page 1 of 2`**, icon top đổi nhãn `Text view` (pageMode=false =
  locked), popup row `Text view` **disabled** (bấm không đổi view, popup không dismiss) →
  khóa PAGE hoạt động. Chưa verify lại fixture Type3 thật đợt này (heuristic giữ nguyên).

Bài học E2.1 (đã fix trong code):
- **sig 48 ký tự vượt ranh giới câu** → `contains` trên từng câu = 0 match (starts toàn 0 →
  "câu 1" mọi trang) → phải match trên CONCAT chuỗi + mảng offset → sentence index.
- `RandomAccessFile` ghi **big-endian** — Python decode `struct.unpack('>…')`, không `'<'`
  (decode `'<'` ra rác, dễ tưởng file hỏng).
- Fixture PDF tự sinh phải **xref đúng thứ tự obj 1..N** (sai → `PdfRenderer.open` fail →
  pageCount=0 → header "Chapter 1/1" giả, dễ hiểu nhầm viewMode). E2.1 hỏng đầu tiên do
  `contains`-từng-câu, KHÔNG phải do fixture marker10 (fixture đó đúng).

## 1.9 E2.3 (phần TOC outline) — TOC PDF từ bookmark /Outlines (2026-10-21)

- `BlockTextReader`: data class `PdfOutline(title, page)`; 6 regex outline
  (`/Type /Outlines`, `/First`, `/Next`, dest `(?:/Dest|/D) \[ N 0 R`, title
  literal/hex); `parsePdfOutline(file)` (raw ≤12MB, lỗi → rỗng) +
  `internal parsePdfOutlineRaw` (obj index → DFS-preorder stack LIFO đảo
  Next/First, `MAX_OUTLINE_ENTRIES=2000`) + `outlinePageOrder` (Catalog→Pages→Kids,
  fallback obj order — gồm trang không /Contents) + `pdfTitleOf` (literal:
  matchPdfString đếm depth ngoặc lồng + unescape `\n\t\(\)\ octal`; hex: BOM
  FEFF→UTF-16BE / FFFE→UTF-16LE, không BOM→ISO-8859-1).
- `ReaderScreen`: state `pdfOutline` (reset ở đầu load, parse nền `if (isPdf)`
  — kể cả sách scan, TOC nhảy theo TRANG vẫn hữu dụng); TOC sheet ưu tiên
  outline `size > 1` (ẩn `onSummarize*` — idx outline ≠ idx chương; mặc định
  params = null); `jumpToOutline` — PAGE: `ReadAnchor(PAGE,page,0)`, đang READ →
  `anchorConfirm` (hỏi kiểu long-press), còn lại `selAnchor + pageIndex` ngay;
  TEXT: `sent = resolvePageSentence(page)`, set `selAnchor = ReadAnchor(TEXT,0,sent)`
  + `requestJump(0, sent)` (chương 0 — PDF thường 1 chương; nhiều chương → caveat
  map chỉ build từ sentences(0)); `outlineTocCurrent` — PAGE: `pageIndex`, TEXT:
  duyệt `pdfPageStarts` tìm trang chứa `currentSentence`.
- **Bug fix lộ ra khi verify:** `startPlayFlow` lấy `selAnchor` bất chấp view →
  neo PAGE cũ (`P4 • câu 10`) thành anchor khi đang TEXT. Sửa:
  `selAnchor?.takeIf { it.view == viewMode } ?: neo tại vị trí hiện view`.
- Test mới `PdfOutlineTest` 8/8 (DFS preorder, escape literal + octal, hex
  UTF-16BE "Chương 1", `/A /GoTo`, không outline, named-dest/ghost-ref → -1,
  thiếu `/First`, thiếu title → skip) → JVM **111/111, 16 suite**.

VERIFY máy ảo (marker10.pdf sinh lại: 34 obj — đánh số lại font=95/root=80/
items 81–90 TRÁNH đè page 3–22/content 4–22; 10 outline "Marker section N"; 0 FATAL):
| Kịch bản | Kết quả |
|---|---|
| Contents (PAGE) | sheet đủ **10 mục** `1..10. Marker section N` (outline thay chương) |
| Chọn #4 | sheet đóng → header **`Page 4 of 10`** |
| ⚙→TEXT → Contents | outline vẫn hiện (mọi PDF) |
| Chọn #6 (TEXT) | dialog Read **`C1 • câu 16 - marker10`** (starts[5]=15) → Yes → `speak c0 s15 text=MARKERPAGE06 opens…` rồi s16, s17 — đúng trang 6 câu 1 |
| Đọc đang READ + nhảy outline PAGE | (code) `anchorConfirm` hỏi trước — cùng đường với long-press |

**Ràng buộc E2.3 còn lại (cần chốt):** `PdfRenderer` (framework) KHÔNG expose
glyph/rect text → selection/copy + search-highlight TRÊN BITMAP PAGE không làm
được bằng API hệ thống; phương án: (a) panel text-layer (copy/search trên text
đã trích, nhảy trang có match), (b) wire lib đọc glyph (MuPDF-GPL — trước đây
loại có chủ ý), (c) bỏ 2 mục này. Reflow button + crop margin thì khả thi.
**(→ ĐÃ CHỐT: phương án (a) panel text-layer — xong + verify, xem §1.10.)**

## 1.10 E2.3 (panel text-layer) — chọn/copy/search + nhảy canvas PAGE (2026-10-21)

Phương án chốt = (a) **panel text-layer** (0 lib thêm; `PdfRenderer` không expose glyph
nên highlight trên bitmap là bất khả thi — nhảy TRANG có khớp thay vì vẽ khung).

- Mới `core/text/PdfTextSearch.kt`: `PdfTextHit(page, snippet)` + `search(pages, query,
  maxHits=50, snippetChars=90)` — lowercase, query ≥2 ký tự, **1 hit/trang đầu**,
  snippet ± ngữ cảnh + `…`, collapse whitespace.
- Mới `feature/reader/PdfTextPanel.kt`: `PdfTextPanelSheet` (ModalBottomSheet): header
  `chrome_text_panel` + `chrome_page_of` **sống theo trang** + Close; OutlinedTextField;
  query ≥2 → LazyColumn hit `TextButton` ("N. snippet") → `onJumpPage` (**sheet GIỮ MỞ**);
  rỗng/`chrome_text_panel_none`/loading; mặc định `SelectionContainer { Text(pageText) }`
  → copy/share hệ thống. Strings mới `chrome_text_panel*` (en+vi) sau `chrome_page_of`.
- `ReaderChrome.ReaderTopBar` thêm `onTextPanel` + icon `Icons.Default.Description`
  (chỉ PDF chữ + PAGE; portrait — landscape CompactBar không thêm).
- `ReaderScreen`: states `pdfPanelOpen/pdfPanelQuery/pdfPageTexts/pdfJumpTarget/pageJumpTick`
  (reset cạnh `pdfPageStarts = null` khi đổi sách); trích nền `BlockTextReader.iterPdfTextPages`
  1 lần/sách → `List<String>` theo maxPage; refactor **`jumpToPage(pageRaw)`** — PAGE BROWSE:
  anchor+pageIndex+`pdfJumpTarget+tick` ngay, READ: `anchorConfirm` (tick khi xác nhận enterRead),
  TEXT: `resolvePageSentence`+`requestJump`; `jumpToOutline = jumpToPage`; mới `sentenceToPage`
  (đảo `pdfPageStarts`, fallback tỉ lệ) + `outlineTocCurrent` dùng lại; `goHit` PAGE-aware.
- `PdfPageView`: param `jumpPage/jumpTick` → `LaunchedEffect` scroll canvas tới trang đích.

**Bug root-cause buộc phải fix khi verify (canvas land SAI index rồi đứng yên):**
placeholder ô chờ render là `Text` thấp sẵn → `scrollToItem(t)` đo item theo layout
**chưa có bitmap** → cộng dồn chiều cao thiếu → land 0→**3** thay vì 0→8, và khi bitmap
render lại bị anchor giữ ở 3 (log chẩn đoán: `scrolled to 3`, `late first=3`, item
1392px vs placeholder ≈200px — số khớp chính xác). Fix:
1. Precompute **tỉ lệ khung hình MỌI trang** — `r.openPage(i)` → `page.width/height`
   (`getSize`, KHÔNG render) vào `pageRatios`, khóa `renderMutex` **từng trang** để
   `renderPage` đang chờ được xen lượt (PdfRenderer: 1 Page mở tại 1 thời điểm).
2. Placeholder + ô lỗi bọc `Box(...aspectRatio(pageRatios[idx] ?: 0.707f))` = cùng
   tỉ lệ bitmap (`renderPdfBitmap` giữ nguyên w/h, kể cả cap 2400 hay half-width retry)
   → chiều cao KHÔNG đổi khi render → anchor ổn định.
3. Effect chờ `pageRatios.size == pageCount` (timeout 4s) rồi mới `scrollToItem`;
   nuốt exception (nhảy hỏng = đứng yên như trước, không crash).

**Bug 2:** `goHit` (book search) PAGE chỉ set `pageIndex` — thiếu `pdfJumpTarget/pageJumpTick`
→ header đổi mà canvas đứng yên (cùng pattern). **Bug 3:** mũi tên search
`hasPrevNext = hitCount > 1` → search ra 1 kết quả **không nhảy được** (arrow disabled)
→ đổi `> 0` (goHit wrap về hit duy nhất = nhảy TỚI hit, cùng trang thì nhảy lại = vô hại).

- Temp log `Log.d("E23", ...)` (jump/goHit/scroll/visible) chỉ để chẩn đoán — **đã xóa hết**
  trước commit; cuối cùng chỉ còn logic + comment tiếng Việt.

Test mới `PdfTextSearchTest` 7/7 → JVM **118/118, 17 suite**. Gates: rules **102/102**,
`go vet`+`go test` ok, `assembleDemoDebug` + `assembleCommercialDebug` BUILD SUCCESSFUL.

VERIFY máy ảo (screenshot + log; **0 FATAL**):
| Kịch bản | Kết quả |
|---|---|
| icon top bar | marker10 (PDF chữ) có **Page text**; grimm10 (scan → `viewLockedPage`) ẩn ✓ |
| panel mở | header `Page text — Page 9` sống; text trang đúng |
| panel search `marker-page-02` | hit `2. MARKER-PAGE-02…` → bấm → **canvas MARKER-PAGE-02** + chip `P2 • câu 4`, sheet GIỮ MỞ, header đổi `Page 2 of 10` |
| TOC #9 | canvas **MARKER-PAGE-09** + header `Page 9 of 10` + chip `P9 • câu 25` (land 8/8 stable — trước land 3) |
| book search `marker-page-05` (1/1) ↓ | `goHit idx=12 → page=4` → canvas trang 5 + header `Page 5 of 10` + chip `P5 • câu 13` |
| long-press panel text | menu **Copy / Select all** + selection handles |
| resume chip F2.1 | vẫn `Page 1 of 10` = saved anchor — đúng design, KHÔNG phải stale |

Bài học verify (đã ghi vào `§3`): `dump.ps1` cũ **dùng lại `/sdcard/ui.xml` cũ khi
`uiautomator dump` fail** → trạng thái "giả" (reader thật đã về home, tap trúng card
Storytelling) — giờ `rm -f` trước dump, báo `DUMP_FAILED`/`PULL_FAILED` + luôn
fresh-dump trước MỖI tap quyết định; ad banner/Chrome mở do tap trúng ad → đọc dump
trước khi bấm tiếp.

## 1.11 E2.4 (display modes) - fit/paging/theme/rotation + fix `‹`/`›` (2026-10-21)
- **Code:** `PdfDisplayMode.kt` (mới, logic thuần + test JVM `PdfDisplayModeTest` 11/11):
  cycle `nextFitMode/nextPagingMode/nextPageTheme/nextRotation`, `effPageRatio`,
  `fitPageSize`, `sourceSizeForRotation`, `displaySizeFor`; `PdfPageView.kt` viết lại
  (params `fitMode/pagingMode/pageTheme/pageRotation`, invalidate khi đổi xoay, jump
  dual-aware + `pendingJumpReport`, visible-effect dual `{−1,+1,+2}`, `PagePane` local
  với themeFilter `ColorMatrix`, snap `rememberSnapFlingBehavior` khi single, render
  `Matrix.postRotate`); `ReaderChrome.kt` popup ⚙ + section display (4 hàng cycle,
  không tự đóng, gated `isPdfBook && !viewLockedPage && viewMode==PAGE`); `ReaderScreen.kt`
  **fix nút `‹`/`›` = TRANG ±1** (trước luôn chương ±1 → header không đổi), 2 call site
  đủ args; `BibotPreferences.kt` +4 key persist; strings en/vi +12.
- **3 bug race đã fix khi verify trên emulator** (quan trọng cho phiên sau):
  1. Bump jump trong `onPagingChange` chạy với pagingMode CŨ → canvas lệch;
  2. Visible effect re-key đọc lại `firstVisible` theo ghép MỚI TRƯỚC khi align scroll
     → `pageIndex` clobbered → jumpTarget sai;
  3. Skip-report consume `pendingJumpReport` dù không report → vis run sau report
     `v=trang trái cặp` → header nhảy Page1 sau khi single→dual.
  **Cơ chế chốt:** `pagingAlignTarget/Tick` set trong `onPagingChange` (pageIndex cũ
  còn đúng) → `LaunchedEffect(pagingAlignTick, pagingMode)` trong PdfPageView chạy 2
  lượt (mode cũ no-op, mode mới scroll + pending); vis chỉ consume pending KHI report
  thật (`wantRep != lastReportedPage`).
- **Verify emulator (marker10, rot=270, 0 FATAL):** cycle 4 hàng popup không tự đóng;
  fit height↔width; paging continuous→single(snap 1 trang/lượt vuốt, header auto)
  →dual (2 cột, `‹`/`›` header đúng trang lẻ) và **ghim vị trí cả 2 chiều**
  (dual page2→single page2+header Page2→dual cặp(1,2)+header Page2); theme
  sepia→night (screenshot trang tối chữ sáng); xoay 0/90/180/270 persist DataStore;
  cold-start nhận đủ params (`rot=270 fit=… paging=… theme=…`).
- **Bài học verify:** log tạm `E24` (đã xóa trước commit) cần thiết để thấy race —
  "header sai" lần đầu bị tưởng tap trượt; tap (540,700) dual trúng GAP 2 cột → không
  toggle chrome (không phải bug); `uiautomator dump` fail lần 1 sau install → retry.

## 2. Gate (chạy lại sau E2)

- `python tools/rules/compile_rules.py` → **102/102 (29 rules)**, sha256 `f824bf137547…`
  (lúc G3.2/G3.3: 64/64 — 17 rules, sha `f1b3414a6abb…`).
- `go vet ./internal/... ./cmd/...` → exit 0; `go test ./internal/... ./cmd/...` → **ok** (chunker/rules/tts).
  - Lưu ý: `go test ./...` **FAIL** ở `bibot/core/OpenReadEra/libwebp/swig` (missing function body —
    pre-existing, swig/C GO) → gate đúng là `./internal/... ./cmd/...`.
- JVM `:app:testDemoDebugUnitTest` → **129/129, 0 fail** (mới ở E2.4:
  `PdfDisplayModeTest 11/11` → 129; trước đó 118/118 — E2.3 panel `PdfTextSearchTest 7/7`
  + TOC `PdfOutlineTest 8/8` → 118; E2.1 `BlockTextReaderPdfPagesTest 6/6` +
  `PdfPageMapTest 8/8` → 102; H3 `RulesOtaTest 4/4` → 88; G1.2 `ReaderViewModelTest 4/4`).
- `:app:assembleDemoDebug` + `:app:assembleCommercialDebug` → BUILD SUCCESSFUL
  (`demo/debug/Bibot.apk`, `commercial/debug/BibotV.apk`); `adb install -r` Success.
- Build **PHẢI** qua `Start-Process -FilePath android\gradlew.bat -ArgumentList ... -WorkingDirectory
  H:\Pi4_Xiaozhi\android_app\android -NoNewWindow -Wait -RedirectStandardOutput ... -RedirectStandardError ...`
  (gọi trực tiếp bị kill child process); log tạm `C:\Users\Thanh Marine\AppData\Local\Temp\opencode\`.
- JDK17: `C:\Users\Thanh Marine\.gradle\gradle.properties` → `org.gradle.java.home=H:/androidkit/jdk17`.

## 3. Quan sát / việc lặt vặt lộ ra (chưa làm)
- **StrictMode `DiskReadViolation ~1833ms`** ngay sau khi nhảy chương (đọc `.prep` cho
  `session.sentences(chapIdx)`) — không fatal nhưng chặn frame; cân nhắc đưa sang IO.
- Ruler: title `Chapter XI.` được log **nguyên văn** (không thấy đổi thành số) — cần check
  rule `roman.single` có áp cho EN `Chapter X` hay không (D4 mới cover `Chương X`).
- `Views/WebViews` (ad-SDK) tăng theo đợt khi xoay/đợi ad — thấy ở G1.3, KHÔNG phải ExoPlayer;
  nếu sau này dọn ads thì kiểm lại.
- `45°`, `1⁄500` (fraction slash U+2044) được đọc nguyên ký hiệu (G1.3) — pre-existing.
- Tương tác `adb shell input tap` hơi "nhạy lần đực lần cá" (nhiều lần bấm top-bar/TOC không ăn
  hoặc sheet chỉ đóng khi bấm ngoài) → luôn dump UI xác nhận trạng thái trước/sau mỗi tap;
  helper: `C:\Users\Thanh Marine\AppData\Local\Temp\opencode\dump.ps1`.
  **ĐÃ FIX (E2.3):** dump cũ `SilentlyContinue` + pull `/sdcard/ui.xml` → khi
  `uiautomator dump` fail (thường sau `adb install`) lấy nhầm xml CŨ → "lạc" trạng thái giả
  (reader thật đã về home, tap 928,419 trúng card Storytelling); giờ `rm -f` trước dump +
  báo `DUMP_FAILED`/`PULL_FAILED` — thấy báo vậy thì chờ 2s dump lại.
- Emulator `emulator-5554` đang chạy; adb = thêm `H:\androidkit\sdk\platform-tools` vào PATH.
- **[ĐÃ FIX ở E2.4]** Nút `‹`/`›` chuyển trang PAGE — trước luôn chương ±1 (header
  không đổi); giờ `‹`/`›` = TRANG ±1 (PDF PAGE mode, non-locked), verify dual + pending.
- Shelf EN Books không tự thấy file mới → bấm **Refresh** (180,390) rồi scroll; Storytelling
  shelf cached 18 story không refresh. Mới mở app cold-start thấy snapshot window cũ nếu dump
  <25s (đã ghi G1.2).
- `scanNoTts`/`viewLockedPage` set 1 lần lúc `loadBook` — đổi nội dung file (thay PDF trong
  inbox) phải xoá prep cache `cache/bibot/prep/<key>.*` rồi mở lại mới nhận lại.
- **Sinh fixture PDF tự chế: số obj PHẢI trải đều, không đè nhau** — marker10 từng bị
  font/outline ghi đè lên page+content (parser regex vẫn chịu được nên "chạy" tới E2.3 mới
  vỡ); hiện font=95, outline root=80, items 81–90, page 3–22, content 4–22 — sinh xong
  check: `d.count(b' 0 obj') == 34` + `/BaseFont` có mặt + 10 `/Type /Page /Parent`.

## 4. Nợ sang phiên sau
- **E2.3 còn lại: reflow-text button + crop margin `IsRectWhite`** (selection/copy/search/TOC
  đã XONG — panel text-layer, xem §1.10);
  **E2.4 còn lại: annotation/highlight persist theo `fileHash`** (display modes +
  fix `‹`/`›` đã XONG — §1.11); E2.5 EPUB rich,
  E2.6 verify 1200 trang (outline parse cap 12MB → PDF lớn không có TOC outline),
  I2.3/I2.4 (Play Integrity + verify relay thật), J1 (release mới),
  B5.8 nợ lặt vặt: server-side duyệt report/ẩn chương, test sách dày 500 chương,
  G2.4 nợ **verify tai nghe** (unit đã cover, chỉ còn nghe VI/EN 3 câu ngoặc).
- Việc gợi ý gần nhất (từ quan sát §3): kiểm `roman.single` cho EN + đưa
  `session.sentences()`/đọc `.prep` sang IO (bấm StrictMode).
- Lưu ý tree: `ui.xml` ở root là rác dump UI — **đã xóa + đưa vào `.gitignore`** (không commit).
- Lưu ý OTA: GitHub raw URL sẽ **404 thật** tới khi push repo `ThanhMarine/Bibot` lên
  main (app fallback nhúng, không lỗi) — khi push xong, JSON trên main = phát hành ngay.
