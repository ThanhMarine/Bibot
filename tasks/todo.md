# Todo — “Một ngôn ngữ + một điểm nhấn”

> Plan chi tiết: `tasks/plan.md`. Rule dự án: `.cursor/rules/bibot-ui.mdc`. Tick từng ô khi hoàn thành; commit theo phase.

## Quyết định đã duyệt
- [x] Accent `#1976D2`; `dynamicColor = false`; hình lớn – chữ nhỏ đủ nhìn
- [x] Xoá toàn bộ Fluent 3D, kể cả 5 ô Home
- [x] Phát hành bản UI này với `versionName 1.0.6` / `versionCode 7`

## Phase 1 — Foundation
- [ ] Task 1: Khoá palette & bỏ dynamic color (`Color.kt`, `Theme.kt`, `themes.xml`, `colors.xml`)
- [ ] Task 2: Chuẩn hoá Typography & spacing token (`Type.kt`, `Dimens.kt`)
- [ ] Task 3: Tạo facade `BibotIcons` (`ui/icon/BibotIcons.kt`)

### Checkpoint: Foundation
- [ ] Build xanh; accent nhất quán Light/Dark; có `BibotIcons`

## Phase 2 — Di cư icon & xoá 3D
- [ ] Task 4: Home grid sang Material Icons (`BibotNavSuite.kt`, `HomeMenu.kt`)
- [ ] Task 5: Settings section headers sang Material Icons (`SettingsScreen.kt`)
- [ ] Task 6: Xoá 20 `fluent_*.png` + dọn credit (`THIRD_PARTY_NOTICES.md`, strings EN/VI)

### Checkpoint: Không còn Fluent 3D
- [ ] 0 tham chiếu `fluent_`, 0 file PNG; Home/Settings cùng bộ icon; build xanh

## Phase 3 — Chữ & component
- [ ] Task 7: Dọn `fontSize` cứng ở Home + Settings
- [ ] Task 8: Component chung `BibotComponents.kt` + refactor Settings
- [ ] Task 9: Dọn `fontSize` cứng ở Reader/Weather/Library

### Checkpoint: Nhất quán chữ & component
- [ ] Cùng typography; component dùng lại; font scale 1.3 không tràn

## Phase 4 — Hoàn thiện & phát hành
- [ ] Task 10: Giảm nhiễu nền & banner, kiểm tra touch target
- [ ] Task 11: Bump `1.0.6` / `versionCode 7`, build AAB/APK + `dist/v1.0.6-*`, SHA256
- [ ] Task 12: Unit test + assemble + smoke 5 màn (hiện `Bibot v1.0.6 (Build 7)`) + version note

### Checkpoint: Complete
- [ ] Review ảnh trước/sau với người dùng; commit/push từng phase
