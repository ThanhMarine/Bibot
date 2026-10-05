Bibot — Chính sách Quyền riêng tư (Privacy Policy)
Cập nhật lần cuối: 04/10/2026
Phiên bản ứng dụng: 1.0.0
Liên hệ: ngn.ngocthanh@gmail.com

Tổng quan
Bibot ("ứng dụng", "chúng tôi", "của chúng tôi") là ứng dụng đọc sách, kể chuyện, trò chuyện AI bằng giọng nói được phát triển bởi Bibot Team. Chúng tôi cam kết bảo vệ quyền riêng tư của bạn. Chính sách này giải thích loại dữ liệu nào được thu thập, cách sử dụng, và quyền của bạn.
Tóm tắt nhanh: Bibot không thu thập dữ liệu cá nhân nhận dạng được (PII). Tất cả dữ liệu người dùng (tiến độ đọc, cài đặt, khóa API, lịch sử chat) lưu cục bộ trên thiết bị. Chỉ khi bạn chủ động bật tính năng đám mây (Cloud TTS, đồng bộ Firebase) thì dữ liệu mới được gửi ra mạng — và bạn có toàn quyền tắt/bật bất kỳ lúc nào.

Dữ liệu chúng tôi XỬ LÝ (Toàn bộ cục bộ)
Loại dữ liệu Mô tả Nơi lưu trữ Mục đích
Tiến độ đọc Chương, câu, vị trí scroll, tốc độ đọc Room Database (files/bibot/) Tiếp tục đọc ngay khi mở app
Cài đặt TTS/STT Engine ưu tiên, tốc độ, giọng, mô hình offline DataStore Preferences Khôi phục tùy chọn người dùng
Khóa API (Gemini, OpenRouter, Cloud TTS) Được mã hóa trước khi lưu DataStore Preferences (encrypted) Gọi Cloud TTS/LLM khi bạn bật
Lịch sử chat AI Tin nhắn user/assistant (tối đa 500 mục) Room Database Hiển thị hội thoại, context cho AI
Thư viện sách/tải về Metadata, file .md/.txt/.pdf, tiến độ files/bibot/books/, Room Đọc offline, quản lý thư viện
Mã VIP / trạng thái VIP Tier, hết hạn, HMAC verify DataStore Preferences Xác thực VIP, tắt quảng cáo
Cài đặt ngôn ngữ/người dùng Locale, theme, persona DataStore/SharedPrefs Giao diện phù hợp
Tất cả dữ liệu trên không bao giờ rời thiết bị trừ khi bạn bật tính năng đám mây (xem mục 3).

Dữ liệu GỬI RA MẠNG (Chỉ khi bạn CHỌN bật)
Tính năng Dữ liệu gửi Đích đến Có thể tắt?
Cloud TTS (Gemini/Google) Văn bản cần đọc → nhận file audio Google Cloud / Gemini API ✅ Settings → Voice priority = "Prefer offline"
AI Chat Cloud (Gemini/OpenRouter) Prompt + lịch sử (tuỳ chọn) → nhận trả lời Google AI / OpenRouter ✅ Settings → API key = để trống (dùng Bibot Free proxy)
Đồng bộ Firestore Tiến độ đọc, bookmark, cài đặt Firebase Firestore (project của bạn) ✅ Settings → Không đăng nhập Google / không cấu hình Firebase
Tin tức / Thời tiết Request RSS/API công khai VnExpress, Tuổi Trẻ, BBC, OpenWeather ✅ Không mở tab News/Weather
Kiểm tra cập nhật / Announce Phiên bản app, locale GitHub Raw / URL bạn cấu hình ✅ Tự động, không PII
Lưu ý quan trọng:

Voice Chat (STT): Sử dụng Vosk offline — âm thanh không bao giờ rời thiết bị.
Piper TTS / Sherpa-ONNX: Chạy hoàn toàn offline trên thiết bị.
Bibot Free Proxy (nếu không có API key): Request đi qua proxy server của nhà phát triển — không log, không lưu nội dung chat.
4. Quảng cáo (Chỉ phiên bản Free / Demo)
Phiên bản Bibot Free (demo flavor) hiển thị quảng cáo AdMob để hỗ trợ phát triển:

