# Todo — “Một ngôn ngữ + một điểm nhấn” (HOÀN THÀNH)

> Plan chi tiết: `tasks/plan.md`. Rule dự án: `.cursor/rules/bibot-ui.mdc`.

## Quyết định đã duyệt
- [x] Icon Material Symbols Rounded; Không dùng asset icon ngoài (Fluent/Magnific/hand-drawn tự vẽ)
- [x] Accent `#1976D2`; `dynamicColor = false`; hình lớn (icon Home 3×) – chữ nhỏ đủ nhìn
- [x] Xoá toàn bộ Fluent 3D, kể cả 5 ô Home
- [x] Chữ: đen đậm trên nền sáng, trắng đậm trên nền tối (không khoá chế độ sáng)
- [x] Phát hành bản UI này với `versionName 1.0.6` / `versionCode 7`

## Phase 1 — Foundation
- [x] Task 1: Khoá palette & bỏ dynamic color
- [x] Task 2: Chuẩn hoá Typography & spacing token
- [x] Task 3: Tạo facade `BibotIcons` — 21 Material Symbols Rounded

### Checkpoint: Foundation
- [x] Build xanh; accent nhất quán Light/Dark; có `BibotIcons`

## Phase 2 — Di cư icon & xoá 3D
- [x] Task 4: Home grid sang icon hand-drawn (kèm phóng 3×)
- [x] Task 5: Settings section headers sang icon hand-drawn
- [x] Task 6: Xoá 20 `fluent_*.png` + dọn credit + license cũ

### Checkpoint: Không còn Fluent 3D
- [x] 0 tham chiếu `fluent_`, 0 file PNG trong `src`; Home/Settings cùng bộ icon; build xanh

## Phase 3 — Chữ & component
- [x] Task 7: Dọn `fontSize` cứng ở Home + Settings
- [x] Task 8: Component chung `BibotComponents.kt` + refactor Settings
- [x] Task 9: Dọn `fontSize` cứng ở Reader/Weather/Library/Music
- [x] Extra: toàn bộ chữBold + màu chữ theo nền (đen/trắng)

### Checkpoint: Nhất quán chữ & component
- [x] Cùng typography; component dùng lại; tương phản Light/Dark OK

## Phase 4 — Hoàn thiện & phát hành
- [x] Task 10: Giảm nhiễu nền & banner, kiểm tra touch target
- [x] Task 11: Bump `1.0.6` / `versionCode 7`, build AAB/APK + `dist/v1.0.6-*`, SHA256
- [x] Task 12: assemble + smoke 5 màn (hiện `Bibot v1.0.6 (Build 7)`) + version note

### Checkpoint: Complete
- [x] Review ảnh trước/sau với người dùng; commit/pushmaster
