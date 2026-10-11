# bibot-relay — 2.6.4 (phía bạn: deploy 1 lần)

Relay free cho user không `GEMINI_API_KEY`. Key nằm ở Secret Manager,
không bao giờ vào APK.

## 1. Deploy lên Cloud Run (free tier)

```bash
cd relay
# Build + push (thay PROJECT_ID)
gcloud builds submit --tag gcr.io/PROJECT_ID/bibot-relay .
gcloud run deploy bibot-relay \
  --image gcr.io/PROJECT_ID/bibot-relay \
  --platform managed --region asia-southeast1 \
  --allow-unauthenticated \
  --set-secrets GEMINI_API_KEY=bibot-gemini-key:latest,BIBOT_VIP_SECRET=bibot-vip-secret:latest \
  --max-instances 1 --memory 256Mi
```

Tạo secret trước 1 lần:

```bash
echo -n "AIza..." | gcloud secrets create bibot-gemini-key --data-file=-
echo -n "<HEX64>" | gcloud secrets create bibot-vip-secret --data-file=-
# HEX lấy từ: python tools/vipcodes/vip_batch.py --gen-secret (KHÔNG commit)
```

## 2. Test

```bash
curl -N -X POST https://bibot-relay-xxx.a.run.app/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"mấy giờ rồi?","system":"Em là Bi","deviceId":"test-1"}'
curl https://bibot-relay-xxx.a.run.app/healthz
# VIP verify (chi tiết relay/VIP_VERIFY.md):
curl -X POST https://bibot-relay-xxx.a.run.app/v1/vip/verify \
  -H 'Content-Type: application/json' \
  -d '{"code":"BIBOT-...","deviceId":"test-1","appVersion":"1.0"}'
```

## 2b. Staging local (không cần cloud, test đường relay thật)

```powershell
cd relay
go build -o relay-staging.exe .
$env:BIBOT_VIP_SECRET = "<HEX test, vd output vip_batch.py --gen-secret>"
$env:PORT = "8080"
.\relay-staging.exe            # nghe :8080 trên PC
# Máy ảo gọi về PC:
adb reverse tcp:8080 tcp:8080  # emulator:8080 -> PC:8080
# Build app trỏ relay staging (không sửa code):
$env:BIBOT_RELAY_URL = "http://localhost:8080"
cd ..\android; .\gradlew.bat :app:assembleDemoDebug
adb install -r app\build\outputs\apk\demo\debug\Bibot.apk
```

Sinh mã test đúng secret staging rồi nhập trên app:
`python tools/vipcodes/vip_batch.py --gen --tier month --months 1 --count 1 --batch 99 --secret <HEX>`.
Xong việc: `adb reverse --remove-all`, xóa binary staging (không commit).

> **Nếu cần nâng cấp thêm (ghi chú chốt phiên 2026-10-11):** toàn bộ flow staging
> trên (build relay + reverse + build app + cài) **chỉ chạy khi thật cần** (VD: đổi
> format entitlement, xoay secret, nghi lệch verify app/server). Lý do hoãn: nhiều
> bước thủ công, dễ lỗi thời theo code; unit test (`go test`, vector mã thật batch 9)
> + curl E2E đã bao phủ logic verify. Khi cần thì làm theo mục này, xong cập nhật lại
> mục này nếu lệnh đổi.

## 3. Nối app

Điền URL vào `android/app/build.gradle.kts`:

```kotlin
buildConfigField("String", "BIBOT_LLM_PROXY_URL", "\"https://bibot-relay-xxx.a.run.app\"")
```

Build lại 2 flavor → user để trống key sẽ thấy
“Dùng Bibot Free (20 tin/ngày via relay)”.

## 4. Giới hạn bản này

- Rate-limit in-memory (restart container reset đếm) — quota tập trung
  `2.5-flash-lite` free cao nên đủ dùng; production thay bằng Firestore/Redis.
- Phạm vi relay: **LLM chat**. TTS key vẫn nằm trên máy theo `ttsPriority`.
