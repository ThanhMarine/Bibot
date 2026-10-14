Bibot — Điều Khoản Sử Dụng (Terms of Use)
Cập nhật lần cuối: 14/10/2026
Phiên bản ứng dụng: 1.0.2 (versionCode 3)
Gói ứng dụng: com.bibot.app (Free) · com.bibot.app.vip (VIP)
Nhà phát triển: Bibot Team
Liên hệ: bibotvadmin@gmail.com · GitHub: https://github.com/ThanhMarine/Bibot

> Bằng việc tải, cài đặt hoặc sử dụng Bibot, bạn xác nhận đã đọc, hiểu và đồng ý với Điều Khoản này cùng Chính sách Quyền riêng tư (https://raw.githubusercontent.com/ThanhMarine/Bibot/main/privacy-policy.md). Nếu không đồng ý, vui lòng không sử dụng ứng dụng.

---

## 1. Mô tả dịch vụ

1.1. Bibot ("ứng dụng", "dịch vụ", "chúng tôi") là ứng dụng đọc sách, kể chuyện, trò chuyện AI bằng giọng nói, nghe tin tức/thời tiết/nhạc trên Android (điện thoại + tablet).
1.2. Tính năng chính:
- 📚 Sách & Kể chuyện: đọc offline, highlight karaoke theo câu, đọc nền/khóa màn hình, lưu tiến độ (chương, câu).
- 🧚 Cổ tích: giao diện thân thiện gia đình, giọng kể dịu dàng.
- 📖 Sách tiếng Anh: kho sách công cộng miễn phí (Project Gutenberg v.v.), giọng en-US.
- 🎙️ Trò chuyện AI (Voice Chat): STT (nhận dạng giọng nói) + LLM (Gemini/OpenRouter hoặc proxy miễn phí) + TTS đa engine (Piper offline, Sherpa-ONNX, Android TTS, Cloud TTS Gemini/Google).
- ⛅🌤️ Tiện ích: thời tiết 3 ngày, điểm tin audio, trình phát nhạc local.
- ⚙️ Tùy biến: persona "Em là Bi", phong cách/tông giọng bot prompt, tốc độ đọc 0.5–2.0x, mô hình offline tải khi cần.
1.3. Bibot hoạt động theo mô hình online-first + fallback offline. Một số tính năng (Cloud TTS, AI chat cloud, đồng bộ Firestore, tin tức/thời tiết, quảng cáo, kiểm tra cập nhật) yêu cầu Internet.
1.4. Hai phiên bản:
- Bibot Free (com.bibot.app): đầy đủ tính năng, có quảng cáo AdMob.
- Bibot VIP (com.bibot.app.vip): không quảng cáo, ưu tiên giọng Cloud và hỗ trợ (xem Mục 7).

## 2. Chấp nhận & điều kiện sử dụng

2.1. Độ tuổi: ứng dụng dành cho người từ 13 tuổi trở lên. Người dưới 13 tuổi chỉ sử dụng module Cổ tích dưới sự giám sát của cha mẹ/người giám hộ.
2.2. Module Cổ tích tuân thủ Google Play Families Policy: gắn cờ child-directed, xếp hạng quảng cáo G, tắt cá nhân hóa quảng cáo, rewarded có parental gate (giữ nút 2 giây).
2.3. Bạn cam kết cung cấp thông tin đúng khi liên hệ hỗ trợ, bảo mật mã VIP / API key của mình, và sử dụng ứng dụng đúng pháp luật Việt Nam và nơi cư trú.
2.4. Chúng tôi có thể từ chối hoặc hạn chế truy cập nếu phát hiện lạm dụng, gian lận, hoặc vi phạm Điều Khoản (xem Mục 10).

## 3. API key & tài khoản của bạn

3.1. Một số tính năng nâng cao dùng API key của chính bạn (Gemini, OpenRouter, Cloud TTS). Key được mã hóa lưu cục bộ trên thiết bị (Android Keystore / EncryptedSharedPreferences) và chỉ gửi tới nhà cung cấp tương ứng khi bạn bật tính năng.
3.2. Nếu để trống key, ứng dụng dùng Bibot Free proxy (chuyển tiếp, không log/không lưu nội dung) hoặc chế độ mock offline — chất lượng có thể hạn chế.
3.3. Bạn chịu trách nhiệm: (a) bảo mật key của mình; (b) chi phí phát sinh từ nhà cung cấp API (Google/OpenRouter) theo biểu giá của họ; (c) không dùng chung key trái phép. Chúng tôi không chịu trách nhiệm nếu key của bạn bị lộ do chia sẻ thiết bị hoặc sao lưu không an toàn.
3.4. Đồng bộ Firestore là tùy chọn, yêu cầu bạn tự cấu hình Firebase / đăng nhập Google. Dữ liệu đồng bộ thuộc quản lý của bạn.

## 4. Sử dụng được phép & bị cấm

4.1. Bạn được phép: cài đặt, sử dụng cá nhân, phi thương mại; import sách/file của mình để đọc; tùy biến persona/prompt cho nhu cầu cá nhân.
4.2. Bạn KHÔNG được:
- (a) đảo ngược kỹ thuật, trích xuất model/engine, vượt qua VIP/parential gate/quảng cáo, can thiệp thanh toán Play Billing;
- (b) dùng app để vi phạm pháp luật: phát tán nội dung đồi trụy, bạo lực, thù ghét, xâm phạm trẻ em, lừa đảo, spam, thu thập dữ liệu người khác;
- (c) lạm dụng tài nguyên: gửi request tự động số lượng lớn tới proxy miễn phí, vượt qua giới hạn (rate limit), gây quá tải dịch vụ;
- (d) ghi âm người khác trái phép qua tính năng Voice Chat; bạn chịu trách nhiệm tuân thủ luật ghi âm tại địa phương;
- (e) mạo danh Bibot Team, phân phối lại APK/AAB đã chỉnh sửa dưới tên Bibot.
4.3. Vi phạm có thể dẫn tới khóa mã VIP, chặn proxy miễn phí, hoặc gỡ quyền sử dụng mà không hoàn tiền (trừ nghĩa vụ hoàn tiền của Google Play nếu có).

## 5. Nội dung & sở hữu trí tuệ

5.1. Kho sách trong app gồm 3 nguồn: (a) sách công cộng public domain (Project Gutenberg, cổ tích/sử Việt hết bảo hộ); (b) kho Drive chính thức do Bibot Team tuyển chọn; (c) file do chính bạn import (SAF/DOC/link URL/local scan).
5.2. Chúng tôi tôn trọng bản quyền. Nếu bạn là chủ sở hữu quyền và cho rằng nội dung trong kho chính thức vi phạm, hãy gửi email tới bibotvadmin@gmail.com kèm bằng chứng — chúng tôi sẽ gỡ trong tối đa 7 ngày làm việc.
5.3. File bạn import: bạn cam kết có quyền sử dụng hợp pháp và chịu toàn bộ trách nhiệm. Chúng tôi không quét, không xác minh, không lưu trữ nội dung này trên máy chủ (toàn bộ nằm trên thiết bị của bạn).
5.4. Thương hiệu, logo "Bi"/trống đồng, giao diện, mã nguồn Go/Kotlin thuộc Bibot Team. Không cấp phép sao chép ngoài phạm vi sử dụng app.
5.5. Nội dung AI tạo sinh (câu trả lời của "Bi") không phải tác phẩm của con người và có thể sai — xem Mục 6.

## 6. Tuyên bố về AI — Không phải lời khuyên chuyên môn

6.1. Trò chuyện AI chỉ mang tính tham khảo, giải trí, học tập. AI có thể "ảo giác" (bịa thông tin, sai ngày/giờ/giá/tin tức).
6.2. KHÔNG dùng câu trả lời AI thay cho tư vấn y tế, pháp lý, tài chính, hoặc quyết định an toàn. Luôn kiểm chứng từ nguồn chính thống.
6.3. Tóm tắt tin tức/thời tiết bằng giọng nói có thể lược ý — hãy đối chiếu bản gốc (VnExpress, Tuổi Trẻ, Dân Trí, Google News, OpenWeather).
6.4. Bạn tự chịu trách nhiệm khi hành động theo nội dung AI.

## 7. Quảng cáo, VIP & thanh toán qua Google Play (IAP)

7.1. Bản Free hiển thị quảng cáo AdMob: banner (tắt được trong Settings), interstitial (mỗi 3 lần chuyển mode + giai đoạn đọc 16–24'), rewarded (xem 15s → mở 3 chương giọng Cloud). Cổ tích chỉ hiện native inline + rewarded có parental gate, không interstitial video có tiếng.
7.2. Bạn có thể tắt cá nhân hóa quảng cáo trong Settings → Ads & Premium. Mua VIP để xóa toàn bộ quảng cáo.
7.3. Thanh toán VIP / gói trong app (khi phát hành) thực hiện qua Google Play Billing. Giá hiển thị đã gồm thuế theo quy định của Google; hóa đơn và hoàn tiền tuân thủ chính sách của Google Play (trừ khi luật địa phương quy định khác có lợi hơn cho bạn).
7.4. Hiện tại VIP kích hoạt bằng mã code (HMAC verify, lưu cục bộ). Mã dùng sai mục đích, chia sẻ lậu, hoặc giả mạo sẽ bị vô hiệu hóa. Khi chuyển sang IAP, mã code cũ (nếu có) được xử lý theo thông báo trong app.
7.5. Rewarded unlock (3 chương giọng Cloud) là ưu đãi sử dụng, không phải tài sản, không quy đổi tiền mặt, có thể thay đổi khi cần chống lạm dụng.

## 8. Dịch vụ bên thứ ba

8.1. App tích hợp: Google AdMob (quảng cáo), Firebase (đồng bộ tùy chọn), Google Cloud/Gemini API (Cloud TTS/LLM), OpenRouter (LLM), Bibot Free proxy (relay miễn phí), nguồn tin công khai (RSS VnExpress/Tuổi Trẻ/BBC...), OpenWeather, GitHub Raw (kiểm tra cập nhật/thông báo).
8.2. Khi dùng các dịch vụ này, bạn đồng thời chịu điều khoản & chính sách của họ. Chúng tôi không kiểm soát thời gian uptime, chất lượng giọng, hay độ chính xác của bên thứ ba.
8.3. Thiết bị & quyền: INTERNET (bắt buộc), RECORD_AUDIO (chỉ khi Voice Chat, STT Vosk xử lý cục bộ), FOREGROUND_SERVICE_MEDIA_PLAYBACK (đọc nền), ACCESS_NETWORK_STATE, READ_MEDIA_AUDIO/READ_EXTERNAL_STORAGE (chỉ khi dùng Nhạc/Import). Chi tiết xem Privacy Policy.

## 9. Miễn trừ bảo hành & giới hạn trách nhiệm

9.1. Ứng dụng cung cấp "NGUYÊN TRẠNG" (AS-IS), không bảo đảm tuyệt đối: không gián đoạn, không lỗi, không mất tiến độ, model offline tương thích mọi thiết bị.
9.2. Trong phạm vi luật cho phép, Bibot Team không chịu trách nhiệm cho thiệt hại gián tiếp, mất dữ liệu, mất lợi nhuận, chi phí API bên thứ ba, hoặc hành động theo nội dung AI/tin tức/thời tiết.
9.3. Tổng trách nhiệm (nếu có) không vượt quá số tiền bạn đã trả cho Bibot trong 12 tháng gần nhất (hoặc 0đ nếu dùng bản Free).
9.4. Không điều khoản nào hạn chế quyền lợi người tiêu dùng mà luật Việt Nam bắt buộc bảo vệ.

## 10. Tạm ngưng & chấm dứt

10.1. Bạn có thể ngừng dùng bất kỳ lúc nào bằng cách gỡ app (dữ liệu cục bộ bị xóa theo cơ chế Android).
10.2. Chúng tôi có thể tạm khóa proxy miễn phí, mã VIP gian lận, hoặc tính năng bị lạm dụng mà không báo trước khi cần bảo vệ hệ thống.
10.3. Khi chấm dứt, quyền sử dụng của bạn hết hiệu lực; các mục 5–9, 11 vẫn còn hiệu lực.

## 11. Thay đổi Điều Khoản

11.1. Chúng tôi có thể cập nhật Điều Khoản khi thêm tính năng, đổi mô hình VIP/IAP, hoặc theo yêu cầu của Google Play / pháp luật.
11.2. Bản mới đăng tại 🔗 https://raw.githubusercontent.com/ThanhMarine/Bibot/main/terms.md và thông báo trong app (Settings → Announcements). Ngày "Cập nhật lần cuối" ở đầu là căn cứ.
11.3. Tiếp tục sử dụng sau khi cập nhật đồng nghĩa bạn đồng ý. Nếu không đồng ý, hãy ngừng sử dụng và gỡ app.

## 12. Luật áp dụng & giải quyết tranh chấp

12.1. Điều Khoản điều chỉnh theo pháp luật Việt Nam.
12.2. Tranh chấp ưu tiên thương lượng thiện chí qua email bibotvadmin@gmail.com trong 30 ngày. Nếu không đạt, đưa ra tòa án có thẩm quyền tại Việt Nam.
12.3. Nếu điều khoản nào vô hiệu, các điều khoản còn lại vẫn hiệu lực.

## 13. Liên hệ

- Nhà phát triển / Data Controller: Bibot Team (nhà phát triển cá nhân tại Việt Nam)
- Email: bibotvadmin@gmail.com
- GitHub: https://github.com/ThanhMarine/Bibot
- Privacy Policy: https://raw.githubusercontent.com/ThanhMarine/Bibot/main/privacy-policy.md
- Terms URL (bản này): https://raw.githubusercontent.com/ThanhMarine/Bibot/main/terms.md

---
---

# Bibot — Terms of Use (English Version)
Last updated: October 14, 2026
App version: 1.0.2 (versionCode 3)
Packages: com.bibot.app (Free) · com.bibot.app.vip (VIP)
Developer: Bibot Team
Contact: bibotvadmin@gmail.com · GitHub: https://github.com/ThanhMarine/Bibot

> By downloading, installing, or using Bibot, you agree to these Terms and to the Privacy Policy (https://raw.githubusercontent.com/ThanhMarine/Bibot/main/privacy-policy.md). If you disagree, please do not use the app.

## 1. Service description

1.1. Bibot ("the App", "the Service", "we") is a Vietnamese reading, storytelling, AI voice chat, news/weather/music app for Android (phones + tablets).
1.2. Key features:
- 📚 Books & Storytelling: offline reading, sentence karaoke highlighting, background/lock-screen playback, saved progress (chapter, sentence).
- 🧚 Fairy Tales: family-friendly UI with gentle narration.
- 📖 English Books: free public-domain library (Project Gutenberg, etc.) with en-US voices.
- 🎙️ AI Voice Chat: STT + LLM (Gemini/OpenRouter or free proxy) + multi-engine TTS (Piper offline, Sherpa-ONNX, Android TTS, Cloud TTS Gemini/Google).
- ⛅ Utilities: 3-day weather, audio news digest, local music player.
- ⚙️ Customization: "Em là Bi" persona, bot prompt style/tone, 0.5–2.0x speed, on-demand offline models.
1.3. Bibot is online-first with offline fallback. Cloud TTS, cloud AI chat, Firestore sync, news/weather, ads, and update checks require Internet.
1.4. Two flavors:
- Bibot Free (com.bibot.app): full features with AdMob ads.
- Bibot VIP (com.bibot.app.vip): ad-free with Cloud-voice priority and support (see Section 7).

## 2. Acceptance & eligibility

2.1. Age: 13+. Users under 13 may only use the Fairy Tales module under parental/guardian supervision.
2.2. Fairy Tales complies with Google Play Families Policy: child-directed tag, G ad rating, personalized ads off, rewarded behind a parental gate (2-second hold).
2.3. You agree to provide accurate info when contacting support, safeguard your VIP code / API keys, and use the app lawfully.
2.4. We may restrict access for abuse, fraud, or Terms violations (see Section 10).

## 3. Your API keys & accounts

3.1. Advanced features use your own API keys (Gemini, OpenRouter, Cloud TTS). Keys are encrypted on-device (Android Keystore / EncryptedSharedPreferences) and sent only to the respective provider when you enable the feature.
3.2. With no key, the app uses the Bibot Free proxy (forward-only, no logging/storage) or offline mock mode — quality may be limited.
3.3. You are responsible for: (a) keeping keys secret; (b) any provider charges (Google/OpenRouter pricing); (c) not sharing keys unlawfully. We are not liable if your key leaks via device sharing or insecure backup.
3.4. Firestore sync is optional and requires your own Firebase / Google sign-in. Synced data is under your control.

## 4. Acceptable use

4.1. You may: install and use the app for personal, non-commercial purposes; import your own files; customize persona/prompts for personal use.
4.2. You must NOT:
- (a) reverse-engineer, extract models/engines, bypass VIP/parental gates/ads, or tamper with Play Billing;
- (b) break the law: distribute obscene, violent, hateful, child-harmful, fraudulent, spam, or data-harvesting content;
- (c) abuse resources: automated bulk requests to the free proxy, bypassing rate limits, overloading the service;
- (d) record others without consent via Voice Chat; you must follow local recording laws;
- (e) impersonate Bibot Team or redistribute modified APKs/AABs as Bibot.
4.3. Violations may lead to VIP revocation, free-proxy blocking, or loss of access without refund (except Google Play refund duties where applicable).

## 5. Content & intellectual property

5.1. Library sources: (a) public-domain books (Project Gutenberg, expired-copyright Vietnamese tales/histories); (b) official Drive library curated by Bibot Team; (c) files you import (SAF/DOC/URL/local scan).
5.2. We respect copyright. Rights holders: email bibotvadmin@gmail.com with evidence — we remove infringing official-library content within 7 business days.
5.3. Imported files: you warrant lawful rights and bear full responsibility. We do not scan, verify, or upload them (they stay on your device).
5.4. Brand, "Bi"/bronze-drum logos, UI, Go/Kotlin code belong to Bibot Team. No copying beyond app usage is licensed.
5.5. AI-generated replies are not human works and may be wrong — see Section 6.

## 6. AI disclaimer — not professional advice

6.1. AI chat is for reference, entertainment, and learning. AI may hallucinate (fabricate dates/prices/news).
6.2. Do NOT substitute AI replies for medical, legal, financial, or safety advice. Always verify with authoritative sources.
6.3. Spoken news/weather summaries may omit nuance — check originals (VnExpress, Tuoi Tre, Dan Tri, Google News, OpenWeather).
6.4. You act on AI content at your own risk.

## 7. Ads, VIP & Google Play purchases (IAP)

7.1. Free shows AdMob ads: banner (disableable in Settings), interstitial (every 3 mode switches + 16–24 min reading phase), rewarded (watch 15s → unlock 3 premium Cloud-voice chapters). Fairy Tales shows only inline native + gated rewarded, no sounding interstitial video.
7.2. Disable ad personalization in Settings → Ads & Premium. Buy VIP to remove all ads.
7.3. Paid VIP / in-app items (when released) use Google Play Billing. Prices include taxes per Google; invoicing and refunds follow Google Play policy (unless local law grants you stronger rights).
7.4. VIP is currently activated by code (HMAC-verified, stored locally). Misused, shared, or forged codes are disabled. Legacy codes will be handled per in-app notice after IAP migration.
7.5. Rewarded unlocks (3 Cloud-voice chapters) are usage perks, not property, not cash-convertible, and may change to prevent abuse.

## 8. Third-party services

8.1. Integrations: Google AdMob (ads), Firebase (optional sync), Google Cloud/Gemini API (Cloud TTS/LLM), OpenRouter (LLM), Bibot Free proxy (free relay), public feeds (VnExpress/Tuoi Tre/BBC RSS...), OpenWeather, GitHub Raw (update/announcement checks).
8.2. Their terms/policies also apply. We do not control their uptime, voice quality, or accuracy.
8.3. Device permissions: INTERNET (required), RECORD_AUDIO (Voice Chat only, Vosk STT on-device), FOREGROUND_SERVICE_MEDIA_PLAYBACK (background reading), ACCESS_NETWORK_STATE, READ_MEDIA_AUDIO/READ_EXTERNAL_STORAGE (Music/Import only). See Privacy Policy for details.

## 9. Warranty disclaimer & liability limit

9.1. The app is provided "AS-IS" without guarantees of uninterrupted, error-free, lossless, or universally compatible operation.
9.2. To the extent permitted by law, Bibot Team is not liable for indirect loss, data loss, lost profits, third-party API costs, or actions taken on AI/news/weather content.
9.3. Total liability (if any) is capped at what you paid for Bibot in the last 12 months (or 0 if on Free).
9.4. Nothing limits mandatory consumer rights under Vietnamese law.

## 10. Suspension & termination

10.1. You may stop anytime by uninstalling (local data removed by Android).
10.2. We may suspend the free proxy, fraudulent VIP codes, or abused features without notice to protect the system.
10.3. On termination your usage rights end; Sections 5–9 and 11 survive.

## 11. Changes to Terms

11.1. We may update these Terms for new features, VIP/IAP changes, or Google Play / legal requirements.
11.2. New versions are posted at 🔗 https://raw.githubusercontent.com/ThanhMarine/Bibot/main/terms.md and announced in-app (Settings → Announcements). The "Last updated" date governs.
11.3. Continued use = acceptance. Otherwise stop using and uninstall.

## 12. Governing law & disputes

12.1. Governed by the laws of Vietnam.
12.2. Disputes: good-faith negotiation via bibotvadmin@gmail.com for 30 days; otherwise competent courts in Vietnam.
12.3. If any clause is invalid, the rest remain effective.

## 13. Contact

- Developer / Data Controller: Bibot Team (individual developer in Vietnam)
- Email: bibotvadmin@gmail.com
- GitHub: https://github.com/ThanhMarine/Bibot
- Privacy Policy: https://raw.githubusercontent.com/ThanhMarine/Bibot/main/privacy-policy.md
- These Terms URL: https://raw.githubusercontent.com/ThanhMarine/Bibot/main/terms.md

---

📋 Ghi chú nộp Play Console (không hiển thị cho user, để dev đối chiếu):
- Terms URL đề xuất: https://raw.githubusercontent.com/ThanhMarine/Bibot/main/terms.md (song ngữ, cùng nơi với privacy-policy.md)
- Khai Contains Ads = Yes (Free) / No (VIP); RECORD_AUDIO = Voice Chat offline, xử lý cục bộ; Families Policy cho Cổ tích như Mục 2.2
- IAP (khi phát hành): khai Products/Subscriptions, chính sách hoàn tiền theo Google Play; giữ bản Terms này đồng bộ versionCode 3 / versionName 1.0.2
