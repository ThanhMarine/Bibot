"""Sinh 2 ma VIP test (1 thang + vinh vien) bang secret trong local.properties.
Secret KHONG bao gio in ra man hinh. CSV ghi ra TEMP, khong de lai trong repo."""
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools', 'vipcodes'))
import vip_batch as vb

props = {}
with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'local.properties'), encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            props[k.strip()] = v.strip()

secret_hex = props.get('bibot.vip.secret', '')
assert len(secret_hex) == 64, 'secret trong local.properties khong hop le (can 64 ky hex)'
secret = bytes.fromhex(secret_hex)

out = os.path.join(tempfile.gettempdir(), 'bibot_vip_test.csv')
rows = []
for tier, months, batch, serial in (('month', 1, 90, 1), ('lifetime', 1, 90, 2)):
    t = vb.TIER_LIFE if tier == 'lifetime' else vb.TIER_MONTH
    code, exp = vb.make_code(secret, t, months, batch, serial)
    v = vb.verify(secret, code)
    assert v.get('ok'), (code, v)
    rows.append((tier, code, exp, v))

with open(out, 'w', encoding='utf-8') as f:
    f.write('tier,code,expiry\n')
    for tier, code, exp, _ in rows:
        f.write('%s,%s,%s\n' % (tier, code, exp))
print('CSV:', out)
for tier, code, exp, v in rows:
    print(tier, '->', code, '| han:', exp, '| verify:', v)
print('END-TO-END OK: ma sinh tu secret that, verify pass')
