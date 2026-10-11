import io
p = 'CHECKLIST.md'
s = io.open(p, encoding='utf-8').read()

# Update Music section
old_mus = '- [x] **P3 — Ho tro nguoi nuoc ngoai hoc TV (lam sau P2)**: UI strings co san; che do song ngu (cau Viet + nghia Anh) + cham tu de nghe lai (dung TTS engine) + toc do cham 0.7x-0.8x (slider 0.5-2.0x da co san) — code san sang, can them UI toggle + LLM service; kho sach Lich su VN do content, khong phai code'
new_mus = '- [x] **P3 — Ho tro nguoi nuoc ngoai hoc TV (lam sau P2)**: UI strings co san; che do song ngu (cau Viet + nghia Anh) + cham tu de nghe lai (dung TTS engine) + toc do cham 0.7x-0.8x (slider 0.5-2.0x da co san) — code san sang, can them UI toggle + LLM service; kho sach Lich su VN do content, khong phai code'
s = s.replace(old_mus, new_mus)

# Update P2 status
old_p2 = '- [x] **P2 Dich man hinh (2026-10-04, verified 2 flavor)**: XONG Nav/Home/Doc sach-Ke chuyen/Cai dat/Diem tin/Thoi tiet/PDF/Weather/Weather/Music/PDF/Doc (~230 keys EN/VI, context.getString trong coroutine/handler, stringResource tren UI; style/tone AI + mo ta model de lai); XONG'
new_p2 = '- [x] **P2 Dich man hinh (2026-10-04, verified 2 flavor)**: XONG Nav/Home/Doc sach-Ke chuyen/Cai dat/Diem tin/Thoi tiet/PDF/Weather/Weather/Music/PDF/Doc (~230 keys EN/VI, context.getString trong coroutine/handler, stringResource tren UI; style/tone AI + mo ta model de lai); XONG'
s = s.replace(old_p2, new_p2)

# Update Music leftovers
old_mus_left = '- [x] **P3 — Ho tro nguoi nuoc ngoai hoc TV (lam sau P2)**: UI strings co san; che do song ngu (cau Viet + nghia Anh) + cham tu de nghe lai (dung TTS engine) + toc do cham 0.7x-0.8x (slider 0.5-2.0x da co san) — code san sang, can them UI toggle + LLM service; kho sach Lich su VN do content, khong phai code'
new_mus_left = '- [x] **P3 — Ho tro nguoi nuoc ngoai hoc TV (lam sau P2)**: UI strings co san; che do song ngu (cau Viet + nghia Anh) + cham tu de nghe lai (dung TTS engine) + toc do cham 0.7x-0.8x (slider 0.5-2.0x da co san) — code san sang, can them UI toggle + LLM service; kho sach Lich su VN do content, khong phai code'
s = s.replace(old_mus_left, new_mus_left)

# Update Music in Medium Priority
old_mus_med = '- [x] **Speed Control UI**: Slider + presets in reader footer (da co san slider 0.5-2.0x, can them UI preset 0.7x/0.8x/1.0x/1.2x/1.5x)'
new_mus_med = '- [x] **Speed Control UI**: Slider + presets in reader footer (da co san slider 0.5-2.0x, can them UI preset 0.7x/0.8x/1.0x/1.2x/1.5x)'
s = s.replace(old_mus_med, new_mus_med)

# Update Background TTS
old_bg = '- [x] **Background TTS (doc nen)**: `KeepAwakeService` foreground mediaPlayback + `WAKE_MODE_LOCAL` (mien Doze) — full MediaSession (nut lockscreen) de ban sau'
new_bg = '- [x] **Background TTS (doc nen)**: `KeepAwakeService` foreground mediaPlayback + `WAKE_MODE_LOCAL` (mien Doze) — full MediaSession (nut lockscreen) de ban sau'
s = s.replace(old_bg, new_bg)

io.open(p, 'w', encoding='utf-8').write(s)
print('checklist updated')