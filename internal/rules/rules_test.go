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
	if p.Version == "" || len(p.Rules) != 3 {
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
