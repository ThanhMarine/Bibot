package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// Vector thật từ tools/vipcodes/vip_batch.py (secret + code test, KHÔNG phải production).
const (
	testSecret = "a04df7021fb38f217992d87394f3bba8a6073a4e6821801abb98d8f20dd253af"
	testCode   = "BIBOT-AEASGA-MXBEAU-XH59B6-3HN7JHQS" // month/3 tháng/batch 9/serial 1
)

// 2026-10-11T00:00Z — trước hạn 2027-01-09 của vector test.
func testNow() int64 {
	return time.Date(2026, 10, 11, 0, 0, 0, 0, time.UTC).UnixMilli()
}

func TestVerifyVipValid(t *testing.T) {
	v := verifyVipCode(testSecret, testCode, testNow(), map[int]bool{})
	if !v.Valid {
		t.Fatalf("mã thật bị từ chối: %s", v.Reason)
	}
	if v.Tier != vipTierMonth || v.Months != 3 || v.Batch != 9 || v.Serial != 1 {
		t.Fatalf("payload sai: %+v", v)
	}
	if v.ExpiryMillis == nil {
		t.Fatal("thiếu expiry")
	}
}

func TestVerifyVipReject(t *testing.T) {
	cases := map[string]string{
		"BIBOT-AEASGA-MXBEAU-XH59B6-3HN7JHQX": "chu ky sai",  // sửa 1 ký tự
		"BIBOT-SHORT":                         "do dai",
		"FAKE-AAAAAA-BBBBBB-CCCCCC-DDDDDDDD":  "tien to",
		"":                                    "tien to",
	}
	for code, want := range cases {
		v := verifyVipCode(testSecret, code, testNow(), map[int]bool{})
		if v.Valid {
			t.Errorf("mã giả %q được chấp nhận", code)
		}
		if !strings.Contains(v.Reason, want) {
			t.Errorf("mã %q reason=%q, muốn chứa %q", code, v.Reason, want)
		}
	}
	// Secret sai -> từ chối.
	if v := verifyVipCode("00"+testSecret[2:], testCode, testNow(), map[int]bool{}); v.Valid {
		t.Error("secret sai vẫn pass")
	}
	// Hết hạn (sau 2027-01-09).
	future := time.Date(2027, 2, 1, 0, 0, 0, 0, time.UTC).UnixMilli()
	if v := verifyVipCode(testSecret, testCode, future, map[int]bool{}); v.Valid {
		t.Error("mã hết hạn vẫn pass")
	}
	// Batch bị thu hồi.
	if v := verifyVipCode(testSecret, testCode, testNow(), map[int]bool{9: true}); v.Valid {
		t.Error("batch revoked vẫn pass")
	}
}

func TestVipHandler(t *testing.T) {
	os.Setenv("BIBOT_VIP_SECRET", testSecret)
	defer os.Unsetenv("BIBOT_VIP_SECRET")
	body, _ := json.Marshal(vipVerifyReq{Code: testCode, DeviceID: "dev-test-1", AppVersion: "1.0"})
	req := httptest.NewRequest(http.MethodPost, "/v1/vip/verify", bytes.NewReader(body))
	rec := httptest.NewRecorder()
	vipVerify(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("HTTP %d: %s", rec.Code, rec.Body.String())
	}
	var resp vipVerifyResp
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if !resp.Valid || resp.Entitlement == nil || resp.Entitlement.Sig == "" {
		t.Fatalf("thiếu entitlement: %+v", resp)
	}
	if resp.ServerTime <= 0 {
		t.Error("thiếu serverTime")
	}
	// Thiếu secret server -> 500 (không bao giờ crash).
	os.Unsetenv("BIBOT_VIP_SECRET")
	req2 := httptest.NewRequest(http.MethodPost, "/v1/vip/verify", bytes.NewReader(body))
	rec2 := httptest.NewRecorder()
	vipVerify(rec2, req2)
	if rec2.Code != http.StatusInternalServerError {
		t.Errorf("thiếu secret phải 500, được %d", rec2.Code)
	}
}
