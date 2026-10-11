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
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
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

func allowQuota(key string, quota int) bool {
	mu.Lock()
	defer mu.Unlock()
	today := time.Now().UTC().Format("2006-01-02")
	b, ok := buckets[key]
	if !ok || b.day != today {
		b = &bucket{day: today}
		buckets[key] = b
	}
	if b.count >= quota {
		return false
	}
	b.count++
	return true
}

func allow(deviceID string) bool {
	return allowQuota("chat:"+deviceID, dailyQuota)
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

// --- VIP verify (server-side; secret KHÔNG bao giờ vào APK) ---
//
// App gửi code thô -> server check HMAC (port 1-1 VipCode.kt + vip_batch.py)
// -> trả verdict + entitlement ký (bind code<->device). App cache entitlement
// để grace offline; hết grace hoặc đổi code thì verify lại khi có mạng.

const (
	vipPrefix       = "BIBOT"
	vipVersion      = 1
	vipTierMonth    = 1
	vipTierLifetime = 2
	vipEpochMillis  = int64(1767225600000) // 2026-01-01T00:00Z
	vipDayMillis    = int64(86400000)
	vipAlphabet     = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
)

// vipVerifyQuota verify/ngày/máy (VIP_VERIFY_QUOTA, mặc định 10) — chống dò mã.
func vipVerifyQuota() int {
	if n, err := strconv.Atoi(os.Getenv("VIP_VERIFY_QUOTA")); err == nil && n > 0 {
		return n
	}
	return 10
}

type vipVerdict struct {
	Valid        bool   `json:"valid"`
	Tier         int    `json:"tier,omitempty"`
	Months       int    `json:"months,omitempty"`
	Batch        int    `json:"batch,omitempty"`
	Serial       int    `json:"serial,omitempty"`
	ExpiryMillis *int64 `json:"expiryMillis,omitempty"` // null = vĩnh viễn
	Reason       string `json:"reason,omitempty"`
}

type vipEntitlement struct {
	DeviceID     string `json:"deviceId"`
	Tier         int    `json:"tier"`
	ExpiryMillis *int64 `json:"expiryMillis,omitempty"`
	IssuedAt     int64  `json:"issuedAt"`
	Sig          string `json:"sig"` // HMAC(secret, "bibot-ent-v1|...") hex
}

type vipVerifyReq struct {
	Code           string `json:"code"`
	DeviceID       string `json:"deviceId"`
	AppVersion     string `json:"appVersion"`
	IntegrityToken string `json:"integrityToken,omitempty"` // Phase 2: Play Integrity (hiện log-only)
}

type vipVerifyResp struct {
	vipVerdict
	ServerTime  int64            `json:"serverTime"` // neo chống chỉnh đồng hồ máy
	Entitlement *vipEntitlement  `json:"entitlement,omitempty"`
	SharedWarn  bool             `json:"sharedWarn,omitempty"` // code xài quá N máy
}

func vipInvalid(reason string) vipVerdict {
	return vipVerdict{Reason: reason}
}

// verifyVipCode port 1-1 VipCode.verify (Kotlin) — cùng ALPHABET/payload/HMAC6.
func verifyVipCode(secretHex, code string, nowMillis int64, revoked map[int]bool) vipVerdict {
	secret, err := hex.DecodeString(strings.TrimSpace(secretHex))
	if err != nil || len(secret) < 16 {
		return vipInvalid("khoa xac thuc loi")
	}
	c := strings.ToUpper(strings.TrimSpace(code))
	if !strings.HasPrefix(c, vipPrefix+"-") {
		return vipInvalid("thieu tien to BIBOT-")
	}
	body := strings.ReplaceAll(c[len(vipPrefix)+1:], "-", "")
	if len(body) != 26 {
		return vipInvalid("do dai/ky tu sai")
	}
	raw := make([]byte, 16)
	acc, bits, pos := 0, 0, 0
	for _, ch := range body {
		v := strings.IndexRune(vipAlphabet, ch)
		if v < 0 {
			return vipInvalid("do dai/ky tu sai")
		}
		acc = (acc << 5) | v
		bits += 5
		for bits >= 8 {
			if pos >= 16 {
				return vipInvalid("giai ma that bai")
			}
			bits -= 8
			raw[pos] = byte((acc >> bits) & 0xFF)
			pos++
		}
	}
	if pos != 16 {
		return vipInvalid("giai ma that bai")
	}
	payload, sig := raw[:10], raw[10:16]
	mac := hmac.New(sha256.New, secret)
	mac.Write(payload)
	expect := mac.Sum(nil)[:6]
	if subtle.ConstantTimeCompare(expect, sig) != 1 {
		return vipInvalid("chu ky sai (ma gia/nhap sai)")
	}
	ver := int(payload[0])
	tier := int(payload[1])
	months := int(payload[2])
	expiryDays := int(payload[3])<<8 | int(payload[4])
	batch := int(payload[5])
	serial := int(payload[6])
	if ver != vipVersion {
		return vipInvalid("phien ban ma khong ho tro")
	}
	if tier != vipTierMonth && tier != vipTierLifetime {
		return vipInvalid("loai ma la")
	}
	if revoked[batch] {
		return vipInvalid("ma bi thu hoi")
	}
	if tier == vipTierLifetime {
		return vipVerdict{Valid: true, Tier: tier, Batch: batch, Serial: serial}
	}
	expMs := vipEpochMillis + int64(expiryDays)*vipDayMillis
	if nowMillis > expMs {
		return vipInvalid("ma het han")
	}
	return vipVerdict{Valid: true, Tier: tier, Months: months,
		Batch: batch, Serial: serial, ExpiryMillis: &expMs}
}

func signEntitlement(secret []byte, deviceID string, v vipVerdict, now int64) *vipEntitlement {
	exp := int64(0)
	if v.ExpiryMillis != nil {
		exp = *v.ExpiryMillis
	}
	msg := fmt.Sprintf("bibot-ent-v1|%s|%d|%d", deviceID, v.Tier, exp)
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(msg))
	return &vipEntitlement{
		DeviceID: deviceID, Tier: v.Tier,
		ExpiryMillis: v.ExpiryMillis, IssuedAt: now,
		Sig: hex.EncodeToString(mac.Sum(nil)),
	}
}

