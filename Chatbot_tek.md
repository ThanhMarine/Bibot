# 🚀 TECHNICAL BLUEPRINT: REAL-TIME LOCAL/HYBRID MOBILE VOICE CHATBOT MODULE

## 1. TỔNG QUAN HỆ THỐNG & MỤC TIÊU (OVERVIEW)
Tài liệu này định nghĩa kiến trúc kỹ thuật để xây dựng mới hoặc cải tạo module Mobile Chatbot thoại Real-time đa luồng (Task Parallelism) trên thiết bị di động tầm trung.

* **Kiến trúc chính:** Async Pipelined Task Parallelism (STT ➔ Router ➔ Web Search ➔ LLM ➔ Parser ➔ sanoTTS ➔ Audio Playback).
* **Đặc tính cốt lõi:**
  * **Time-To-First-Audio (TTFA):** < 400ms.
  * **Local-First & Offline Capabilities:** Tối ưu hóa CPU execution (ARM NEON), sử dụng ONNX Runtime & llama.cpp.
  * **Memory Footprint:** < 1.2 GB RAM (cho toàn bộ pipeline local).

---

## 2. KIẾN TRÚC LUỒNG DỮ LIỆU (PIPELINE DATAFLOW)

[User Audio Input]
│
▼
┌─────────────────────────┐
│  1. STT Engine (Local)  │ ──► Trả về Text Query
└─────────────────────────┘
│
▼
┌─────────────────────────┐
│  2. Intent Router (LLM) │ ──► Quyết định: Cần thông tin thời gian thực không?
└──────────┬──────────────┘
│
┌─────┴─────────────────────────────┐
│ (Cần thông tin ngoài)             │ (Trò chuyện thông thường)
▼                                   ▼
┌─────────────────────────┐     ┌─────────────────────────┐
│ 3. Fast Web Search/RAG  │     │ Direct LLM Inference    │
└──────────┬──────────────┘     └──────────┬──────────────┘
│                               │
└───────────────┬───────────────┘
▼
┌─────────────────────────────┐
│  4. LLM Streaming Generator │ ──► Token Stream (Yield)
└──────────────┬──────────────┘
▼
┌─────────────────────────────┐
│ 5. Async Sentence Parser    │ ──► Sliding Window Chunking (. , ? !)
└──────────────┬──────────────┘
▼
┌─────────────────────────────┐
│  6. sanoTTS Engine (ONNX)   │ ──► Render PCM Audio Chunk
└──────────────┬──────────────┘
▼
┌─────────────────────────────┐
│ 7. Gapless Audio Playback   │ ──► Output Speakers
└─────────────────────────────┘


---

## 3. TECH STACK & ENGINE MATRIX

| Thành phần | Công nghệ / Model đề xuất | Phương thức thực thi (Runtime) |
| :--- | :--- | :--- |
| **STT Engine** | Sherpa-onnx / Whisper Tiny | ONNX Runtime (C++ Native) |
| **LLM Core** | Qwen2.5-0.5B-Instruct / RWKV-6 1.5B (INT4 GGUF) | `llama.cpp` JNI / ExecuTorch |
| **Search Engine API** | Tavily Search / SearXNG API | Async HTTP Client |
| **TTS Engine** | **sanoTTS** (bản `int8` ~337KB) qua Piper Architecture | ONNX Runtime Native C++ / Piper CAPI |
| **Orchestrator** | C++20 Coroutines / Libuv Event Loop | Multithreaded Core Manager |

---

## 4. CHIẾN LƯỢC PHÂN BỔ NHÂN CPU (THREAD & CPU AFFINITY MAP)

Áp dụng chiến thuật gán cứng thread cho các cụm nhân trên SoC Snapdragon / Dimensity (Big.LITTLE Architecture):

* **Thread 1 (Big Core - High Priority):** Chạy **LLM Generator Engine** (GGUF INT4).
* **Thread 2 (Big/Mid Core - High Priority):** Chạy **STT Engine** + **Web Search HTTP Worker**.
* **Thread 3 (Little Core - Normal Priority):** Chạy **sanoTTS ONNX Engine** (1-thread C++ SIMD ARM NEON).
* **Thread 4 (Little Core - Realtime Priority):** Chạy **Audio Track Playback Queue Buffer**.

---

## 5. NHIỆM VỤ THỰC THI CHO AI CODER (STEP-BY-STEP IMPLEMENTATION TASKS)

