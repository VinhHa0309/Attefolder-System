# Campus Attendance

Hệ thống điểm danh sinh viên bằng React, Spring Boot và PostgreSQL. Mã nguồn hiện triển khai các luồng nền tảng: tài khoản theo vai trò, tạo trường/lớp, nhập định danh và whitelist Excel, mở phiên GPS, QR/PIN luân phiên, check-in, đối soát, chốt phiên, lịch sử và xuất Excel.

## Chạy local

Cần Docker Desktop. Từ thư mục gốc:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

Mở `http://localhost:5173`. API chạy tại `http://localhost:8080`; PostgreSQL tại cổng `5432`. Flyway tự tạo schema. Để chạy frontend/backend ngoài Docker, dùng PostgreSQL ở trên, rồi trong `backend` chạy `mvn spring-boot:run`; trong `frontend` chạy `npm install` và `npm run dev`.

### Tài khoản demo

Khi database mới và `app.demo-data` đang bật (mặc định), có hai trường demo:

| Vai trò | Tài khoản | Mật khẩu | Mã trường |
|---|---|---|---|
| Quản trị hệ thống | `root@local.test` | giá trị `BOOTSTRAP_ADMIN_PASSWORD` trong `.env` | — |
| Admin trường Hà Nội | `admin1@campus.test` | `CampusDemo2026!` | — |
| Giảng viên Hà Nội | `lecturer1@campus.test` | `CampusDemo2026!` | — |
| Sinh viên Hà Nội | `00112345` | `CampusDemo2026!` | `DEMO-HN` |
| Admin trường Sài Gòn | `admin2@campus.test` | `CampusDemo2026!` | — |
| Giảng viên Sài Gòn | `lecturer2@campus.test` | `CampusDemo2026!` | — |
| Sinh viên Sài Gòn | `00212345` | `CampusDemo2026!` | `DEMO-HCM` |

Admin trường demo có sẵn mật khẩu. Sinh viên mới nhập từ Excel được cấp mật khẩu tạm và bắt buộc đổi ở lần đăng nhập đầu. Danh sách demo chỉ được tạo khi database chưa có trường. `app.demo-data=false` tắt dữ liệu này.

## Luồng chính

1. Admin hệ thống đăng nhập, tạo trường và tài khoản Admin trường. API trả mật khẩu tạm một lần; cần chuyển cho đúng người bằng kênh riêng.
2. Admin trường nhập Excel có cột `MSSV`, `Họ tên`, `Email trường`. MSSV được đọc bằng `DataFormatter` và lưu dạng chuỗi. Nạp lại cập nhật tên/email nhưng giữ mật khẩu hiện có. Import whitelist lớp dùng Excel có cột `MSSV`; tài khoản sinh viên phải được Admin trường tạo trước.
3. Giảng viên tạo lớp, nhập whitelist, lấy tâm điểm GPS trên thiết bị hoặc nhập tọa độ bản đồ rồi mở phiên. Trình duyệt phải cho phép GPS; camera/GPS trên điện thoại cần HTTPS.
4. QR chứa URL phiên cùng bearer code luân phiên; PIN là chuỗi sáu chữ số có thể giữ số 0 đầu. Mã có hạn theo chu kỳ cấu hình (`CODE_INTERVAL_SECONDS`, mặc định 15 giây). Chỉ hash được lưu trong DB; giá trị được tái tạo bằng HMAC với `SESSION_SECRET`, nên không mất mã hiện thời khi restart. Mỗi phiên có một mã dùng chung cho QR/PIN.
5. Sinh viên đăng nhập email hoặc MSSV + mã trường, quét QR hoặc nhập PIN, rồi gửi GPS và fingerprint thiết bị. Backend kiểm tra lớp/trường/mã/phiên, tính khoảng cách Haversine, đối chiếu CIDR của trường với IP kết nối tin cậy, áp dụng bảng phân loại và chống ghi trùng bằng unique key cùng khóa hàng mã phiên.
6. Giảng viên đóng phiên để dừng check-in, xử lý bản ghi chờ, rồi chốt riêng. Hạn đối soát được chụp theo cấu hình trường khi tạo phiên. Excel chỉ lấy phiên `FINALIZED`, theo thời gian, `P` cho có mặt và ô trống cho vắng.