// revokedBatches đọc VIP_REVOKED_BATCHES="3,7" (thu hồi theo batch, không cần xoay secret).
func revokedBatches() map[int]bool {
	out := map[int]bool{}
	for _, s := range strings.Split(os.Getenv("VIP_REVOKED_BATCHES"), ",") {
		if n, err := strconv.Atoi(strings.TrimSpace(s)); err == nil {
			out[n] = true
		}
	}
	return out
}

// codeDevices chống share 1 mã tràn lan: cùng code quá N máy/ngày -> SharedWarn.
// In-memory (restart reset) — cùng giới hạn đã ghi ở /v1/chat.
var codeDevices = struct {
	sync.Mutex
	m map[string]map[string]bool
}{m: map[string]map[string]bool{}}

func maxDevices() int {
	if n, err := strconv.Atoi(os.Getenv("VIP_MAX_DEVICES")); err == nil && n > 0 {
		return n
	}
	return 3
}

func vipVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	secretHex := strings.TrimSpace(os.Getenv("BIBOT_VIP_SECRET"))
	if secretHex == "" {
		http.Error(w, "server chưa cấu hình BIBOT_VIP_SECRET", http.StatusInternalServerError)
		return
	}
	var req vipVerifyReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		http.Error(w, "body JSON sai", http.StatusBadRequest)
		return
	}
	req.Code = strings.TrimSpace(req.Code)
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.Code == "" || req.DeviceID == "" {
		http.Error(w, "thiếu code/deviceId", http.StatusBadRequest)
		return
	}
	if !allowQuota("vip:"+req.DeviceID, vipVerifyQuota()) {
		http.Error(w, "verify quá nhiều, thử lại mai", http.StatusTooManyRequests)
		return
	}
	now := time.Now().UnixMilli()
	v := verifyVipCode(secretHex, req.Code, now, revokedBatches())
	resp := vipVerifyResp{vipVerdict: v, ServerTime: now}
	if v.Valid {
		secret, _ := hex.DecodeString(secretHex)
		resp.Entitlement = signEntitlement(secret, req.DeviceID, v, now)
		codeDevices.Lock()
		set := codeDevices.m[req.Code]
		if set == nil {
			set = map[string]bool{}
			codeDevices.m[req.Code] = set
		}
		set[req.DeviceID] = true
		if len(set) > maxDevices() {
			resp.SharedWarn = true
		}
		codeDevices.Unlock()
		if req.IntegrityToken != "" {
			log.Printf("[vip] integrity token nhận (chưa enforce): device=%s app=%s",
				req.DeviceID, req.AppVersion)
		}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp) //nolint:errcheck
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	http.HandleFunc("/healthz", healthz)
	http.HandleFunc("/v1/chat", chat)
	http.HandleFunc("/v1/vip/verify", vipVerify)
	log.Printf("bibot-relay nghe :%s model=%s quota=%d/ngày", port, model, dailyQuota)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}
