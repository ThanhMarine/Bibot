# VIP verify server-side — relay + grace offline

> Nguyên tắc: HMAC secret KHÔNG bao giờ vào APK. App gửi code thô,
> server verify + ký entitlement. Port 1-1 `VipCode.kt` + `vip_batch.py`.

## 1. API

`POST /v1/vip/verify` — body ≤4KB:

```json
{"code":"BIBOT-XXXXXX-XXXXXX-XXXXXX-XXXXXXXX","deviceId":"...","appVersion":"1.0"}
```

Trả 200 (mã sai vẫn 200 + `valid:false`, chỉ sai request mới 4xx):

```json
{
  "valid": true, "tier": 1, "months": 3, "batch": 9, "serial": 1,
  "expiryMillis": 1799520000000,
  "serverTime": 1791234567890,
  "entitlement": {
    "deviceId": "...", "tier": 1, "expiryMillis": 1799520000000,
    "issuedAt": 1791234567890,
    "sig": "hmac-hex"
  },
  "sharedWarn": false
}
```

- `expiryMillis: null` = lifetime. `sig` = HMAC-SHA256(secret,
  `bibot-ent-v1|deviceId|tier|expiry`) — bind code↔máy.
- `sharedWarn: true` = 1 mã xài quá `VIP_MAX_DEVICES` máy/ngày.
- Rate-limit 10 verify/ngày/máy (khác quota chat 20) — chống dò mã.

## 2. Cấu hình relay (Cloud Run + Secret Manager)

| Biến | Bắt buộc | Mặc định | Ý nghĩa |
|---|---|---|---|
| `GEMINI_API_KEY` | có (chat) | — | secret sẵn có |
| `BIBOT_VIP_SECRET` | có (verify) | — | **cùng secret HMAC đã dùng sinh mã**, HEX ≥16B |
| `PORT` | không | 8080 | Cloud Run tự tiêm |
| `VIP_VERIFY_QUOTA` | không | 10 | verify/ngày/máy — chống dò mã |
| `VIP_MAX_DEVICES` | không | 3 | số máy tối đa 1 mã/ngày |
| `VIP_REVOKED_BATCHES` | không | rỗng | VD `3,7` — thu hồi theo batch, không cần xoay secret |

Tạo secret + deploy (secret KHÔNG commit git — kể cả repo `meme` private;
repo đó chỉ chứa file non-secret như Dockerfile, script deploy):

```bash
python tools/vipcodes/vip_batch.py --gen-secret   # 1 lần, giữ kỹ output
echo -n "<HEX>" | gcloud secrets create bibot-vip-secret --data-file=-
cd relay
gcloud builds submit --tag gcr.io/PROJECT_ID/bibot-relay .
gcloud run deploy bibot-relay \
  --image gcr.io/PROJECT_ID/bibot-relay \
  --platform managed --region asia-southeast1 \
  --allow-unauthenticated \
  --set-secrets GEMINI_API_KEY=bibot-gemini-key:latest,BIBOT_VIP_SECRET=bibot-vip-secret:latest \
  --max-instances 1 --memory 256Mi
```

Test:

```bash
curl -X POST https://bibot-relay-xxx.a.run.app/v1/vip/verify \
  -H 'Content-Type: application/json' \
  -d '{"code":"BIBOT-...","deviceId":"test-1","appVersion":"1.0"}'
```

Local: `BIBOT_VIP_SECRET=... go test ./...` (vector mã thật batch 9 trong `main_test.go`).

## 3. Grace offline — hợp đồng phía app (Kotlin, làm tiếp)

DataStore keys: `vip_entitlement_json`, `vip_last_check_ms`,
`vip_max_seen_ms`, `vip_code_sha256`.

- **Online:** POST verify → lưu entitlement + `last_check=serverTime`,
  `max_seen=max(max_seen, serverTime)` → VIP theo `tier/expiry`.
- **Offline:** dùng entitlement đã lưu nếu (a) chưa qua `expiry` (lifetime: recheck
  mỗi 30 ngày), (b) `now >= max_seen - skew(24h)` (chống vặn đồng hồ ngược),
  (c) sha256(code nhập) == code đã verify (đổi code → bắt buộc online).
  Monthly hết grace 7 ngày không gặp server → rớt VIP tới khi online lại.
- **Migration quan trọng:** release APK **ngừng nướng `VIP_HMAC_SECRET`**
  (build.gradle chỉ inject khi dev; release để rỗng → `VipCode.verify` local luôn
  Invalid, server là nguồn duy nhất). Dev build giữ verify local để test offline.
- Wiring: `SettingsScreen.kt:1357` (thay đọc `BuildConfig` → gọi relay + fallback
  entitlement), `AdManager` gate đọc state VIP từ DataStore thay vì boolean RAM.

## 4. Giới hạn đã biết (trung thực)

- Rate-limit + anti-share **in-memory** (restart container reset) — cùng giới hạn
  đã ghi của `/v1/chat`; đủ cho quy mô hiện tại, đông user thì chuyển Firestore.
- `integrityToken` hiện nhận + log, **chưa enforce** — Phase 2: verify Play Integrity
  API phía server rồi mới tin entitlement cho quota relay.
- Thu hồi mã lẻ chưa có — chỉ thu hồi theo batch hoặc xoay secret + bump `VERSION`.
