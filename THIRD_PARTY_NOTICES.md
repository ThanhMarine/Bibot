# Third-Party Notices — Bibot

Bibot sử dụng các tài nguyên và thư viện mã nguồn mở dưới đây. Tài liệu này ghi nhận
nguồn gốc và giấy phép; toàn văn giấy phép của Fluent Emoji cũng được đóng gói trong
ứng dụng tại `android/app/src/main/assets/licenses/fluent-emoji-LICENSE.txt`.

## Fluent Emoji 3D

- **Nguồn:** https://github.com/microsoft/fluentui-emoji
- **Tác giả:** Microsoft Corporation
- **Giấy phép:** MIT License
- **Sử dụng trong Bibot:** icon Home (Sách, Truyện, Cổ tích, Nhạc, Thời tiết, Tin tức)
  và tiêu đề các nhóm trong Cài đặt. Các file PNG đã được chuẩn hoá kích thước
  (256×256 → 192×192, RGBA) và đặt tại `android/app/src/main/res/drawable-nodpi/fluent_*.png`.

```
MIT License

Copyright (c) Microsoft Corporation.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Material Icons (Jetpack Compose)

- **Nguồn:** https://fonts.google.com/icons · https://developer.android.com/jetpack/androidx/releases/compose-material
- **Tác giả:** Google LLC
- **Giấy phép:** Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0
- **Sử dụng trong Bibot:** các icon đơn sắc còn lại (thanh công cụ, nút phụ, trạng thái).

## Ghi chú

- Các bộ emoji khác (Twemoji, OpenMoji, Noto) **không** được sử dụng trong bản phát hành này.
- Khi cập nhật/thay thế icon, cần cập nhật lại tệp này và mục **Giấy phép & ghi công**
  trong Cài đặt.
