package tts

import (
	"bibot/core/internal/config"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"
	"unicode"
)

var (
	thinkingRegex = regexp.MustCompile(`(?s)<think>.*?</think>`)
	mdLinkRegex   = regexp.MustCompile(`\[([^\]]*)\]\([^)]*\)`)
	urlRegex      = regexp.MustCompile(`(?i)(?:https?|ftp)://[^\s/$.?#].[^\s]*|www\.[^\s/$.?#].[^\s]*|[a-z0-9-]+\.(?:com|vn|org|net|edu|gov|io|co|info|biz|me|xyz|top|site|online|tech|app|dev|ai)[^\s]*`)
	danglingOpen  = regexp.MustCompile(`\[[^\]]*$`)
	danglingMid   = regexp.MustCompile(`\]\([^)]*$`)
	danglingParen = regexp.MustCompile(`\([^)]*https?[^)]*$`)
	markdownBold  = regexp.MustCompile("\\*\\*|\\*|__|```|`")
	headerRegex   = regexp.MustCompile(`(?m)^#+\s*`)
	bulletRegex   = regexp.MustCompile(`(?m)^\s*[-*+]\s+`)
	emptyParen    = regexp.MustCompile(`\(\s*\)`)
	emojiRegex    = regexp.MustCompile(`[\x{1F600}-\x{1F64F}\x{1F300}-\x{1F5FF}\x{1F680}-\x{1F6FF}\x{1F1E0}-\x{1F1FF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}]`)
	// Ngày tháng: có năm (/, -, . đều nhận) hoặc không năm (chỉ "/").
	// Không-năm + "-" là khoảng ("100-120" giữ nguyên cho "đến" lo).
	dateFullRe  = regexp.MustCompile(`(?i)(?:ngày\s+)?\b([0-3]?\d)\s*[/\-.]\s*([01]?\d)\s*[/\-.]\s*(\d{2}|\d{4})\b`)
	dateShortRe = regexp.MustCompile(`(?i)(?:ngày\s+)?\b([0-3]?\d)\s*/\s*([01]?\d)\b`)
	// Nhiệt độ: "25°C" -> "25 độ C"; "°" lẻ -> " độ ".
	degreeCRe    = regexp.MustCompile(`(?i)(\d)\s*°\s*C\b`)
	degreeFRe    = regexp.MustCompile(`(?i)(\d)\s*°\s*F\b`)
	degreeLoneRe = regexp.MustCompile(`°`)
	digitDegreeRe = regexp.MustCompile(`(?i)(\d)\s*(độ)`)
	// Số La Mã sau từ khóa (Go giữ chữ số cho engine đọc: "Chương X" -> "Chương 10").
	romanSingleRe = regexp.MustCompile(`(?i)\b(thế\s+kỷ|kỷ|chương|phần|hồi|tập|quyển|mục|điều|khoản|phụ\s+lục)\s+([IVXLCDM]+)\b`)
	romanRangeRe  = regexp.MustCompile(`(?i)\b(thế\s+kỷ|kỷ|chương|phần|hồi|tập|quyển|mục|điều|khoản|phụ\s+lục)\s+([IVXLCDM]+)\s*[-–—_]\s*([IVXLCDM]+)\b`)
	validRomanRe  = regexp.MustCompile(`^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$`)
	splitUpperLowerRe = regexp.MustCompile("\\b([A-ZÀÁẠẢÃĂẰẮẶẲẴẦẤẬẨẪÈÉẸẺẼỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨỪỨỰỬỮỲÝỴỶỸĐ]) ([a-zàáạảãăằắặẳẵầấậẩẫèéẹẻẽềếệểễìíịỉĩòóọỏõồốộổỗơờớợởỡùúụủũừứựửữỳýỵỷỹđ])")
	// Chữ đơn đứng riêng (điểm/vitamin/nhóm máu/tia): không dán với từ sau.
	soloTokenLetters = "ABCDEFKOXY"
)

