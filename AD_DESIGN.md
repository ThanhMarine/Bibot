# Bibot Advertising System — Thiết kế quảng cáo cho APK miễn phí

> **Ngày thiết kế:** 2026-09-24 | **Trạng thái:** Thiết kế → Implement  
> **Phiên bản:** v1.0  
> **Mục tiêu:** Monetize APK miễn phí trên Google Play mà không phá vỡ UX voice-first

---

## 1) Chiến lược quảng cáo tổng thể

### Nguyên tắc thiết kế
| Nguyên tắc | Quy định | Lý do |
|---|---|---|
| **Voice-first không gián đoạn** | Không quảng cáo trong lúc Voice chat active | Trải nghiệm cốt lõi, RAM <250MB |
| **Lightweight** | AdMob chỉ ở flavor commercial, RAM overhead <5MB | Máy yếu 1-2GB RAM |
| **Non-intrusive** | Banner nhỏ, interstitial mỗi 3 mode switch | Giữ UX sạch |
| **Rewarded = Premium** | Xem ad → nhận reward (ad-free 24h, extra messages) | User có lựa chọn |
| **Demo không ads** | Flavor demo không có AdMob dependency | APK demo nhẹ hơn |

### Mô hình doanh thu dự kiến
```
User free (có ads)                    User premium
├── Banner ad trên Home/Book/News     ├── Không quảng cáo (mua ad-free)
├── Interstitial mỗi 3 lần chuyển mode ├── Mở khóa tất cả features
├── Rewarded ad → extra daily messages ├── Không xem ad
│                                      └── Hỗ trợ developer
└── 1 interstitial/30 phút max        
```

---

## 2) Architecture Ads

```
android_app/
├── data/ads/
│   ├── AdManager.kt              # Singleton: AdMob init, load, show
│   ├── AdsPreferences.kt         # DataStore: ad-free state, click count, quota
│   └── AdConfig.kt              # Ad unit IDs, config constants
├── ui/components/ads/
│   ├── AdsBannerComposable.kt    # Banner ad composable
│   ├── AdsInterstitialHelper.kt  # Interstitial logic (between modes)
│   └── AdsRewardedHelper.kt      # Rewarded ad logic (premium features)
├── feature/
│   ├── voice/VoiceScreen.kt      # No ads during voice chat
│   ├── settings/SettingsScreen.kt # "Ad-Free" toggle + "Get Premium" button
│   └── home/HomeMenu.kt          # Banner at bottom
├── build.gradle.kts              # AdMob commercialImplementation only
└── AndroidManifest.xml           # AdMob APP_ID meta-data
```

---

## 3) Ad Placement — chi tiết

### 3.1 Banner Ad (Home, Book, News, Weather, PDF, Music)

```
┌──────────────────────────────────┐
│                                  │
│    [Content Screen]              │
│                                  │
│  ┌────────────────────────────┐  │
│  │  ██████████████████████████ │  │ ← Banner AdMob (320x50)
│  │  │ Ad text here          │ │  │
│  │  └───────────────────────┘  │  │
│  └────────────────────────────┘  │
└──────────────────────────────────┘
```

- **Vị trí:** Bottom of screen (footer area), height 50dp
- **Hiển thị:** Home, Book, Story, News, Weather, PDF, Music screens
- **Ẩn:** Voice screen (chỉ voice-first), Settings (không rối), Ad-free users
- **RAM:** ~2-3MB khi banner loaded

### 3.2 Interstitial Ad (Between Mode Switches)

```
User selects "Book" from Home
  → 1 interstitial shown (if 3+ mode switches since last ad)
  → User taps "Book"
  → Book screen loads
```

- **Tần suất:** 1 interstitial / 3 lần chuyển mode
- **Cooldown:** 30 phút (không show liên tục)
- **Vị trí:** Giữa 2 mode (khi user chọn mode mới từ Home)
- **Ẩn khi:** Voice active, Ad-free user, last 30 phút
- **RAM:** ~3-5MB khi loading, giải phóng ngay sau show

