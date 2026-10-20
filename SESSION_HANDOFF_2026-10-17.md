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
- ~~A2.5b~~ **XONG 2026-10-17** (§7): font slider ăn thật (lỗi cũ = bắn sai toạ độ),
  sách 5,3MB + md 501 chương load 4,9s không treo, gate sách dày NoCredit đúng;
  quan sát cleartext http khi URL-paste (chi tiết §7).
- ~~Reward +3 live~~ **XONG 2026-10-17** (§6); ~~C4 2 flavor~~ **XONG 2026-10-17**.
- State máy ảo (đã verify thêm 17-10 chiều): **key Gemini vẫn MẤT**, share **ON**,
  `rewarded_summary=3`, font reader đã **trả lại 15sp/1,2/6dp** (mặc định),
  Khe Sanh epub 5,3MB + grimm10.pdf 916KB + 4 sách VI local trong inbox
  (dense_501.md test **đã xóa**), 8 sách EN (chưa tải trừ grimm10).

## 4. Cam kết/môi trường (không đổi so với 10-14)
- JDK17 qua `C:\Users\Thanh Marine\.gradle\gradle.properties`; AVD `H:\androidkit\avd\BibotTest.avd`.
- APK: `android\app\build\outputs\apk\demo\debug\Bibot.apk`; secret chỉ `android/local.properties`.
- Version v1.0.2 / versionCode 3 — chưa bump.

## 5. Bổ sung cùng ngày (B6.19 + C4 + A3.2) — ĐÃ commit `f36d03a`
> Đã commit theo lệnh user: B6.19 (nền màn đọc) + C4 (2 flavor) + A3.2 (gộp player).
> Chi tiết đầy đủ trong `Checklist_Upgrade_Bibot.md` (B6.19, A3.2, J3).

### 5.1 B6.19 Nền màn đọc (drum/đơn sắc)
- `PrefKeys.READER_BACKGROUND` + `BibotPrefs.readerBackground` (`setReaderBackground`),
  file mới `feature/reader/ReaderBackground.kt` (`ReaderBackgroundLayer` vẽ ở ROOT `BibotNavSuite`
  trước Scaffold trong suốt, gate `inReader`, phủ luôn statusBar/navBar).
- Popup ⚙ `ReaderChrome` thêm 2 mục tick ✓ (`chrome_background_drum/_solid`), nút ⚙ portrait
  bỏ `enabled = pageMode`; strings EN/VI sau `chrome_brightness_auto`.
- Verify máy: chọn Solid → nền tan đặc full màn kể cả dải system bar → force-stop mở lại VẪN Solid
  (DataStore), chọn Drum → về trống đồng ngay.

### 5.2 C4 Build 2 flavor
- `assembleDemoDebug` Bibot.apk 56MB + `assembleCommercialDebug` BibotV.apk 57,2MB PASS;
  JVM 69/69 + ruler 64/64 + `go vet` 0 + `go test` PASS.

### 5.3 A3.2 Gộp 2 player → 1 controller (mục chính của phiên)
- **Bệnh**: News/Weather/Voice/Reader mỗi màn tự `OfflineTtsHelper(context)` (4–5 engine TTS song
  song) + mỗi màn stop kiểu của mình → đọc chồng (Weather/News vẫn nói khi sang màn khác; đọc
  offline bypass `activeIds` của karaoke).
- **Sửa** (chi tiết trong checklist A3.2): `OfflineTtsHelper.shared(context)` = 1 engine/process
  (constructor private, bỏ `shutdown()` ở News/Voice); `PlayerViewModel` = controller sở hữu engine
  + `speakOffline(..., mediaId)` (tự `stopPlayback()` trước, ghi `activeIds`) + **`stopAll()`**
  (listener → ExoPlayer → engine TTS) + `stopListeners`; `playUrl`/`enqueueBytes` cắt engine TTS;
  `BibotNavSuite.switchMode()` mọi đổi màn qua `stopAll()`; News/Voice `bindPlayer` + job hủy theo;
  Voice thêm `DisposableEffect`; Weather/Reader nhận `playerVm`.
