package chunker

// TTS segment plan + content-addressed cache identity.
//
// Port ý tưởng từ openreader `packages/tts/src/` (segment-plan.ts, segments.ts,
// locator.ts) sang Go core. Giữ đúng:
//   - Segment = {ordinal, key, text, ownerSourceKey, ownerLocator, start/endAnchor}.
//   - key = "{prefix}:{FNV-1a 32-bit của identity-text}", FNV tính trên
//     UTF-16 code units (khớp `stableHash` JS: charCodeAt + imul) — với ASCII
//     thì trùng vector FNV-1a chuẩn (""=811c9dc5, "a"=e40c292c).
//   - settingsHash = SHA256(settingsCanonical + 0x00 + segmentationCanonical).
//   - audioContentHash bao {doc, version, settings, key, text, locatorFP};
//     khi đã có segmentKey thì bỏ ordinal (chủ đích dedup: cùng text+voice
//     ở 2 vị trí khác nhau dùng chung 1 file audio).
//   - anchor offset tính theo UTF-16 units (khớp JS), clamp trong unit.
//
// KHÁC BIỆT CÓ CHỦ ĐÍCH (ghi nhận để hội tụ sau):
//   - normalizeIdentity ở đây = lowercase + collapse ws + trim (side-local).
//     openreader còn audio-preprocess (URL/de-hyphen/glyph) trước; phía Kotlin
//     (TtsSegments) dùng TextCleaner. Key chỉ cần tự nhất quán trong 1 side
//     vì Go core và Android không chia sẻ cache file.
//   - locator Bibot = {spineIndex = chapter/page, charOffset best-effort};
//     không có CFI/blockId như openreader (PrepStore Bibot tổ chức theo chương).

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// ---------------------------------------------------------------------------
// Locator / Anchor / Segment
// ---------------------------------------------------------------------------

// SegmentLocator định vị 1 segment trong tài liệu.
type SegmentLocator struct {
	ReaderType string `json:"readerType"` // "pdf" | "epub" | "md"
	SpineIndex int    `json:"spineIndex"` // epub/md: chapter index; pdf: page 0-based
	CharOffset int    `json:"charOffset"` // UTF-16 units từ đầu owner unit; -1 = không rõ
}

// SegmentAnchor neo ký tự trong unit nguồn (offset theo UTF-16 units, đã clamp).
type SegmentAnchor struct {
	SourceKey string `json:"sourceKey"`
	Offset    int    `json:"offset"`
}

// Segment là đơn vị TTS nhỏ nhất có identity ổn định để play/seek/resume/cache.
type Segment struct {
	Ordinal        int            `json:"ordinal"` // 0-based, thứ tự emit = identity resume
	Text           string         `json:"text"`
	Key            string         `json:"key"`
	OwnerSourceKey string         `json:"ownerSourceKey"`
	Locator        SegmentLocator `json:"locator"`
	StartAnchor    SegmentAnchor  `json:"startAnchor"`
	EndAnchor      SegmentAnchor  `json:"endAnchor"`
}

// SourceUnit là 1 đơn vị nguồn (1 chương/page/block) đưa vào plan.
type SourceUnit struct {
	SourceKey  string
	Text       string
	SpineIndex int
	ReaderType string
}

// PlanOptions cho PlanSegments.
type PlanOptions struct {
	DocID      string
	ReaderType string // default "md" khi rỗng
	KeyPrefix  string // default "{docID}:{readerType}:v1"
	Language   string
}

// ---------------------------------------------------------------------------
// Normalize + StableHash (FNV-1a / UTF-16)
// ---------------------------------------------------------------------------

// NormalizeIdentityText chuẩn hoá text để tính key: lowercase + collapse ws + trim.
func NormalizeIdentityText(s string) string {
	return strings.Join(strings.Fields(strings.ToLower(s)), " ")
}

