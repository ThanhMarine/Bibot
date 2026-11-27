# GHI CHU TEST - Bibot v1.0.7 (16 KB page size)

| Ban | versionName | versionCode |
|-----|-------------|-------------|
| Demo (com.bibot.app) | 1.0.7 | 8 |
| VIP (com.bibot.app.vip) | 1.0.7-vip | 8 |

## Kiem tra 16 KB
- [x] AAB/APK chi con ABI 64-bit: arm64-v8a, x86_64.
- [x] Moi file .so deu co LOAD align 2**14 (0x4000) qua llvm-objdump -p.
- [x] zipalign -c -P 16 -v 4 - OK cho ca 2 APK.
- [x] bundletool dump config: PAGE_ALIGNMENT_16K.

## Kiem tra chuc nang
- [ ] Home: icon Material Symbols Rounded, chu dam de doc, accent xanh Bibot.
- [ ] PDF: le trai/phai deu, zoom khong ket trang.
- [ ] Thoi tiet: tim dia diem bat ky / nhap lat,lon.
- [ ] Chat AI: hands-free, tra cuu web, gioi han 30/50/80/120 tu, giu 8 luot.
- [ ] Settings > Library: them sach qua SAF, khong upload, khong xin quyen them.
