import os
os.chdir('H:\\\\Pi4_Xiaozhi\\\\android_app\\\\android')

with open('app/src/main/java/com/bibot/app/feature/settings/SettingsScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

print('File length:', len(content))
print('Last 100 chars:', repr(content[-100:]))

# Find the exact end pattern
old_end = '}\\n    }\\n}\\n}'
if content.endswith(old_end):
    print('Found exact end pattern')
else:
    print('Pattern not found exactly')
    print('Last 50 chars:', repr(content[-50:]))

# Find the position of the last closing braces
# The file ends with: }\n    }\n}\n}
# Let's find the last 3 closing braces pattern
last_braces = content[-50:]
print('Last 50 chars:')
for i, c in enumerate(content[-50:]):
    print(f'{len(content)-50+i}: {repr(c)}')