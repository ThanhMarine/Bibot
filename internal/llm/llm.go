package llm

import (
	"bibot/core/internal/config"
	"bibot/core/internal/tts"
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// answerDatetimeLocal ports streaming_llm.py:247 local datetime shortcut (no LLM call).
func answerDatetimeLocal(text string) (string, bool) {
	t := strings.ToLower(strings.TrimSpace(text))
	liveBlock := []string{"thoi tiet", "thời tiết", "du bao", "dự báo", "nhiet do", "nhiệt độ", "tin tuc", "tin tức", "gia vang", "giá vàng", "ty gia", "tỷ giá", "ve may bay", "vé máy bay", "duong di", "đường đi"}
	for _, kw := range liveBlock {
		if strings.Contains(t, kw) {
			return "", false
		}
	}
	now := time.Now()
	viDays := []string{"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"}
	wIdx := (int(now.Weekday()) + 6) % 7
	fmtDay := func(dt time.Time) string {
		return fmt.Sprintf("%s, ngày %d tháng %d năm %d", viDays[(int(dt.Weekday())+6)%7], dt.Day(), int(dt.Month()), dt.Year())
	}
	fmtTime := func(dt time.Time) string { return fmt.Sprintf("%d giờ %02d phút", dt.Hour(), dt.Minute()) }
	hasGio := strings.Contains(t, "may gio") || strings.Contains(t, "mấy giờ") || strings.Contains(t, "gio hien tai") || strings.Contains(t, "giờ hiện tại") || t == "gio" || t == "giờ"
	hasHomNay := strings.Contains(t, "hom nay") || strings.Contains(t, "hôm nay")
	hasNgayMai := strings.Contains(t, "ngay mai") || strings.Contains(t, "ngày mai") || strings.HasPrefix(t, "mai ") || t == "mai"
	hasNgayKia := strings.Contains(t, "ngay kia") || strings.Contains(t, "ngày kia")
	hasHomQua := strings.Contains(t, "hom qua") || strings.Contains(t, "hôm qua")
	isThuMay := strings.Contains(t, "thu may") || strings.Contains(t, "thứ mấy")
	isNgayMay := strings.Contains(t, "ngay may") || strings.Contains(t, "ngày mấy") || strings.Contains(t, "ngay bao nhieu") || strings.Contains(t, "ngày bao nhiêu")
	if hasGio && !(hasNgayMai || hasNgayKia || hasHomQua) {
		return fmt.Sprintf("Bây giờ là %s, %s.", fmtTime(now), fmtDay(now)), true
	}
	if hasNgayMai && (isThuMay || isNgayMay || len(strings.Fields(t)) <= 5) {
		tm := now.Add(24 * time.Hour)
		return fmt.Sprintf("Ngày mai là %s.", fmtDay(tm)), true
	}
	if hasNgayKia {
		tk := now.Add(48 * time.Hour)
		return fmt.Sprintf("Ngày kia là %s.", fmtDay(tk)), true
	}
	if hasHomQua && (isThuMay || isNgayMay) {
		hq := now.Add(-24 * time.Hour)
		return fmt.Sprintf("Hôm qua là %s.", fmtDay(hq)), true
	}
	if hasHomNay && (isThuMay || isNgayMay) {
		return fmt.Sprintf("Hôm nay là %s.", fmtDay(now)), true
	}
	_ = wIdx
	return "", false
}

// StreamGenerate streams Gemini (primary) → Proxy (free for normal users) → OpenRouter fallback, else mock.
func StreamGenerate(prompt string, onToken func(token string)) error {
	cfg := config.GetConfig()
	// local datetime shortcut (0 token, no API)
	if ans, ok := answerDatetimeLocal(prompt); ok {
		for _, w := range strings.Fields(ans) {
			onToken(w + " ")
			time.Sleep(20 * time.Millisecond)
		}
		return nil
	}
	systemPrompt := cfg.BuildSystemPrompt()

	// Try Gemini REST streaming if key present
	if cfg.GeminiAPIKey != "" {
		if err := streamGemini(cfg.GeminiAPIKey, cfg.GeminiModel, systemPrompt, prompt, cfg.GeminiThinkingBudget, onToken); err == nil {
			return nil
		} else {
			_ = err
		}
	}
	// Proxy relay for users without key (Bibot Free 20/day) — 2.6
	if cfg.LlmProxyURL != "" {
		if err := streamProxy(cfg.LlmProxyURL, systemPrompt, prompt, onToken); err == nil {
			return nil
		} else {
			_ = err
		}
	}
	if cfg.OpenRouterAPIKey != "" {
		if err := streamOpenRouter(cfg.OpenRouterAPIKey, cfg.OpenRouterModel, systemPrompt, prompt, onToken); err == nil {
			return nil
		}
	}
	// graceful mock fallback with persona
	persona := cfg.Persona
	if persona == "" {
		persona = "Em là Bi"
	}
	mock := fmt.Sprintf("%s. Hiện tại em chưa kết nối được AI, nhưng em vẫn ở đây ạ!", persona)
	for _, w := range strings.Fields(mock) {
		onToken(w + " ")
		time.Sleep(30 * time.Millisecond)
	}
	return nil
}

func streamGemini(apiKey, model, systemPrompt, prompt string, thinkingBudget int, onToken func(string)) error {
	url := fmt.Sprintf("https://generativelanguage.googleapis.com/v1beta/models/%s:streamGenerateContent?key=%s&alt=sse", model, apiKey)
	body := map[string]interface{}{
		"system_instruction": map[string]interface{}{"parts": []map[string]string{{"text": systemPrompt}}},
		"contents":           []map[string]interface{}{{"role": "user", "parts": []map[string]string{{"text": prompt}}}},
		"generationConfig": map[string]interface{}{
			"temperature":     0.7,
			"maxOutputTokens": 1024,
		},
	}
	if thinkingBudget >= 0 {
		body["generationConfig"].(map[string]interface{})["thinkingConfig"] = map[string]interface{}{"thinkingBudget": thinkingBudget}
	}
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest("POST", url, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 90 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		bb, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("gemini http %d: %s", resp.StatusCode, string(bb[:min(400, len(bb))]))
	}
	reader := bufio.NewReader(resp.Body)
	yielded := 0
	for {
		line, err := reader.ReadString('\n')
		if err != nil && err != io.EOF {
			break
		}
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, ":") {
			if err == io.EOF {
				break
			}
			continue
		}
		if strings.HasPrefix(line, "data: ") {
			line = strings.TrimPrefix(line, "data: ")
		}
		if line == "[DONE]" {
			break
		}
		var evt map[string]interface{}
		if json.Unmarshal([]byte(line), &evt) != nil {
			if err == io.EOF {
				break
			}
			continue
		}
		// candidates[0].content.parts[].text
		if cands, ok := evt["candidates"].([]interface{}); ok && len(cands) > 0 {
			if c0, ok := cands[0].(map[string]interface{}); ok {
				if content, ok := c0["content"].(map[string]interface{}); ok {
					if parts, ok := content["parts"].([]interface{}); ok {
						for _, p := range parts {
							if pm, ok := p.(map[string]interface{}); ok {
								if txt, ok := pm["text"].(string); ok && txt != "" {
									// filter thinking-like leak
									if isThinkingLeak(txt) {
										continue
									}
									clean := tts.CleanTTSText(txt)
									// stream token-wise but keep original token granularity
									yielded++
									onToken(txt + " ")
									_ = clean
								}
							}
						}
					}
				}
			}
		}
		if err == io.EOF {
			break
		}
	}
	if yielded == 0 {
		return fmt.Errorf("gemini stream empty")
	}
	return nil
}

