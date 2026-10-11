import io

p = r'H:\Pi4_Xiaozhi\android_app\android\app\src\main\res\values\strings.xml'
s = io.open(p, encoding='utf-8').read()

old = '<string name="mus_search_default_app">Search & play on default music app</string>'
new = '<string name="mus_search_default_app">Search & play on default music app</string>'

if old in s:
    s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8').write(s)
    print('replaced EN')
else:
    print('NOT FOUND EN')

p2 = r'H:\Pi4_Xiaozhi\android_app\android\app\src\main\res\values-vi\strings.xml'
s2 = io.open(p2, encoding='utf-8').read()
old2 = '<string name="mus_search_default_app">Tìm & phát trên app nhạc mặc định</string>'
new2 = '<string name="mus_search_default_app">Tìm & phát trên app nhạc mặc định</string>'
if old2 in s2:
    s2 = s2.replace(old2, new2)
    io.open(p2, 'w', encoding='utf-8').write(s2)
    print('replaced VI')
else:
    print('NOT FOUND VI')

print('done')