# Biên bản phiên làm việc 2026-10-20 — B5.8 Wiki book reader: wiring + rules live + contribute LLM E2E + 4 release builds (CHỐT PHIÊN)

> Đọc kèm `Checklist_Upgrade_Bibot.md` (B5.8 đã đánh dấu XONG, J3 đã cập nhật).
> Phiên trước: `SESSION_HANDOFF_2026-10-17.md`.
> **CHƯA COMMIT** (chờ lệnh user) — tree đang có 8 file sửa + 1 file mới.

## 1. Việc đã xong

### 1.1 Fix build B5.8 (4 lỗi còn nợ của phiên 10-17)
1. `FirestoreSync.kt`: đóng hàm `reportWikiChapterSummary` bằng `}` sau `catch`
   (thừa `}` → knock-on "unresolved reference" ở 3 chỗ sau) + **xoá `bumpWikiHits`
   trùng lặp** (bản ở ~dòng 240 giữ nguyên) + `observeCore` trả
   `Flow<Map<String,String>> = flowOf(emptyMap())` (đúng chữ ký Flow).
2. 2 lỗi còn lại trong biên bản cũ (`WikiBookScreen` delegates, `ReaderScreen Chapter(it)`)
   đã được sửa từ trước phiên này — tree compile sạch ngay lần chạy đầu.

### 1.2 Wiring B5.8 THẬT (user chọn "Wire B5.8 thật")
- **`ReaderHandoff.kt` (mới)**: object 1-giây `post/consume(book, chapterIdx, sentenceIdx)`
  — handoff Detail→Reader KHÔNG cần sửa ViewModel hiện có.
- **`BibotNavSuite.kt`**: state `detailBook`; `openDetail(book)` → `mode=ABOUT`;
  `openReader(book, chap, sent)` → `ReaderHandoff.post` + `switchMode` theo
  `book.collection` (story→STORY, cotich→COTICH, else BOOK); 3 chỗ
  `ReaderScreen(..., onOpenDetail = ::openDetail)`.
- **`ReaderScreen.kt`**: param `onOpenDetail`; `loadBook(book, forceChap=-1, forceSent=0)`
  — `forced` ghi đè progress + `savedResume/savedPos` theo vị trí mục tiêu; icon
  `Icons.Default.Info` (`contentDescription=nav_about`) trên card EN (2 `ReaderCard`)
  + story shelf card; `LaunchedEffect(Unit) { ReaderHandoff.consume()?.let { loadBook(...) } }`.
  - Vì `openReader` LUÔN đi từ mode ABOUT → `switchMode` luôn đổi state →
    `ReaderScreen` compose mới → `LaunchedEffect(Unit)` consume đúng 1 lần.
- **`BookDetailScreen.kt`**: nhận `book`/`progress`/`playerVm`/`onOpenReader`;
  empty state nếu book==null; nạp TOC bằng `BookPrepStore.open` → fallback `build` (IO);
  ĐỌC → `onOpenReader(b, resumePos.chap, resumePos.sent)`, "Đọc từ đầu" → `(b,0,0)`;
  **wiki thay hẳn detail** (fix bug 2 màn xếp chồng trong 1 Column); contribute:
  `SummaryRepository.summarizeChapter` → Ready: SummaryDialog + `uploadWikiBook` (chỉ khi
  share ON) + `wikiRefresh++` / NoCredit-NeedKey → Gate / watch-ad retry mirror reader
  (`AdManager.onRewardedClaimed`, `claimRewardedSummary(3)`); report dialog reason →
  `reportWikiChapterSummary` + Toast; `creditsLeft` lấy reactive từ `adsState.rewardedSummary`
  (KHÔNG gọi suspend `getRewardedSummary()` trong @Composable).
- **`WikiBookScreen.kt`**: viết lại — load `getWikiBook` **gated share-ON**
  (`LaunchedEffect(bookId, refreshKey, shareEnabled)`; OFF = bỏ fetch, không đọc wiki);
  union chương local ∪ wiki doc ∪ "Chương N+1"; banner `wiki_share_off`; nút
  "Contribute to wiki" ẩn khi OFF; per-chapter `enabled = !busy && shareEnabled`;
  spinner khi `busyIdx == idx`.
