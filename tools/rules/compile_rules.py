#!/usr/bin/env python3
"""Biên dịch reading-rules.ruler (YAML, người/AI sửa) -> reading-rules.json (máy đọc).

- Validate schema + tính duy nhất id.
- Từ chối pattern ngoài giao Java regex ∩ RE2 ∩ Python re.
- Chạy toàn bộ tests nhúng (rewrite/drop_line/start_marker).
- Sinh JSON {version, checksum, rules} + in size.
Dùng: python compile_rules.py [reading-rules.ruler] [--out reading-rules.json]
"""
import hashlib
import json
import re
import sys
from pathlib import Path

import yaml

KINDS = ("rewrite", "drop_line", "start_marker", "split_guard")

# Cấu trúc regex ngoài giao 3 engine -> từ chối (RE2 không có các món này).
BANNED = [
    "(?<!", "(?<=", "(?!=", "(?=", "(?!", "(?>",  # lookaround + atomic
    "\\k", "(?P<", "(?P=", "(?|",                  # named/branch-reset
]
POSSESSIVE = re.compile(r"[+*?}]\+")


def check_pattern(pat):
    for b in BANNED:
        if b in pat:
            return f"banned construct {b!r}"
    if POSSESSIVE.search(pat):
        return "possessive quantifier"
    m = re.search(r"\\([1-9])", pat)
    if m:
        return f"backreference \\{m.group(1)} (RE2 lacks it)"
    try:
        re.compile(pat)
    except re.error as e:
        return f"invalid regex: {e}"
    return None


# ---------------- hooks: cùng ngữ nghĩa 3 nơi Kotlin/Go/Python ----------------
VI_D = ["không", "một", "hai", "ba", "bốn", "năm",
        "sáu", "bảy", "tám", "chín"]


def vi_under100(n):
    t, u = divmod(n, 10)
    if t == 0:
        return VI_D[u]
    tens = "mười" if t == 1 else VI_D[t] + " mươi"
    if u == 0:
        return tens
    return tens + {1: " mốt", 4: " tư", 5: " lăm"}.get(u, " " + VI_D[u])


def vi_under1000(g):
    h, rest = divmod(g, 100)
    if h == 0:
        return vi_under100(g)
    s = VI_D[h] + " trăm"
    if rest == 0:
        return s
    return s + (" lẻ " + VI_D[rest] if rest < 10 else " " + vi_under100(rest))


def vi_words(n):
    if n == 0:
        return "không"
    groups = []
    x = n
    while x > 0:
        groups.append(x % 1000)
        x //= 1000
    scales = ["", "nghìn", "triệu"]
    out, higher = [], False
    for i in range(len(groups) - 1, -1, -1):
        g = groups[i]
        if g == 0:
            continue
        if higher and g < 100:
            part = ("lẻ " + VI_D[g]) if g < 10 else vi_under100(g)
        else:
            part = vi_under1000(g)
        if scales[i]:
            part += " " + scales[i]
        out.append(part)
        higher = True
    return " ".join(out)


VALID_ROMAN = re.compile(
    r"^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")


def roman_int(s):
    u = s.upper()
    if u == "IIX":
        return 8
    if not VALID_ROMAN.match(u):
        return None
    vals = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}
    total = prev = 0
    for c in reversed(u):
        v = vals[c]
        if v < prev:
            total -= v
        else:
            total += v
            prev = v
    return total if 1 <= total <= 3999 else None


def to_int(t):
    try:
        return int(t) if t.lstrip("0123456789") == "" and t != "" else None
    except ValueError:
        return None


def hook(name, raw):
    """Trả None -> bỏ cả match (giữ nguyên chữ)."""
    if name is None:
        return raw
    v = to_int(raw.strip())
    if name == "roman":
        # La Mã là chữ, không qua to_int — kiểm tra riêng, sai thì bỏ match.
        r = roman_int(raw.strip())
        return vi_words(r) if r is not None else None
    if v is None:
        return None
    if name == "day":
        if not 1 <= v <= 31:
            return None
        w = vi_words(v)
        return f"ngày mùng {w}" if v <= 10 else f"ngày {w}"
    if name == "month":
        return vi_words(v) if 1 <= v <= 12 else None
    if name == "year":
        ln = len(raw.strip())
        if ln == 2:
            v += 2000
        elif ln == 4:
            if not 1000 <= v <= 2999:
                return None
        else:
            return None
        return vi_words(v)
    if name == "num":
        return vi_words(v)
    raise ValueError(f"unknown hook {name!r}")


PH = re.compile(r"\{([a-z]+:)?(\d+)\}")


