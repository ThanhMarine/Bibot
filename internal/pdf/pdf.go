package pdf

import "strings"

// TCVN3 (TCVN 5712-3 / ABC) — font tieng Viet doi cu trong PDF.
// Bang byte->Unicode CHUAN (vietunicode.sourceforge.net, 74 slot + 60 cap ghep HOA).
// ASCII (0x00-0x7f) KHONG doi. VNI khong xu ly o day (tone roi/combining).
var tcvn3Lower = [128]rune{
	-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
	-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
	-1, 0x0102, 0x00C2, 0x00CA, 0x00D4, 0x01A0, 0x01AF, 0x0110, 0x0103, 0x00E2, 0x00EA, 0x00F4, 0x01A1, 0x01B0, 0x0111, -1,
	-1, -1, -1, -1, -1, 0x00E0, 0x1EA3, 0x00E3, 0x00E1, 0x1EA1, -1, 0x1EB1, 0x1EB3, 0x1EB5, 0x1EAF, -1,
	-1, -1, -1, -1, -1, -1, 0x1EB7, 0x1EA7, 0x1EA9, 0x1EAB, 0x1EA5, 0x1EAD, 0x00E8, -1, 0x1EBB, 0x1EBD,
	0x00E9, 0x1EB9, 0x1EC1, 0x1EC3, 0x1EC5, 0x1EBF, 0x1EC7, 0x00EC, 0x1EC9, -1, -1, -1, 0x0129, 0x00ED, 0x1ECB, 0x00F2,
	-1, 0x1ECF, 0x00F5, 0x00F3, 0x1ECD, 0x1ED3, 0x1ED5, 0x1ED7, 0x1ED1, 0x1ED9, 0x1EDD, 0x1EDF, 0x1EE1, 0x1EDB, 0x1EE3, 0x00F9,
	-1, 0x1EE7, 0x0169, 0x00FA, 0x1EE5, 0x1EEB, 0x1EED, 0x1EEF, 0x1EE9, 0x1EF1, 0x1EF3, 0x1EF7, 0x1EF9, 0x00FD, 0x1EF5, -1,
}
var tcvn3Upper = [128]rune{
	-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
	-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
	-1, 0x0102, 0x00C2, 0x00CA, 0x00D4, 0x01A0, 0x01AF, 0x0110, 0x0102, 0x00C2, 0x00CA, 0x00D4, 0x01A0, 0x01AF, 0x0110, -1,
	-1, -1, -1, -1, -1, 0x00C0, 0x1EA2, 0x00C3, 0x00C1, 0x1EA0, -1, 0x1EB0, 0x1EB2, 0x1EB4, 0x1EAE, -1,
	-1, -1, -1, -1, -1, -1, 0x1EB6, 0x1EA6, 0x1EA8, 0x1EAA, 0x1EA4, 0x1EAC, 0x00C8, -1, 0x1EBA, 0x1EBC,
	0x00C9, 0x1EB8, 0x1EC0, 0x1EC2, 0x1EC4, 0x1EBE, 0x1EC6, 0x00CC, 0x1EC8, -1, -1, -1, 0x0128, 0x00CD, 0x1ECA, 0x00D2,
	-1, 0x1ECE, 0x00D5, 0x00D3, 0x1ECC, 0x1ED2, 0x1ED4, 0x1ED6, 0x1ED0, 0x1ED8, 0x1EDC, 0x1EDE, 0x1EE0, 0x1EDA, 0x1EE2, 0x00D9,
	-1, 0x1EE6, 0x0168, 0x00DA, 0x1EE4, 0x1EEA, 0x1EEC, 0x1EEE, 0x1EE8, 0x1EF0, 0x1EF2, 0x1EF6, 0x1EF8, 0x00DD, 0x1EF4, -1,
}

var tcvn3Compose = map[string]rune{
	"A\xe0": 0x00C0,
	"A\xe1": 0x00C1,
	"A\xe3": 0x00C3,
	"A\u1ea1": 0x1EA0,
	"A\u1ea3": 0x1EA2,
	"E\xe8": 0x00C8,
	"E\xe9": 0x00C9,
	"E\u1eb9": 0x1EB8,
	"E\u1ebb": 0x1EBA,
	"E\u1ebd": 0x1EBC,
	"I\xec": 0x00CC,
	"I\xed": 0x00CD,
	"I\u0129": 0x0128,
	"I\u1ec9": 0x1EC8,
	"I\u1ecb": 0x1ECA,
	"O\xf2": 0x00D2,
	"O\xf3": 0x00D3,
	"O\xf5": 0x00D5,
	"O\u1ecd": 0x1ECC,
	"O\u1ecf": 0x1ECE,
	"U\xf9": 0x00D9,
	"U\xfa": 0x00DA,
	"U\u0169": 0x0168,
	"U\u1ee5": 0x1EE4,
	"U\u1ee7": 0x1EE6,
	"Y\xfd": 0x00DD,
	"Y\u1ef3": 0x1EF2,
	"Y\u1ef5": 0x1EF4,
	"Y\u1ef7": 0x1EF6,
	"Y\u1ef9": 0x1EF8,
	"\xc2\u1ea5": 0x1EA4,
	"\xc2\u1ea7": 0x1EA6,
	"\xc2\u1ea9": 0x1EA8,
	"\xc2\u1eab": 0x1EAA,
	"\xc2\u1ead": 0x1EAC,
	"\xca\u1ebf": 0x1EBE,
	"\xca\u1ec1": 0x1EC0,
	"\xca\u1ec3": 0x1EC2,
	"\xca\u1ec5": 0x1EC4,
	"\xca\u1ec7": 0x1EC6,
	"\xd4\u1ed1": 0x1ED0,
	"\xd4\u1ed3": 0x1ED2,
	"\xd4\u1ed5": 0x1ED4,
	"\xd4\u1ed7": 0x1ED6,
	"\xd4\u1ed9": 0x1ED8,
	"\u0102\u1eaf": 0x1EAE,
	"\u0102\u1eb1": 0x1EB0,
	"\u0102\u1eb3": 0x1EB2,
	"\u0102\u1eb5": 0x1EB4,
	"\u0102\u1eb7": 0x1EB6,
	"\u01a0\u1edb": 0x1EDA,
	"\u01a0\u1edd": 0x1EDC,
	"\u01a0\u1edf": 0x1EDE,
	"\u01a0\u1ee1": 0x1EE0,
	"\u01a0\u1ee3": 0x1EE2,
	"\u01af\u1ee9": 0x1EE8,
	"\u01af\u1eeb": 0x1EEA,
	"\u01af\u1eed": 0x1EEC,
	"\u01af\u1eef": 0x1EEE,
	"\u01af\u1ef1": 0x1EF0,
}

