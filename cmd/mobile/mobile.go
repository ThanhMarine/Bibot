package mobile

import (
	"bibot/core/internal/chunker"
	"bibot/core/internal/config"
	"bibot/core/internal/llm"
	"bibot/core/internal/memory"
	"bibot/core/internal/pdf"
	"bibot/core/internal/tts"
	"fmt"
)

// StreamCallback represents the callback interface for mobile callers (Kotlin/Java)
type StreamCallback interface {
	OnToken(token string)
	OnComplete()
	OnError(err string)
}

// Init initializes the Bibot mobile core with API key and persona
func Init(geminiApiKey, persona string) {
	config.InitConfig(geminiApiKey, persona)
}

// InitCloudTTS sets Cloud TTS/STT API key (may be same as Gemini key if same GCP project)
func InitCloudTTS(apiKey string) {
	config.InitCloudTTS(apiKey)
}

// InitProxy sets LLM relay URL for users without GEMINI_API_KEY (Bibot Free) — 2.6
func InitProxy(proxyURL string) {
	config.InitProxy(proxyURL)
}

// UpdateBotPrompt updates persona/template/style/tone/maxWords for prompt personalization (Settings)
func UpdateBotPrompt(persona, template, style, tone string, maxWords int) {
	config.UpdateBotPrompt(persona, template, style, tone, maxWords)
}

// GetSystemPrompt returns current system prompt for preview
func GetSystemPrompt() string {
	return config.GetConfig().BuildSystemPrompt()
}

// LLMStream streams response tokens for a given prompt via callback
func LLMStream(prompt string, callback StreamCallback) {
	go func() {
		err := llm.StreamGenerate(prompt, func(token string) {
			if callback != nil {
				callback.OnToken(token)
			}
		})
		if err != nil {
			if callback != nil {
				callback.OnError(err.Error())
			}
			return
		}
		if callback != nil {
			callback.OnComplete()
		}
	}()
}

// TTSSynthesize synthesizes text to audio bytes (WAV/MP3) using Cloud TTS or fallback
func TTSSynthesize(text, voice string) ([]byte, error) {
	cleanText := tts.CleanTTSText(text)
	if cleanText == "" {
		return nil, fmt.Errorf("empty text after cleaning")
	}
	return tts.Synthesize(cleanText, voice)
}

// PDFConvert extracts and converts PDF document text to clean Markdown
func PDFConvert(path string) (string, error) {
	return pdf.ConvertToMarkdown(path)
}

// MemoryRecall queries the 3-tier memory system (Core/Facts/Log)
func MemoryRecall(query string) string {
	return memory.Recall(query)
}

// ChunkerSplit splits Vietnamese text into sentence chunks suitable for TTS streaming
func ChunkerSplit(text string) string {
	chunks := chunker.SplitVietnameseSentences(text)
	if len(chunks) == 0 {
		return text
	}
	// Return delimited by newline
	result := ""
	for i, c := range chunks {
		if i > 0 {
			result += "\n"
		}
		result += c
	}
	return result
}