- **Strings mới** (EN `values/strings.xml` + VI `values-vi/strings.xml`):
  `wiki_subtitle`, `wiki_share_off`, `wiki_no_toc`, `wiki_has_summary`,
  `wiki_report_reason/_send/_done`, `book_detail_empty`, `book_detail_toc`.

### 1.3 Firestore rules + `putWikiBook` an toàn
- `firestore.rules`: match `/wiki_books/{bookId}` — public read; auth create/update
  `hasOnly` 12 keys + `hasAll` 4 keys (`chapterSummaries` map ≤500 entry,
  title ≤300, source ≤500, status/updatedAt/summarizedChapters typed); **no delete**.
  Subcollection `/reports/{reportId}`: auth read/create, no update/delete.
  - `title`/`source` check là **có điều kiện** (`!"title" in data || ...`) vì
    `putWikiChapterSummary` chỉ ghi 4 field (merge-create) — nếu bắt buộc title
    thì create kiểu đó bị deny.
- `FirestoreSync.putWikiBook`: đọc doc cũ → merge `chapterSummaries` (read-modify-write)
  → `ref.set(data, SetOptions.merge())` (trước đây `set()` full → xoá field lạ người khác).
  Bỏ key trùng lặp `"lang"`/`"source"` trong `mapOf`.
- **ĐÃ PUBLISH rules (user, 2026-10-20)** — verify REST sau publish: `wiki_books`
  create 200 / read-back 200 / reports `status=pending` / share-OFF 404.

### 1.3b KIỂM TRA RULES ĐANG PUBLISH (2026-10-20, bằng REST — không cần console)
Cách check: anonymous `accounts:signUp` với API key từ `local.properties`
(project **bibot-wiki**) → `POST .../documents:runQuery` với Bearer idToken:
- **`wiki_summaries` → HTTP 200, 3 doc còn nguyên** (model `gemini-3.5-flash-lite`,
  hits 1–2 — đúng wiki Khe Sanh 2 máy ngày 10-14) → **rules bản commit `32048a6`
  (10-14) ĐANG LIVE**, wiki per-chapter cũ (B5.6/B5.7) vẫn hoạt động bình thường.
- **`bibot/{ownUid}` → 404** (rules cho phép, doc trống — đúng),
  `bibot/{otherUid}` → **403** (auth-uid gate sống).
- ~~`wiki_books` → 403 → CHƯA publish~~ **ĐÃ PUBLISH (user, 20-10) + verify REST**:
  create 200 (12 keys), GET read-back 200, subcollection `reports` tạo doc
  `status=pending`, share-OFF read → 404 = allowed. Cả 4 API của rules sống.

### 1.4 Gate + verify máy ảo (BibotTest, demoDebug cài mới)
- Gate: `:app:compileDemoDebugKotlin` OK; `:app:testDemoDebugUnitTest` **69/69** (10 xml);
  ruler **64/64** (17 rules); `go vet` 0 + go tests PASS;
  `assembleDemoDebug` (**Bibot.apk 58.805.443B**) + `assembleCommercialDebug`
  (**BibotV.apk 60.032.456B**) PASS.