- **Verify máy ảo** (log tag `AudioCtrl`): Weather đọc→Back = `stopAll` + `dumpsys audio`
  `state:started` 1→0; News đọc giữa chừng→Settings = `stopAll listeners=1` + job hủy
  (không `done ok=false`) → quay lại KHÔNG banner lỗi + nút Play reset;
  reader `speakOffline ... mediaId=story_0_0_1` + pause/resume/Back đều `stopAll`;
  Voice vào/ra `stopAll listeners=2`; **0 FATAL**.
- Gate: `go vet` 0, `go test` PASS, ruler 64/64, JVM 69/69, 2 flavor PASS.
- **Log debug mới**: tag `AudioCtrl` (`speakOffline start/done`, `stopAll listeners=N`) giữ lại
  để lần sau chẩn đoán audio chỉ cần `adb logcat -s AudioCtrl:D`.

### 5.4 Mẹo máy ảo dùng lại trong phiên này
- Chứng minh "engine TTS thật sự dừng": `adb shell dumpsys audio | Select-String 'state:started'
  -Context 1,0` — có dòng `type:android.media.AudioTrack ... state:started` (uid Google TTS) lúc
  đang đọc, biến mất sau `stopAll` (không cần nghe máy).
- Bảng home: Weather `(282,1175)`, News `(798,1175)`, EN Books `(282,434)`, Storytelling `(798,434)`;
  top bar Back `(74,132)` / Chat `(894,132)` / Settings `(1011,132)`; nút đọc bắn từ
  `uiautomator dump` (`desc='Read weather'`, `'Read this story'`, FAB `'Tell'`).
- Gradle dài: `Start-Process .\gradlew.bat -ArgumentList ':app:assembleDemoDebug' -RedirectStandardOutput $env:TEMP\x.log -RedirectStandardError $env:TEMP\x.err -NoNewWindow -Wait` (chạy từ thư mục `android\`, không pipe → không kill shell).

## 6. B5.6 reward +3 LIVE — XONG 2026-10-17 (sau commit `f36d03a`)
- Data máy ảo bị wipe 13:41 cùng ngày → key Gemini MẤT, `bibot_ads.preferences_pb` chưa
  tồn tại (0 lượt). Tải lại Khe Sanh (5,3MB, **7 chương — chương 1 là "Cover" RỖNG**).
- **Quy trình verify**: TOC → "Summarize whole book" → gate "Out of turns (0 left)" →
  bấm `Watch ad (+3 turns)` → AdActivity `TEST_REWARDED_ID` (Test Ad) đếm "Reward in 5
  seconds" → **"Reward granted"** → đóng QC → dialog TỰ mở lại "Out of turns (3 left)"
  + `bibot_ads.preferences_pb` 54B vừa xuất hiện (`rewarded_summary=3`).
  Lưu ý: tag `BibotReward` chỉ log ở nhánh blocked/not-ready, **claim không log** —
  verify bằng UI dialog + file datastore + `D AdManager: Rewarded loaded` sau đóng QC.
- Chương cấp (tắt share OFF để chắc không wiki-hit): chương 2 → `BibotWiki lookup
  share=false` → NeedKey gate **giữ nguyên 3 lượt** (không đốt). Đã bật lại share ON.
- **BUG nhỏ sửa kèm (commit cùng phiên)**: chương rỗng trước đây báo sai "Summary failed.
  Check network/key and retry." → thêm chuỗi `summary_empty` EN/VI + `SummaryDialog` nhánh
  `msg=="empty"` → verify máy: "This chapter has no text to summarize."
- Mẹo máy ảo: `run-as com.bibot.app ls -la files/datastore/` (54B = 3 lượt);
  đọc toggle Compose trong `uiautomator dump` = node `android.view.View` có `checked`;
  tắt/bật share để ép đường tổng hợp đi LLM thay vì wiki.

## 7. A2.5b Font slider + sách dày — XONG 2026-10-17 (chiều)
- **Font slider ăn thật**: `input tap` đúng dải SeekBar (`bounds` SeekBar, KHÔNG phải
  dòng nhãn `15 sp`) đổi 15→25→18→28sp; `input swipe` chậm trên thumb đổi interline
  1,2→1,7. Ghi chú cũ "không ăn" = bắn sai toạ độ. Sau test đã trả lại **15sp/1,2/6dp**
  (tap x≈313 cho 15sp — x=296 lệch về 10sp, rồi mới đúng).
- **Sách 5,3MB**: Khe Sanh epub mở OK, đổi 28sp → render lại ngay (RenderIfDirty),
  0 FATAL. **grimm10.pdf** 916KB/301trang mở OK.
- **Sách dày chương**: tự dựng `%TEMP%\thickbook\dense_501.md` (501 heading `## Chuong N`,
  112KB). URL-paste fail (xem cleartext dưới) → **push thẳng**: `adb push` →
  `/data/local/tmp/dense_501.md` → `run-as com.bibot.app cp ... files/bibot/pdfs/inbox/`
  → Refresh → row trong section `Downloaded / links`. Verify: **"Loaded 501 chapters ·
  1003 sentences"** nạp **4,9s**, TOC 501 dòng scroll mượt, nhảy TOC → `Chapter 84/501`
  đúng nội dung, **0 FATAL**. Ranh giới: stream md/txt/pdf dừng ở **501** title
  (`BlockTextReader.kt:403` cho mở thêm 1) còn EPUB/MOBI `take(500)` đúng 500 — chặn
  vô hạn ĐẠT mục đích, chỉ ghi nhận lệch 1. Gate sách dày: "Summarize whole book" →
  `Out of turns (3 left)` ngay (cần 21 lượt) → NoCredit trước LLM.
