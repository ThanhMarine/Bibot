package tts

import "testing"

// Nguyên tắc đọc sách: ngày tháng / nhiệt độ / La Mã.
func TestReadDatesGo(t *testing.T) {
	cases := map[string]string{
		"Gặp nhau ngày 31/12 nhé":  "Gặp nhau ngày 31 tháng 12 nhé",
		"Ngày 31/12 nghỉ":          "ngày 31 tháng 12 nghỉ",
		"31-12-2026":               "ngày 31 tháng 12 năm 2026",
		"31/12/2026":               "ngày 31 tháng 12 năm 2026",
		"05/06":                    "ngày mùng 5 tháng 6",
		"1.440 đồng":               "1.440 đồng",   // nghìn, không phải ngày
		"3.5 điểm":                 "3.5 điểm",     // thập phân + thiếu năm
		"12:30 trưa":               "12:30 trưa",   // giờ
		"100-120 trang":            "100-120 trang", // khoảng, "-" không năm
		"32/12 sai":                "32/12 sai",    // ngày quá
		"31/13 sai":                "31/13 sai",    // tháng quá
		"2026-05-10 iso":           "2026-05-10 iso", // ISO giữ nguyên
		"10/11/2026 họp":           "ngày mùng 10 tháng 11 năm 2026 họp",
	}
	for in, want := range cases {
		if got := readDatesGo(in); got != want {
			t.Errorf("readDatesGo(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestNormalizeDegreesGo(t *testing.T) {
	cases := map[string]string{
		"25°C hôm nay":      "25 độ C hôm nay",
		"25-30°C":           "25-30 độ C",
		"25 - 30 độ C":      "25 - 30 độ C",
		"nóng 30độ quá":     "nóng 30 độ quá",
		"nhiệt độ phòng":    "nhiệt độ phòng",
		"100°F lò":          "100 độ F lò",
		"không có gì":       "không có gì",
	}
	for in, want := range cases {
		if got := normalizeDegreesGo(in); got != want {
			t.Errorf("normalizeDegreesGo(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestReadRomanGo(t *testing.T) {
	cases := map[string]string{
		"Chương X mở đầu":      "Chương 10 mở đầu",
		"thế kỷ XX":            "thế kỷ 20",
		"Phần IIX đặc biệt":    "Phần 8 đặc biệt",
		"Phụ lục VII":          "Phụ lục 7",
		"thế kỷ XI-XIX dài":    "thế kỷ 11 đến thế kỷ 19 dài",
		"Chương IIII sai":      "Chương IIII sai",
		"Louis XIV đến chơi":   "Louis XIV đến chơi",
		"Tôi đi chơi":          "Tôi đi chơi",
	}
	for in, want := range cases {
		if got := readRomanGo(in); got != want {
			t.Errorf("readRomanGo(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestCleanTTSTextReadingRules(t *testing.T) {
	cases := map[string]string{
		"Hẹn 31/12 nhé":        "Hẹn ngày 31 tháng 12 nhé",
		"Chương X bắt đầu":     "Chương 10 bắt đầu",
		"25-30 độ C hôm nay":   "25 đến 30 độ C hôm nay",
		"Nhiệt 25°C":           "Nhiệt 25 độ C",
		"100-120 trang sách":   "100 đến 120 trang sách",
		"vitamin C mỗi ngày":   "vitamin C mỗi ngày",
		"tia X quang phổi":     "tia X quang phổi",
		"M èo con kêu":         "Mèo con kêu",
		"Phần IIX đặc biệt":    "Phần 8 đặc biệt",
		"Ngày 31-12-2026 họp":  "ngày 31 tháng 12 năm 2026 họp",
	}
	for in, want := range cases {
		if got := CleanTTSText(in); got != want {
			t.Errorf("CleanTTSText(%q) = %q, want %q", in, got, want)
		}
	}
}