Loại QC Tần suất Dữ liệu AdMob thu thập
Banner Luôn hiển thị dưới cùng (có thể tắt trong Settings) Advertising ID, coarse location, device info (theo chính sách Google)
Interstitial Mỗi 3 lần chuyển mode + giai đoạn đọc 16–24' Giống banner
Rewarded Xem 15s → mở 3 chương giọng Cloud Giống banner + user action
Bạn có quyền:

Tắt personalized ads trong Settings → Ads & Premium
Tắt banner hoàn toàn trong Settings
Mua VIP để xóa toàn bộ quảng cáo (commercial flavor)
Cổ tích (Fairy Tales) — Tuân thủ Google Play Families Policy:

tagForChildDirectedTreatment = true
max_ad_content_rating = "G"
personalized_ads_enabled = false
Chỉ hiển thị native inline (1/2 chu kỳ) + rewarded có parental gate (giữ nút 2s)
Không hiển thị interstitial video có tiếng trong Cổ tích.
5. Quyền truy cập thiết bị (Permissions)
Quyền Lý do Có bắt buộc?
INTERNET Cloud TTS, AI chat, sync, news, weather, ads ✅ (core)
RECORD_AUDIO Voice Chat (STT offline — xử lý cục bộ) Chỉ khi dùng Voice Chat
FOREGROUND_SERVICE_MEDIA_PLAYBACK Đọc sách khi tắt màn hình / màn hình khóa ✅ (core reading)
ACCESS_NETWORK_STATE Kiểm tra mạng trước khi tải quảng cáo / sync ✅ (core)
READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE Quét thư viện nhạc, import sách từ bộ nhớ Chỉ khi dùng Music / Import
Chúng tôi KHÔNG yêu cầu: Contacts, Location (precise), Camera, SMS, Phone.

An toàn dữ liệu & Bảo mật
Mã hóa tại chỗ: API key được lưu qua Android Keystore / EncryptedSharedPreferences.
Không truyền dữ liệu nhạy cảm qua HTTP không mã hóa — mọi request dùng HTTPS.
Xóa dữ liệu: Gỡ cài đặt app → toàn bộ dữ liệu cục bộ bị xóa (Android managed). Không có backup tự động lên Google Drive (android:allowBackup="true" nhưng fullBackupContent chỉ backup preferences, không backup database/file lớn).
Giới hạn lưu trữ: Chat log tự động xóa cũ khi > 500 mục. Cache tự dọn theo WorkManager hàng ngày.
Quyền của bạn (GDPR / CCPA / Luật Việt Nam)
Bạn có quyền:
Truy xuất — Xem toàn bộ dữ liệu cục bộ trong Settings → App Data
Sửa đổi — Thay đổi cài đặt, xóa sách, reset tiến độ
Xóa — Gỡ app hoặc Settings → Reset all settings / Delete downloads
Hủy đồng ý — Tắt Cloud TTS, AI Cloud, Sync, Ads personalization bất kỳ lúc nào
Khiếu nại — Liên hệ chúng tôi hoặc cơ quan bảo vệ dữ liệu
Chúng tôi không bán dữ liệu. Không chia sẻ với bên thứ 3 trừ các processor dưới đây.

Bộ xử lý dữ liệu thứ ba (Data Processors)
Nhà cung cấp Dịch vụ Cam kết
Google AdMob Quảng cáo (Free flavor) GDPR/CCPA compliant, Data Processing Addendum
Google Firebase Firestore Sync (tùy chọn) GDPR/CCPA, ISO 27001, data region chọn được
Google Cloud / Gemini API Cloud TTS, LLM (tùy chọn) Google Cloud DPA
OpenRouter LLM proxy (tùy chọn) Privacy Policy của OpenRouter
Bibot Free Proxy Relay miễn phí cho user không có key Không log, không lưu, chỉ forward request

