package chunker

import (
	"strings"
	"testing"
)

// D6: gom chunk trong ngoặc/trích dẫn + "Fig."/"No." cuối chunk không phải hết câu.
func TestEndsWithCiteAbbrev(t *testing.T) {
	yes := []string{"xem (Fig.", "tai (Fig.", "No.", "muc (Sec.", "xem (PP."}
	for _, s := range yes {
		if !endsWithCiteAbbrev(s) {
			t.Errorf("endsWithCiteAbbrev(%q) = false, want true", s)
		}
	}
	no := []string{"câu này.", "hết rồi!", "Fig 5", "no 5", "xong."}
	for _, s := range no {
		if endsWithCiteAbbrev(s) {
			t.Errorf("endsWithCiteAbbrev(%q) = true, want false", s)
		}
	}
}

func TestInsideBracketsOrQuote(t *testing.T) {
	open := []string{"abc (def", "x [y", "(Fig. 5", `"nói`, "“lời dẫn"}
	for _, s := range open {
		if !insideBracketsOrQuote(s) {
			t.Errorf("insideBracketsOrQuote(%q) = false, want true", s)
		}
	}
	closed := []string{"abc (def) ghi", "câu này.", `"trả lời" xong`, "“xong” rồi"}
	for _, s := range closed {
		if insideBracketsOrQuote(s) {
			t.Errorf("insideBracketsOrQuote(%q) = true, want false", s)
		}
	}
}

func TestFeedKeepsParenthetical(t *testing.T) {
	ch := NewChunker(4, 12, 120)
	var res []string
	res = append(res, ch.Feed("Xem minh họa ở (Fig. 5) trong sách này.")...)
	res = append(res, ch.Flush()...)
	joined := strings.Join(res, " | ")
	for _, c := range res {
		if strings.HasSuffix(strings.TrimSpace(c), "(Fig.") {
			t.Fatalf("split inside citation: %q", joined)
		}
	}
	if !strings.Contains(joined, "(Fig. 5)") {
		t.Fatalf("citation lost: %q", joined)
	}
	if len(res) != 1 {
		t.Fatalf("expected 1 chunk, got %d: %q", len(res), joined)
	}
}
