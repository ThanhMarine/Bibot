Walkthrough

Walkthrough: Scaffold go.mod và android/ cho Bibot (Mobile)

Đã hoàn thành khởi tạo cấu trúc cho toàn bộ dự án android\_app bao gồm:



Module Go Core (go.mod, cmd/mobile, internal/\*)

Module Android App (android/, Gradle KTS, Jetpack Compose, Material 3, Media3 ExoPlayer)

1\. Cấu trúc đã tạo

A. Go Core Module

go.mod

: Module bibot/core với Go 1.22+.

.env.example

: Cấu hình mẫu cho Gemini, OpenRouter, Persona (Em là Bi).

cmd/mobile/mobile.go

: Bridge API cho gomobile bind sinh bibot.aar (LLMStream, TTSSynthesize, PDFConvert, MemoryRecall, ChunkerSplit).

internal/:

internal/config/config.go

: Config singleton, format persona và system prompt với datetime context.

internal/chunker/chunker.go

: Chia tách câu tiếng Việt cho streaming TTS.

internal/tts/tts.go

: Làm sạch text (clean\_tts\_text loại bỏ Markdown, URL, Emoji, <think>) và synthesis.

internal/llm/llm.go

: Bộ sinh phản hồi streaming Gemini Flash / fallback.

internal/stt/stt.go

: STT Cloud bridge.

internal/memory/memory.go

: Trí nhớ 3 tầng (Core / Facts / Log).

internal/pdf/pdf.go

: Pipeline bóc tách PDF sang Markdown.

internal/player/player.go

: Quản lý cấu hình pipeline audio (jitter 300ms, speed 0.85x/0.88x).

B. Android Kotlin + Jetpack Compose

Gradle Cấu hình (KTS):

settings.gradle.kts

&#x20;\& 

build.gradle.kts

gradle/libs.versions.toml

: Version catalog AGP 8.5.2, Kotlin 2.0.0, Compose, Media3 1.3.1.

gradle-wrapper.properties

, 

gradlew.bat

, 

gradlew

.

app/build.gradle.kts

: Namespace com.bibot.app, minSdk 26, targetSdk 34, định dạng xuất Bibot.apk, BIBOT\_PERSONA = "Em là Bi".

UI \& Player Bar:

BibotPlayerBar.kt

: Thanh điều khiển audio cố định ở đáy theo đúng wireframe (⏮, ⏯, ⏭, timeline seek, speed chip 0.75x/0.85x/1.0x/1.25x).

PlayerViewModel.kt

: Singleton quản lý trạng thái playback chung cho toàn bộ app.

BibotNavSuite.kt

: Menu điều hướng Material 3 hiển thị Google Emoji (🎙️, 📚, 📖, 🎵, ⛅, 📰, 📄→📝, ⚙️).

Màn hình \& Core Bridges:

VoiceScreen.kt

: Voice chat với persona Em là Bi.

BookScreen.kt

, 

StoryScreen.kt

, 

MusicScreen.kt

, 

WeatherScreen.kt

, 

NewsScreen.kt

, 

PdfScreen.kt

, 

SettingsScreen.kt

.

AudioBridge.kt

, 

LlmBridge.kt

, 

SttBridge.kt

.

2\. Kết quả kiểm tra (Verification)

go build ./... và go vet ./... trong android\_app: Pass 100% (code 0), toàn bộ module Go và các internal package biên dịch chuẩn xác.

