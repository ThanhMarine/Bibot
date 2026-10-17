# Biên bản phiên làm việc 2026-10-15→17 — Luật công bằng wiki + B5.6 live opt-out

> Đọc kèm `Checklist_Upgrade_Bibot.md` (B5.6/B5.7 + LUẬT CÔNG BẰNG đã cập nhật trong phiên này).
> Phiên trước: `SESSION_HANDOFF_2026-10-14.md`.
> Tree SẠCH — đã commit `b8b8683` (9c3c677, f4d9a5f, aa6a4f2, 32048a6 trong cùng chuỗi).

## 1. Việc đã xong

### 1.1 Luật công bằng wiki (user yêu cầu: "ai share thì người đó được nhận")
- **Trước đây KHÔNG công bằng**: code cũ đọc Firestore wiki bất kể toggle → tắt share
  vẫn hưởng free cache người khác trả token.
- **Sửa** `SummaryRepository.summarizeChapter`: wiki-read giờ gate `shareEnabled` —
  TẮT = bỏ qua wiki (đi thẳng LLM/NoCredit), BẬT = đọc free + badge + `bumpWikiHits`.
  Doc K/K: thứ tự local (luôn đọc, của chính máy) → wiki (chỉ khi share ON) → LLM.
- Strings `summary_share_sub` EN/VI ghi rõ luật (Fair rule: OFF = neither send nor receive).
- Log chẩn đoán `BibotWiki` (lookup share/id → getWikiSummary hit/len → error) giữ trong code.
- **VERIFY LIVE máy ảo (Khe Sanh epub 7ch, key đã nhập lại bởi user):**
  - share OFF → chương local-miss (row idx0 đã xóa tay) → Failed/NoCredit, KHÔNG đọc
    chùa dù wiki có bản (test trước khi bật Log dùng dialog "Summary failed").
  - share ON → tóm tắt chương thứ 2 → badge **"From community cache (0 tokens)"**,
    text tiếng Việt đầy đủ, turns giữ 3, DB row `chapterIdx=2, shared=1, fromShared=1`.
  - Lệch idx máy thật/máy ảo là do split epub 2 máy khác nhau → hash khác → miss đúng.

### 1.2 B5.6 live phần còn lại
- Opt-out OFF→ON verify bằng `od` prefs (`summary_share_enabled 08 00`→`08 01`).
- Reward +3 summary (nút Watch ad): đã sửa `AdManager.loadRewarded` DEBUG dùng
  `TEST_REWARDED_ID` (tránh no-fill với unit thật) + Toast `ads_cooldown`/`ads_loading`
  + log `BibotReward` ở `watchAdForSummary` — **chưa verify lại live** (còn nợ §3).

### 1.3 Build/test/commit
- `assembleDemoDebug` BUILD SUCCESSFUL; JVM suite `--rerun-tasks` **69/69 PASS** (0 fail).
- Commit `b8b8683` (6 files): SummaryRepository (luật + log), AdManager, ReaderScreen
  (Toast/log reward), strings EN/VI, Checklist. Working tree sạch.

## 2. Bẫy môi trường MỚI phát hiện trong phiên (quan trọng cho lần sau)
1. **`run_bibot_emulator.bat` boot không lên** khi emulator process đã chết hoàn toàn —
   script chỉ `start` emulator rồi chờ; lần này phải boot tay:
   `$env:ANDROID_AVD_HOME='H:\androidkit\avd'; Start-Process ...\emulator.exe -avd BibotTest`
   → adb nhận `emulator-5554` ngay, `sys.boot_completed=1` (~20s nếu snapshot còn).
2. **Screenshot màn hình ĐEN toàn bộ cả status bar** = display ngủ, KHÔNG phải app crash.
   Chữa: `adb shell input keyevent KEYCODE_WAKEUP` rồi `input keyevent 82`.
3. **Màn hình treo đen sau `install -r`/force-stop** cũng do sleep; app vẫn sống
   (`pidof` có pid, logcat AdMob initialized bình thường).
4. **Cold boot xong "System UI isn't responding"** → bấm **Wait** (không Close) + đợi 60s.
5. **`uiautomator dump` trả "null root node"** vài giây sau install/force-stop → chờ rồi dump lại.
6. **Tap theo tọa độ screenshot dễ trượt** — đọc `bounds` từ `uiautomator dump` làm chuẩn.
   Sai bao nhiêu lần trong phiên: bấm nhầm nút Read của "Learning Spoken English" (tự tải
   + tự mở) 3 lần; nút Read Khe Sanh thật `[734,1686][910,1793]`→`(822,1739)` sau 2 swipe.
   TOC icon = `content-desc="Contents"` `[872,138][970,221]`; icon tóm tắt/chương =
   `content-desc="Chapter summary"` (đầu tiên = chương 1).
7. Emulator chết giữa phiên 1 lần (10-16, `adb devices` rỗng) — boot lại bằng snapshot
   giữ nguyên state (prefs/DB còn nguyên).
8. PowerShell: `head` không có (dùng `Select-Object -First`), `tail` OK, pipe từ
   `Select-String` sau `$x.IndexOf(...)` regex bắt `bounds` nhớ thoát `\d` đúng.

## 3. Còn nợ phiên sau
- **[ƯU TIÊN] Reward +3 live**: mở chương gate NoCredit (còn 3 lượt → 0 trước) → bấm
  "Watch ad" → QC hiện? → `rewarded_summary 0→3` verify bằng `od`
  `files/datastore/bibot_ads.preferences_pb` (`18 03`→`18 00`→`18 03`) + log `BibotReward`.
  Chú ý cooldown `REWARDED_COOLDOWN_MS=30'` + log `AdManager: Rewarded loaded`.
- **A2.5b**: font slider (tap/swipe/thumb không ăn — thử `input swipe` chậm trên thumb
  thật của Compose Slider hoặc +`/* pointerInput */` nếu vẫn hỏng) + test sách dày
  (How it Works 7.7MB / Khe Sanh 5.3MB) + cap `MAX_CHAPTERS=500`.
- C4 2 flavor (Bibot/BibotV) song song verify.
- State máy ảo đang giữ: key Gemini đã lưu, share **ON**, Khe Sanh local row idx0 bị
  xóa (test) — nếu cần tóm tắt lại chương đó thì mở TOC → icon → LLM (trừ 1 lượt).

## 4. Cam kết/môi trường (không đổi so với 10-14)
- JDK17 qua `C:\Users\Thanh Marine\.gradle\gradle.properties`; AVD `H:\androidkit\avd\BibotTest.avd`.
- APK: `android\app\build\outputs\apk\demo\debug\Bibot.apk`; secret chỉ `android/local.properties`.
- Version v1.0.2 / versionCode 3 — chưa bump.
