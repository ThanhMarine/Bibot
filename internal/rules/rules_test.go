package rules

import "testing"

// Test rulepack THẬT (nhúng từ tools/rules/reading-rules.json):
// cùng kỳ vọng với compile_rules.py — 3 engine 1 chuẩn.
func loadTestPack(t *testing.T) *Pack {
	t.Helper()
	p, err := LoadEmbedded()
	if err != nil {
		t.Fatalf("LoadEmbedded: %v", err)
	}
	// v4 (G2.2): 17 rules v3 + 12 rule viết-tắt/vỏ-ngoặc.
	if p.Version == "" || len(p.Rules) != 29 {
		t.Fatalf("pack lạ: version=%q rules=%d", p.Version, len(p.Rules))
	}
	return p
}

func TestApplyDates(t *testing.T) {
	p := loadTestPack(t)
	cases := map[string]string{
		"31/12":                    "ngày ba mươi mốt tháng mười hai",
		"Gặp nhau ngày 31/12 nhé": "Gặp nhau ngày ba mươi mốt tháng mười hai nhé",
		"05/06":                    "ngày mùng năm tháng sáu",
		"1.440 đồng":               "1.440 đồng",
		"12:30 trưa":               "12:30 trưa",
		"32/12 sai":                "32/12 sai",
	}
	for in, want := range cases {
		if got := p.Apply(in, "vi"); got != want {
			t.Errorf("Apply(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestIsDropLine(t *testing.T) {
	p := loadTestPack(t)
	drop := []string{"12", "[12]", "- 12 -", "Trang 12"}
	keep := []string{"năm 2026 rồi", "19840 người", "Chương 12 hay"}
	for _, s := range drop {
		if !p.IsDropLine(s, "vi") {
			t.Errorf("IsDropLine(%q) = false, want true", s)
		}
	}
	for _, s := range keep {
		if p.IsDropLine(s, "vi") {
			t.Errorf("IsDropLine(%q) = true, want false", s)
		}
	}
}

func TestApplyRoman(t *testing.T) {
	p := loadTestPack(t)
	cases := map[string]string{
		"Chương X mở đầu":    "Chương mười mở đầu",
		"Phụ lục VII":        "Phụ lục bảy",
		"Phần IIX đặc biệt":  "Phần tám đặc biệt",
		"Chương IIII sai":    "Chương IIII sai",
		"Louis XIV đến chơi": "Louis XIV đến chơi",
	}
	for in, want := range cases {
		if got := p.Apply(in, "vi"); got != want {
			t.Errorf("Apply(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestApplyV2(t *testing.T) {
	p := loadTestPack(t)
	cases := map[string]string{
		"31-12-2026":              "ngày ba mươi mốt tháng mười hai năm hai nghìn hai mươi sáu",
		"25°C hôm nay":            "25 độ C hôm nay",
		"thế kỷ XI-XIX rực rỡ":    "thế kỷ mười mốt đến thế kỷ mười chín rực rỡ",
		"Xem (Fig. 5) nhé":        "Xem, hình 5 nhé", // G2.2: vỏ ngoặc ngoài → phẩy
		"Chương 1 .... 5 còn đọc": "Chương 1 .... 5 còn đọc", // drop chỉ theo dòng
		// G2.2: viết tắt đọc ra từ (gói đủ → ngoặc ngoài cũng thành phẩy).
		"nhiều loài (e.g. mèo, chó)": "nhiều loài, ví dụ mèo, chó",
		"mèo, chó, etc. đều được":    "mèo, chó, vân vân đều được",
		"A vs. B hay hơn":            "A so với B hay hơn",
		"không ai (i.e. tôi) đến":     "không ai, nghĩa là tôi đến",
		"A, , b":                      "A, b",
		"ngoặc vuông [giữ]":           "ngoặc vuông [giữ]",
	}
	for in, want := range cases {
		if got := p.Apply(in, "vi"); got != want {
			t.Errorf("Apply(%q) = %q, want %q", in, got, want)
		}
	}
	if got := p.Apply("See (Fig. 5).", "en"); got != "See, figure 5." {
		t.Errorf("Apply en fig = %q", got)
	}
	if got := p.Apply("the winner (i.e. me) wins.", "en"); got != "the winner, that is me wins." {
		t.Errorf("Apply en ie = %q", got)
	}
	if got := p.Apply("cats, dogs, etc. live here.", "en"); got != "cats, dogs, and so on live here." {
		t.Errorf("Apply en etc = %q", got)
	}
	// Scope B: display giữ "[Pg 62]", TTS (Apply) bỏ qua.
	if got := p.Apply("Text Tam[Pg 62] continues.", "en"); got != "Text Tam continues." {
		t.Errorf("Apply en pageno = %q", got)
	}
	if got := p.Apply("Xem [Pg. 7] nhé", "vi"); got != "Xem  nhé" {
		t.Errorf("Apply vi pageno = %q", got)
	}
	if !p.IsDropLine("Chương 1 .... 5", "vi") {
		t.Errorf("IsDropLine toc-leader = false, want true")
	}
	if !p.IsStartMarker("Lời giới thiệu", "vi") || !p.IsStartMarker("Chương 1", "vi") {
		t.Errorf("IsStartMarker intro/ch1 = false, want true")
	}
	if p.IsStartMarker("Chương 2", "vi") {
		t.Errorf("IsStartMarker Chương 2 = true, want false")
	}
	var guard *Rule
	for _, r := range p.Rules {
		if r.Kind == "split_guard" {
			guard = r
		}
	}
	if guard == nil || guard.Params["brackets_open"] != "([{" || guard.Params["overrun_words"] != "8" {
		t.Errorf("split_guard params lạ: %+v", guard)
	}
}

func TestViWords(t *testing.T) {
	cases := map[int]string{
		0: "không", 5: "năm", 10: "mười", 11: "mười mốt", // quirk parity Kotlin
		15: "mười lăm", 21: "hai mươi mốt", 24: "hai mươi tư",
		105: "một trăm lẻ năm", 1005: "một nghìn lẻ năm",
		2026: "hai nghìn hai mươi sáu", 1440: "một nghìn bốn trăm bốn mươi",
	}
	for n, want := range cases {
		if got := viWords(n); got != want {
			t.Errorf("viWords(%d) = %q, want %q", n, got, want)
		}
	}
}