// decodeTcvn3Byte giai ma 1 byte TCVN3; upper=true cho font HOA (H-font).
func decodeTcvn3Byte(b byte, upper bool) rune {
	if b < 0x80 {
		return rune(b)
	}
	tab := tcvn3Lower
	if upper {
		tab = tcvn3Upper
	}
	if v := tab[b-0x80]; v >= 0 {
		return v
	}
	return rune(b)
}

// looksLikeTcvn3 heuristic khi khong biet font (giong ban Kotlin).
func looksLikeTcvn3(s string) bool {
	if s == "" {
		return false
	}
	mapped, strong, marker := 0, 0, false
	hasVnUni := false
	for _, r := range s {
		if r >= 0x80 && r <= 0xFF && tcvn3Lower[r-0x80] >= 0 {
			mapped++
			if r == 0xAC || r == 0xAD {
				marker = true
			}
			if r < 0xC0 || r > 0xDF {
				strong++
			}
		}
		if r == 0x0102 || r == 0x0103 || r == 0x0110 || r == 0x0111 ||
			r == 0x01A0 || r == 0x01A1 || r == 0x01AF || r == 0x01B0 ||
			(r >= 0x1EA0 && r <= 0x1EF9) {
			hasVnUni = true
		}
	}
	if mapped == 0 || hasVnUni {
		return false
	}
	if marker {
		return mapped >= 2
	}
	return mapped >= 8 && strong >= 2
}

// decodeTcvn3 giai ma chuoi raw (moi rune 0x00-0xff = 1 byte goc).
func decodeTcvn3(s string, upper bool) string {
	if s == "" {
		return s
	}
	var sb strings.Builder
	sb.Grow(len(s))
	hasHigh := false
	for _, r := range s {
		if r >= 0x80 && r <= 0xFF {
			hasHigh = true
			break
		}
	}
	if !hasHigh {
		return s
	}
	decoded := make([]rune, 0, len(s))
	for _, r := range s {
		if r >= 0 && r <= 0xFF {
			decoded = append(decoded, decodeTcvn3Byte(byte(r), upper))
		} else {
			decoded = append(decoded, r)
		}
	}
	if upper {
		return string(decoded)
	}
	// Ghep cap 2-byte HOA (font thuong).
	var out strings.Builder
	i := 0
	for i < len(decoded) {
		if i+2 <= len(decoded) {
			if hit, ok := tcvn3Compose[string(decoded[i:i+2])]; ok {
				out.WriteRune(hit)
				i += 2
				continue
			}
		}
		out.WriteRune(decoded[i])
		i++
	}
	return out.String()
}

// ConvertToMarkdown converts a PDF file to clean Markdown using Document AI / ML Kit OCR
func ConvertToMarkdown(path string) (string, error) {
	// Stub for PDF -> Markdown conversion pipeline
	return "# Tài liệu trích xuất\n\nNội dung từ: " + path, nil
}

// hasVnDistinctive true neu co chu Viet dac trung (d/o/u) —
// text Latin (vd tieng Phap) decode "sai" khong bao gio sinh ra cac chu nay.
func hasVnDistinctive(s string) bool {
	for _, r := range s {
		switch r {
		case 'đ', 'Đ', 'ơ', 'Ơ', 'ư', 'Ư':
			return true
		}
	}
	return false
}

// ConvertTcvn3VniToUtf8 converts TCVN3 encoded Vietnamese text to UTF-8.
// Used for legacy PDFs with non-UTF-8 Vietnamese text. VNI is not handled
// (combining tones need a dedicated engine). Co hau kiem: ket qua phai
// chua d/D/o/U moi chap nhan, neu khong day la text Latin — tra nguyen.
func ConvertTcvn3VniToUtf8(s string) string {
	if s == "" {
		return s
	}
	if !looksLikeTcvn3(s) {
		return s
	}
	d := decodeTcvn3(s, false)
	if d == s || !hasVnDistinctive(d) {
		return s
	}
	return d
}
