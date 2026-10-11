with open('app/src/main/java/com/bibot/app/feature/news/NewsModels.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# The issue: we need the HTML entity " as a literal string
# In Kotlin source: "\"quot;\"" (escaped) or use unicode
# Let's use the unicode escape for & = \u0026
old = '            """"" to "\\"",'
new = '            "\u0026quot;" to "\\"",'
content = content.replace(old, new)

with open('app/src/main/java/com/bibot/app/feature/news/NewsModels.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print('Fixed')