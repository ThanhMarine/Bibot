# GHI-CHU-TEST - Bibot v1.0.5 Demo (Closed Testing)

## Thông tin bản build

| Thành viên | Phiên bản | VersionCode | Kích thước |
|------------|-----------|-------------|------------|
| Bibot Demo (Free + Ads) | 1.0.5 | 6 | Bibot.aab ~23.2 MB |
| Bibot VIP (Commercial) | 1.0.5-vip | 6 | BibotV.aab ~23.5 MB |

minSdk 26 (Android 8.0) · targetSdk 36 · ABI: arm64-v8a, armeabi-v7a, x86_64

## Thành phần nộp Play Console (Demo / com.bibot.app)

- **App bundle:** `Bibot.aab` (versionCode 6)
- **Deobfuscation file:** `mapping.txt`
- **Native debug symbols:** `native-debug-symbols.zip`
- **Kiểm tra toàn vẹn:** `SHA256.txt`

## Checklist kiểm thử

- [ ] Cài từ AAB qua Internal testing trên Android 8.0+ và Android 14/15
- [ ] Mở app, persona "Em là Bi", greeting đúng
- [ ] Home hiển thị icon Fluent Emoji 3D (không bị tint màu)
- [ ] Cài đặt → chọn thành phố thời tiết; bản tin đúng thành phố đã chọn
- [ ] STT/TTS offline, reader sách, chat AI hoạt động
- [ ] Cài đặt → Giấy phép & ghi công mở được, không crash
- [ ] Kiểm tra quảng cáo AdMob (bản Free)
