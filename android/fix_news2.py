with open('app/src/main/java/com/bibot/app/feature/news/NewsModels.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# The issue: line 286 has """ which Kotlin sees as triple-quoted string
# We need to write the HTML entity " as a literal string """
# In Kotlin source, this is written as "\""\"" (escaped quotes around ")
old = '            """ to "\\"",'
new = '            "\""\"" to "\\"",'
content = content.replace(old, new)

with open('app/src/main/java/com/bibot/app/feature/news/NewsModels.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print('Fixed')