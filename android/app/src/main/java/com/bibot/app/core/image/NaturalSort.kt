package com.bibot.app.core.image

/**
 * A3.4 NaturalSort — sắp xếp theo thứ tự tự nhiên (natural order).
 *
 * Ví dụ: "page1.jpg", "page2.jpg", "page10.jpg" (thứ tự ASCII: 1, 10, 2 — sai)
 * Natural sort: 1, 2, 10 (đúng ý định người dùng).
 *
 * Dùng cho: comic/CBZ pages, EPUB spine items, PDF page files, folder listing.
 */
object NaturalSort {

    /**
     * So sánh 2 chuỗi theo natural order (chữ số được so sánh theo giá trị số).
     * @return -1 nếu a < b, 0 nếu bằng, 1 nếu a > b
     */
    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // Đọc số liên tiếp từ a
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                val numA = a.substring(i, i2).toLongOrNull() ?: 0L

                // Đọc số liên tiếp từ b
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++
                val numB = b.substring(j, j2).toLongOrNull() ?: 0L

                if (numA != numB) return numA.compareTo(numB)

                i = i2
                j = j2
            } else {
                // So sánh ký tự thường (case-insensitive cho chữ cái)
                val cmp = ca.toString().compareTo(cb.toString(), ignoreCase = true)
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return a.length.compareTo(b.length)
    }

    /**
     * Comparator cho List<String>.
     */
    val comparator: Comparator<String> = Comparator { a, b -> compare(a, b) }

    /**
     * Sắp xếp danh sách in-place.
     */
    fun sort(list: MutableList<String>) {
        list.sortWith(comparator)
    }

    /**
     * Trả về list đã sort (không thay đổi list gốc).
     */
    fun sorted(list: List<String>): List<String> = list.sortedWith(comparator)

    /**
     * Comparator cho file theo tên (name).
     */
    fun fileNameComparator(): Comparator<java.io.File> = Comparator { f1, f2 ->
        compare(f1.name, f2.name)
    }

/**
 * Comparator cho file theo đường dẫn.
 */
fun filePathComparator(): Comparator<java.io.File> = Comparator { f1, f2 ->
    compare(f1.path, f2.path)
}

/**
 * Comparator cho DocFileItem theo tên (A3.4).
 */
fun docFileItemNameComparator(): Comparator<com.bibot.app.data.library.DocFileItem> = Comparator { f1, f2 ->
    compare(f1.name, f2.name)
}
}