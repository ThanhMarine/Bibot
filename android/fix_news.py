with open('app/src/main/java/com/bibot/app/feature/news/NewsModels.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# The issue is the triple-quote characters in the source
# Replace with proper escaped string for HTML entity "
# The file contains literal triple-quote chars which Kotlin interprets as triple-quoted string
old_line = '            """ to "\\"",'
new_line = '            """ to "\\"",'
content = content.replace(old_line, new_line)

with open('app/src/main/java/com/bibot/app/feature/news/NewsModels.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print('Fixed')