### 3.3 Rewarded Ad (Premium Features)

```
Settings → "Get Ad-Free" button
  → Rewarded ad shown
  → User watches 15-30s ad
  → User gets: Ad-Free 24h OR Extra Daily Messages (+10)
```

- **Rewards:**
  - **Ad-Free 24h**: Xóa quảng cáo 24 giờ
  - **Extra Daily Messages**: +10 tin/ngày (vượt quota 20 relay)
- **Frequency cap:** 1 rewarded / 30 phút
- **Auto-load:** Pre-load khi Settings mở

---

## 4) AdsPreferences — DataStore Design

```kotlin
data class AdsState(
    val isAdFree: Boolean = false,        // User purchased ad-free
    val adClickCount: Long = 0,           // Total ad interactions
    val adLastShowTime: Long = 0,         // Timestamp last ad shown
    val isBannerEnabled: Boolean = true,  // User can toggle banner
    val isRewardedClaimed: Boolean = false,// Rewarded claimed today
    val isDailyQuotaExhausted: Boolean = false // 20 msg/day via relay
)
```

**DataStore file:** `data/data/com.bibot.app/files/datastore/bibot_ads.preferences_pb`

**Quotas:**
- Relay: 20 messages/day/device (from `Bibot_build.md` 2.6)
- Interstitial: 1 per 30 minutes
- Rewarded: 1 per 30 minutes

---

## 5) Implementation Details

### 5.1 Dependencies (libs.versions.toml)

```toml
[versions]
admob = "23.4.0"

[libraries]
admob = { group = "com.google.android.gms", name = "play-services-ads", version.ref = "admob" }
```

**Flavor gate:** `"commercialImplementation"(libs.admob)` — demo APK không có ads, nhẹ hơn.

### 5.2 AdManager Singleton

```kotlin
object AdManager {
    fun initialize(context: Context)  // Called from BibotApp.onCreate
    fun loadInterstitial(activity: Activity)
    fun showInterstitial(activity: Activity): Boolean
    fun loadRewarded(activity: Activity)
    fun showRewarded(activity: Activity): Boolean
    fun loadBanner(adView: AdView)
    fun release()  // Called onTrimMemory TRIM_MEMORY_COMPLETE
}
```

### 5.3 SettingsScreen — Ad Section

```
⚙️ Cài đặt
├── 🤖 Bot Prompt & Cá nhân hoá
├── 📦 Mô hình offline
├── 📚 Kho sách
├── 📡 Cloud & Sync
├── 🎨 Giao diện
├── 🏷️ Quảng cáo                    ← NEW SECTION
│   ├── Cho phép quảng cáo [Toggle]
│   ├── Chế độ quảng cáo [Radio: Banner / Interstitial / None]
│   ├── 【Xem quảng cáo để nhận】[Button]  ← Rewarded ad
│   │   └── "Xem 15s → +10 tin/ngày" hoặc "Xem 15s → Bỏ quảng cáo 24h"
│   └── Trạng thái: "Đã mua ad-free đến 2026-09-25 14:00" / "Free — xem ad để nhận"
```

### 5.4 VoiceScreen — No Ads

```kotlin
// VoiceScreen.kt — KHÔNG có ads trong voice chat
@Composable
fun VoiceScreen(...) {
    // Only voice controls, no AdManager.showInterstitial()
    // Full-duplex STT→LLM→Chunk→TTS pipeline
    // OnTrimMemory → AdManager.release()
}
```

---

## 6) AdMob Setup — Hướng dẫn

### Bước 1: Tạo AdMob account
1. Đăng ký tại https://admob.google.com
2. Tạo app `com.bibot.app`
3. Tạo ad units:
   - **Banner Ad Unit ID**: `ca-app-pub-XXXXXXXX/YYYYYYYY`
   - **Interstitial Ad Unit ID**: `ca-app-pub-XXXXXXXX/ZZZZZZZZ`
   - **Rewarded Ad Unit ID**: `ca-app-pub-XXXXXXXX/AAAAAAAA`

