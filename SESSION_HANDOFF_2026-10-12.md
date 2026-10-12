# Biên bản phiên làm việc 2026-10-12 — Xoá phase-ads + rulepack v2 + audit checklist

> Đọc kèm `Checklist_Upgrade_Bibot.md` (đã audit + cập nhật trong phiên này),
> `tools/rules/RULES_AUTHORING.md` (§4 quy trình publish rule).
> Phiên trước: `SESSION_HANDOFF_2026-10-11.md`.

## 1. Việc đã xong (4 cụm)

### 1.1 Xoá phase-based interstitial ads — B6.14 (code theo checklist đã cập nhật trước)
- Xóa file `core/ads/PhaseTracker.kt` (`ReadingPhaseTracker` + `AdPhase`).
- `feature/reader/ReaderScreen.kt`: xóa `phaseTracker`/`adPreloaded`/`adBreakActive`/
  `lastAdBreakMs`/`lastAdBreakChap` + mọi `reset()` + preload interstitial + toàn bộ
  block ngắt 8–10' (`shouldBreakAtBoundary`, `bonusChapter`, `showInterstitial`,
  auto-resume callbacks) + import `Log`. Giữ `AdsRewarded` Cloud-chapter + VIP bypass.
- `core/ads/AdCopy.kt`: xóa `bonusChapter()` (hết caller). `data/ads/AdConfig.kt`: sửa
  comment stale nhắc PhaseTracker.
- `ui/menu/BibotNavSuite.kt`: banner HIỆN cả khi đọc (bỏ `if (!inReader)`); topBar/
  bottomBar/Rail vẫn ẩn ở mode đọc.

### 1.2 Module "Nguyên tắc đọc sách" mở rộng — rulepack v2 (H2.0)
- `.ruler` v1→v2: 3 → **16 rules** (thêm `date.full`, `temp.celsius/fahrenheit/stuck`,
  `roman.range`, `fig.vi/en`, `no.vi/en`, `drop.toc_leader`, `start.intro`,
  `start.first_chapter`, `chunk.brackets` params flat). GIỮ code có lý do trong `note:`:
  `readPageNumbers` (cần lookahead, ngoài giao 3 engine), `dropPageNumberLines` vòng khối.
- Wire Kotlin: `ReadingRules.init()` ở `BibotApp.onCreate` + `applyIfReady` trong
  `cleanForTts` (hàm cứng thành no-op/fallback) + vòng dòng `BlockTextReader` thử ruler
  trước. `MiniJson` thuần thay `org.json` (stub crash trên JVM thường), 0 dep.
- 2 bug thật lòi ra khi verify, vá đồng bộ: (1) `roman.single` tái-match chữ "m" trong
  output của range (`\b` ASCII ở Java/Go vs Python-unicode) → đuôi
  `([^A-Za-z0-9À-ɏḀ-ỹ]|$)` + giữ `{3}` ở cả 4 nơi; (2) Go `hook("",…)` trim mất đuôi.
- Bằng chứng: `compile_rules.py` **61/61** · `go test -count=1 ./internal/...` 3 package
  PASS · JVM **23/23** (5 ReadingRules mới đọc JSON thật + 9 TextRules + 9 TtsSegments) ·
  `assembleDemoDebug` SUCCESS — APK demoDebug **58.881.939B** (+107B).
- 3 bản JSON đồng bộ (cùng hash `C7EBE059…`, 4594B): `tools/` + `internal/` + `assets/`.

### 1.3 Cài APK lên máy ảo
- Boot AVD `BibotTest` (emulator-5554), `adb install -r` bản phase-removal
  (58.881.832B) **Success** — `com.bibot.app` v1.0.2.
- LƯU Ý: máy ảo đang chạy bản này = bản TRƯỚC rulepack v2 (ruler v2 build sau,
  58.881.939B, chưa cài). Phiên sau cài đè.

### 1.4 Audit + cập nhật `Checklist_Upgrade_Bibot.md` (sửa claims sai khi đối chiếu code)
- "2 Chế Độ Đọc" viết lại: Mode 1 sửa "3 rules"/"`TtsSegments` gọi ruler" (sai);
  **Mode 2 hạ [x]→[ ]** — `ViewScreen.kt` chỉ là mock tĩnh (list giả, nút rỗng).