// isVietnameseLetter reports if r is in Vietnamese letter ranges + ASCII letters.
func isVietnameseLetter(r rune) bool {
	if (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z') {
		return true
	}
	// Vietnamese extended Latin: À-ỹ range 0xC0-0x1EF9 plus Đ/đ
	if r == 'Đ' || r == 'đ' {
		return true
	}
	if r >= 0xC0 && r <= 0x1EF9 {
		return true
	}
	if r >= 0x100 && r <= 0x17F {
		return true
	}
	return false
}

// stripMarkdownLink keeps label unless label itself is URL/domain -> drop.
func stripMarkdownLink(s string) string {
	return mdLinkRegex.ReplaceAllStringFunc(s, func(m string) string {
		sub := mdLinkRegex.FindStringSubmatch(m)
		if len(sub) < 2 {
			return ""
		}
		label := sub[1]
		// if label contains URL or is domain-like (has dot and no space) -> drop
		if urlRegex.MatchString(label) {
			return ""
		}
		if strings.Contains(label, ".") && !strings.Contains(strings.TrimSpace(label), " ") {
			return ""
		}
		return label
	})
}

// removeDigitGluedToLetter removes digits glued to Vietnamese letters: "Colombia1 " -> ""
// Exception: keep digit if pattern is math like "2x3" (digit-x-digit) to allow later "nhân" replacement
func removeDigitGluedToLetter(s string) string {
	var b strings.Builder
	runes := []rune(s)
	for i := 0; i < len(runes); i++ {
		if unicode.IsDigit(runes[i]) && i > 0 && isVietnameseLetter(runes[i-1]) {
			// math exception: digit after x/X which follows a digit -> keep (2x3)
			if (runes[i-1] == 'x' || runes[i-1] == 'X') && i >= 2 && unicode.IsDigit(runes[i-2]) {
				b.WriteRune(runes[i])
				continue
			}
			// consume consecutive digits
			j := i
			for j < len(runes) && unicode.IsDigit(runes[j]) {
				j++
			}
			// check what follows: space, .,!?;: or end
			if j >= len(runes) || unicode.IsSpace(runes[j]) || strings.ContainsRune(".,!?;:", runes[j]) {
				i = j - 1
				continue
			}
		}
		b.WriteRune(runes[i])
	}
	return b.String()
}

// replaceAbbrevDot: "TP.HCM" -> "TP HCM"  (dot between Vietnamese letters -> space)
func replaceAbbrevDot(s string) string {
	var b strings.Builder
	r := []rune(s)
	for i, ch := range r {
		if ch == '.' && i > 0 && i < len(r)-1 && isVietnameseLetter(r[i-1]) && isVietnameseLetter(r[i+1]) {
			b.WriteRune(' ')
		} else {
			b.WriteRune(ch)
		}
	}
	return b.String()
}

// replaceNumDot: "1.440" -> "1440" (dot between digits -> remove)
func replaceNumDot(s string) string {
	var b strings.Builder
	r := []rune(s)
	for i, ch := range r {
		if ch == '.' && i > 0 && i < len(r)-1 && unicode.IsDigit(r[i-1]) && unicode.IsDigit(r[i+1]) {
			continue
		}
		b.WriteRune(ch)
	}
	return b.String()
}

// replaceThousandComma: "1,440" -> "1440" but "3,5" -> "3 phẩy 5"
func replaceComma(s string) string {
	// first handle thousand commas
	var b strings.Builder
	r := []rune(s)
	for i := 0; i < len(r); i++ {
		if r[i] == ',' && i > 0 && i < len(r)-1 && unicode.IsDigit(r[i-1]) && unicode.IsDigit(r[i+1]) {
			// check if 3 digits ahead and not digit after
			remaining := 0
			j := i + 1
			for j < len(r) && unicode.IsDigit(r[j]) && remaining < 3 {
				remaining++
				j++
			}
			if remaining == 3 && (j >= len(r) || !unicode.IsDigit(r[j])) {
				// thousand separator -> drop
				continue
			}
			// decimal comma -> " phẩy "
			b.WriteString(" phẩy ")
			continue
		}
		b.WriteRune(r[i])
	}
	return b.String()
}