### Bước 2: Cấu hình trong app
1. Thay `ADMOB_APP_ID` trong `build.gradle.kts` buildConfigField
2. Thay `BANNER_AD_UNIT_ID`, `INTERSTITIAL_AD_UNIT_ID`, `REWARDED_AD_UNIT_ID` trong `AdManager.kt`
3. `gcloud builds submit` nếu deploy relay (không liên quan ads nhưng cần thiết cho 2.6.4)

### Bước 3: Test
```bash
# Test ad trên emulator/device
# Sử dụng test ad unit IDs của AdMob:
# Banner: ca-app-pub-3940256099942544/6300978111
# Interstitial: ca-app-pub-3940256099942544/1033173712
# Rewarded: ca-app-pub-3940256099942544/5224354917
```

---

## 7) RAM Impact — ước tính

| Component | RAM overhead | Thời điểm |
|---|---|---|
| AdMob SDK | ~3-5MB | Khi initialize |
| Banner AdView | ~2MB | Khi visible |
| Interstitial | ~3-5MB | Khi load + show |
| Rewarded Ad | ~2-3MB | Khi load |
| **Tổng peak** | **~10-15MB** | Khi có interstitial đang load |

**So sánh:** Bibot idle RAM <120MB → quảng cáo thêm ~15MB = <135MB (vẫn trong target)

---

## 8) Compliance & Privacy

- **COPPA:** AdMob auto-handles under-13 users (personalized ads disabled)
- **GDPR:** AdMob consent SDK integration (không cần code riêng)
- **Data:** Không thu thập dữ liệu cá nhân ngoài AdMob's standard
- **Content filter:** AdMob automatically filters inappropriate ads
- **Privacy policy:** Cần thêm trong Settings → "Privacy Policy" link

---

## 9) Revenue Model — dự kiến eCPM

| Ad Type | eCPM (VN) | Frequency | Revenue/User/month |
|---|---|---|---|
| Banner | ~$1-2 | Always visible | ~$0.5-1.0 |
| Interstitial | ~$3-8 | 1/3 mode switches | ~$1-3 |
| Rewarded | ~$5-15 | 1/30 min | ~$0.5-2 |

**Dự kiến:** User active 30 phút/ngày → ~$1-4/user/month (VN market)

---

## 10) Files tạo ra trong session này

| File | Mô tả |
|---|---|
| `data/ads/AdsPreferences.kt` | DataStore cho ad preferences |
| `data/ads/AdManager.kt` | AdMob singleton manager |
| `ui/components/ads/AdsBannerComposable.kt` | Banner ad composable |
| `ui/components/ads/AdsInterstitialHelper.kt` | Interstitial + Rewarded logic |
| Modified `gradle/libs.versions.toml` | Thêm AdMob version |
| Modified `build.gradle.kts` | Thêm commercialImplementation adMob + ADMOB_APP_ID |
| Modified `AndroidManifest.xml` | Thêm permission + meta-data |
| Modified `BibotApp.kt` | AdManager.initialize() |

---

## 11) Next Steps — triển khai

1. ✅ **Thiết kế hoàn tất** (file này)
2. [ ] Tạo AdMob account, lấy APP_ID + Ad Unit IDs
3. [ ] Thay placeholder IDs trong `AdManager.kt`
4. [ ] Test `assembleCommercialDebug` với AdMob
5. [ ] Deploy lên Play Store (commercial flavor)
6. [ ] Monitor eCPM + revenue qua AdMob dashboard

---

> **Lưu ý:** Đây là thiết kế đầy đủ. Triển khai chỉ cần thay placeholder IDs bằng giá trị thật từ AdMob. Commercial flavor đã sẵn sàng; demo flavor không có ads (APK nhẹ hơn).
