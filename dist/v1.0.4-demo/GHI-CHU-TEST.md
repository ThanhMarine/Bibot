# GHI-CHU-TEST — Bibot v1.0.4 (Closed Testing)

## 📋 Thông tin bản build

| Thành viên | Phiên bản | VersionCode | Kích thước |
|------------|-----------|-------------|------------|
| Bibot Demo (Free + Ads) | 1.0.4 | 5 | Bibot.apk ~33.8 MB |
| Bibot VIP (Commercial) | 1.0.4 | 5 | BibotV.apk ~34.1 MB |

**SHA256:** Xem file `SHA256.txt` trong mỗi thư mục.

**Cài đặt:** `adb install -r Bibot.apk` (hoặc BibotV.apk)

---

## 🆕 Có gì mới ở 1.0.4

- **Sửa lỗi STT (nhận dạng giọng nói) không dùng model đã cài:**
  - Máy không có / không dùng được dịch vụ nhận dạng của Google (một số ROM Trung Quốc, máy không có Google app) giờ **tự dùng model STT offline** thay vì báo lỗi.
  - Khi STT online lỗi mạng/timeout/no-match, app chuyển sang model offline ngay (trước đây bỏ qua ở push-to-talk và nghe liên tục).
  - Báo lỗi đúng nguyên nhân: **chưa cài model** ≠ **đã cài model nhưng thiếu engine sherpa-onnx (~46MB)** ≠ **không nghe được câu nào**.
  - Không còn im lặng khi push-to-talk không nhận được chữ.
  - Engine sherpa-onnx: không treo 15–120s khi máy không có mạng và chưa cài engine; cài engine xong không cần khởi động lại app.
  - Ổn định: decode sherpa được tuần tự hoá (trước đây nghe liên tục + push-to-talk cùng lúc có thể gây crash native).
- **Nâng cấp trải nghiệm đọc:**
  - PDF: chụm 2 ngón để phóng to (1×–4×) + kéo trang để di chuyển; chạm đúp phóng/thu nhanh; nút "1×" ở góc trang để về cỡ gốc.
  - Cột chữ dàn trang cân bằng: lề trái = lề phải, cột tối đa 640dp (căn giữa trên máy lớn), ngắt dòng + gạch nối từ điển → chữ đều, không còn lệch trái / lề phải rộng.

---

## 🎯 Mục tiêu kiểm thử (Closed Testing GG Play)

### 1. Cài đặt & Khởi động (P0)
- [ ] APK/AAB cài thành công mà không báo lỗi
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

### 5. STT offline / Trò chuyện bằng giọng nói (P0 — trọng tâm 1.0.4)
- [ ] Cài model: ⚙️ Cài đặt → Mô hình offline → **Sherpa STT VI Zipformer 30M** → cài xong báo "Đã sẵn sàng"
- [ ] Cài engine khi có mạng (lần đầu ~46MB) → không treo UI, có tiến độ
- [ ] Vào màn **Chat** → bấm **Chạm để nói** → nói 1 câu tiếng Việt ngắn → có chữ hiển thị và bot trả lời
- [ ] Bật **nghe liên tục** → nói 2–3 câu liên tiếp → nhận đúng từng câu, không crash
- [ ] **Tắt mạng (máy bay)** rồi nói lại → vẫn nhận dạng bằng model offline
- [ ] Chưa cài model + tắt mạng → báo rõ "Chưa cài model STT offline..." (không im lặng)
- [ ] Đã cài model nhưng **chưa cài engine** + tắt mạng → báo rõ "Model sherpa-vi đã cài nhưng thiếu engine sherpa-onnx..."
- [ ] Nói quá nhỏ/không nói gì → báo "Không nhận diện được giọng nói. Nói to/rõ hơn..."

### 6. VIP / Mã đổi mã (P0)
- [ ] Nhập mã VIP → kích hoạt thành công (hiển thị "VIP tháng/năm")
- [ ] Tắt mạng → VIP vẫn giữ (grace offline 7 ngày)
- [ ] Đổi mã khác khi offline → báo "Cần mạng để xác thực"
- [ ] Vặn đồng hồ ngược >24h → VIP rớt (chống chọc đồng hồ)

### 7. Quảng cáo & Play Integrity (P0)
- [ ] Quảng cáo hiển thị đúng vị trí (banner, interstitial)
- [ ] Xem quảng cáo 15s → mở 3 chapter giọng Cloud
- [ ] Play Integrity token không gây crash app

### 8. Tiện ích khác (P1)
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
4. Vào ⚙️ → Mô hình offline → cài Sherpa STT VI → báo "Đã sẵn sàng"
5. Vào Chat → bấm "Chạm để nói" → nói 1 câu → có chữ + bot trả lời
6. Bật máy bay → nói lại → vẫn nhận dạng offline (hoặc báo lỗi rõ nguyên nhân)
7. Vào VIP → nhập mã test → kiểm tra kích hoạt
8. Thoát app → mở lại → không crash
```

---

## 🐛 Báo cáo lỗi

Ghi rõ: Thiết bị, Android version, Bước tái tạo, Ảnh chụp màn hình, Logcat nếu có crash.

**Gửi qua:** GitHub Issues hoặc email `bibotvadmin@gmail.com`

---

*Ngày tạo: 2026-11-15 | Bản build: v1.0.4 (code 5)*