func streamOpenRouter(apiKey, model, systemPrompt, prompt string, onToken func(string)) error {
	url := "https://openrouter.ai/api/v1/chat/completions"
	payload := map[string]interface{}{
		"model":       model,
		"messages":    []map[string]string{{"role": "system", "content": systemPrompt}, {"role": "user", "content": prompt}},
		"temperature": 0.7,
		"max_tokens":  1024,
		"stream":      true,
	}
	b, _ := json.Marshal(payload)
	req, _ := http.NewRequest("POST", url, bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer "+apiKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "text/event-stream")
	req.Header.Set("HTTP-Referer", "https://github.com/bibot")
	req.Header.Set("X-Title", "Bibot")
	client := &http.Client{Timeout: 90 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		bb, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("openrouter http %d: %s", resp.StatusCode, string(bb[:min(300, len(bb))]))
	}
	reader := bufio.NewReader(resp.Body)
	yielded := 0
	for {
		line, err := reader.ReadString('\n')
		if err != nil && err != io.EOF {
			break
		}
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, ":") {
			if err == io.EOF {
				break
			}
			continue
		}
		if line == "data: [DONE]" {
			break
		}
		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimPrefix(line, "data: ")
			var evt map[string]interface{}
			if json.Unmarshal([]byte(data), &evt) != nil {
				continue
			}
			if choices, ok := evt["choices"].([]interface{}); ok && len(choices) > 0 {
				if c0, ok := choices[0].(map[string]interface{}); ok {
					if delta, ok := c0["delta"].(map[string]interface{}); ok {
						if txt, ok := delta["content"].(string); ok && txt != "" {
							if isThinkingLeak(txt) {
								continue
							}
							yielded++
							onToken(txt)
						}
					}
				}
			}
		}
		if err == io.EOF {
			break
		}
	}
	if yielded == 0 {
		return fmt.Errorf("openrouter stream empty")
	}
	return nil
}

