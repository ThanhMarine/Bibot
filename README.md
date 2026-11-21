# Bibot — Trợ lý đọc & nghe tiếng Việt

> Đọc sách, kể chuyện, trò chuyện AI bằng giọng nói — offline-first, không bắt buộc đăng nhập.

> **Bản public:** `v1.0.5-demo` (Free, có quảng cáo) · Package `com.bibot.app` · Android 8.0+

## Bibot là gì?

Bibot ("Em là Bi") là ứng dụng Android đọc sách & kể chuyện tiếng Việt: kho truyện Việt, cổ tích gia đình, sách tiếng Anh, chat thoại AI, tin tức, thời tiết, phát nhạc trên máy — chạy mượt cả khi tắt màn hình.

- Không thu thập dữ liệu cá nhân. Mọi tiến độ đọc, cài đặt, API key lưu trên máy bạn.
- Chỉ khi bạn chủ động bật Cloud TTS / AI cloud / đồng bộ thì dữ liệu mới ra mạng — tắt được bất kỳ lúc nào.
- STT (nhận dạng giọng nói) chạy offline, giọng nói không rời thiết bị.

## Tính năng chính

- 📖 **Kể chuyện Việt** — truyện sử Việt, cổ tích, tiểu thuyết; highlight karaoke theo câu, đọc nền, hẹn giờ.
- 🧚 **Cổ tích gia đình** — giao diện an toàn cho trẻ, giọng kể dịu, tuân thủ Families Policy.
- 📚 **Sách tiếng Anh** — đọc sách miễn phí bằng giọng en-US, học từ vựng song song.
- 🎙️ **Trò chuyện AI** — chat thoại thực tế, dùng API key của bạn (Gemini/OpenRouter) hoặc relay miễn phí; persona "Em là Bi" tùy biến trong Cài đặt.
- 🌤️ **Tiện ích đọc bằng giọng nói** — thời tiết 3 ngày, điểm tin VnExpress/Tuổi Trẻ/BBC, quét nhạc trên máy.
- ⚙️ **Đa engine giọng nói** — Piper / Sherpa-ONNX offline, Android TTS, Cloud TTS (khi có mạng); tốc độ 0.5–2.0x.
- 🧩 **Fluent Emoji 3D** — icon Home + Settings chuyển từ Material Emoji sang bề mặt hình ảnh 3D Fluent (MIT, Microsoft) cho giao diện sắc nét hơn.
- 🧭 **Thời tiết theo thành phố** — chọn HCM, Hà Nội, Đà Nẵng, Hải Phòng, Cần Thơ trong Cài đặt → Bản tin thời tiết 3 ngày theo khu vực của bạn.
- 📜 **Giấy phép & ghi công** — license MIT (Microsoft), chi tiết trong `THIRD_PARTY_NOTICES.md` + section Cài đặt → Giấy phép & ghi công.

## Tải về & cài đặt (không qua CH Play)

1. Vào [**Releases**](https://github.com/ThanhMarine/Bibot/releases) → tải `Bibot-v1.0.5-demo.apk` (~34MB).
2. Mở file trên điện thoại → cho phép **"Cài đặt ứng dụng không xác định"** 1 lần → Cài đặt.
3. Mở app, cấp quyền Microphone khi dùng Voice Chat. Không cần tài khoản.

- Yêu cầu: Android 8.0+, ~150MB trống.
- Kiểm tra toàn vẹn: đối chiếu SHA256 trong `SHA256.txt` kèm theo mỗi bản release.
- Cập nhật: tải APK mới đè lên bản cũ (giữ nguyên chữ ký nên không mất dữ liệu).

> Bản VIP (`com.bibot.app.vip`, không quảng cáo) là bản đặc biệt, **chưa phát hành đợt này**.

## Quyền riêng tư & quảng cáo

- Chính sách đầy đủ (Việt + Anh): [privacy-policy.md](https://github.com/ThanhMarine/Bibot/blob/main/privacy-policy.md) · [bản raw](https://raw.githubusercontent.com/ThanhMarine/Bibot/main/privacy-policy.md)
- Tóm tắt: không PII, không theo dõi vị trí, không đọc danh bạ/tin nhắn. API key mã hóa trên máy, mọi request dùng HTTPS.
- Bản Free hiển thị quảng cáo AdMob (banner / interstitial / rewarded) để duy trì phát triển; tắt được cá nhân hóa trong Cài đặt → Quảng cáo & Premium. File xác minh: [`app-ads.txt`](https://github.com/ThanhMarine/Bibot/blob/main/app-ads.txt) (`pub-2478233272401556`).
- Quyền app xin: Internet, Microphone (chỉ khi voice chat), Foreground Service (đọc nền), kiểm tra mạng, đọc media (chỉ khi import sách/nhạc).

## Hỗ trợ & báo lỗi

- Báo lỗi / góp ý truyện, giọng đọc: mở [**Issues**](https://github.com/ThanhMarine/Bibot/issues) (mô tả máy + Android + các bước tái hiện).
- Email: bibotvadmin@gmail.com
- Đây là repo phát hành, **chưa open-source mã nguồn** nên hiện chưa nhận pull request code. Mọi góp ý nội dung, bản dịch, giọng đọc đều welcome qua Issues.

## Lộ trình

- [x] v1.0.0-demo public qua GitHub Releases
- [x] v1.0.5-demo: Fluent Emoji 3D icons + weather city picker (5 thành phố Việt Nam) + version bump
- [ ] Bản cập nhật OTA trong app + kênh store hãng (Galaxy/Xiaomi/Huawei)
- [ ] Thêm kho truyện + model offline tiếng Việt
- [ ] Bản VIP phát hành riêng

## Giấy phép

© 2026 Bibot Team. Bảo lưu mọi quyền đối với mã nguồn và thương hiệu.
Bản APK Free được phép tải, cài và chia sẻ nguyên vẹn cho mục đích cá nhân, phi thương mại.

---

### English summary

Bibot ("Em là Bi") is a Vietnamese reading & listening assistant for Android: audiobooks, fairy tales, English books, AI voice chat, news, weather and on-device music — offline-first, no login required. Grab `Bibot-v1.0.5-demo.apk` from [Releases](https://github.com/ThanhMarine/Bibot/releases) (Android 8.0+), allow unknown-source install once, and open. Privacy policy: [privacy-policy.md](https://github.com/ThanhMarine/Bibot/blob/main/privacy-policy.md). Contact: bibotvadmin@gmail.com. Source code is not open-source yet; feedback via Issues is welcome.