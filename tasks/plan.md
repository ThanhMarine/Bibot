# Implementation Plan: “Một ngôn ngữ + một điểm nhấn” (loại bỏ Fluent 3D)

## Overview
Chuyển toàn bộ giao diện Bibot sang **một ngôn ngữ icon duy nhất (vector hand-drawn tự vẽ)** và **một màu nhấn duy nhất (Bibot Accent)**. Loại bỏ hoàn toàn 20 file Fluent Emoji 3D (`fluent_*.png`) khỏi Home, Settings và mã nguồn. Đồng thời khoá palette màu, chuẩn hoá typography và trích component dùng chung để giao diện nhìn như được thiết kế có chủ đích thay vì ghép nhiều phong cách.

Quyết định thiết kế **đã được người dùng duyệt**:
- **Icon:** bộ **vector hand-drawn tự vẽ** (stroke tròn ~1.8dp, nét hơi lệch) trong `BibotIcons` cho MỌI chức năng: Home grid, Settings header, TopBar, action. Magnific **chỉ tham khảo phong cách**, không dùng asset.
- **Accent:** `Bibot Accent = #1976D2` (xanh dương Bibot). Secondary/tertiary chỉ là biến thể tone cùng họ màu; không dùng teal/pink tách biệt như hiện nay.
- **Không giữ bất kỳ raster emoji nào** — xoá toàn bộ Fluent 3D, **kể cả 5 ô Home**. Không dùng `Image(painterResource(...))` cho icon chức năng nữa.
- **Hình lớn – chữ nhỏ đủ nhìn:** ưu tiên icon/badge lớn (Home 56dp badge / 30–32dp icon); chữ tối thiểu label 12sp, body 14sp, title 16sp, không phóng to chữ quá mức.
- `dynamicColor = false` để palette nhất quán trên mọi máy; hỗ trợ Light/Dark rõ ràng.
- **Phát hành bản UI này với `versionName = 1.0.6` / `versionCode = 7`** (bump từ 1.0.5 / 6).

Rule dự án đã được tạo: `.cursor/rules/bibot-ui.mdc` (alwaysApply) để các session sau không tái phạm.

## Architecture Decisions
- **Dependency hạ tầng trước**: palette/typography/icon facade phải xong trước khi migrate màn hình,否则 mỗi màn hình sẽ tự chọn màu/icon riêng tiếp.
- **Facade `BibotIcons`**: mọi icon đi qua object ánh xạ tên ngữ nghĩa → `ImageVector` hand-drawn tự vẽ. Tránh rải `Icons.Filled.X` (hiện có hàng chục chỗ).
- **Migrate theo chiều dọc từng màn hình**: Home xong mới tới Settings, rồi mới xoá file PNG. Mỗi bước đều build được và chụp màn hình đối chiếu.
- **Font chữ**: dùng `MaterialTheme.typography.*`; xoá dần các `fontSize = N.sp` ghi cứng (hiện ~91 chỗ) theo từng màn hình thay vì sửa toàn bộ cùng lúc.
- **Component hoá Settings**: `SettingsScreen.kt` ~1.900 dòng nên tách dần `BibotSectionHeader`, `BibotIconBadge`, `BibotInfoCard` để các bước sau không làm tăng lỗi.
- **Bump version ở cuối**: đổi `versionName 1.0.5 → 1.0.6`, `versionCode 6 → 7` trước khi build artifact phát hành; rebuild AAB/APK + SHA256 + release notes.

## Task List

### Phase 1 — Nền tảng (Foundation)

