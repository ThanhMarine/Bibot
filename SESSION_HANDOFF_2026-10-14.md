# Biên bản phiên làm việc 2026-10-14 — Firebase bibot-wiki + wiki 2 máy + lề ReadEra + release song song

> Đọc kèm `Checklist_Upgrade_Bibot.md` (E2, B5.6/B5.7/B5.9, B6.18, J3 đã cập nhật trong phiên này).
> Phiên trước: `SESSION_HANDOFF_2026-10-13.md`.
> Tree CHƯA commit (user chưa yêu cầu) — danh sách file đổi ở §2.

## 1. Việc đã xong

### 1.1 Đấu nối Firebase `bibot-wiki` (B5.9 [ ]→[x])
- User tạo project mới trên console: Auth ẩn danh Enabled; Firestore `asia-southeast1`
  production + Rules đã Publish; 2 app Android `com.bibot.app` + `com.bibot.app.vip`;
  App ID `1:62701146933:android:d4ea976099f1701743727c`, key Android `AIza...SA1M`
  (key Browser dự phòng khi 403).
- Code: MỚI `firestore.rules` (repo root: `bibot/{uid}` auth-owner,
  `wiki_summaries` public-read/auth-write ≤8KB); `build.gradle.kts` đọc
  `bibot.firebase.projectId/appId/apiKey` từ `local.properties` (gitignore) → env CI
  → rỗng (=offline, app chạy local-only bình thường).
- Không vào được console user (đòi login Google) — mọi verify console do user tự làm.
- Lệch build đã sửa: `java` trên PATH máy là **Java 8** (HotSpot build 25 — user tưởng
  JDK 25) → Gradle 8.7 vỡ tương thích. Chốt máy bằng
  `C:\Users\Thanh Marine\.gradle\gradle.properties`
  (`org.gradle.java.home=H:/androidkit/jdk17`, ngoài repo).
  `./gradlew --version` báo `JVM 17.0.17`. Code vẫn `VERSION_17`/`jvmTarget 17`, không đổi.

### 1.2 Wiki 2 máy VERIFIED (Khe Sanh epub, máy thật + máy ảo)
- Máy thật tóm tắt Chương 1 OK (tốn token) → doc
  `wiki_summaries/{hash}_gemini-3-5-flash-lite_v1` đủ 7 field.
- Máy ảo đọc ké FREE không mất lượt + `hits` 1→2 (`bumpWikiHits` chạy).
- Chữ `lang` lạ trên console là Google dịch tự động, không phải bug.
- Lệch "máy ảo 1 chương cả sách" đã rõ nguyên nhân: file epub trên máy ảo hỏng/khác
  bản (zip lỗi → spine rỗng → 1 chương rỗng → summary `empty`). Xóa + tải lại đúng
  file → đủ Chương + tóm tắt OK. Parser vô can (thuần JDK, cùng file = cùng kết quả).

### 1.3 Lề hẹp + căn đều kiểu ReadEra (B6.18 mới, code xong — còn nợ walkthrough)
- TEXT (`ReaderScreen`) + EPUB rich (`EpubRichView`): lề ngang `16dp→10dp`,
  `BODY Start→Justify`. PDF (`PdfPageView`): khung `horizontal 8dp→4dp` + viền tờ
  giấy `0.4dp outlineVariant` bo 2dp (user chốt viền = 40% của 1dp). Giữ `MaterialTheme`
  (nền giấy sepia để ở E2.4). B6.17 của phiên song song giữ nguyên, chỉ sửa ref dòng
  `1571-1594`→`1571-1597` do code mới chèn thêm dòng.

### 1.4 Toggle chia sẻ wiki ở Settings (B5.7 hết nợ code)
- `SettingsScreen` thêm `SettingSwitchRow(summary_share)` + sub sau cụm
  paragraph-margin; strings `summary_share(_sub)` EN/VI; setter đã có sẵn.
  Còn nợ test opt-out trên máy (tắt → tóm tắt mới không upload).

### 1.5 Build + cài máy
- `clean assembleDemoDebug` BUILD SUCCESSFUL (đã nhúng Firebase — verify
  `BuildConfig.java` có đủ 3 giá trị). `install -r` máy ảo Success, logcat sạch FATAL.
- Release song song (giữ 1.0.2/code 3 — bản test, chưa bump): `Bibot.apk` 33.7MB +
  `BibotV.apk` 34MB, `Bibot.aab` 22.2MB + `BibotV.aab` 22.5MB; `apksigner verify`
  cả 2 exit 0. R8 bản commercial chậm (~8 phút, timeout lần 1 — chạy lại incremental xong).

## 2. Trạng thái repo (CHƯA commit)
- Modified: `Checklist_Upgrade_Bibot.md`, `android/app/build.gradle.kts`,
  `feature/reader/{ReaderScreen,EpubRichView,PdfPageView}.kt`,
  `feature/settings/SettingsScreen.kt`, `res/values{,-vi}/strings.xml`.
- Mới: `firestore.rules`. KHÔNG đụng: `Tóm_tắt_hiện_tại.md` (phiên khác đang sửa),
  `SESSION_HANDOFF_2026-10-13.md`/`Terms.md`/`test/` untracked có sẵn.
- Secret chỉ nằm ở `android/local.properties` (gitignore) + `~/.gradle/gradle.properties`
  (ngoài repo) — không có gì nhạy cảm trong tree.

## 3. Môi trường / bẫy đã gặp
- Task `generate*BuildConfig`/`compile*` hay UP-TO-DATE giả sau sửa `build.gradle.kts`:
  ép bằng `--rerun-tasks` task generate rồi `clean` build khi đổi BuildConfig.
- `adb install -r` bản debug giữ data; release ký `bibot-release.keystore` cài đè được.
- Emulator hiện tại: emulator-5554, app `com.bibot.app` bản debug mới nhất đã chạy.
- Console Firebase user tự giữ — phiên sau cần gì (rules/số liệu) thì nhờ user chụp.

## 4. Việc tiếp theo (thứ tự)
1. Worker tóm tắt sách + test toggle opt-out + spot-check 3 sách (khóa B5.6).
2. Walkthrough B6.17/B6.18 trên máy (FAB một tay + lề/Justify/viền).
3. A2.5b (sách 3–5MB/font/sách dày), B5.8, rồi A3 (export/player).
4. Release chính thức: bump version (J1.1) + dist `SHA256.txt` (J1.4) khi user lệnh.
