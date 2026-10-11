#!/usr/bin/env python3
"""Sinh batch ma VIP Bibot (20-30 ma/dot) + kiem tra. Chay offline phia dev.

Dinh dang: BIBOT-XXXXXX-XXXXXX-XXXXXX-XXXXXXXX (26 ky tu base32 tu che)
  payload 10B: ver(1) tier(1) months(1) expiryDays u16(2) batchId(1) serial(1) random(3)
  + chu ky HMAC-SHA256(secret, payload) cat 6B. Tong 16B = 128 bit.
  tier: 0x01=thang (months=so thang), 0x02=vinh vien (expiry=0xFFFF).
  expiryDays: so ngay tu 2026-01-01 (khi phat hanh; app doi ra han dung).

  python vip_batch.py --gen-secret
  python vip_batch.py --gen --tier month --months 1 --count 25 --batch 7 --secret <HEX64> --out vip_batch07.csv
  python vip_batch.py --verify BIBOT-... --secret <HEX64>
  python vip_batch.py --selftest
Secret giu rieng, KHONG commit. App nhuung secret de verify offline (chap nhan trich xuat o v1).
"""
import argparse
import base64
import csv
import datetime
import hashlib
import hmac
import secrets
import sys

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # 32 ky tu: bo I/O (lan voi 1/0), giu L
_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
_ENC_TAB = str.maketrans(_STD, ALPHABET)
_DEC_TAB = str.maketrans(ALPHABET, _STD)
EPOCH = datetime.date(2026, 1, 1)
PREFIX = "BIBOT"
VER = 1
TIER_MONTH, TIER_LIFE = 1, 2


def b32enc(raw: bytes) -> str:
    return base64.b32encode(raw).decode().rstrip("=").translate(_ENC_TAB)


def b32dec(s: str) -> bytes:
    t = s.translate(_DEC_TAB)
    return base64.b32decode(t + "=" * (-len(t) % 8))


def build_payload(tier: int, months: int, expiry_days: int, batch: int, serial: int, rnd: bytes) -> bytes:
    return bytes([VER, tier, months & 0xFF,
                  (expiry_days >> 8) & 0xFF, expiry_days & 0xFF,
                  batch & 0xFF, serial & 0xFF]) + rnd[:3]


def sign(secret: bytes, payload: bytes) -> bytes:
    return hmac.new(secret, payload, hashlib.sha256).digest()[:6]


def make_code(secret: bytes, tier: int, months: int, batch: int, serial: int) -> tuple[str, str]:
    if tier == TIER_LIFE:
        expiry = 0xFFFF
    else:
        expiry = (datetime.date.today() - EPOCH).days + months * 30
        if not (0 < expiry < 0xFFFF):
            raise ValueError("han vuot pham vi ma hoa")
    payload = build_payload(tier, months, expiry, batch, serial, secrets.token_bytes(3))
    body = b32enc(payload + sign(secret, payload))
    code = PREFIX + "-" + body[0:6] + "-" + body[6:12] + "-" + body[12:18] + "-" + body[18:26]
    return code, EPOCH + datetime.timedelta(days=expiry) if expiry != 0xFFFF else "vinh-vien"


def verify(secret: bytes, code: str) -> dict:
    c = code.strip().upper()
    if not c.startswith(PREFIX + "-"):
        return {"ok": False, "reason": "thieu tien to BIBOT-"}
    body = c[len(PREFIX) + 1:].replace("-", "")
    if len(body) != 26 or any(ch not in ALPHABET for ch in body):
        return {"ok": False, "reason": "do dai/ky tu sai"}
    try:
        raw = b32dec(body)
    except Exception:
        return {"ok": False, "reason": "giai ma that bai"}
    payload, sig = raw[:10], raw[10:]
    if not hmac.compare_digest(sign(secret, payload), sig):
        return {"ok": False, "reason": "chu ky sai (ma gia/nhap sai)"}
    ver, tier, months = payload[0], payload[1], payload[2]
    expiry = (payload[3] << 8) | payload[4]
    if ver != VER:
        return {"ok": False, "reason": "phien ban ma khong ho tro"}
    if tier not in (TIER_MONTH, TIER_LIFE):
        return {"ok": False, "reason": "tier la"}
    if tier == TIER_LIFE:
        return {"ok": True, "tier": "lifetime", "expiry": "vinh-vien",
                "batch": payload[5], "serial": payload[6]}
    exp_date = EPOCH + datetime.timedelta(days=expiry)
    if datetime.date.today() > exp_date:
        return {"ok": False, "reason": "ma het han %s" % exp_date}
    return {"ok": True, "tier": "month", "months": months, "expiry": str(exp_date),
            "batch": payload[5], "serial": payload[6]}


def cmd_gen(a) -> int:
    if not (1 <= a.count <= 255):
        print("count phai 1..255 (dot phat hanh 20-30 ma)", file=sys.stderr)
        return 2
    try:
        secret = bytes.fromhex(a.secret)
    except ValueError:
        print("secret phai la HEX (xem --gen-secret)", file=sys.stderr)
        return 2
    if len(secret) < 16:
        print("secret qua ngan (toi thieu 16 byte)", file=sys.stderr)
        return 2
    tier = TIER_LIFE if a.tier == "lifetime" else TIER_MONTH
    rows = []
    for serial in range(1, a.count + 1):
        code, exp = make_code(secret, tier, a.months, a.batch, serial)
        rows.append({"code": code, "tier": a.tier, "months": a.months,
                     "expiry": exp, "batch": a.batch, "serial": serial})
    # tu kiem tra toan bo truoc khi xuat (dam bao app doc duoc)
    for r in rows:
        v = verify(secret, r["code"])
        if not v.get("ok"):
            print("TU-KTRA LOI %s: %s" % (r["code"], v), file=sys.stderr)
            return 1
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["code", "tier", "months", "expiry", "batch", "serial"])
        w.writeheader()
        w.writerows(rows)
    print("xong %d ma -> %s (batch %d, tu-ktra OK)" % (len(rows), a.out, a.batch))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Sinh batch ma VIP Bibot")
    ap.add_argument("--gen-secret", action="store_true", help="sinh secret moi (HEX64)")
    ap.add_argument("--gen", action="store_true")
    ap.add_argument("--tier", choices=["month", "lifetime"], default="month")
    ap.add_argument("--months", type=int, default=1)
    ap.add_argument("--count", type=int, default=25)
    ap.add_argument("--batch", type=int, default=1)
    ap.add_argument("--secret", default="")
    ap.add_argument("--out", default="vip_batch.csv")
    ap.add_argument("--verify", default="")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.gen_secret:
        print(secrets.token_bytes(32).hex())
        return 0
    if a.selftest:
        s = secrets.token_bytes(32)
        c, _ = make_code(s, TIER_MONTH, 1, 9, 3)
        assert verify(s, c)["ok"], "ma that phai pass"
        bad = c[:-1] + ("A" if c[-1] != "A" else "B")
        assert not verify(s, bad)["ok"], "ma sua phai rot"
        assert not verify(secrets.token_bytes(32), c)["ok"], "sai secret phai rot"
        print("selftest OK:", c)
        return 0
    if a.verify:
        if not a.secret:
            print("thieu --secret", file=sys.stderr)
            return 2
        print(verify(bytes.fromhex(a.secret), a.verify))
        return 0
    if a.gen:
        return cmd_gen(a)
    ap.print_help()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
