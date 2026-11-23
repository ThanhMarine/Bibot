# Third-Party Notices - Bibot

Bibot dùng **Material Symbols Rounded** qua facade `ui/icon/BibotIcons.kt` cho mọi icon
chức năng. Không dùng asset icon bên ngoài (Fluent Emoji, Magnific, Twemoji, OpenMoji,
Noto, hay bộ vector hand-drawn tự vẽ trước đó đều đã bị loại bỏ).

Ngoài ra Bibot dùng các thành phần mã nguồn mở/công cộng sau. Mỗi mục ghi rõ thành phần
**đóng gói trong APK** hay **do người dùng tự tải/cài khi dùng**.

## Material Icons / Material 3 (Google)

- **Nguồn:** https://fonts.google.com/icons · https://developer.android.com/jetpack/androidx/releases/compose-material
- **Tác giả:** Google LLC
- **Giấy phép:** Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0
- **Sử dụng trong Bibot:** hệ thống icon Material Symbols Rounded và bộ Material 3 Compose.
- **Trạng thái:** **đóng gói trong APK** (dependency + vector được sinh ra).

## Vosk Android (AlphaCephei)

- **Nguồn:** https://github.com/alphacep/vosk-api
- **Tác giả:** AlphaCephei / Vosk contributors
- **Giấy phép:** Apache License 2.0
- **Sử dụng trong Bibot:** nhận dạng giọng nói offline (STT) làm đường dự phòng; dependency `com.alphacephei:vosk-android:0.3.45`.
- **Trạng thái:** **đóng gói trong APK**.

## sherpa-onnx (k2-fsa)

- **Nguồn:** https://github.com/k2-fsa/sherpa-onnx
- **Tác giả:** k2-fsa contributors
- **Giấy phép:** Apache License 2.0
- **Sử dụng trong Bibot:** phần binding Kotlin/JNI nằm trực tiếp trong repo tại `android/app/src/main/java/com/k2fsa/sherpa/onnx/`; dùng cho STT/TTS offline (Zipformer / engine sherpa).
- **Trạng thái:** **mã nguồn binding đóng gói trong APK**; **engine native `.so` (~46MB) do người dùng tải/cài trong Cài đặt → Mô hình offline**, không đóng gói sẵn.

## Piper (rhasspy)

- **Nguồn:** https://github.com/rhasspy/piper
- **Tác giả:** rhasspy contributors
- **Giấy phép:** MIT License
- **Sử dụng trong Bibot:** giọng nói offline tiếng Việt và tiếng Anh (`.onnx`), người dùng cài từ mạng hoặc chọn file trong Cài đặt → Giọng đọc.
- **Trạng thái:** **do người dùng tải/cài**, model/voice pack **không đóng gói trong APK**.

## eSpeak NG data

- **Nguồn:** https://github.com/espeak-ng/espeak-ng
- **Tác giả:** eSpeak NG contributors
- **Giấy phép:** GPL-3.0
- **Sử dụng trong Bibot:** dữ liệu `espeak-ng-data` (`.zip`) dùng cùng Piper; người dùng tự tải/cài nếu cần.
- **Trạng thái:** **do người dùng tải/cài**, **không đóng gói trong APK**; chỉ ghi nhận nguồn/giấy phép.

## ONNX Runtime (Microsoft)

- **Nguồn:** https://github.com/microsoft/onnxruntime
- **Tác giả:** Microsoft Corporation
- **Giấy phép:** MIT License
- **Sử dụng trong Bibot:** runtime suy luận đi kèm engine/model dạng sherpa/Piper do người dùng tải.
- **Trạng thái:** **không đóng gói như một dependency riêng**; có mặt trong gói engine/model do người dùng cài.

## Tệp giấy phép đính kèm

- `android/app/src/main/assets/licenses/APACHE-2.0.txt` —toàn văn Apache License 2.0 (Material Icons, Vosk, sherpa-onnx).
- `android/app/src/main/assets/licenses/MIT.txt` —toàn văn MIT License (Piper, ONNX Runtime).
- eSpeak NG: chỉ attribution ở trên vì Bibot không phân phối dữ liệu GPL.

## Ghi chú

- `SpeechRecognizer` và `android.speech.tts.TextToSpeech` là API nền tảng Android, không phải thành phần bên thứ ba cần notice riêng.
- Google Cloud TTS là dịch vụ người dùng tự nhập key; Bibot không đóng gói mã/service của bên thứ ba này.
- Khi đổi dependency STT/TTS hoặc bộ icon, cần cập nhật tệp này **và** mục **Giấy phép & ghi công** trong Cài đặt.
- Các tên giấy phép ở trên nên được đối chiếu lại với trang chính thức của từng dự án trước khi phát hành.
