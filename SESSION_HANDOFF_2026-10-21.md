# Biên bản phiên làm việc 2026-10-20/21 — G3.2/G3.3 + G2.2 + G1.3 (CHỐT PHIÊN)

> Đọc kèm `Checklist_Upgrade_Bibot.md` (G3.2/G3.3, G2.2, G1.3 đã đánh dấu XONG).
> Phiên trước: `SESSION_HANDOFF_2026-10-20.md` (B5.8 — đã commit `fb9aa5d`).
> **G3.2/G3.3 ĐÃ COMMIT `51069bb`; G2.2 ĐÃ COMMIT `3d8c9f1`**; **G1.3 (verify, chỉ docs) CHƯA COMMIT**:

```
 M Checklist_Upgrade_Bibot.md
 M SESSION_HANDOFF_2026-10-21.md
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

## 2. Gate (chạy lại sau G2.2)
- `python tools/rules/compile_rules.py` → **102/102 (29 rules)**, sha256 `f824bf137547…`
  (lúc G3.2/G3.3: 64/64 — 17 rules, sha `f1b3414a6abb…`).
- `go vet ./internal/... ./cmd/...` → exit 0; `go test ./internal/... ./cmd/...` → **ok** (chunker/rules/tts).
  - Lưu ý: `go test ./...` **FAIL** ở `bibot/core/OpenReadEra/libwebp/swig` (missing function body —
    pre-existing, swig/C GO) → gate đúng là `./internal/... ./cmd/...`.
- JVM `:app:testDemoDebugUnitTest` → **80/80, 0 fail** (13 suite; mới: `ReaderHeadingsTest 9/9`
  ở G3.2, +2 test G2.2; lúc G3.2/G3.3: 78/78).
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
  helper: `C:\Users\Thanh Marine\AppData\Local\Temp\opencode\dump.ps1` (retry nếu `null root node`).
- Emulator `emulator-5554` đang chạy; adb = thêm `H:\androidkit\sdk\platform-tools` vào PATH.

## 4. Nợ sang phiên sau
- E2 (view nguyên bản), G1.2 (hoist state vào `ReaderViewModel` + `SavedStateHandle`),
  H3 (OTA rulepack),
  I2.3/I2.4 (Play Integrity + verify relay thật), J1 (release mới),
  B5.8 nợ lặt vặt: server-side duyệt report/ẩn chương, test sách dày 500 chương,
  G2.4 nợ **verify tai nghe** (unit đã cover, chỉ còn nghe VI/EN 3 câu ngoặc).
- Việc gợi ý gần nhất (từ quan sát §3): kiểm `roman.single` cho EN + đưa
  `session.sentences()`/đọc `.prep` sang IO (bấm StrictMode).
- Lưu ý tree: `ui.xml` ở root là rác dump UI (không commit).