// replaceDashBetweenNumbers: "100-120" / "100–120" -> "100 đến 120" then normalize dashes to ","
func replaceDashBetweenNumbers(s string) string {
	var b strings.Builder
	r := []rune(s)
	for i := 0; i < len(r); i++ {
		if (r[i] == '-' || r[i] == '–' || r[i] == '—') && i > 0 && i < len(r)-1 {
			// skip spaces around dash to check digits
			left := i - 1
			for left >= 0 && unicode.IsSpace(r[left]) {
				left--
			}
			right := i + 1
			for right < len(r) && unicode.IsSpace(r[right]) {
				right++
			}
			if left >= 0 && right < len(r) && unicode.IsDigit(r[left]) && unicode.IsDigit(r[right]) {
				// trim spaces already written? we wrote left part, need to ensure single spaces
				// remove trailing spaces in builder
				str := b.String()
				str = strings.TrimRight(str, " \t")
				b.Reset()
				b.WriteString(str)
				b.WriteString(" đến ")
				// skip spaces after dash
				for right > i+1 && unicode.IsSpace(r[i+1]) {
					i++
				}
				// also skip spaces after dash in source
				for i+1 < len(r) && unicode.IsSpace(r[i+1]) {
					i++
				}
				continue
			}
		}
		b.WriteRune(r[i])
	}
	// remaining en/em dashes -> comma
	res := b.String()
	res = strings.ReplaceAll(res, "–", ",")
	res = strings.ReplaceAll(res, "—", ",")
	return res
}

// romanToIntGo: Roman hợp lệ -> 1..3999; phi chuẩn sách cũ (IIX=8) -> map cứu; còn lại -1.
func romanToIntGo(s string) int {
	u := strings.ToUpper(s)
	if u == "IIX" {
		return 8
	}
	if !validRomanRe.MatchString(u) {
		return -1
	}
	vals := map[rune]int{'I': 1, 'V': 5, 'X': 10, 'L': 50, 'C': 100, 'D': 500, 'M': 1000}
	r := []rune(u)
	total, prev := 0, 0
	for i := len(r) - 1; i >= 0; i-- {
		v, ok := vals[r[i]]
		if !ok {
			return -1
		}
		if v < prev {
			total -= v
		} else {
			total += v
			prev = v
		}
	}
	if total < 1 || total > 3999 {
		return -1
	}
	return total
}

// readRomanGo: "Chương X" -> "Chương 10"; "thế kỷ XI-XIX" -> "thế kỷ 11 đến thế kỷ 19".
// Chỉ sau từ khóa nên "Louis XIV"/"I" đứng riêng giữ nguyên.
func readRomanGo(s string) string {
	s = romanRangeRe.ReplaceAllStringFunc(s, func(m string) string {
		sub := romanRangeRe.FindStringSubmatch(m)
		if len(sub) < 4 {
			return m
		}
		a, b := romanToIntGo(sub[2]), romanToIntGo(sub[3])
		if a < 0 || b < 0 {
			return m
		}
		return fmt.Sprintf("%s %d đến %s %d", sub[1], a, sub[1], b)
	})
	return romanSingleRe.ReplaceAllStringFunc(s, func(m string) string {
		sub := romanSingleRe.FindStringSubmatch(m)
		if len(sub) < 3 {
			return m
		}
		v := romanToIntGo(sub[2])
		if v < 0 {
			return m
		}
		return fmt.Sprintf("%s %d", sub[1], v)
	})
}

func toInt2(t string) int {
	n := 0
	for _, r := range t {
		if r < '0' || r > '9' {
			return -1
		}
		n = n*10 + int(r-'0')
	}
	return n
}