- **Quan sát cleartext (không blocks)**: `http://localhost:8000/...` qua
  `PublicUrlSource.download` → status `Error: Cleartext HTTP traffic to localhost not
  permitted` (Android chặn cleartext từ API28, app không có networkSecurityConfig).
  **Bẫy**: status hiển thị ở **item cuối LazyColumn** (`ReaderScreen.kt:1183`) — phải
  cuộn hết list mới thấy, không hiện gần ô URL. Link `https://` không ảnh hưởng;
  dev localhost nếu cần → thêm networkSecurityConfig (để ticket riêng).
- Dọn sau test: xóa dense_501.md (inbox + `/data/local/tmp`), tắt python http.server,
  `adb reverse --remove tcp:8000`, font về mặc định (15sp/1,2/6dp).
- **Bẫy mới**: bấm nhầm banner QC (`Open`) → màn full-screen Google Calendar → Back
  1 lần về launcher (app vẫn sống, `pidof` có pid) → bấm icon Bibot để quay vào.
  Tránh vùng banner `y≥1689` khi bấm.

## 8. B5.8 Wiki book reader — INFRA + UI DONE, BUILD FAIL (2026-10-17 tối)
- **FirestoreSync.kt**: thêm `WIKI_BOOKS` collection, `WikiBook`/`WikiChapterSummary` data classes
  + CRUD: `getWikiBook`, `putWikiBook`, `putWikiChapterSummary`, `reportWikiChapterSummary`
  + `getWikiBook`: deserialize chapterSummaries map, tính `summarizedChapters`/`status`
  + `putWikiBook`/`putWikiChapterSummary`: upsert with merge, auto tính `summarizedChapters`/`status`
  + `reportWikiChapterSummary`: ghi report vào subcollection `reports/{chapterIdx_timestamp}`
- **WikiBookScreen.kt**: Progress header (summarized/total), progress bar, contribute button,
  chapter list với check-circle ✓, AI warning banner, summary text, actions (open/report/contribute)
- **BookDetailScreen.kt**: nút "Wiki sách" (MenuBook icon) ở hàng action row
- Strings EN/VI: `wiki_complete`, `wiki_in_progress`, `wiki_contribute`, `wiki_report_bad`, `wiki_ai_warning`
- **CÒN LỖI BUILD (FIX NGAY TRƯỚC PHIÊN TIẾP)**:
  1. `FirestoreSync.kt:401` `observeCore` return type mismatch (Flow vs Boolean) — **FIX NGAY**
  2. `WikiBookScreen.kt:70` `var wikiBook by remember { mutableStateOf(...) }` delegate error
  3. `WikiBookScreen.kt:201,303` `Modifier.width` unresolved (thiếu import `androidx.compose.foundation.layout.width`)
  4. `ReaderScreen.kt:308` `Chapter(it)` cần 2 params (idx, title) — Chapter data class đã thêm `idx`
- **Firebase `wiki_books` rules**: public read, auth write, doc ≤ vài KB — **CHƯA LÀM** (Firebase console)
- **Còn nợ**: `ReaderScreen.kt:308` `Chapter(it)` cần 2 params (idx, title) — Chapter data class đã thêm `idx`
