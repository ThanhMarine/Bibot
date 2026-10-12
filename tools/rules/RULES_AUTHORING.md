# RULES_AUTHORING — thêm/sửa Reading Rule (cho người và AI)

File duy nhất cần sửa: `tools/rules/reading-rules.ruler`.
Xong chạy: `python tools/rules/compile_rules.py` (phải `tests pass`, JSON sinh lại).
Commit cả 2 file `.ruler` + `.json` (JSON là artifact có version, review được diff).

## 1. Thêm 1 rule mới (4 bước)

1. Chọn `kind`:
   - `rewrite`: biến đổi chữ (`31/12` → `ngày…`). Cần `pattern` + `template`.
   - `drop_line`: bỏ cả dòng theo DÒNG (`12`, `Trang 12`). Cần `patterns[]`
     (nhiều pattern = OR). Engine xét từng dòng.
   - `start_marker`: đánh dấu mốc bắt đầu đọc (Lời giới thiệu/Chương 1). Cần `pattern`.
   - `split_guard`: tham số cho chunker (VD cặp ngoặc), không phải regex.
     Cần `params` (xem H2.3 checklist).
2. Viết `pattern` **trong giao Java ∩ RE2 ∩ Python** — cấm:
   lookbehind `(?<=`/`(?<!`, lookahead `(?=`/`(?!`, atomic `(?>`,
   possessive (`++`/`**+`…), backreference `\1`…`\9`, named `(?P<`.
   Được dùng: `\b \s \d \w . * + ? {m,n} [..] ( ) (?: ) (?i) ^ $ |`.
   Script tự từ chối pattern vi phạm (báo tên cấu trúc).
3. Viết `template` (chỉ `rewrite`) bằng placeholder:
   `{N}` group N nguyên văn; `{day:N}` ngày (1–10 có `mùng`);
   `{month:N}` (1–12); `{year:N}` (2 số→2000+, 4 số 1000–2999);
   `{num:N}` số thành chữ; `{roman:N}` La Mã thành chữ.
   Hook sai (ngày 32, tháng 13, roman `IIII`) → **bỏ cả match, giữ nguyên chữ**.
4. Viết `tests[]` (ít nhất 3 case, gồm case GIỮ NGUYÊN):
   rewrite `{in, out}`; drop_line `{in, drop: true/false}`;
   start_marker `{in, match: true/false}`.
   Đặt `priority` = thứ tự pipeline (số nhỏ chạy trước; xem `cleanForTts` bước 3a…).

## 2. Ví dụ thêm rule (copy-paste)

```yaml
  - id: temp.celsius
    kind: rewrite
    scope: [tts]
    lang: [vi]
    priority: 11
    pattern: '(\d)\s*°\s*C\b'
    template: '{1} độ C'
    tests:
      - {in: '25°C hôm nay', out: '25 độ C hôm nay'}
      - {in: 'nhiệt độ phòng', out: 'nhiệt độ phòng'}
```

## 3. Quy tắc chung

- 1 pattern chạy giống nhau Kotlin/Go/Python — test trong ruler là chuẩn cuối.
- Không nhét logic locale vào ruler; cần tính toán mới thì thêm **hook tên sẵn**
  (cài 1 lần ở 3 engine, tài liệu ở đây) thay vì regex mẹo.
- `version` tăng mỗi lần đổi hành vi; `checksum` do script tự tính — không sửa tay file `.json`.
- Params của `split_guard` để **flat string** (VD `cite_abbrev: 'fig no ...'`,
  số cũng viết dạng string `'8'`) để loader Kotlin/Go nhẹ (0 dep, chỉ Map).
- Cái gì KHÔNG ép được vào giao 3 engine (VD lookahead như `readPageNumbers`,
  vòng khối chỉ-bỏ-số-trang) thì GIỮ code + ghi `note:` lý do — không ép.

## 4. Quy trình publish 1 bản rule mới (v2+)

1. Sửa `.ruler` (tăng `version` nếu đổi hành vi) → chạy
   `python tools/rules/compile_rules.py` (phải `tests pass`, JSON sinh lại +
   mirror sang `internal/rules/`).
2. Copy JSON sang assets Android:
   `Copy-Item tools/rules/reading-rules.json android/app/src/main/assets/reading-rules.json`
   (JSON ~5KB, trần 30KB — kiểm tra size khi thêm rule).
3. Chạy test 3 nơi: `compile_rules.py` (chuẩn), `:app:testDemoDebugUnitTest`
   (`ReadingRulesTest` đọc JSON thật, chống drift), `go test ./internal/rules/`.
4. Nếu đổi pipeline clean (TextCleaner/BlockTextReader/chunker): bump
   `PREP_VERSION` (`BookPrepStore`) để cache `.prep` cũ build lại — rulepack đổi
   mà `PREP_VERSION` giữ nguyên thì sách mở từ cache vẫn là text clean cũ.
5. Commit cả `.ruler` + 3 bản `.json` (tools, internal, assets).