## API trọng tâm

- `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/auth/change-password`
- `POST /api/system/schools`, `GET /api/schools`
- `GET/PUT /api/school/settings`; `GET/POST /api/school/lecturers`; `POST /api/school/students/import`; `GET /api/school/students/template.xlsx`
- `POST /api/classes`, `GET /api/classes`; `POST /api/classes/{id}/students/import`; `GET /api/classes/{id}/students/template.xlsx`
- `POST /api/sessions`; `GET /api/sessions/{id}/code`
- `POST /api/sessions/{id}/check-in` (QR); `POST /api/student/check-in` (PIN unique theo trường); `GET /api/sessions/{id}/records`
- `POST /api/sessions/{id}/close`; `POST /api/sessions/{id}/records/{recordId}/review`; `POST /api/sessions/{id}/finalize`
- `PATCH /api/sessions/{id}/records/{recordId}/finalized-result`
- `GET /api/student/history`; `GET /api/classes/{id}/attendance.xlsx`

Yêu cầu bearer token cho mọi API nghiệp vụ. Login trả opaque token ngẫu nhiên lưu hash trong PostgreSQL, hết hạn sau 12 giờ và có thể thu hồi khi logout. Vai trò, chủ sở hữu lớp và trường được kiểm tra phía server. API không trả IP thô hoặc tọa độ trong danh sách đối soát; chỉ trả phân loại IP nội bộ và khoảng cách/sai số.

### Xử lý vị trí và IP

- Khoảng cách tính bằng Haversine từ tọa độ phiên đến GPS do trình duyệt gửi. `gpsAccuracyMeters` là sai số GPS client báo; không xem đây là bằng chứng chống giả lập. Không có GPS hoặc từ chối quyền vẫn có thể ghi nhận sau khi mã và danh tính/lớp hợp lệ, nhưng được đưa vào chờ duyệt.
- GPS vượt `maxDeviationMeters` bị từ chối và không tạo bản ghi. GPS ngoài bán kính hợp lệ được đưa vào đối soát. GPS trong bán kính cùng IP trường được nhận có mặt; IP ngoài trường chỉ nhận tự động khi sai số trong ngưỡng.
- IP mặc định lấy từ TCP peer. Chỉ khi peer thuộc `TRUSTED_PROXIES` mới đọc `X-Forwarded-For`, đi từ proxy gần nhất về client và bỏ qua các hop tin cậy. Không đặt `0.0.0.0/0` làm proxy tin cậy. Nginx trong compose có IP `172.28.0.10`, đã có trong cấu hình mẫu; nếu đổi subnet hoặc proxy, cập nhật biến này.
- Thiết bị dùng random ID lưu trong localStorage rồi hash phía backend; đây là tín hiệu đối soát, không phải định danh phần cứng có thể chống giả mạo tuyệt đối.

## Cấu hình production cần thay

Đổi toàn bộ mật khẩu/secrets, bật HTTPS, khóa truy cập PostgreSQL, cấu hình `WEB_URL`, `TRUSTED_PROXIES` theo hạ tầng thực và SMTP. Hiện tại SMTP chỉ có cấu hình môi trường; quy trình OTP, liên kết kích hoạt email, khôi phục mật khẩu, khóa/mở khóa tài khoản và đăng ký giảng viên tự phục vụ chưa được nối hoàn chỉnh. Tài khoản giảng viên hiện thuộc một trường; luồng giảng viên đa trường cần thêm quan hệ thành viên trường. Nhập sinh viên/giảng viên trả mật khẩu tạm một lần trong response; hệ thống chưa gửi email kích hoạt. Tọa độ hiện lưu cho phục vụ đối soát nhưng endpoint chỉ trả dữ liệu cần thiết cho giảng viên. Đối soát MAD có triển khai API. API có mẫu Excel nhập danh sách và whitelist; Excel xuất dùng workbook tiêu chuẩn tạo mới, chưa hỗ trợ cấu hình mẫu tùy chỉnh và giữ layout mẫu gốc.

## Môi trường

Xem [.env.example](.env.example). Các thay đổi schema cần tạo migration Flyway mới thay vì sửa migration đã chạy ở môi trường có dữ liệu.
