# TetherShot

Ứng dụng Android chụp ảnh tethering hoàn toàn offline — không cần tài khoản, chỉ cài APK là chạy.

## Tính năng

- **Kết nối máy ảnh qua USB (PTP)**: cắm máy ảnh vào điện thoại/máy tính bảng qua cáp USB (OTG). Hỗ trợ mọi máy ảnh nói chuẩn PTP (Canon, Nikon, Sony, Fujifilm...). Trên máy ảnh chọn chế độ kết nối **PC / PTP**.
- **Tự động nhận ảnh khi chụp**: app lắng nghe sự kiện PTP `ObjectAdded` (kèm cơ chế poll dự phòng cho máy không hỗ trợ event) và tự tải ảnh JPEG về.
- **Preset LUT (.cube)**: import file LUT 3D chuẩn `.cube`, áp màu bằng nội suy trilinear.
- **Tự động xuất file**: ảnh sau khi áp preset được lưu tự động vào `Pictures/TetherShot`.
- **Thử preset không cần máy ảnh**: nút "Xử lý ảnh từ bộ nhớ" cho phép áp preset lên ảnh có sẵn.

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Cách dùng

1. Cài APK, mở app.
2. Nhấn **Thêm LUT (.cube)** để import preset.
3. Cắm máy ảnh qua USB (chế độ PC/PTP), nhấn **Kết nối máy ảnh** và cấp quyền USB.
4. Chụp ảnh — app tự tải về, áp preset và xuất vào `Pictures/TetherShot`.