Trẻ em & Families Policy
Độ tuổi mục tiêu: 13+ (toàn app), Cổ tích: thiết kế an toàn cho trẻ em (Families Policy).
Cổ tích module: Tag child-directed, rating G, tắt personalized ads, parental gate cho rewarded.
Chúng tôi không cố ý thu thập dữ liệu từ trẻ em < 13 tuổi ngoài(module Cổ tích). Nếu phát hiện, sẽ xóa ngay lập tức.

Lưu trữ & Xóa dữ liệu
Dữ liệu Thời gian lưu Xóa khi nào
Cài đặt, preferences Vĩnh viễn (cho đến khi user xóa/reset) Gỡ app / Reset settings
Tiến độ đọc, bookmark Vĩnh viễn Gỡ app / Delete book
Chat log AI Tối đa 500 mục (auto-prune) Tự động xóa cũ / Reset
File sách/tải về Vĩnh viễn (user quản lý) User xóa / Cleanup Worker
Cache / temp TTL 24h / max 150-200MB WorkManager hàng ngày

Thay đổi chính sách
Chúng tôi có thể cập nhật chính sách này. Phiên bản mới sẽ được đăng tại:
🔗 https://github.com/ThanhMarine/Bibot/privacy-policy.md
và cập nhật trong app (Settings → Announcements). Việc tiếp tục sử dụng app sau khi cập nhật tức là bạn đồng ý.

Liên hệ
Data Controller / Nhà phát triển: Bibot Team
Email: ngn.ngocthanh@gmail.com


Bibot — Privacy Policy (English Version)
Last updated: October 4, 2026
App version: 1.0.0
Contact: ngn.ngocthanh@gmail.com

Overview
Bibot ("the App", "we", "us") is a Vietnamese text-to-speech, audiobook, storytelling, and AI voice chat application developed by Bibot Team. We are committed to protecting your privacy. This policy explains what data we process, how it is used, and your rights.
Quick summary: Bibot does not collect personally identifiable information (PII). All user data (reading progress, settings, API keys, chat history) is stored locally on your device. Data only leaves your device when you explicitly enable cloud features (Cloud TTS, Firebase sync) — and you can disable them at any time.

Data We Process (Entirely Local)
Data Type Description Storage Location Purpose
Reading Progress Chapter, sentence, scroll position, speed Room Database (files/bibot/) Resume reading instantly
TTS/STT Settings Preferred engine, rate, voice, offline models DataStore Preferences Restore user preferences
API Keys (Gemini, OpenRouter, Cloud TTS) Encrypted at rest DataStore (encrypted) Call Cloud TTS/LLM when enabled
AI Chat History User/assistant messages (max 500) Room Database Display conversation, AI context
Library/Downloads Metadata, .md/.txt/.pdf files, progress files/bibot/books/, Room Offline reading, library mgmt
VIP Code / Status Tier, expiry, HMAC verification DataStore Preferences Verify VIP, remove ads
Language/UI Settings Locale, theme, persona DataStore/SharedPrefs Personalized UI
None of the above ever leaves your device unless you enable cloud features (see Section 3).

Data Sent Over Network (Only When YOU Opt In)
Feature Data Sent Destination Can Disable?
Cloud TTS (Gemini/Google) Text to synthesize → audio file Google Cloud / Gemini API ✅ Settings → Voice priority = "Prefer offline"
AI Chat Cloud (Gemini/OpenRouter) Prompt + optional history → response Google AI / OpenRouter ✅ Settings → API key = empty (uses Bibot Free proxy)
Firestore Sync Reading progress, bookmarks, settings Your Firebase project ✅ Don't sign in / don't configure Firebase
News / Weather Public RSS/API requests VnExpress, Tuổi Trẻ, BBC, OpenWeather ✅ Don't open News/Weather tabs
Update / Announce Check App version, locale GitHub Raw / configured URL ✅ Automatic, no PII
Critical notes:

