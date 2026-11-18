# Hướng dẫn phát tin tới người dùng (Thông Báo – Tin mới từ nhà phát triển)

Cơ chế: app **tự kéo** một file JSON công khai trên GitHub, không cần FCM, không cần server,
không cần phát hành lại APK. Sửa file → push → mọi máy đang chạy BiBot sẽ thấy tin (tối đa sau 24h,
hoặc ngay khi người dùng bấm **Làm mới** trong Cài đặt → Thông báo).

## 1. File nguồn

| Mục | Giá trị |
| --- | --- |
| File | `announcements.json` ở thư mục gốc repo `ThanhMarine/Bibot` |
| Nhánh | `main` (repo mặc định là `main`; raw `master` trả 404) |
| URL chính | `https://raw.githubusercontent.com/ThanhMarine/Bibot/main/announcements.json` |
| URL dự phòng | `https://cdn.jsdelivr.net/gh/ThanhMarine/Bibot@main/announcements.json` |

Hai URL được nhúng vào `BuildConfig.ANNOUNCE_URL` / `ANNOUNCE_URL_FALLBACK`
(`android/app/build.gradle.kts`). App thử lần lượt; nguồn đầu lỗi (chặn mạng, DNS…) thì dùng nguồn sau.

## 2. Định dạng JSON

```json
{
  "updatedAt": "2026-11-18",
  "announcements": [
    {
      "id": "v1.0.4-stt-offline-fix",
      "title": "Bản 1.0.4 — sửa lỗi nhận giọng nói offline",
      "body": "Nội dung đầy đủ, có thể dài nhiều câu.",
      "type": "info",
      "minVersion": "",
      "url": "",
      "publishedAt": "2026-11-18"
    }
  ]
}
```

Trường bắt buộc: `id` (duy nhất, không đổi) và `title`. Thiếu một trong hai thì tin bị bỏ qua.

| Trường | Ý nghĩa |
| --- | --- |
| `id` | Khoá duy nhất. **Không đổi id của tin cũ** — đổi id là app coi như tin mới và hiện lại banner. |
| `title` | Dòng tiêu đề (banner chỉ hiện tiêu đề + 2 dòng đầu của `body`). |
| `body` | Nội dung đầy đủ, hiện trong hộp thoại khi bấm banner và trong Cài đặt → Thông báo. |
| `type` | `info` (mặc định) · `upgrade` · `bookstore` · `promo` — chỉ đổi biểu tượng. |
| `minVersion` | Chỉ dùng với `type: "upgrade"`: tin **tự ẩn** khi app đã ≥ phiên bản này. |
| `url` | Tuỳ chọn. Có link thì hộp thoại hiện thêm nút **Mở liên kết**. |
| `publishedAt` | `YYYY-MM-DD`. App sắp xếp mới nhất trước (so chuỗi), tin không có ngày xuống cuối. |

`updatedAt` chỉ để người đọc file biết lần sửa cuối; app không dùng.

## 3. Quy trình phát tin

1. Sửa `announcements.json`: thêm object mới vào **đầu** mảng (hoặc cuối — app tự sắp xếp theo `publishedAt`).
2. Cập nhật `updatedAt`.
3. `git add announcements.json && git commit -m "announce: <nội dung>" && git push origin main`
4. Kiểm tra nhanh 2 URL:
   ```powershell
   (Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/ThanhMarine/Bibot/main/announcements.json' -UseBasicParsing).StatusCode
   (Invoke-WebRequest -Uri 'https://cdn.jsdelivr.net/gh/ThanhMarine/Bibot@main/announcements.json' -UseBasicParsing).StatusCode
   ```
   Cả hai phải trả `200`.
5. Trên máy người dùng: cache 24h → muốn thấy ngay thì vào **Cài đặt → Thông báo → Làm mới**.

## 4. App xử lý thế nào

- **Banner ở Home**: hiện tin *chưa đọc* mới nhất (lọc theo `isApplicable`). Bấm banner → mở hộp
  thoại đọc đầy đủ + nút **Đã hiểu** (đánh dấu đã đọc, tin kế tiếp tự hiện) và **Mở liên kết**
  (nếu có `url`). Đóng hộp thoại cũng đánh dấu đã đọc.
- **Cài đặt → Thông báo**: danh sách đầy đủ, có dòng `Cập nhật: dd/MM/yyyy HH:mm` (thời điểm tải
  thành công gần nhất) và nút **Làm mới** (bỏ qua cache). Mở mục này = đánh dấu tất cả đã đọc (tắt badge).
- **Cache**: `cache/announce.json`, TTL 24h. Hết hạn thì tải lại; tải lỗi mà còn cache thì vẫn
  hiện bản đã lưu kèm cảnh báo "Không tải được bản mới — đang hiện bản đã lưu."
- **Ngoại tuyến hoàn toàn**: vẫn đọc được tin đã cache.

## 5. Lưu ý an toàn

- File này **công khai** — không ghi thông tin nội bộ, khoá API, hay chi tiết bảo mật.
- Chỉ là nội dung tin: không chứa/mô tả gì về APK VIP riêng tư (`dist/*-vip/`).
- Nội dung do mình kiểm soát nên không cần ký; nhưng nếu repo bị chiếm quyền thì kẻ xấu có thể
  đăng tin giả → bật 2FA cho tài khoản GitHub.
- Không đổi tên file/thư mục: đổi là mọi bản app đang cài mất kênh tin (phải phát hành bản mới).
