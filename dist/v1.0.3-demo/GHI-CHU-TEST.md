# GHI-CHU-TEST — Bibot v1.0.3 (Closed Testing)

## 📋 Thông tin bản build

| Thành viên | Phiên bản | VersionCode | Kích thước |
|------------|-----------|-------------|------------|
| Bibot Demo (Free + Ads) | 1.0.3 | 4 | Bibot.apk ~33.8 MB |
| Bibot VIP (Commercial) | 1.0.3 | 4 | BibotV.apk ~34.1 MB |

**SHA256:** Xem file `SHA256.txt` trong mỗi thư mục.

**Cài đặt:** `adb install -r Bibot.apk` (hoặc BibotV.apk)

---

## 🎯 Mục tiêu kiểm thử (Closed Testing GG Play)

### 1. Cài đặt & Khởi động (P0)
- [ ] APK cài thành công mà không báo lỗi
- [ ] App mở được màn hình Home (NavigationBar hiển thị 5 tab)
- [ ] Không crash/ANR khi mở lần đầu

### 2. Ngôn ngữ giao diện (P0)
- [ ] Đổi ngôn ngữ: Tiếng Việt ↔ English ↔ Theo máy trong ⚙️ Cài đặt → Ngôn ngữ
- [ ] UI hiển thị đúng ngôn ngữ đã chọn (không để trống/lỗi encoding)
- [ ] Áp dụng ngay khi bấm chip (API 33+) hoặc sau recreate (API <33)

### 3. Đọc sách / Kể chuyện (P0)
- [ ] Mở sách/truyện từ kho sách, hiển thị nội dung
- [ ] Chuyển trang (⏮/⏭) hoạt động đúng, không bị đơ
- [ ] Tùy chỉnh font size, khoảng dòng, khoảng đoạn trong ⚙️
- [ ] Chế độ trang (Trang/Trang liên tục/Một trang/Hai trang) hoạt động
- [ ] Chế độ nền (Trống đồng/Đơn sắc/Nâu cổ/Đêm/Mặc định)

### 4. TTS / Đọc aloud (P0)
- [ ] Bấm nút "Đọc"/"Kể" phát âm thanh
- [ ] Giọng đọc offline (Piper VI/EN) phát thành công
- [ ] Dừng/tiếp tục phát âm thanh
- [ ] Không bị treo khi đọc sách dài (>200 câu)

### 5. VIP / Mã đổi mã (P0)
- [ ] Nhập mã VIP → kích hoạt thành công (hiển thị "VIP tháng/năm")
- [ ] Tắt mạng → VIP vẫn giữ (grace offline 7 ngày)
- [ ] Đổi mã khác khi offline → báo "Cần mạng để xác thực"
- [ ] Vặn đồng hồ ngược >24h → VIP rớt (chống chọc đồng hồ)

### 6. Quảng cáo & Play Integrity (P0)
- [ ] Quảng cáo hiển thị đúng vị trí (banner, interstitial)
- [ ] Xem quảng cáo 15s → mở 3 chapter giọng Cloud
- [ ] Play Integrity token không gây crash app

### 7. Tiện ích khác (P1)
- [ ] Thời tiết: hiển thị đúng thành phố, nhiệt độ
- [ ] Tin tức: tải danh mục, đọc tin liên tục
- [ ] Nhạc: phát file trong thư mục Bibot
- [ ] PDF: mở file PDF, hiển thị trang, không bị đơ UI

---

## 🧪 Cách kiểm thử nhanh (5-10 phút)

```
1. Cài APK → mở app → chờ home xuất hiện (<3s)
2. Vào ⚙️ → Ngôn ngữ → đổi sang English/Tiếng Việt → kiểm tra UI đổi ngay
3. Vào Kho sách → chọn 1 sách → bấm Đọc → chuyển trang 3-4 lần
4. Bấm nút Đọc/Kể → kiểm tra âm thanh phát
5. Vào VIP → nhập mã test → kiểm tra kích hoạt
6. Tắt máy bay (mạng) → vào VIP → kiểm tra giữ VIP (grace)
7. Thoát app → mở lại → không crash
```

---

## 🐛 Báo cáo lỗi

Ghi rõ: Thiết bị, Android version, Bước tái tạo, Ảnh chụp màn hình, Logcat nếu có crash.

**Gửi qua:** GitHub Issues hoặc email `bibotvadmin@gmail.com`

---

*Ngày tạo: 2026-10-26 | Bản build: v1.0.3 (code 4)*
