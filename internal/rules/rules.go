// Package rules — H0/H1 Reading Rules: rulepack tools/rules/reading-rules.json
// (sinh từ .ruler, KHÔNG sửa tay). Engine chạy rewrite theo priority +
// drop_line/start_marker theo dòng. Ngữ nghĩa hook/template Y HỆT
// compile_rules.py và ReadingRules.kt. 0 dependency ngoài stdlib.
package rules

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"
)

//go:embed reading-rules.json
var embedded []byte

// Rule là 1 quy tắc đọc/nói từ rulepack.
type Rule struct {
	ID       string   `json:"id"`
	Kind     string   `json:"kind"`
	Scope    []string `json:"scope"`
	Lang     []string `json:"lang"`
	Priority int      `json:"priority"`
	Pattern  string   `json:"pattern,omitempty"`
	Patterns []string `json:"patterns,omitempty"`
	Template string   `json:"template,omitempty"`

	rx []*regexp.Regexp
}

// Pack là rulepack đã nạp, sắp theo priority.
type Pack struct {
	Version string `json:"version"`
	Rules   []*Rule
}

// LoadEmbedded nạp rulepack nhúng lúc build (fallback khi không có OTA).
func LoadEmbedded() (*Pack, error) {
	return Load(embedded)
}

// Load nạp rulepack từ JSON (nhúng hoặc OTA tải về, verify checksum trước khi gọi).
func Load(data []byte) (*Pack, error) {
	var raw struct {
		Version interface{} `json:"version"`
		Rules   []*Rule     `json:"rules"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	p := &Pack{Rules: raw.Rules}
	switch v := raw.Version.(type) {
	case float64:
		p.Version = fmt.Sprintf("%d", int(v))
	case string:
		p.Version = v
	}
	for _, r := range p.Rules {
		pats := []string{}
		if r.Pattern != "" {
			pats = append(pats, r.Pattern)
		}
		pats = append(pats, r.Patterns...)
		for _, s := range pats {
			rx, err := regexp.Compile(s)
			if err != nil {
				return nil, fmt.Errorf("rule %s: bad pattern: %w", r.ID, err)
			}
			r.rx = append(r.rx, rx)
		}
		if len(r.rx) == 0 {
			return nil, fmt.Errorf("rule %s: no pattern", r.ID)
		}
	}
	sort.Slice(p.Rules, func(i, j int) bool {
		if p.Rules[i].Priority != p.Rules[j].Priority {
			return p.Rules[i].Priority < p.Rules[j].Priority
		}
		return p.Rules[i].ID < p.Rules[j].ID
	})
	return p, nil
}

func matchLang(r *Rule, lang string) bool {
	if len(r.Lang) == 0 {
		return true
	}
	for _, l := range r.Lang {
		if l == lang {
			return true
		}
	}
	return false
}

var phRe = regexp.MustCompile(`\{([a-z]+:)?(\d+)\}`)

// hook Y HỆT Python/Kotlin — "" + ok=false = bỏ cả match, giữ nguyên chữ.
func hook(name, raw string) (string, bool) {
	t := strings.TrimSpace(raw)
	if name == "" {
		return t, true
	}
	toInt := func() (int, bool) {
		n := 0
		if t == "" {
			return 0, false
		}
		for _, r := range t {
			if r < '0' || r > '9' {
				return 0, false
			}
			n = n*10 + int(r-'0')
		}
		return n, true
	}
	switch name {
	case "day":
		v, ok := toInt()
		if !ok || v < 1 || v > 31 {
			return "", false
		}
		if v <= 10 {
			return fmt.Sprintf("ngày mùng %s", viWords(v)), true
		}
		return fmt.Sprintf("ngày %s", viWords(v)), true
	case "month":
		v, ok := toInt()
		if !ok || v < 1 || v > 12 {
			return "", false
		}
		return viWords(v), true
	case "year":
		v, ok := toInt()
		if !ok {
			return "", false
		}
		switch len(t) {
		case 2:
			v += 2000
		case 4:
			if v < 1000 || v > 2999 {
				return "", false
			}
		default:
			return "", false
		}
		return viWords(v), true
	case "num":
		v, ok := toInt()
		if !ok {
			return "", false
		}
		return viWords(v), true
	case "roman":
		v := romanInt(t)
		if v < 0 {
			return "", false
		}
		return viWords(v), true
	}
	return "", false
}

func applyTemplate(tpl string, m []string) (string, bool) {
	var b strings.Builder
	pos := 0
	for _, loc := range phRe.FindAllStringSubmatchIndex(tpl, -1) {
		b.WriteString(tpl[pos:loc[0]])
		name := ""
		if loc[2] >= 0 { // group ([a-z]+:)? có thể vắng mặt -> index -1
			name = tpl[loc[2]:loc[3]]
			if strings.HasSuffix(name, ":") {
				name = name[:len(name)-1]
			}
		}
		idx := 0
		for _, r := range tpl[loc[4]:loc[5]] {
			idx = idx*10 + int(r-'0')
		}
		if idx < 1 || idx >= len(m) {
			return "", false
		}
		r, ok := hook(name, m[idx])
		if !ok {
			return "", false
		}
		b.WriteString(r)
		pos = loc[1]
	}
	b.WriteString(tpl[pos:])
	return b.String(), true
}

// Apply chạy rewrite rules (scope tts) theo priority.
func (p *Pack) Apply(text, lang string) string {
	t := text
	for _, r := range p.Rules {
		if r.Kind != "rewrite" || !matchLang(r, lang) {
			continue
		}
		rx := r.rx[0]
		t = rx.ReplaceAllStringFunc(t, func(mstr string) string {
			sub := rx.FindStringSubmatch(mstr)
			if out, ok := applyTemplate(r.Template, sub); ok {
				return out
			}
			return mstr
		})
	}
	return t
}

// IsDropLine true nếu dòng bị bỏ (scope segment).
func (p *Pack) IsDropLine(line, lang string) bool {
	for _, r := range p.Rules {
		if r.Kind != "drop_line" || !matchLang(r, lang) {
			continue
		}
		for _, rx := range r.rx {
			if rx.MatchString(line) {
				return true
			}
		}
	}
	return false
}

// IsStartMarker true nếu là mốc bắt đầu đọc.
func (p *Pack) IsStartMarker(text, lang string) bool {
	for _, r := range p.Rules {
		if r.Kind != "start_marker" || !matchLang(r, lang) {
			continue
		}
		for _, rx := range r.rx {
			if rx.MatchString(text) {
				return true
			}
		}
	}
	return false
}

var viDigits = []string{"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"}

func viUnder100(n int) string {
	t, u := n/10, n%10
	if t == 0 {
		return viDigits[u]
	}
	tens := "mười"
	if t != 1 {
		tens = viDigits[t] + " mươi"
	}
	if u == 0 {
		return tens
	}
	// Quirk parity Kotlin readUnder100: u==1 luôn "mốt" (kể cả "mười mốt").
	// Chuẩn là "mười một" — dời fix sang H2 (ghi nhận, không đổi hành vi lẻ).
	switch u {
	case 1:
		return tens + " mốt"
	case 4:
		return tens + " tư"
	case 5:
		return tens + " lăm"
	default:
		return tens + " " + viDigits[u]
	}
}

func viUnder1000(g int) string {
	h, rest := g/100, g%100
	if h == 0 {
		return viUnder100(g)
	}
	s := viDigits[h] + " trăm"
	if rest == 0 {
		return s
	}
	if rest < 10 {
		return s + " lẻ " + viDigits[rest]
	}
	return s + " " + viUnder100(rest)
}

// viWords đọc số nguyên 0..999999999 thành tiếng Việt (port TextCleaner).
func viWords(n int) string {
	if n == 0 {
		return "không"
	}
	var groups []int
	for x := n; x > 0; x /= 1000 {
		groups = append(groups, x%1000)
	}
	scales := []string{"", "nghìn", "triệu"}
	var out []string
	higher := false
	for i := len(groups) - 1; i >= 0; i-- {
		g := groups[i]
		if g == 0 {
			continue
		}
		var part string
		if higher && g < 100 {
			if g < 10 {
				part = "lẻ " + viDigits[g]
			} else {
				part = viUnder100(g)
			}
		} else {
			part = viUnder1000(g)
		}
		if scales[i] != "" {
			part += " " + scales[i]
		}
		out = append(out, part)
		higher = true
	}
	return strings.Join(out, " ")
}

var validRomanRe = regexp.MustCompile(`^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$`)

// romanInt La Mã hợp lệ -> 1..3999; IIX=8 cứu; còn lại -1.
func romanInt(s string) int {
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
