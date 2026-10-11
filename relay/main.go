// bibot-relay — 2.6.4 R1 Bibot Relay (khuyến nghị).
//
// App (user không GEMINI_API_KEY) → POST /v1/chat {prompt, system, deviceId}
// → server inject GEMINI_API_KEY (env, không bao giờ lộ vào APK)
// → generativelanguage gemini-2.5-flash-lite SSE → pipe thẳng về app.
// Rate-limit 20 req/ngày/device (in-memory; production thay bằng Firestore/Redis).
//
// Chạy local:  GEMINI_API_KEY=... go run .   (nghe :8080)
// Deploy:      xem Dockerfile + README.md (Cloud Run free tier 0đ).
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

const (
	model           = "gemini-2.5-flash-lite"
	maxOutputTokens = 1024
	thinkingBudget  = 128
	dailyQuota      = 20
	maxPromptLen    = 4000
)

type chatReq struct {
	Prompt   string `json:"prompt"`
	System   string `json:"system"`
	DeviceID string `json:"deviceId"`
}

// --- rate limit 20/ngày/device (in-memory) ---

type bucket struct {
	day   string
	count int
}

var (
	mu      sync.Mutex
	buckets = map[string]*bucket{}
)

func allow(deviceID string) bool {
	mu.Lock()
	defer mu.Unlock()
	today := time.Now().UTC().Format("2006-01-02")
	b, ok := buckets[deviceID]
	if !ok || b.day != today {
		b = &bucket{day: today}
		buckets[deviceID] = b
	}
	if b.count >= dailyQuota {
		return false
	}
	b.count++
	return true
}

// --- handlers ---

func healthz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	io.WriteString(w, `{"ok":true}`) //nolint:errcheck
}

func chat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	apiKey := os.Getenv("GEMINI_API_KEY")
	if apiKey == "" {
		http.Error(w, "server chưa cấu hình GEMINI_API_KEY", http.StatusInternalServerError)
		return
	}
	var req chatReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		http.Error(w, "body JSON sai", http.StatusBadRequest)
		return
	}
	if req.DeviceID == "" || req.Prompt == "" {
		http.Error(w, "thiếu prompt/deviceId", http.StatusBadRequest)
		return
	}
	if len(req.Prompt) > maxPromptLen {
		http.Error(w, "prompt quá dài", http.StatusBadRequest)
		return
	}
	if !allow(req.DeviceID) {
		http.Error(w, "hết quota free hôm nay (20 tin/ngày)", http.StatusTooManyRequests)
		return
	}

	// Body gửi Gemini — reuse cấu trúc streamGemini bên internal/llm
	body, _ := json.Marshal(map[string]any{
		"contents": []any{map[string]any{
			"role":  "user",
			"parts": []any{map[string]any{"text": req.Prompt}},
		}},
		"systemInstruction": map[string]any{
			"parts": []any{map[string]any{"text": req.System}},
		},
		"generationConfig": map[string]any{
			"maxOutputTokens": maxOutputTokens,
			"thinkingConfig":  map[string]any{"thinkingBudget": thinkingBudget},
		},
	})
	url := fmt.Sprintf(
		"https://generativelanguage.googleapis.com/v1beta/models/%s:streamGenerateContent?alt=sse&key=%s",
		model, apiKey,
	)
	upReq, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		http.Error(w, "tạo request lỗi", http.StatusInternalServerError)
		return
	}
	upReq.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 120 * time.Second}
	upResp, err := client.Do(upReq)
	if err != nil {
		http.Error(w, "gọi Gemini lỗi", http.StatusBadGateway)
		return
	}
	defer upResp.Body.Close() //nolint:errcheck
	if upResp.StatusCode < 200 || upResp.StatusCode > 299 {
		b, _ := io.ReadAll(io.LimitReader(upResp.Body, 4096))
		http.Error(w, "Gemini HTTP "+upResp.Status+": "+string(b), http.StatusBadGateway)
		return
	}

	// SSE passthrough về app (LlmBridge.streamProxy parse lại như streamGemini)
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, 32*1024)
	for {
		n, err := upResp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if err != nil {
			return
		}
	}
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	http.HandleFunc("/healthz", healthz)
	http.HandleFunc("/v1/chat", chat)
	log.Printf("bibot-relay nghe :%s model=%s quota=%d/ngày", port, model, dailyQuota)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}