#### Task 1: Khoá palette & bỏ dynamic color
**Description:** Định nghĩa lại Color.kt với một accent (#1976D2) + biến thể tone, set `dynamicColor = false` mặc định, dọn legacy `purple_700` khỏi `themes.xml`/`colors.xml`. Giữ nguyên Light/Dark.

**Acceptance:**
- [ ] `Color.kt` chỉ còn một họ accent (BibotAccent/Soft/OnAccent) + neutral; không còn `Purple80/Pink40/SecondaryTeal`.
- [ ] `Theme.kt`: `dynamicColor: Boolean = false`; Light/Dark dùng scheme nội bộ.
- [ ] `themes.xml` không còn `purple_700`; status bar theo `onSurface`.
- [ ] Ảnh chụp Light + Dark cùng một màn hình có cùng accent.

**Verification:** `:app:assembleDemoDebug --offline` BUILD SUCCESSFUL; screenshot emulator Home/Settings ở 2 chế độ.

**Dependencies:** None
**Files:** `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `res/values/themes.xml`, `res/values/colors.xml`
**Scope:** S

#### Task 2: Chuẩn hoá Typography & spacing token
**Description:** Bổ sung đủ 7 style chuẩn trong `Type.kt` (display/title/body/label) và tạo object `BibotSpacing` (4/8/16/24/32dp) + `BibotRadius` (12/16/20dp). Không đổi giao diện ngay, chỉ tạo token để các task sau dùng.

**Acceptance:**
- [ ] `Typography` có `headlineSmall`, `titleLarge`, `titleMedium`, `bodyLarge`, `bodyMedium`, `labelLarge`, `labelSmall`; `labelSmall` >= 12sp.
- [ ] `BibotSpacing`/`BibotRadius` tồn tại và dùng được từ Compose.
- [ ] Không có style nào để font mặc định 14sp khó đọc.

**Verification:** build success; diff chỉ có file theme/type.

**Dependencies:** Task 1
**Files:** `ui/theme/Type.kt`, new `ui/theme/Dimens.kt`
**Scope:** S

#### Task 3: Tạo facade `BibotIcons` (vector hand-drawn tự vẽ)
**Description:** Tạo ~21 `ImageVector` hand-drawn bằng `ImageVector.Builder` (viewport 24dp, stroke 1.8f, cap/join round, nét hơi lệch) cho các ngữ nghĩa: microphone, book, story, fairy, music, weather, news, settings, globe, robot, key, speaker, ticket, crown, package, books, bell, broom, question, letter, info. Không dùng asset Magnific/Fluent.

**Acceptance:**
- [ ] `BibotIcons` biên dịch; 21 icon đều là vector tự vẽ, cùng stroke/cap/join.
- [ ] Không có `ImageVector` nullable; có fallback rõ ràng.
- [ ] Chưa xoá file Fluent ở task này (tránh build broken giữa chừng).

**Verification:** `:app:compileDemoDebugKotlin --offline` OK.

**Dependencies:** Task 1
**Files:** new `ui/icon/BibotIcons.kt`
**Scope:** S

### Checkpoint: Foundation
- [ ] Build xanh, accent nhất quán Light/Dark
- [ ] Có `BibotIcons` để các màn hình dùng chung

### Phase 2 — Di cư icon & xoá 3D

#### Task 4: Home grid sang Material Icons
**Description:** `BibotMode` đổi `@DrawableRes iconRes` → `ImageVector icon` (qua `BibotIcons`). `HomeTile` thay `Image(painterResource)` bằng `Icon(BibotIcons…)`, giữ huy hiệu tròn 50dp với `accent.copy(alpha=.18f)` nhưng đổi kích thước icon 26dp, tint `onPrimaryContainer`.

**Acceptance:**
- [ ] Không còn `R.drawable.fluent_` trong `BibotNavSuite.kt` / `HomeMenu.kt`.
- [ ] 6 ô Home có icon phẳng cùng kích thước, cùng rule tint.
- [ ] Mic trên TopBar và Settings dùng chung bộ icon.
- [ ] Screenshot Home trước/sau rõ ràng sạch hơn.

**Verification:** build + smoke Home, bấm đủ 6 ô vẫn mở đúng màn hình.

**Dependencies:** Task 3
**Files:** `ui/menu/BibotNavSuite.kt`, `ui/menu/HomeMenu.kt`
**Scope:** M

#### Task 5: Settings section headers sang Material Icons
**Description:** `ExpandableSection` đổi `iconRes: Int?` → `icon: ImageVector?`. Thay 14 chỗ `fluent_*` trong `SettingsScreen.kt` bằng `BibotIcons.*`, render trong vòng tròn 32dp nền `primaryContainer`, icon 20dp tint `primary`.

**Acceptance:**
- [ ] `Select-String fluent_` trong `SettingsScreen.kt` trả 0 kết quả.
- [ ] Mọi mục Settings (Weather, AI, Gemini, Voice, Ads, VIP, Models, Library, Help, Author, Licenses…) có header cùng chuẩn.
- [ ] Thẻ phiên bản cuối trang giữ nguyên thiết kếCard vừa làm, nhưng dùng typography token.

**Verification:** build + screenshot Settings cuộn từ đầu tới cuối.

**Dependencies:** Task 3
**Files:** `feature/settings/SettingsScreen.kt`
**Scope:** M

#### Task 6: Xoá tài sản Fluent & cập nhật credit
**Description:** Xoá 20 file `res/drawable-nodpi/fluent_*.png`. Bỏ mục Fluent Emoji khỏi `THIRD_PARTY_NOTICES.md` và string `licenses_fluent`; cập nhật `licenses_intro` cho còn Material Icons + các dependency thật. Rà soát README/legal nếu có nhắc Fluent.

**Acceptance:**
- [ ] `Get-ChildItem res/drawable-nodpi -Filter fluent_*` trả 0.
- [ ] `Select-String fluent_` toàn project trả 0 (kể cả XML/docs).
- [ ] Không còn credit MIT của Microsoft cho asset đã xoá.
- [ ] Build success (chứng minh không còn tham chiếu sót).

**Verification:** build + grep toàn repo.

**Dependencies:** Task 4, Task 5
**Files:** `res/drawable-nodpi/fluent_*.png` (xoá), `THIRD_PARTY_NOTICES.md`, `res/values/strings.xml`, `res/values-vi/strings.xml`
**Scope:** M

### Checkpoint: Không còn Fluent 3D
- [ ] 0 tham chiếu `fluent_`, 0 file PNG
- [ ] Home + Settings cùng bộ icon phẳng
- [ ] Build xanh, credit hợp lệ

### Phase 3 — Chữ & component

#### Task 7: Dọn font size cứng ở Home + Settings
**Description:** Thay `fontSize = 15.sp` trong `HomeTile` và các `fontSize = N.sp` trong `SettingsScreen` bằng `MaterialTheme.typography.*` phù hợp (`titleSmall`, `bodyMedium`, `labelLarge`, `labelSmall`).

**Acceptance:**
- [ ] `Select-String 'fontSize = '` trong 2 file trả 0.
- [ ] Chữ không bị nhỏ đi; labelSmall >= 12sp.
- [ ] Screenshot Home/Settings so sánh trước/sau.

**Verification:** build + screenshot.

**Dependencies:** Task 2, Task 5
**Files:** `ui/menu/HomeMenu.kt`, `feature/settings/SettingsScreen.kt`
**Scope:** S

#### Task 8: Component chung cho Settings
**Description:** Tạo `ui/components/BibotComponents.kt` gồm `BibotIconBadge`, `BibotSectionHeader`, `BibotInfoCard`. Refactor `SettingsScreen` dùng lại cho header + thẻ phiên bản + thẻ giải thích ngắn.

**Acceptance:**
- [ ] Header Settings không còn code lặp vòng tròn/border.
- [ ] Thẻ phiên bản dùng `BibotInfoCard` (vẫn nổi bật như hiện tại).
- [ ] Giảm được ít nhất ~40 dòng trùng lặp trong SettingsScreen.

**Verification:** build + diff check + screenshot.

**Dependencies:** Task 5, Task 7
**Files:** new `ui/components/BibotComponents.kt`, `feature/settings/SettingsScreen.kt`
**Scope:** M

#### Task 9: Dọn font size cứng ở các màn hình chính còn lại
**Description:** Áp typography token cho `ReaderScreen`, `WeatherScreen`, `DocBrowserScreen` (những chỗ hay thấy nhất). Giữ nguyên nội dung, chỉ đổi style.

**Acceptance:**
- [ ] 0 `fontSize =` trong 3 file trên.
- [ ] Danh sách đọc/PDF/weather không bị tràn chữ ở font scale 1.3.

**Verification:** build + emulator chỉnh font scale 1.3, screenshot 3 màn hình.

**Dependencies:** Task 2
**Files:** `feature/reader/ReaderScreen.kt`, `feature/weather/WeatherScreen.kt`, `feature/library/DocBrowserScreen.kt`
**Scope:** M

### Checkpoint: Nhất quán chữ & component
- [ ] Home/Settings/Reader/Weather/Library dùng chung typography
- [ ] Component dùng lại, không code lặp

### Phase 4 — Hoàn thiện & nghiệm thu

#### Task 10: Giảm nhiễu nền & banner
**Description:** Home giữ hoạ tiết Trọng Đồng nhưng hạ opacity dưới thẻ; `AnnounceBanner` tăng contrast; gom `AdsBanner` vào container có padding/bo góc để không dính nội dung. Kiểm tra touch target ≥48dp.

**Acceptance:**
- [ ] Chữ trên nền họa tiết đọc rõ (contrast >= 4.5:1 với surface).
- [ ] Banner quảng cáo không chồng lên nút/card.
- [ ] Mọi icon-button có contentDescription và target >=48dp.

**Verification:** build + screenshot Home có banner + TalkBack/accessibility scanner cơ bản.

**Dependencies:** Task 7
**Files:** `ui/menu/HomeMenu.kt`, `ui/menu/BibotNavSuite.kt`
**Scope:** S

#### Task 11: Bump phiên bản 1.0.6 / versionCode 7
**Description:** Đổi `versionName = "1.0.6"`, `versionCode = 7` trong `build.gradle.kts`, build lại AAB/APK demo + commercial, cập nhật `dist/v1.0.6-*` (SHA256, native symbols, release notes) và ghi chú versionCode 7 cho Play Console.

**Acceptance:**
- [ ] `versionName 1.0.6` (VIP `1.0.6-vip`), `versionCode 7`.
- [ ] `dist/v1.0.6-demo/` + `dist/v1.0.6-vip/` có AAB/APK/mapping/symbols/SHA256/notes.
- [ ] `.gitignore` chặn `dist/v1.0.6-vip/` như các bản trước.
- [ ] Không dùng lại asset 1.0.5 (đã lỗi thời sau đổi UI).

**Verification:** `:app:assembleDemoRelease --offline` + `:app:bundleDemoRelease --offline`; `jarsigner -verify` AAB; đối chiếu SHA256.

**Dependencies:** Task 6, Task 8, Task 9, Task 10
**Files:** `android/app/build.gradle.kts`, `dist/v1.0.6-demo/*`, `dist/v1.0.6-vip/*`, `.gitignore`
**Scope:** M

#### Task 12: Regression & cập nhật tài liệu phát hành
**Description:** Chạy unit test, assemble demo+commercial, smoke emulator 5 màn (Home, Settings, Reader/PDF, Weather, Library) ở Light/Dark. Viết ngắn gọn Version Note cho bản đổi UI vào `dist/v1.0.6-demo/RELEASE-NOTES-*.txt`.

**Acceptance:**
- [ ] `:app:testDemoDebugUnitTest --offline` pass (hoặc apt unit test tương đương).
- [ ] `:app:assembleDemoDebug --offline` + `:app:assembleDemoRelease --offline` thành công.
- [ ] 0 crash logcat; screenshot đủ 5 màn; không còn `fluent_`; app hiển thị `Bibot v1.0.6 (Build 7)`.
- [ ] Version note mô tả “giao diện nhất quán, bỏ icon 3D”.

**Verification:** checklist trên + review ảnh trước/sau với người dùng.

**Dependencies:** Task 11
**Files:** `dist/v1.0.6-demo/RELEASE-NOTES-vi.txt`, `dist/v1.0.6-demo/RELEASE-NOTES-en.txt`, `tasks/todo.md`
**Scope:** S

### Checkpoint: Complete
- [ ] Mọi acceptance criteria ở trên đạt
- [ ] Human review ảnh trước/sau
- [ ] Commit riêng từng phase, push master sau khi duyệt

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| `SettingsScreen.kt` ~1.900 dòng, sửa icon + font dễ sót | High | Chia Task 5 và Task 7 riêng; build + screenshot sau mỗi task; grep `fluent_`/`fontSize` sau cùng |
| Vector hand-drawn khó đọc ở 20dp | Medium | Dùng stroke 1.8f + cap round, kiểm tra screenshot; tăng lên 22dp nếu cần |
| Tắt dynamic color làm “khác máy” cũ | Medium | Review ảnh Light/Dark ở Checkpoint 1 trước khi migrate icon |
| Xoá asset nhưng sót tham chiếu → build fail | High | Task 6 chạy sau Task 4/5; grep toàn repo trước khi xoá |
| Hoạ tiết nền + banner vẫn làm nhiễu | Medium | Task 10 đo contrast, hạ alpha, gom banner |
| Không có device thật | Low | Dùng AVD `bibot_smoke_api34` như các lần trước |

## Decisions Confirmed (không còn open question)
- Icon: vector hand-drawn **tự vẽ**; Magnific chỉ tham khảo phong cách, **không dùng asset/bản quyền ngoài**.
- Accent `#1976D2` — đã duyệt.
- Xoá toàn bộ Fluent 3D, kể cả 5 ô Home — đã xác nhận.
- Bump `1.0.6` / `versionCode 7` khi phát hành bản UI — đã duyệt.

## Out of Scope
- Không đổi logic nghiệp vụ (đọc sách, STT/TTS, thời tiết, tin tức).
- Không làm onboarding/hero illustration mới; chỉ dùng icon hệ thống + nền hiện có.

## Task List Index
Xem `tasks/todo.md` để đánh dấu tiến độ từng task.
