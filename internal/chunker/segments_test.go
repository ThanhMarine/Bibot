package chunker

import (
	"strings"
	"testing"
)

// Vector FNV-1a 32-bit chuẩn (ASCII: UTF-16 units == bytes).
func TestStableHashVectors(t *testing.T) {
	cases := map[string]string{
		"":      "811c9dc5",
		"a":     "e40c292c",
		"hello": "4f9f2cab",
	}
	for in, want := range cases {
		if got := StableHash(in); got != want {
			t.Fatalf("StableHash(%q) = %s, want %s", in, got, want)
		}
	}
}

func TestStableHashSurrogate(t *testing.T) {
	// U+1D11E = surrogate pair D834 DD1E — phải hash 2 units, khác hẳn 1 unit.
	h := StableHash("\U0001D11E")
	if len(h) != 8 {
		t.Fatalf("bad hex len: %q", h)
	}
	if h == StableHash("a") || h == StableHash("") {
		t.Fatalf("surrogate hash collides: %q", h)
	}
	if StableHash("\U0001D11E") != h {
		t.Fatalf("not deterministic")
	}
	t.Logf("surrogate vector: %s", h)
}

func TestNormalizeIdentity(t *testing.T) {
	if got := NormalizeIdentityText("  Héllo   WORLD\n"); got != "héllo world" {
		t.Fatalf("got %q", got)
	}
	if got := NormalizeContentText("  Hello   World\n"); got != "Hello World" {
		t.Fatalf("got %q", got)
	}
}

func TestPlanSegments(t *testing.T) {
	units := []SourceUnit{
		{SourceKey: "chap1", Text: "Xin chào các bạn. Hôm nay trời đẹp quá!", SpineIndex: 0, ReaderType: "md"},
		{SourceKey: "chap2", Text: "Ngày mùng năm tháng ba.", SpineIndex: 1, ReaderType: "md"},
	}
	segs := PlanSegments(units, PlanOptions{DocID: "doc1", ReaderType: "md"}, SplitVietnameseSentences)
	if len(segs) == 0 {
		t.Fatalf("no segments")
	}
	for i, s := range segs {
		if s.Ordinal != i {
			t.Fatalf("ordinal %d != %d", s.Ordinal, i)
		}
		if !strings.HasPrefix(s.Key, "doc1:md:v1:") {
			t.Fatalf("bad key prefix: %q", s.Key)
		}
		if s.Text == "" || s.OwnerSourceKey == "" {
			t.Fatalf("empty text/owner: %+v", s)
		}
		if s.StartAnchor.Offset < 0 || s.EndAnchor.Offset < s.StartAnchor.Offset {
			t.Fatalf("bad anchors: %+v", s)
		}
		if s.Locator.SpineIndex < 0 || s.Locator.CharOffset < 0 {
			t.Fatalf("bad locator: %+v", s)
		}
	}
	// Cùng text ở unit khác nhau → cùng key (dedup chủ đích).
	dup := PlanSegments([]SourceUnit{
		{SourceKey: "a", Text: "Xin chào.", SpineIndex: 0},
		{SourceKey: "b", Text: "Xin chào.", SpineIndex: 5},
	}, PlanOptions{DocID: "doc1"}, SplitVietnameseSentences)
	if len(dup) != 2 || dup[0].Key != dup[1].Key {
		t.Fatalf("dedup key mismatch: %+v", dup)
	}
	if dup[0].Ordinal == dup[1].Ordinal {
		t.Fatalf("ordinals must differ")
	}
}

func TestSettingsHash(t *testing.T) {
	base := TTSSettings{Voice: "cloud:vi-VN-Neural2-A", Speed: "0.88", Format: "mp3", Language: "vi"}
	seg := Segmentation{MaxBlockLength: 200, Language: "vi", EnforceSourceBoundaries: true}
	h1 := SettingsHash(base, seg)
	h2 := SettingsHash(base, seg)
	if h1 != h2 || len(h1) != 64 {
		t.Fatalf("not deterministic/hex: %q", h1)
	}
	// Đổi voice → hash khác (cache tách theo voice → switch-back trúng cũ).
	other := base
	other.Voice = "cloud:en-US-Neural2-A"
	if SettingsHash(other, seg) == h1 {
		t.Fatalf("voice change must change hash")
	}
	other = base
	other.Speed = "1.0"
	if SettingsHash(other, seg) == h1 {
		t.Fatalf("speed change must change hash")
	}
}

func TestAudioContentHash(t *testing.T) {
	s := "tts-segment-plan-v2"
	_ = s
	a := AudioContentHash("doc1", 123, "sethash", "doc1:md:v1:abcd1234", "xin chào", "fp1")
	b := AudioContentHash("doc1", 123, "sethash", "doc1:md:v1:abcd1234", "xin chào", "fp1")
	if a != b || len(a) != 64 {
		t.Fatalf("not deterministic: %q", a)
	}
	// Cùng key ở ordinal khác (locator khác) → CÙNG hash (dedup chủ đích).
	c := AudioContentHash("doc1", 123, "sethash", "doc1:md:v1:abcd1234", "xin chào", "fp2")
	if a != c {
		t.Fatalf("same segmentKey must dedup, got %q vs %q", a, c)
	}
	// Text khác → khác hash.
	d := AudioContentHash("doc1", 123, "sethash", "doc1:md:v1:ffff0000", "tạm biệt", "fp1")
	if a == d {
		t.Fatalf("different text must differ")
	}
	// File đổi (version khác) → khác hash (không đọc nhầm cache file cũ).
	e := AudioContentHash("doc1", 999, "sethash", "doc1:md:v1:abcd1234", "xin chào", "fp1")
	if a == e {
		t.Fatalf("doc version change must differ")
	}
}

func TestLocatorFingerprint(t *testing.T) {
	a := LocatorFingerprint(SegmentLocator{ReaderType: "md", SpineIndex: 3, CharOffset: 10})
	b := LocatorFingerprint(SegmentLocator{ReaderType: "md", SpineIndex: 4, CharOffset: 10})
	if a == "" || a == b {
		t.Fatalf("bad fingerprints: %q %q", a, b)
	}
	if LocatorFingerprint(SegmentLocator{}) != "" {
		t.Fatalf("empty locator must give empty fingerprint")
	}
}

func TestAudioKey(t *testing.T) {
	if got := AudioKey("sethash", "audiohash"); got != "sethash/audiohash" {
		t.Fatalf("got %q", got)
	}
}
