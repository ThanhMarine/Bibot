package chunker

import (
	"regexp"
	"strings"
)

var (
	chunkSplitPattern = regexp.MustCompile(`([.!?…]|[;:,、]|\s+(?:và|hoặc|nhưng|mà|tuy nhiên|do đó|vì vậy|nên|khi|nếu|khi mà)\s+)`)
	wordCountRegex    = regexp.MustCompile(`\S+`)
)

var singleWords = map[string]struct{}{
	"là": {}, "của": {}, "cho": {}, "với": {}, "tại": {}, "từ": {}, "đến": {}, "về": {}, "trên": {}, "dưới": {},
	"trong": {}, "ngoài": {}, "sau": {}, "trước": {}, "giữa": {}, "bên": {}, "cạnh": {}, "gần": {}, "xa": {},
	"một": {}, "hai": {}, "ba": {}, "bốn": {}, "năm": {}, "sáu": {}, "bảy": {}, "tám": {}, "chín": {}, "mười": {},
	"cái": {}, "con": {}, "chiếc": {}, "người": {}, "địa": {}, "này": {}, "kia": {}, "đó": {}, "nọ": {},
	"tôi": {}, "bạn": {}, "anh": {}, "chị": {}, "em": {}, "chúng": {}, "họ": {}, "ta": {}, "mình": {},
}

// D6: viết tắt trích dẫn — "Fig."/"No." cuối chunk KHÔNG phải hết câu
// (parity Kotlin TextCleaner.endsWithCiteAbbrev).
var citeAbbrevWords = map[string]struct{}{
	"fig": {}, "no": {}, "st": {}, "mr": {}, "mrs": {}, "ms": {}, "dr": {}, "prof": {}, "vs": {},
	"etc": {}, "ie": {}, "eg": {}, "am": {}, "pm": {}, "tr": {}, "tp": {}, "nxb": {},
	"p": {}, "pp": {}, "ch": {}, "sec": {}, "vol": {}, "ed": {},
}

func endsWithCiteAbbrev(chunk string) bool {
	t := strings.TrimRight(chunk, " \t\n")
	if !strings.HasSuffix(t, ".") {
		return false
	}
	w := t[:len(t)-1]
	if i := strings.LastIndex(w, " "); i >= 0 {
		w = w[i+1:]
	}
	w = strings.TrimLeft(w, "([{\"'“")
	w = strings.ToLower(strings.TrimSpace(w))
	_, ok := citeAbbrevWords[w]
	return ok
}

// D6: còn mở ngoặc ([{ chưa đóng, hoặc trong "..."/“...” → chưa gom đủ đoạn.
func insideBracketsOrQuote(t string) bool {
	depth := 0
	straightQuote := false
	curlyOpen := false
	for _, c := range t {
		switch c {
		case '(', '[', '{':
			depth++
		case ')', ']', '}':
			if depth > 0 {
				depth--
			}
		case '"':
			straightQuote = !straightQuote
		case '“':
			curlyOpen = true
		case '”':
			curlyOpen = false
		}
	}
	return depth > 0 || straightQuote || curlyOpen
}

func wordCount(s string) int { return len(strings.Fields(s)) }

// Chunker ports services/vietnamese_chunker.py:36 VietnameseTextChunker
type Chunker struct {
	MinWords int
	MaxWords int
	MaxChars int
	buffer   string
}

func NewChunker(minWords, maxWords, maxChars int) *Chunker {
	if minWords <= 0 {
		minWords = 4
	}
	if maxWords <= 0 {
		maxWords = 12
	}
	if maxChars <= 0 {
		maxChars = 120
	}
	return &Chunker{MinWords: minWords, MaxWords: maxWords, MaxChars: maxChars}
}

func isDelimiter(s string) bool { return chunkSplitPattern.MatchString(s) }

