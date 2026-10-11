package config

import (
	"strings"
	"sync"
	"time"
)

type Config struct {
	GeminiAPIKey         string
	GeminiModel          string
	GeminiThinkingBudget int
	OpenRouterAPIKey     string
	OpenRouterModel      string
	CloudTTSAPIKey       string
	LlmProxyURL          string
	Persona              string
	BotPrompt            string
	BotStyle             string
	BotTone              string
	BotMaxWords          int
	TTSPriority          string
	VoicePlaybackSpeed   float64
	StoryPlaybackSpeed   float64
}

var (
	cfg  *Config
	once sync.Once
)

func GetConfig() *Config {
	once.Do(func() {
		cfg = &Config{
			GeminiModel:          "gemini-3.6-flash",
			GeminiThinkingBudget: 128,
			OpenRouterModel:      "nex-agi/nex-n2.5-mini:free",
			Persona:              "Em là Bi",
			BotPrompt:            "Bạn là Bi, trợ lý giọng nói Bibot. Luôn xưng \"{{persona}}\", trả lời tiếng Việt, dưới {{maxWords}} từ, {{style}}, {{tone}}, không markdown/link/emoji trong TTS. Chỉ dùng web search cho thời tiết/giá/tin tức. Ngày/giờ hiện tại: {{datetime}}.",
			BotStyle:             "friendly",
			BotTone:              "warm",
			BotMaxWords:          30,
			TTSPriority:          "auto",
			VoicePlaybackSpeed:   0.85,
			StoryPlaybackSpeed:   0.88,
			LlmProxyURL:          "", // set via InitProxy or BIBOT_LLM_PROXY_URL (BuildConfig)
		}
	})
	return cfg
}

func InitConfig(apiKey, persona string) {
	c := GetConfig()
	if apiKey != "" {
		c.GeminiAPIKey = apiKey
	}
	if persona != "" {
		c.Persona = persona
	}
}

func InitCloudTTS(apiKey string) {
	if apiKey == "" {
		return
	}
	GetConfig().CloudTTSAPIKey = apiKey
}

func InitProxy(proxyURL string) {
	if proxyURL == "" {
		return
	}
	GetConfig().LlmProxyURL = proxyURL
}

func UpdateBotPrompt(persona, template, style, tone string, maxWords int) {
	c := GetConfig()
	if persona != "" {
		c.Persona = persona
	}
	if template != "" {
		c.BotPrompt = template
	}
	if style != "" {
		c.BotStyle = style
	}
	if tone != "" {
		c.BotTone = tone
	}
	if maxWords > 0 {
		c.BotMaxWords = maxWords
	}
}

// BuildSystemPrompt ports services/streaming_llm.py:212 SYSTEM_PROMPT_VI + _current_datetime_context():232
// Supports template {{persona}} {{datetime}} {{style}} {{tone}} {{maxWords}} (BotPromptManager)
func (c *Config) BuildSystemPrompt() string {
	weekdays := []string{"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"}
	now := time.Now()
	wIdx := (int(now.Weekday()) + 6) % 7
	dtCtx := weekdays[wIdx] + ", " + now.Format("02/01/2006, 15:04")
	template := c.BotPrompt
	if template == "" {
		template = "Bạn là Bi, trợ lý giọng nói Bibot. Luôn xưng \"{{persona}}\", trả lời tiếng Việt, dưới {{maxWords}} từ, {{style}}, {{tone}}, không markdown/link/emoji trong TTS. Chỉ dùng web search cho thời tiết/giá/tin tức. Ngày/giờ hiện tại: {{datetime}}."
	}
	persona := c.Persona
	if persona == "" {
		persona = "Em là Bi"
	}
	style := c.BotStyle
	if style == "" {
		style = "thân thiện"
	} else {
		m := map[string]string{"friendly": "thân thiện", "formal": "trang trọng", "humorous": "hài hước", "concise": "cô đọng", "storyteller": "kể chuyện"}
		if v, ok := m[style]; ok {
			style = v
		}
	}
	tone := c.BotTone
	if tone == "" {
		tone = "ấm áp"
	} else {
		m := map[string]string{"warm": "ấm áp", "professional": "chuyên nghiệp", "playful": "vui tươi", "calm": "điềm đạm"}
		if v, ok := m[tone]; ok {
			tone = v
		}
	}
	maxW := c.BotMaxWords
	if maxW <= 0 {
		maxW = 30
	}
	res := strings.ReplaceAll(template, "{{persona}}", persona)
	res = strings.ReplaceAll(res, "{{datetime}}", dtCtx)
	res = strings.ReplaceAll(res, "{{datetime_context}}", dtCtx)
	res = strings.ReplaceAll(res, "{{style}}", style)
	res = strings.ReplaceAll(res, "{{tone}}", tone)
	res = strings.ReplaceAll(res, "{{maxWords}}", itoa(maxW))
	return res
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := false
	if n < 0 {
		neg = true
		n = -n
	}
	b := []byte{}
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	if neg {
		b = append([]byte{'-'}, b...)
	}
	return string(b)
}

// BuildSystemPromptEN for English mode (SYSTEM_PROMPT_EN)
func (c *Config) BuildSystemPromptEN() string {
	weekdays := []string{"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"}
	now := time.Now()
	wIdx := (int(now.Weekday()) + 6) % 7
	dtCtx := weekdays[wIdx] + ", " + now.Format("02/01/2006, 15:04")
	return "You are Bi, Bibot voice assistant. Always introduce as \"" + c.Persona + "\". Reply in English, under 30 words, warm, no markdown/links/emojis. Today: " + dtCtx + "."
}