def apply_template(template, m):
    out = []
    pos = 0
    for ph in PH.finditer(template):
        out.append(template[pos:ph.start()])
        prefix, idx = ph.group(1), int(ph.group(2))
        try:
            raw = m.group(idx)
        except IndexError:
            return None
        name = prefix[:-1] if prefix else None
        try:
            r = hook(name, raw)
        except ValueError as e:
            print(f"  template error: {e}")
            return None
        if r is None:
            return None
        out.append(r)
        pos = ph.end()
    out.append(template[pos:])
    return "".join(out)


# ---------------- validate + test ----------------
def load_ruler(path):
    doc = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    assert isinstance(doc, dict) and isinstance(doc.get("version"), int), \
        "ruler cần {version: int, rules: [...]}"
    rules = doc.get("rules") or []
    seen = set()
    for r in rules:
        assert r.get("id") not in seen, f"trùng id {r.get('id')}"
        seen.add(r["id"])
        assert r.get("kind") in KINDS, f"{r['id']}: kind lạ"
        assert isinstance(r.get("priority", 0), int), f"{r['id']}: priority phải int"
        pats = ([r["pattern"]] if "pattern" in r else []) + list(r.get("patterns", []))
        if r["kind"] in ("rewrite", "drop_line", "start_marker"):
            assert pats, f"{r['id']}: thiếu pattern"
            for p in pats:
                err = check_pattern(p)
                assert err is None, f"{r['id']}: pattern bị cấm ({err}): {p}"
        if r["kind"] == "rewrite":
            assert r.get("template"), f"{r['id']}: rewrite thiếu template"
    return doc


def run_tests(doc):
    fails = []
    for r in doc["rules"]:
        kind = r["kind"]
        pats = [re.compile(p) for p in
                (([r["pattern"]] if "pattern" in r else []) + list(r.get("patterns", [])))]
        for t in r.get("tests", []):
            src = t["in"]
            if kind == "rewrite":
                got = pats[0].sub(
                    lambda m: (apply_template(r["template"], m) or m.group(0)), src)
                # sub chỉ thay match ĐẦU? Không — sub thay mọi match; test so toàn chuỗi.
                if got != t["out"]:
                    fails.append(f"{r['id']}: {src!r} -> {got!r}, muốn {t['out']!r}")
            elif kind == "drop_line":
                got = any(p.search(src) for p in pats)
                if got != t["drop"]:
                    fails.append(f"{r['id']}: drop({src!r})={got}, muốn {t['drop']}")
            elif kind == "start_marker":
                got = bool(pats[0].search(src))
                if got != t["match"]:
                    fails.append(f"{r['id']}: match({src!r})={got}, muốn {t['match']}")
    return fails


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    src = Path(args[0]) if args else Path(__file__).with_name("reading-rules.ruler")
    out_path = None
    for a in sys.argv[1:]:
        if a.startswith("--out="):
            out_path = Path(a[len("--out="):])
    if out_path is None:
        out_path = src.with_name("reading-rules.json")

    doc = load_ruler(src)
    fails = run_tests(doc)
    n_tests = sum(len(r.get("tests", [])) for r in doc["rules"])
    if fails:
        print(f"TESTS FAIL ({len(fails)}/{n_tests}):")
        for f in fails:
            print(" -", f)
        sys.exit(1)
    print(f"tests pass: {n_tests}/{n_tests} ({len(doc['rules'])} rules)")

    rules_out = []
    for r in sorted(doc["rules"], key=lambda x: (x.get("priority", 0), x["id"])):
        e = {"id": r["id"], "kind": r["kind"],
             "scope": r.get("scope", []), "lang": r.get("lang", []),
             "priority": r.get("priority", 0)}
        if "pattern" in r:
            e["pattern"] = r["pattern"]
        if "patterns" in r:
            e["patterns"] = list(r["patterns"])
        if "template" in r:
            e["template"] = r["template"]
        if "params" in r:
            e["params"] = r["params"]
        rules_out.append(e)
    payload = {"version": doc["version"], "rules": rules_out}
    canon = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    payload["checksum"] = hashlib.sha256(canon.encode("utf-8")).hexdigest()
    text = json.dumps(payload, ensure_ascii=False, indent=1)
    out_path.write_text(text, encoding="utf-8")
    print(f"wrote {out_path} ({len(text.encode('utf-8'))} bytes, "
          f"sha256 {payload['checksum'][:12]}…)")
    # Bản copy nhúng cho Go (go:embed cấm ".."): cùng nội dung, cùng lệnh sinh.
    go_copy = Path(__file__).resolve().parent.parent.parent \
        / "internal" / "rules" / "reading-rules.json"
    if go_copy.parent.is_dir():
        go_copy.write_text(text, encoding="utf-8")
        print(f"mirrored {go_copy}")


if __name__ == "__main__":
    main()
