package com.bibot.app.data.books

/**
 * Folder Drive CÔNG KHAI của app — CHỈ giữ folderId.
 *
 * Danh sách file lấy ĐỘNG khi user thao tác (bấm Đồng bộ / mở màn kho sách)
 * bằng cách scrape trang embeddedfolderview — không hardcode trong APK nữa.
 *
 * Lịch sử: catalog cứng 17 file .md sử Việt đã bị GỠ — lỗi bảng mã,
 * file cũ đã xoá khỏi folder. Folder VI hiện giữ các file .txt bản mới
 * (bản sửa lỗi) nên list động luôn phản ánh đúng nội dung thật trên Drive.
 *
 * VI (Kể chuyện): https://drive.google.com/drive/folders/1UfFOF2FQbA5L9MKJtevEEGja1K0dAhxm
 * EN (Đọc sách):  https://drive.google.com/drive/folders/17XoYAOpCxqb_b2lKjTtrVc3xl8HRd6-2
 * Cổ tích:        https://drive.google.com/drive/folders/1l8gX5ynd9J9Z1u6EQze08sK3VOXzG0Gx
 */
object VietBooksCatalog {
    const val FOLDER_ID = "1UfFOF2FQbA5L9MKJtevEEGja1K0dAhxm"
}

object EnBooksCatalog {
    const val FOLDER_ID = "17XoYAOpCxqb_b2lKjTtrVc3xl8HRd6-2"
}

object CotichBooksCatalog {
    const val FOLDER_ID = "1l8gX5ynd9J9Z1u6EQze08sK3VOXzG0Gx"
}

/**
 * R0/F0.2 — Kho "Tham khảo" (tài liệu tham khảo, thường là PDF): thêm folder ID
 * theo đúng pattern VietBooksCatalog, 0 màn mới (dùng chung ReaderScreen).
 * TODO: điền folderId Drive khi có; để trống = ẩn module (listAll bỏ qua).
 */
object ThamkhaoBooksCatalog {
    const val FOLDER_ID = ""
}