Voice Chat (STT): Uses Vosk offline — audio never leaves device.
Piper TTS / Sherpa-ONNX: Run fully offline on-device.
Bibot Free Proxy (no API key): Requests relayed via developer proxy — no logging, no storage of chat content.
4. Advertising (Free / Demo Flavor Only)
The Bibot Free (demo flavor) shows AdMob ads to support development:

Ad Type Frequency AdMob Data Collected
Banner Persistent bottom (can disable in Settings) Advertising ID, coarse location, device info (per Google policy)
Interstitial Every 3 mode switches + reading phase 16–24' Same as banner
Rewarded Watch 15s → unlock 3 premium-voice chapters Same + user action
Your controls:

Disable personalized ads in Settings → Ads & Premium
Disable banner entirely in Settings
Purchase VIP to remove all ads (commercial flavor)
Fairy Tales (Cổ tích) — Google Play Families Policy Compliant:

tagForChildDirectedTreatment = true
max_ad_content_rating = "G"
personalized_ads_enabled = false
Only native inline (1 per 2 phases) + rewarded with parental gate (hold 2s)
No interstitial video with sound in Fairy Tales.
5. Device Permissions
Permission Reason Required?
INTERNET Cloud TTS, AI chat, sync, news, weather, ads ✅ Core
RECORD_AUDIO Voice Chat (STT offline — processed locally) Only when using Voice Chat
FOREGROUND_SERVICE_MEDIA_PLAYBACK Background reading with screen off/locked ✅ Core reading
ACCESS_NETWORK_STATE Network check before ads/sync ✅ Core
READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE Scan music library, import books from storage Only when using Music/Import
We do NOT request: Contacts, Precise Location, Camera, SMS, Phone.

Data Security
Encryption at rest: API keys stored via Android Keystore / EncryptedSharedPreferences.
No sensitive data over HTTP — all network requests use HTTPS.
Deletion: Uninstalling app removes all local data (Android managed). No automatic backup of large databases/files to Google Drive.
Retention limits: Chat log auto-prunes > 500 entries. Cache cleaned daily via WorkManager.
Your Rights (GDPR / CCPA / Vietnam Law)
You have the right to:
Access — View all local data in Settings → App Data
Rectify — Change settings, delete books, reset progress
Erase — Uninstall app or Settings → Reset all / Delete downloads
Withdraw consent — Disable Cloud TTS, AI Cloud, Sync, Ads personalization anytime
Complain — Contact us or your data protection authority
We do not sell your data. No sharing with third parties except processors below.

Third-Party Data Processors
Provider Service Commitment
Google AdMob Ads (Free flavor) GDPR/CCPA compliant, DPA signed
Google Firebase Firestore Sync (optional) GDPR/CCPA, ISO 27001, configurable region
Google Cloud / Gemini API Cloud TTS, LLM (optional) Google Cloud DPA
OpenRouter LLM proxy (optional) OpenRouter Privacy Policy
Bibot Free Proxy Free relay for keyless users No logs, no storage, request forward only

Children & Families Policy
Target age: 13+ (general), Fairy Tales: child-safe design (Families Policy).
Fairy Tales module: Child-directed tag, rating G, personalized ads disabled, parental gate for rewarded.
We do not knowingly collect data from children under 13 outside Fairy Tales. If discovered, deleted immediately.

Retention & Deletion
Data Retention Deleted When
Settings, preferences Until user resets/uninstalls Uninstall / Reset settings
Reading progress, bookmarks Until user deletes book Uninstall / Delete book
AI chat log Max 500 entries (auto-prune) Auto-prune old / Reset
Downloaded books/files User-managed User deletes / Cleanup Worker
Cache/temp TTL 24h / max 150-200MB Daily WorkManager

Policy Changes
We may update this policy. New version posted at:
🔗 https://github.com/ThanhMarine/Bibot/privacy-policy.md
and in-app (Settings → Announcements). Continued use = acceptance.

Contact
Data Controller / Developer: Bibot Team
Email: ngn.ngocthanh@gmail.com