// readDatesGo: "31/12" -> "ngày 31 tháng 12"; "31-12-2026" -> "ngày 31 tháng 12 năm 2026".
// Chữ số giữ nguyên cho engine TTS đọc; validate ngày/tháng/năm nên
// "1.440", "3.5", "12:30", "100-120" đều thoát.
func readDatesGo(s string) string {
	dayWord := func(d int) string {
		if d >= 1 && d <= 10 {
			return fmt.Sprintf("ngày mùng %d", d)
		}
		return fmt.Sprintf("ngày %d", d)
	}
	s = dateFullRe.ReplaceAllStringFunc(s, func(m string) string {
		sub := dateFullRe.FindStringSubmatch(m)
		if len(sub) < 4 {
			return m
		}
		d, mo := toInt2(sub[1]), toInt2(sub[2])
		if d < 1 || d > 31 || mo < 1 || mo > 12 {
			return m
		}
		y := toInt2(sub[3])
		if len(sub[3]) == 4 {
			if y < 1000 || y > 2999 {
				return m
			}
		} else {
			y += 2000
		}
		return fmt.Sprintf("%s tháng %d năm %d", dayWord(d), mo, y)
	})
	return dateShortRe.ReplaceAllStringFunc(s, func(m string) string {
		sub := dateShortRe.FindStringSubmatch(m)
		if len(sub) < 3 {
			return m
		}
		d, mo := toInt2(sub[1]), toInt2(sub[2])
		if d < 1 || d > 31 || mo < 1 || mo > 12 {
			return m
		}
		return fmt.Sprintf("%s tháng %d", dayWord(d), mo)
	})
}

// normalizeDegreesGo: "25°C"/"25-30°C" -> "25 độ C"/"25-30 độ C"
// (gạch số-số do replaceDashBetweenNumbers đổi "đến" sau); "30độ" -> "30 độ".
func normalizeDegreesGo(s string) string {
	if !strings.Contains(s, "°") && !strings.Contains(strings.ToLower(s), "độ") {
		return s
	}
	s = degreeCRe.ReplaceAllString(s, "$1 độ C")
	s = degreeFRe.ReplaceAllString(s, "$1 độ F")
	s = degreeLoneRe.ReplaceAllString(s, " độ ")
	s = digitDegreeRe.ReplaceAllString(s, "$1 $2")
	return s
}

// CleanTTSText ports services/streaming_llm.py:143-208 clean_tts_text faithfully.
func CleanTTSText(text string) string {
	if text == "" {
		return ""
	}
	text = stripMarkdownLink(text)
	text = urlRegex.ReplaceAllString(text, " ")
	text = danglingOpen.ReplaceAllString(text, "")
	text = danglingMid.ReplaceAllString(text, "")
	text = danglingParen.ReplaceAllString(text, "")
	// Nguyên tắc đọc sách (parity Kotlin TextCleaner): ngày tháng + độ + La Mã
	// chạy trước mọi thay thế số, để "/" (ngày), "-" (khoảng/ngày-năm) đúng nghĩa.
	text = readDatesGo(text)
	text = normalizeDegreesGo(text)
	text = readRomanGo(text)
	text = removeDigitGluedToLetter(text)
	text = replaceAbbrevDot(text)
	text = replaceNumDot(text)
	text = replaceComma(text)
	text = replaceDashBetweenNumbers(text)
	text = strings.ReplaceAll(text, "×", " nhân ")
	text = regexp.MustCompile(`(?i)(\d)\s*x\s*(\d)`).ReplaceAllString(text, "$1 nhân $2")
	text = strings.ReplaceAll(text, "=", " bằng ")
	text = strings.ReplaceAll(text, "%", " phần trăm ")
	text = strings.ReplaceAll(text, "/", " ")
	// Fix split chars like "M èo" -> "Mèo" for Vietnamese uppercase + lowercase
	// Vá chữ chẻ PDF ("M èo" -> "Mèo") — nhưng giữ token chữ đơn đứng riêng
	// ("vitamin C hôm nay", "tia X quang"): A-F/K/O/X/Y + chữ thường sau
	// hầu như luôn là token, dán vào thành "Chôm"/"Xquang" là sai.
	text = splitUpperLowerRe.ReplaceAllStringFunc(text, func(m string) string {
		sub := splitUpperLowerRe.FindStringSubmatch(m)
		if len(sub) < 3 {
			return m
		}
		if len(sub[1]) == 1 && strings.Contains(soloTokenLetters, sub[1]) {
			return m
		}
		return sub[1] + sub[2]
	})
	text = regexp.MustCompile(`\b([A-ZÀÁẠẢÃĂẰẮẶẲẴẦẤẬẨẪÈÉẸẺẼỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨỪỨỰỬỮỲÝỴỶỸĐ]) ([A-ZÀÁẠẢÃĂẰẮẶẲẴẦẤẬẨẪÈÉẸẺẼỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨỪỨỰỬỮỲÝỴỶỸĐ]{1,3})\b`).ReplaceAllString(text, "$1$2")
	text = markdownBold.ReplaceAllString(text, "")
	text = headerRegex.ReplaceAllString(text, "")
	text = bulletRegex.ReplaceAllString(text, "")
	text = emptyParen.ReplaceAllString(text, "")
	text = emojiRegex.ReplaceAllString(text, "")
	text = regexp.MustCompile(`\s+([.,!?;:])`).ReplaceAllString(text, "$1")
	text = regexp.MustCompile(`\s+`).ReplaceAllString(text, " ")
	return strings.TrimSpace(text)
}