- **Verify UI thật (uiautomator dump làm chuẩn):**
  1. Shelf EN → icon Info grimm10.pdf → **Detail mới**: title, meta
     `EN • PDF • 0 MB`, **"Bookmark: Chương 1 Câu 1"** (đọc từ Room),
     "1 chapters" (`book_detail_toc`), spinner TOC tắt.
  2. **Read → reader mở đúng grimm10.pdf** (handoff consume hoạt động; PDF 301 trang render).
  3. Detail → Wiki sách → **WikiBookScreen**: `Community wiki summary`
     (subtitle EN mới), banner AI warning, "Building wiki: 0/1 chapters" + 0%,
     chương **"1. grimm10"** (TOC thật) + "Summarize this chapter".
  4. Contribute → **SummaryDialog** mở ("Chapter summary / grimm10 / This chapter has
     no text to summarize. / Summary turns left: 3 / Retry / Close") — grimm10.pdf
     chương 1 không có text extract (đúng nhánh `text.isBlank()` → `Error("empty")`).
  5. Share OFF (Settings → "Share my summaries to community wiki") → wiki hiện
     **"Sharing is off: you cannot view or contribute to the community wiki. Turn it on
     in Settings."** (`wiki_share_off`), nút contribute biến mất, per-chapter disable.
  6. **0 FATAL** trong logcat toàn phiên.
- Quay lại: share đã toggle về **ON** (giữ nguyên state máy ảo).

### 1.5 Cơ chế Summarize local + public (B5.8b) + E2E Firestore (chi tiết ở Checklist B5.8/B5.8b)
- **2 đường trigger đã có sẵn** cho mọi sách local lẫn pdrive (cả 3 kệ EN/Story/Cổ tích
  đều có icon Info → Detail): (1) Reader TOC → icon tóm tắt chương/cả sách
  (`fetchChapterSummary`/`startBookSummary`), (2) Detail → Wiki → contribute.
- **Vá 3 lỗ hổng sách CHƯA tải** (code mới, gate xanh 69/69 + 2 flavor):
  1. `BookDetailScreen`: Download icon **wire thật** — `repo.download` → set
     `b.localFile` + `prepKey++` (LaunchedEffect `(b.id, prepKey)` nạp lại prep/TOC),
     spinner khi tải, toast `book_download_done`.
  2. `contribute()`: prep==null → **Toast `book_not_downloaded`** (trước = return im lặng).
  3. `WikiBookScreen`: ẩn header-contribute khi `total==0` (trước bấm không làm gì).
- **Verify máy ảo (How it Works epub 7.7MB + Basic English Grammar md):**
  - Detail thật: bookmark "Chương 2 Câu 13", meta EN/EPUB/7MB, "7 chapters".
  - Chương 1 của 2 sách PDF/EPUB test **đã biết**: text rỗng (trang bìa) → nhánh
    `Error("empty")` đúng — dùng chương 2 test LLM.
  - **Rules live end-to-end**: REST create `wiki_books` HTTP 200 (12 keys đúng rules)
    → app đọc lại: "Building wiki: **1/7**, 14%" + badge "Has summary" + text từ cloud
    (local Room 0 row → 100% Firestore); **report** → `reports` doc `status=pending`.
  - **Download wire**: `run-as rm` file 18KB → Detail "Not downloaded" → bấm Download
    → file 19KB quay lại + "4 chapters" (TOC tự nạp lại).
  - Wiki sách chưa tải: "0/0 chapters" + `wiki_no_toc` + **không có** nút contribute.
  - **0 FATAL** toàn phiên.
- ~~BLOCKER: contribute LLM chờ key Gemini~~ **GIẢI QUYẾT — E2E XONG 2026-10-20**:
  - Key cũ sai format (56 ký tự, `AQ.Ab8… by`, KHÔNG phải `AIza…`) → 401
    `ACCESS_TOKEN_TYPE_UNSUPPORTED`; key mới user nhập (53 ký tự) verify
    `GET generativelanguage…/models?key=` → **HTTP 200**.
  - Bấm "Summarize this chapter" chương 3 (Chapter V.) → SummaryDialog **Content**:
    tóm tắt VI 654 ký tự + badge "AI summary, may be wrong" +
    **Summary turns left: 3→2**; log `BibotWiki lookup share=true … hit=false len=0`.
  - REST doc: `summarizedChapters=2/7`, keys=**1,2** (merge giữ chương cũ),
    ch2 `model=gemini-3.5-flash-lite, shared=true`, `updatedAt` mới.
  - **Read-back chứng minh**: `run-as sqlite3` DELETE local row chương 2 (count=0) →
    reopen app → Wiki vẫn "Has summary" + text (100% Firestore), "2/7, 28%".

### 1.6 Build 4 bản RELEASE mới (2026-10-20, gate test 69/69 + ruler 64/64 + go vet 0)
| File | Đường dẫn | Size | Ký |
|---|---|---|---|
| Bibot.apk | `android/app/build/outputs/apk/demo/release/Bibot.apk` | 33,80MB | apksigner v1+v2+v3 ✓ |
| Bibot.aab | `android/app/build/outputs/bundle/demoRelease/Bibot.aab` | 22,54MB | jar verified ✓ |
| BibotV.apk | `android/app/build/outputs/apk/commercial/release/BibotV.apk` | 34,06MB | apksigner ✓ |
| BibotV.aab | `android/app/build/outputs/bundle/commercialRelease/BibotV.aab` | 22,71MB | jar verified ✓ |

Cert chung `CN=Bibot App` SHA-256 `706e00a9…`; AAB đã copy tên tạm
`app-{demo,commercial}-release.aab` → `Bibot.aab`/`BibotV.aab`. Release = R8 shrink +
VIP_HMAC_SECRET rỗng + native-debug-symbols.zip kèm AAB.

## 2. Bẫy môi trường MỚI trong phiên
1. `adb devices` rỗng lần đầu (daemon chưa start) → chạy `adb devices` 1 lần tự start
   daemon rồi boot emulator bình thường.
2. Emulator exe thật: **`H:\androidkit\sdk\emulator\emulator.exe`**; AVD dir có 2 bản:
   `H:\androidkit\avd` và `H:\androidkit\.android\avd` (cả 2 đều có `BibotTest.avd`).
   Dùng `ANDROID_AVD_HOME=H:\androidkit\avd` + snapshot → boot ~25s.
3. `uiautomator dump` 1 lần trả garbage/stale (kết hợp "null root node" + dialog cũ) —
   **dump lại sau 2-3s** (lần 2 đúng). Đã có trong handoff 10-17 nhưng lặp lại.
4. Shelf KHÔNG có bottom nav → tap (1023,195) trên shelf KHÔNG vào Settings;
   phải "Back to home" trước.
5. **Đọc `content-desc` trong dump**: icon Info trên card có `content-desc="About"`
   (= `R.string.nav_about`) — nhìn như nút text "About" nhưng bounds nhỏ (45px) là icon.
6. Settings là 1 list dài collapsed — toggle share nằm ở CUỐI (sau "Khoảng đoạn"):
   swipe `540 1800 → 540 500` 1 lần là thấy "Share my summaries to community wiki".
   Tap vào switch (~950, y của row) — KHÔNG tap label (label tap cũng toggle được nhưng
   dễ trượt). Toggle state KHÔNG hiện trong dump → tin vào UI wiki banner để xác nhận.
7. Tap vào dialog Compose + tap ngoài → dismiss dialog (không navigate) — sequence
   tap liên tiếp dễ trượt; mỗi bước nên dump xác nhận trước khi tap tiếp.

## 3. Còn nợ phiên sau
- ~~PUBLISH `firestore.rules`~~ ✅ (user 20-10, verify REST 4 API).
- ~~Contribute với sách có chữ~~ ✅ ("How it Works" chương 3, E2E xong).
- B5.8 lặt vặt: server-side duyệt report/ẩn chương, test sách dày 500 chương
  (`MAX_CHAPTERS=500` code đã có).
- Cài 4 bản release lên máy ảo/máy thật để test (chưa cài — app đang là demoDebug).
- E2 (view nguyên bản), G1.2/G1.3, G2.2, G3.2/G3.3, H3 (OTA rulepack), I2.3/I2.4, J1.

## 4. State máy ảo (sau phiên 2026-10-20)
- **Share toggle: ON** (đã verify ON→OFF→ON bằng banner wiki).
- Gemini key: **key mới HỢP LỆ** (verify REST 200) — contribute LLM thành công,
  **Summary turns left: 2** (trừ 1 lượt cho chương 3 "Chapter V.").
- Wiki doc `pdrive-1M77…` (How it Works): `summarizedChapters=2/7` (chương 1 do REST
  test tạo, chương 3 do app contribute), local Room trống → đọc sạch từ cloud.
- `rewarded_summary=3` (không đổi); grimm10.pdf + Khe Sanh epub + 8 sách EN như cũ.
- App đang mở ở Wiki "How it Works" sau read-back verify.

## 5. Cam kết/môi trường
- JDK17 qua `C:\Users\Thanh Marine\.gradle\gradle.properties`; AVD `H:\androidkit\avd\BibotTest.avd`.
- APK: `android\app\build\outputs\apk\demo\debug\Bibot.apk`; secret chỉ `android/local.properties`.
- Version v1.0.2 / versionCode 3 — chưa bump.
- Build ALWAYS qua `Start-Process .\gradlew.bat -NoNewWindow -Wait -RedirectStandardOutput log`
  (call trực tiếp bị kill child process).