- **A2.2 [x]→[ ]**: `MobiReader.kt` là draft chưa commit + 0 caller.
- **A2.4 [x]→[ ]**: progress vẫn `chap*100+sent` (Hitbox mới dùng ở search).
- G1 (giả thuyết race sai — nguyên nhân thật ở B3.6), G2/G3.1 (xong ở D6/D7, giữ nợ nhỏ),
  A2.1/A2.3/F0.1/H0/H1/D5.2/D6.4/B6.6/D4.1 (refs file đã xóa + số liệu mới).

## 2. Trạng thái repo (CHƯA COMMIT — engineered, đừng commit bừa)

- Thay đổi của phiên này: `Checklist`, `tools/rules/*` (ruler/json/authoring),
  `internal/rules/*`, `internal/tts/tts.go`, `core/text/{ReadingRules,TextCleaner,
  BlockTextReader}.kt`, `BibotApp.kt`, `core/ads/AdCopy.kt`, `data/ads/AdConfig.kt`,
  `feature/reader/ReaderScreen.kt`, `ui/menu/BibotNavSuite.kt`, `assets/reading-rules.json`,
  mới `src/test/.../ReadingRulesTest.kt`, xóa `core/ads/PhaseTracker.kt`.
- Có sẵn từ trước (không đụng): `BookRepository`, `BibotDatabase`, `BibotPreferences`,
  `SettingsScreen`, `HomeMenu`, strings, `Build_plan.md`, `Readme.md`, `store-info.txt`.
- Untracked: `MobiReader.kt`, `BookDetailScreen.kt`, `ViewScreen.kt` (từ phiên trước),
  **`android/outfile.txt` (rác — xóa được)**.
- Gợi ý commit phiên sau (sau khi walkthrough máy ảo): `git add` từng nhóm trên +
  `git commit -m "..."` — KHÔNG `git add -A` mù (dính rác + file phiên khác).

## 3. Môi trường
- Build offline: `$env:JAVA_HOME="H:\androidkit\jdk17"`, `ANDROID_HOME=SDK`,
  `./gradlew ... --offline` trong `android/`. JDK 8 mặc định của máy KHÔNG build được.
- Emulator: `emulator.exe -avd BibotTest`; vẫn ĐANG CHẠY (PID 13212, emulator-5554,
  booted) — tái dùng luôn, khỏi boot lại. Adb: `H:\androidkit\sdk\platform-tools\adb.exe`.
- Python: `compile_rules.py` cần `PYTHONIOENCODING=utf-8` (console cp1252 vỡ chữ Việt).

## 4. Việc tiếp theo (thứ tự)
1. Cài đè APK ruler v2 (58.881.939B) lên emulator đang chạy + walkthrough B6.14
   (đọc liền mạch không ngắt, banner đáy, rewarded Cloud TTS, interstitial màn khác).
2. Quyết `ViewScreen`: wire sách thật hoặc gỡ khỏi nav (hiện là mock).
3. A2.2: wire + test + commit `MobiReader.kt` (detect `.mobi/.azw` ở `BookRepository`).
4. H2.1 (xóa hàm cứng sau khi v2 ổn định máy thật) → H2.3 (cắm `chunkGuard()` vào chunker).
5. A2.4: migrate progress `chap*100` → Hitbox/xpointer. Xóa `android/outfile.txt`. Commit.
6. Xa hơn: B5/A3/C4, OTA ruler (H3.2), verify máy thật D1.7/D2.6/D3.3/D4.4.

## 5. Lệnh nhanh
```powershell
$env:JAVA_HOME="H:\androidkit\jdk17"; $env:ANDROID_HOME="H:\androidkit\sdk"
./gradlew :app:assembleDemoDebug --offline                                 # build
./gradlew :app:testDemoDebugUnitTest --offline                            # JVM 23 tests
go test -count=1 ./internal/...                                           # Go
$env:PYTHONIOENCODING="utf-8"; python tools/rules/compile_rules.py        # ruler 61 tests
& "H:\androidkit\sdk\platform-tools\adb.exe" -s emulator-5554 install -r `
  "android\app\build\outputs\apk\demo\debug\Bibot.apk"                    # cài máy ảo
```
