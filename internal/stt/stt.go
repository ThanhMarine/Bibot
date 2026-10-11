package stt

import (
	"bibot/core/internal/config"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

// RecognizeSpeech converts audio buffer (LINEAR16) to text via Google Cloud STT v1 REST.
// audioData: raw PCM 16-bit mono; sampleRate e.g. 16000.
// Returns empty and error if no key (caller falls back to SpeechRecognizer/Vosk).
func RecognizeSpeech(audioData []byte, sampleRate int) (string, error) {
	if len(audioData) == 0 {
		return "", fmt.Errorf("empty audio")
	}
	if sampleRate <= 0 {
		sampleRate = 16000
	}
	apiKey := config.GetConfig().CloudTTSAPIKey
	if apiKey == "" {
		apiKey = os.Getenv("GOOGLE_CLOUD_TTS_API_KEY")
	}
	if apiKey == "" {
		apiKey = os.Getenv("GEMINI_API_KEY")
	}
	if apiKey == "" {
		return "", fmt.Errorf("no Cloud STT key, fallback to on-device")
	}
	// REST: POST https://speech.googleapis.com/v1/speech:recognize?key=KEY
	url := "https://speech.googleapis.com/v1/speech:recognize?key=" + apiKey
	b64 := base64.StdEncoding.EncodeToString(audioData)
	body := map[string]interface{}{
		"config": map[string]interface{}{
			"encoding":        "LINEAR16",
			"sampleRateHertz": sampleRate,
			"languageCode":    "vi-VN",
			"alternativeLanguageCodes": []string{"en-US"},
			"model":           "default",
		},
		"audio": map[string]string{"content": b64},
	}
	bb, _ := json.Marshal(body)
	req, _ := http.NewRequest("POST", url, bytes.NewReader(bb))
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("cloud stt request: %w", err)
	}
	defer resp.Body.Close()
	respBytes, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("cloud stt http %d: %s", resp.StatusCode, string(respBytes[:minInt(400, len(respBytes))]))
	}
	var out struct {
		Results []struct {
			Alternatives []struct {
				Transcript string `json:"transcript"`
			} `json:"alternatives"`
		} `json:"results"`
	}
	if err := json.Unmarshal(respBytes, &out); err != nil {
		return "", fmt.Errorf("cloud stt parse: %w", err)
	}
	if len(out.Results) == 0 || len(out.Results[0].Alternatives) == 0 {
		return "", fmt.Errorf("cloud stt no results")
	}
	return out.Results[0].Alternatives[0].Transcript, nil
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