func (c *Chunker) shouldYield(chunk string, wc int) bool {
	chunk = strings.TrimSpace(chunk)
	if chunk == "" {
		return false
	}
	words := strings.Fields(chunk)
	lastW := ""
	if len(words) > 0 {
		lastW = strings.Trim(words[len(words)-1], ".,!?;:")
		lastW = strings.ToLower(lastW)
	}
	endsSentence := regexp.MustCompile(`[.!?…]\s*$`).MatchString(chunk)
	if regexp.MustCompile(`\d\.\s*$`).MatchString(chunk) {
		if wc < c.MaxWords+4 {
			return false
		}
	}
	// D6: gom đủ đoạn trong ngoặc/trích dẫn; ép yield khi quá dài để không kẹt.
	if insideBracketsOrQuote(chunk) && wc < c.MaxWords+8 && len(chunk) < c.MaxChars*2 {
		return false
	}
	// D6: "Fig."/"No." cuối chunk không phải hết câu.
	if endsWithCiteAbbrev(chunk) && wc < c.MaxWords+4 && len(chunk) < int(float64(c.MaxChars)*1.5) {
		return false
	}
	if _, isSingle := singleWords[lastW]; (isSingle || isDigits(lastW)) && !endsSentence {
		if wc < c.MaxWords+4 && len(chunk) < int(float64(c.MaxChars)*1.5) {
			return false
		}
	}
	if regexp.MustCompile(`[.!?…]\s*$`).MatchString(chunk) {
		return wc >= c.MinWords
	}
	if wc >= c.MinWords {
		if regexp.MustCompile(`[;:,、]\s*$`).MatchString(chunk) {
			return true
		}
		if regexp.MustCompile(`(?i)\s+(?:và|hoặc|nhưng|mà|tuy nhiên|do đó|vì vậy|nên|khi|nếu|khi mà)\s*$`).MatchString(chunk) {
			return true
		}
	}
	if wc >= c.MaxWords || len(chunk) >= c.MaxChars {
		return true
	}
	return false
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// Feed ports VietnameseTextChunker.feed()
func (c *Chunker) Feed(text string) []string {
	if text == "" {
		return nil
	}
	c.buffer += text
	// finditer split
	type part struct {
		isDel bool
		txt   string
	}
	var parts []part
	pos := 0
	for _, m := range chunkSplitPattern.FindAllStringIndex(c.buffer, -1) {
		s, e := m[0], m[1]
		if s > pos {
			parts = append(parts, part{false, c.buffer[pos:s]})
		}
		parts = append(parts, part{true, c.buffer[s:e]})
		pos = e
	}
	if pos < len(c.buffer) {
		parts = append(parts, part{false, c.buffer[pos:]})
	}
	var out []string
	cur := ""
	for _, p := range parts {
		if p.txt == "" {
			continue
		}
		if p.isDel {
			cur += p.txt
			wc := wordCount(cur)
			if c.shouldYield(cur, wc) {
				out = append(out, strings.TrimSpace(cur))
				cur = ""
			}
		} else {
			test := cur + p.txt
			if cur != "" && wordCount(test) > c.MaxWords {
				out = append(out, strings.TrimSpace(cur))
				cur = p.txt
			} else {
				cur = test
			}
		}
	}
	c.buffer = cur
	return out
}

func (c *Chunker) Flush() []string {
	tail := strings.TrimSpace(c.buffer)
	c.buffer = ""
	if tail == "" {
		return nil
	}
	if !regexp.MustCompile(`\w`).MatchString(tail) {
		return nil
	}
	return []string{tail}
}

// SplitVietnameseSentences is the static helper used by cmd/mobile.
func SplitVietnameseSentences(text string) []string {
	ch := NewChunker(4, 12, 120)
	var res []string
	res = append(res, ch.Feed(text)...)
	res = append(res, ch.Flush()...)
	return res
}

// StreamingAccumulator ports StreamingTextAccumulator
type StreamingAccumulator struct {
	chunker *Chunker
}

func NewStreamingAccumulator(minWords, maxWords int) *StreamingAccumulator {
	return &StreamingAccumulator{chunker: NewChunker(minWords, maxWords, 120)}
}
func (s *StreamingAccumulator) FeedToken(token string) []string { return s.chunker.Feed(token) }
func (s *StreamingAccumulator) Flush() []string                 { return s.chunker.Flush() }

var _ = wordCountRegex