func streamProxy(proxyURL, systemPrompt, prompt string, onToken func(string)) error {
	if proxyURL == "" {
		return fmt.Errorf("empty proxy url")
	}
	// Proxy expects POST {prompt, system_prompt} → SSE {data: {"text":"…"}} or OpenAI-style chunks
	body := map[string]interface{}{
		"prompt":        prompt,
		"system_prompt": systemPrompt,
		"model":         config.GetConfig().GeminiModel,
	}
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest("POST", strings.TrimRight(proxyURL, "/")+"/v1/chat", bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "text/event-stream")
	client := &http.Client{Timeout: 90 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		bb, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("proxy http %d: %s", resp.StatusCode, string(bb[:min(300, len(bb))]))
	}
	reader := bufio.NewReader(resp.Body)
	yielded := 0
	for {
		line, err := reader.ReadString('\n')
		if err != nil && err != io.EOF {
			break
		}
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, ":") {
			if err == io.EOF {
				break
			}
			continue
		}
		if line == "data: [DONE]" || line == "[DONE]" {
			break
		}
		if strings.HasPrefix(line, "data: ") {
			line = strings.TrimPrefix(line, "data: ")
		}
		// Try Gemini-style candidates → OpenAI delta → plain text
		var evt map[string]interface{}
		if json.Unmarshal([]byte(line), &evt) == nil {
			// Gemini proxy
			if cands, ok := evt["candidates"].([]interface{}); ok && len(cands) > 0 {
				if c0, ok := cands[0].(map[string]interface{}); ok {
					if content, ok := c0["content"].(map[string]interface{}); ok {
						if parts, ok := content["parts"].([]interface{}); ok {
							for _, p := range parts {
								if pm, ok := p.(map[string]interface{}); ok {
									if txt, ok := pm["text"].(string); ok && txt != "" {
										if isThinkingLeak(txt) {
											continue
										}
										yielded++
										onToken(txt + " ")
									}
								}
							}
							continue
						}
					}
				}
			}
			// OpenAI proxy
			if choices, ok := evt["choices"].([]interface{}); ok && len(choices) > 0 {
				if c0, ok := choices[0].(map[string]interface{}); ok {
					if delta, ok := c0["delta"].(map[string]interface{}); ok {
						if txt, ok := delta["content"].(string); ok && txt != "" {
							if isThinkingLeak(txt) {
								continue
							}
							yielded++
							onToken(txt)
							continue
						}
					}
				}
			}
			// Plain {"text":"…"} fallback
			if txt, ok := evt["text"].(string); ok && txt != "" {
				if isThinkingLeak(txt) {
					continue
				}
				yielded++
				onToken(txt + " ")
				continue
			}
		} else {
			// raw line as token
			if line != "" && !isThinkingLeak(line) {
				yielded++
				onToken(line + " ")
			}
		}
		if err == io.EOF {
			break
		}
	}
	if yielded == 0 {
		return fmt.Errorf("proxy stream empty")
	}
	return nil
}

func isThinkingLeak(s string) bool {
	low := strings.ToLower(strings.TrimSpace(s))
	leaders := []string{"need under", "need maybe", "under 30", "too long", "shorten", "no markdown", "use punctuation", "great.", "count "}
	for _, l := range leaders {
		if strings.HasPrefix(low, l) {
			return true
		}
	}
	return false
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