// Synthesize generates audio from text using Google Cloud TTS REST (WaveNet/Neural2) or returns error for fallback.
// Supported voice: vi-VN-Neural2-A, vi-VN-Wavenet-A, en-US-Neural2-A. Fallback caller handles Android TTS offline.
func Synthesize(text, voice string) ([]byte, error) {
	clean := CleanTTSText(text)
	if clean == "" {
		return nil, fmt.Errorf("empty text after cleaning")
	}
	if voice == "" {
		voice = "vi-VN-Neural2-A"
	}
	// Language derived from voice prefix
	lang := "vi-VN"
	if strings.HasPrefix(voice, "en-") {
		lang = "en-US"
	}
	apiKey := config.GetConfig().CloudTTSAPIKey
	if apiKey == "" {
		apiKey = os.Getenv("GOOGLE_CLOUD_TTS_API_KEY")
	}
	if apiKey == "" {
		apiKey = os.Getenv("GEMINI_API_KEY")
	}
	// If still no key, signal caller to fallback to offline Android TTS
	if apiKey == "" {
		return nil, fmt.Errorf("no Cloud TTS key: set CloudTTSAPIKey or GEMINI_API_KEY, fallback to offline")
	}
	// Build REST request: POST https://texttospeech.googleapis.com/v1/text:synthesize?key=KEY
	url := "https://texttospeech.googleapis.com/v1/text:synthesize?key=" + apiKey
	body := map[string]interface{}{
		"input": map[string]string{"text": clean},
		"voice": map[string]string{"languageCode": lang, "name": voice},
		"audioConfig": map[string]interface{}{
			"audioEncoding":   "MP3",
			"speakingRate":    0.95,
			"sampleRateHertz": 24000,
		},
	}
	b, _ := json.Marshal(body)
	req, err := http.NewRequest("POST", url, bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 25 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("cloud tts request failed: %w", err)
	}
	defer resp.Body.Close()
	bb, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("cloud tts http %d: %s", resp.StatusCode, string(bb[:minInt(400, len(bb))]))
	}
	var out struct {
		AudioContent string `json:"audioContent"`
	}
	if err := json.Unmarshal(bb, &out); err != nil {
		return nil, fmt.Errorf("cloud tts parse: %w", err)
	}
	if out.AudioContent == "" {
		return nil, fmt.Errorf("cloud tts empty audioContent")
	}
	decoded, err := base64.StdEncoding.DecodeString(out.AudioContent)
	if err != nil {
		return nil, fmt.Errorf("cloud tts base64 decode: %w", err)
	}
	return decoded, nil
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