// NormalizeContentText chuẩn hoá text để tính content hash: collapse ws + trim (giữ case).
func NormalizeContentText(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

// utf16Units trả về UTF-16 code units của s (rune lỗi → U+FFFD như Go decode).
func utf16Units(s string) []uint16 {
	return utf16.Encode([]rune(s))
}

// utf16Len đếm UTF-16 units của tiền tố byte s[:byteLen] (cắt an toàn ở biên rune).
func utf16Len(s string, byteLen int) int {
	if byteLen <= 0 {
		return 0
	}
	if byteLen > len(s) {
		byteLen = len(s)
	}
	// Lùi về biên rune hợp lệ.
	for byteLen > 0 && !utf8.RuneStart(s[byteLen-1]) {
		byteLen--
	}
	// Nếu đang đứng giữa rune (byte tiếp theo là continuation), lùi tiếp.
	for byteLen < len(s) && byteLen > 0 && (s[byteLen]&0xC0) == 0x80 {
		byteLen--
	}
	return len(utf16.Encode([]rune(s[:byteLen])))
}

// StableHash = FNV-1a 32-bit trên UTF-16 code units, hex 8 ký tự.
// Khớp openreader stableHash: h=0x811C9DC5; h^=code; h=imul(h,0x01000193)>>>0.
func StableHash(s string) string {
	h := uint32(0x811C9DC5)
	for _, u := range utf16Units(s) {
		h ^= uint32(u)
		h *= 0x01000193
	}
	return fmt.Sprintf("%08x", h)
}

// SegmentKey = "{prefix}:{StableHash(identity)}".
func SegmentKey(prefix, text string) string {
	return prefix + ":" + StableHash(NormalizeIdentityText(text))
}

// ---------------------------------------------------------------------------
// PlanSegments
// ---------------------------------------------------------------------------

// PlanSegments chia units thành segments có identity ổn định.
// splitter: hàm tách câu/block của caller (vd SplitVietnameseSentences).
func PlanSegments(units []SourceUnit, opts PlanOptions, splitter func(string) []string) []Segment {
	readerType := opts.ReaderType
	if readerType == "" {
		readerType = "md"
	}
	prefix := opts.KeyPrefix
	if prefix == "" {
		doc := opts.DocID
		if doc == "" {
			doc = "document"
		}
		prefix = doc + ":" + readerType + ":v1"
	}
	var out []Segment
	ordinal := 0
	for _, u := range units {
		blocks := splitter(u.Text)
		cursor := 0 // byte offset tìm tiếp trong unit
		unitUnits := len(utf16Units(u.Text))
		for _, b := range blocks {
			block := strings.TrimSpace(b)
			if block == "" {
				continue
			}
			// Rematch block về offset trong unit (như openreader indexOf từ rawCursor).
			rel := strings.Index(u.Text[cursor:], block)
			abs := cursor
			if rel >= 0 {
				abs = cursor + rel
			}
			start := utf16Len(u.Text, abs)
			if start < 0 {
				start = 0
			}
			if start > unitUnits {
				start = unitUnits
			}
			end := utf16Len(u.Text, abs+len(block))
			if end < start {
				end = start
			}
			if end > unitUnits {
				end = unitUnits
			}
			cursor = abs + len(block)
			rt := u.ReaderType
			if rt == "" {
				rt = readerType
			}
			loc := SegmentLocator{ReaderType: rt, SpineIndex: u.SpineIndex, CharOffset: start}
			out = append(out, Segment{
				Ordinal:        ordinal,
				Key:            SegmentKey(prefix, block),
				Text:           block,
				OwnerSourceKey: u.SourceKey,
				Locator:        loc,
				StartAnchor:    SegmentAnchor{SourceKey: u.SourceKey, Offset: start},
				EndAnchor:      SegmentAnchor{SourceKey: u.SourceKey, Offset: end},
			})
			ordinal++
		}
	}
	return out
}

// ---------------------------------------------------------------------------
// settingsHash / audioContentHash / audioKey (port segments.ts)
// ---------------------------------------------------------------------------

// TTSSettings là đầu vào settingsCanonical (Bibot mapping, xem TtsSegments.kt).
type TTSSettings struct {
	Voice    string `json:"voice"`
	Speed    string `json:"speed"`
	Format   string `json:"format"`
	Language string `json:"language"`
}

// Segmentation là signature cách chia (port SegmentationSignatureInput).
type Segmentation struct {
	MaxBlockLength         int      `json:"maxBlockLength"`
	Language               string   `json:"language"`
	EnforceSourceBoundaries bool    `json:"enforceSourceBoundaries"`
	SkipBlockKinds         []string `json:"skipBlockKinds"`
	IsPlainText            bool     `json:"isPlainText"`
	Namespace              string   `json:"namespace"`
}

func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return ""
	}
	return string(b)
}

// SettingsHash = SHA256(settingsCanonical + 0x00 + segmentationCanonical).
// Đổi voice/speed/format/language/maxBlock → trúng cache cũ khi switch-back,
// thay vì mất cache như trước (A1.2).
func SettingsHash(s TTSSettings, seg Segmentation) string {
	if seg.SkipBlockKinds == nil {
		seg.SkipBlockKinds = []string{}
	}
	return sha256Hex(mustJSON(s) + "\x00" + mustJSON(seg))
}

// LocatorFingerprint = SHA256(canonical locator), '' khi locator rỗng.
func LocatorFingerprint(loc SegmentLocator) string {
	if loc.ReaderType == "" {
		return ""
	}
	return sha256Hex(mustJSON(loc))
}

// AudioContentHash bao {doc, version, settings, key, text, locatorFP}.
// Có segmentKey → locatorFP bị null (dedup cùng text ở nhiều vị trí —
// khớp openreader: i/l = null khi đã có segmentKey).
func AudioContentHash(docID string, docVersion int64, settingsHash, segmentKey, normalizedText, locatorFP string) string {
	l := locatorFP
	if segmentKey != "" {
		l = ""
	}
	payload := struct {
		D string `json:"d"`
		V int64  `json:"v"`
		S string `json:"s"`
		K string `json:"k"`
		T string `json:"t"`
		L string `json:"l"`
	}{D: docID, V: docVersion, S: settingsHash, K: segmentKey, T: normalizedText, L: l}
	return sha256Hex(mustJSON(payload))
}

// AudioKey = "{settingsHash}/{audioContentHash}" (dir/file; caller thêm ext).
func AudioKey(settingsHash, audioHash string) string {
	return settingsHash + "/" + audioHash
}