### TASK 1: Tích hợp sanoTTS vào Piper / ONNX C++ Runtime
1. Convert/Export weights sanoTTS sang định dạng `.onnx` (định dạng `int8`).
2. Viết Wrapper C++ nhận chuỗi văn bản (Sentence Chunk) ➔ Tokenize/Phonemize ➔ Suy luận ONNX Runtime ➔ Xuất mảng PCM float/int16 audio.
3. Thiết lập cờ biên dịch tối ưu ARM NEON: `-O3 -mfpu=neon -mfloat-abi=hard`.
4. **Setting Talk_speed**: Thêm tham số `speech_rate` (0.5x - 2.0x, default 1.0x) vào TTS request, áp dụng cho Piper/sanoTTS qua tham số `speed` trong `synthesize()`, đồng bộ với setting người dùng.
5. **Voice Style Selection**: Hỗ trợ multi-voice trong Piper (ví dụ: `vi-VN-phatjkk`, `en-US-lessac`), Cloud TTS (Neural2/Wavenet), Offline TTS. Cung cấp API `setVoice(String voiceId)` và `listAvailableVoices()`, lưu preference vào DataStore.

### TASK 2: Xây dựng Async Sentence Parser (Streaming Chunking)
1. Tạo một `SentenceParser` class lắng nghe Token Stream từ LLM Engine.
2. Thiết lập quy tắc ngắt câu:
   * Gom tụ tối thiểu 3-5 từ.
   * Ngắt và gửi ngay sang sanoTTS Queue khi xuất hiện các ký tự: `,`, `.`, `?`, `!`, `;` hoặc khi độ dài buffer đạt 15 từ.
3. Tránh hiện tượng gửi từng từ đơn lẻ gây đứt đoạn ngữ điệu âm thanh.

### TASK 3: Tích hợp LLM Function Calling & Router
1. Cấu hình Prompt hệ thống cho Qwen2.5-0.5B để xuất ra JSON lệnh nếu cần tìm kiếm:
   ```json
   {"action": "search", "query": "giá vàng hôm nay"}
Nếu phát hiện action == "search":

Kích hoạt Filler Audio ngay lập tức: Đẩy 1 đoạn âm thanh ngắn render sẵn bằng sanoTTS qua Audio Queue: "Đợi em một chút để em tìm thông tin nhé..." (Triệt tiêu độ trễ chờ mạng).

Gửi request async tới Search API, lấy 2 đoạn text tóm tắt ngắn nhất đưa vào Context của LLM Generator.

TASK 4: Xây dựng Audio Queue Buffer (Gapless Playback)
Khởi tạo FIFO Lock-free Thread-safe Queue chứa các đoạn PCM Audio generated từ sanoTTS.

Audio Player liên tục ngắt và phát mượt mà giữa các đoạn âm thanh mà không để lại khoảng lặng (Zero Gap).

6. LƯU Ý KỸ THUẬT QUAN TRỌNG (GUARDRAILS & OPTIMIZATION)
Bảo vệ RAM & Nhiệt độ (Thermal Throttling):

Không đặt số thread llama.cpp vượt quá 3.

Giới hạn max_tokens sinh ra cho mỗi lượt hội thoại thoại trong khoảng 100 - 150 tokens (đủ ngắn gọn cho nói chuyện).

Quản lý bộ nhớ Cache:

Giữ cố định KV Cache của LLM ở mức 512 context size để tránh rò rỉ RAM trên Android/iOS.

Giấy phép phần mềm:

sanoTTS dùng giấy phép GPL-3.0. Nếu app là closed-source, hãy đảm bảo compiled sanoTTS engine thành một thư viện .so / .dylib riêng biệt liên kết động (Dynamic Linking).

7. ACCEPTANCE CRITERIA (TIÊU CHUẨN NGHIỆM THU)
[ ] Chatbot phản hồi tiếng nói đầu tiên < 500ms sau khi người dùng kết thúc câu nói.

[ ] LLM streaming mượt mà, sanoTTS đọc nối tiếp các câu không bị vấp/ngắt quãng.

[ ] Luồng Web Search phản hồi có phát Filler Audio trong lúc tải dữ liệu.

[ ] Ứng dụng chạy liên tục 15 phút không bị crash do Out Of Memory (OOM) hoặc quá nhiệt.


---

Bản blueprint này đã chuẩn hóa toàn bộ các giải pháp về **sanoTTS**, **Piper ONNX**, **Tas