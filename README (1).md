# Hệ thống điểm danh sinh viên

## 1. Nhiệm vụ giao cho Agent

Triển khai web điểm danh tiếng Việt với giao diện, API và cơ sở dữ liệu thật. Hoàn thành luồng khởi tạo trường, cấp tài khoản, tạo lớp, mở phiên, điểm danh, đối soát, chốt kết quả và xuất Excel. Sinh viên sử dụng trình duyệt điện thoại. Giảng viên quản lý phiên trên máy tính và có màn hình QR/PIN dễ đọc khi trình chiếu.

Đọc cấu trúc repo và hướng dẫn dự án trước khi sửa. Tận dụng kiến trúc hiện có. Nếu repo trống, mặc định dùng **React + Vite, Spring Boot REST API và PostgreSQL**. Dùng migration cho DB, Apache POI cho Excel và SMTP cho email. Triển khai và kiểm thử từng luồng theo thứ tự ở mục 8.

### Nguồn yêu cầu

- **Nhom9.docx**, chương 2, mục 2.3–2.6. Dùng để đối chiếu ca sử dụng, biểu mẫu và ngoại lệ.
- **Kịch bản hệ thống Điểm danh sinh viên.docx**, trang 1–8. Khi hai tài liệu khác nhau, ưu tiên quy trình trong kịch bản theo bảng dưới đây.

| Hạng mục | Quy tắc cần triển khai |
|---|---|
| Vai trò | Quản trị hệ thống, Admin trường, Giảng viên, Sinh viên |
| Tài khoản sinh viên | Admin trường cấp từ danh sách định danh. Sinh viên chọn trường rồi nhập MSSV và mật khẩu |
| File Excel của giảng viên | Tạo whitelist MSSV cho lớp. Tài khoản sinh viên thuộc phạm vi quản lý của Admin trường |
| Thiết bị gốc | Xác nhận khi điểm danh lần đầu đã qua kiểm tra mã và danh tính. Kiểm tra thiết bị độc lập với kết quả vị trí |
| Đóng phiên | Dừng nhận điểm danh và chuyển sang đối soát. Chốt kết quả là thao tác riêng |
| Excel chuyên cần | Chỉ tổng hợp các buổi **Đã chốt** của lớp, theo thứ tự thời gian |

## 2. Vai trò, quyền và màn hình

| Vai trò | Màn hình và thao tác | Phạm vi truy cập |
|---|---|---|
| Quản trị hệ thống | Đăng nhập, danh sách trường, khởi tạo trường, cấp lại tài khoản Admin trường, đổi mật khẩu, đăng xuất | Danh mục trường và kích hoạt Admin. Không cấp quyền xem dữ liệu điểm danh |
| Admin trường | Đăng nhập, tổng quan và nhật ký của trường theo báo cáo, cấu hình, nhập danh sách định danh, tra cứu tài khoản, khóa/mở khóa, cấp lại mật khẩu, đổi/khôi phục mật khẩu, đăng xuất | Trường được giao. Quyền đối với giảng viên đa trường theo mục 3 |
| Giảng viên | Đăng ký/OTP, đăng nhập, đổi/khôi phục mật khẩu, dashboard, tạo/chọn lớp, chi tiết lớp, thiết lập và trình chiếu phiên, theo dõi trực tiếp, đối soát, chốt kết quả, xem/sửa kết quả, quản lý mẫu và xuất Excel, đăng xuất | Lớp do mình tạo, có thể thuộc nhiều trường |
| Sinh viên | Đăng nhập theo trường, đổi mật khẩu lần đầu và theo nhu cầu, khôi phục mật khẩu, lớp của tôi, quét QR hoặc nhập PIN, kết quả tức thời, lịch sử, đăng xuất | Lớp hợp lệ trong cùng trường và kết quả của chính mình |

Kiểm tra quyền ở API theo vai trò, trường và chủ sở hữu lớp. Các màn hình nghiệp vụ cần đăng nhập. Biểu mẫu đăng nhập, đăng ký giảng viên, xác thực OTP, kích hoạt Admin và khôi phục mật khẩu cho phép truy cập khi chưa đăng nhập. Đăng xuất hủy phiên làm việc hiện tại.

## 3. Trường, tài khoản và lớp

### Khởi tạo trường và quản lý tài khoản

1. Bootstrap tài khoản Quản trị hệ thống qua cấu hình môi trường. Người này nhập mã trường duy nhất, tên trường và email khôi phục của cán bộ quản trị. Mỗi trường có đúng một Admin. Gửi liên kết kích hoạt một lần, có hạn (ví dụ 24 giờ), để Admin tự đặt mật khẩu. Khi cấp lại tài khoản, vô hiệu hóa liên kết cũ và gửi liên kết mới đến email đã khai báo. Quy trình khôi phục của tài khoản Quản trị hệ thống nằm ngoài phạm vi đồ án.
2. Admin cấu hình CIDR của mạng công cộng trường, bán kính hợp lệ, ngưỡng sai số định vị, ngưỡng lệch tối đa, ngưỡng phân tán, số bản ghi tối thiểu để phân tích, thời hạn đối soát, thời lượng phiên và chu kỳ đổi mã. Cấu hình giá trị mặc định và khoảng cho phép giảng viên điều chỉnh bán kính và ngưỡng sai số. Ngưỡng lệch tối đa chỉ Admin chỉnh.
3. Admin nhập Excel định danh gồm **MSSV, Họ tên, Email trường**. Giữ MSSV dưới dạng chuỗi, kể cả số 0 đầu. Kiểm tra định dạng, cột và dữ liệu, báo lỗi theo dòng, lưu lịch sử lần nạp. Tạo tài khoản theo khóa **trường + MSSV** và giữ tài khoản/mật khẩu đã có khi nạp lại. Cấp mật khẩu tạm, bắt buộc đổi khi đăng nhập lần đầu và chặn các API nghiệp vụ đến khi đổi xong. Dùng email trường để khôi phục.
4. Quản lý tài khoản có tìm theo MSSV/email/tên, lọc vai trò và trạng thái, xem cờ vi phạm trong phạm vi trường. Yêu cầu lý do khi khóa, xác nhận lại nếu có phiên điểm danh hoạt động và ghi nhật ký thao tác. Giữ tài khoản để bảo toàn lịch sử. Tài khoản ở trạng thái **Chưa kích hoạt**, **Đang hoạt động** hoặc **Bị khóa**.
5. **Quyết định triển khai với giảng viên đa trường.** Admin chỉ khóa/mở quyền của giảng viên trong trường mình. Khôi phục mật khẩu giảng viên toàn hệ thống qua email đã xác minh. Chức năng cấp lại mật khẩu của Admin áp dụng cho sinh viên do trường quản lý.

### Giảng viên, lớp và ghi danh

- Giảng viên đăng ký họ tên, giới tính, ngày sinh, email và mật khẩu. Mỗi email giảng viên là duy nhất. Kích hoạt qua OTP có hạn và hỗ trợ gửi lại. Email dùng để đăng nhập và khôi phục mật khẩu. OTP xác minh quyền truy cập hộp thư. Quyền truy cập dữ liệu lớp dựa trên chủ sở hữu lớp.
- Tạo lớp gồm tên, mã lớp, trường, học kỳ, niên khóa và Excel chứa MSSV. Kiểm tra dữ liệu, lưu whitelist và trạng thái lớp, tạo QR tĩnh. Dữ liệu định danh sinh viên đến từ hồ sơ Admin cấp.
- **QR tĩnh** mở trang lớp. Sau khi sinh viên đăng nhập, kiểm tra lớp còn hoạt động, cùng trường và MSSV trong whitelist. Tạo ghi danh nếu chưa có và hiển thị lịch sử. QR tĩnh không tạo bản ghi điểm danh.
- **QR động/PIN** dành cho phiên đang mở. Sau khi mã và danh tính hợp lệ, tạo ghi danh nếu chưa có dù bước kiểm tra vị trí sau đó không cho phép ghi điểm danh. Đối chiếu họ tên tài khoản với hồ sơ định danh do Admin cấp. Không dùng họ tên do client gửi để xác thực danh tính.

## 4. Mở phiên và nhận điểm danh

### Mở phiên

- Giảng viên chọn lớp, đặt thời lượng dương và chốt tâm điểm bằng bản đồ số hoặc GPS điện thoại. Có thể dùng QR xác nhận vị trí riêng cho giảng viên trên điện thoại. Phiên chỉ nhận điểm danh khi đã có tâm điểm. Lưu tọa độ và nguồn **bản đồ** hoặc **GPS điện thoại**.
- Hiển thị bán kính và ngưỡng sai số theo trường, cho giảng viên chỉnh trong khoảng được cấp. Lưu cấu hình dùng thực tế theo phiên để việc đổi thông số trường về sau không ảnh hưởng tới đối soát.
- Hiển thị QR động, PIN 6 chữ số, thời gian còn lại của phiên và thời điểm đổi mã. Ví dụ phiên 5 phút, QR/PIN đổi mỗi 15 giây. Backend kiểm tra thời hạn theo thời điểm nhận yêu cầu. Giữ PIN dạng chuỗi để bảo toàn số 0 đầu. Khi nhập PIN từ trang chung, mã cần xác định duy nhất phiên đang mở của trường.
- Cập nhật trực tiếp tổng số có mặt, chờ đối chiếu, chờ duyệt và chưa ghi nhận trên màn hình giảng viên. Có thể dùng SSE hoặc polling ngắn. Giao diện sinh viên cần hỗ trợ camera, nhập PIN, lỗi quyền GPS, mã hết hạn và thử lại khi phù hợp.

### Xử lý điểm danh

1. Xác thực vai trò, trường và quyền thao tác. Với phiên đã xác định, truy xuất bản ghi đã có để xử lý yêu cầu lặp. Với yêu cầu mới, kiểm tra phiên còn mở, mã còn hiệu lực, MSSV trong whitelist, họ tên khớp hồ sơ định danh. Mã sai/hết hạn hoặc sai danh tính trả **Không thành công** và không tạo bản ghi.
2. Kiểm tra fingerprint và lịch sử dùng thiết bị. Sinh viên chưa có thiết bị gốc có thể xác nhận gắn thiết bị hiện tại nếu thiết bị chưa từng dùng cho tài khoản khác. Việc gắn độc lập với kết quả vị trí. Thiết bị khác thiết bị gốc hoặc đã dùng cho tài khoản khác tạo cờ **Thiết bị mới** hoặc **Nghi vấn gian lận thiết bị**. Tra cứu fingerprint toàn hệ thống để phát hiện dùng chung giữa hai trường. Chỉ trả về cờ cần xử lý, giữ kín dữ liệu của tài khoản và trường khác.
3. Backend lấy IP qua kết nối/proxy tin cậy. Tính khoảng cách từ GPS đến tâm điểm theo mét và xét riêng sai số định vị. Áp dụng bảng theo thứ tự sau.

| Ưu tiên | Điều kiện | Kết quả |
|---|---|---|
| 1 | Có GPS và khoảng cách vượt ngưỡng lệch tối đa, kể cả IP thuộc trường | **Không thành công**, không tạo bản ghi. Có thể thử lại khi còn giờ |
| 2 | Thiết bị mới, dùng chung, GPS mất tín hiệu/hết thời gian, hoặc từ chối quyền vị trí | **Chờ duyệt**, lưu các cờ tương ứng |
| 3 | GPS ngoài bán kính, chưa vượt ngưỡng lệch tối đa, với IP trong hoặc ngoài trường | **Chờ đối chiếu** |
| 4 | GPS trong bán kính, IP thuộc trường | **Có mặt**, bất kể sai số định vị |
| 5 | GPS trong bán kính, IP ngoài trường, sai số không vượt ngưỡng | **Có mặt** |
| 6 | GPS trong bán kính, IP ngoài trường, sai số vượt ngưỡng hoặc thiếu giá trị sai số | **Chờ duyệt**, cờ sai số định vị |

Cấu hình minh họa để demo gồm bán kính 50 m, ngưỡng sai số 30 m và ngưỡng lệch tối đa 500 m. Tại đúng ngưỡng, tính là “không vượt”. UI hiển thị **Thành công**, **Đã ghi nhận – chờ đối chiếu**, **Chờ duyệt** hoặc **Không thành công** theo bốn kết quả xử lý. **Vắng mặt** xuất hiện trong lịch sử sau khi được ghi nhận qua đối soát hoặc chốt.

## 5. Đóng phiên, đối soát và chốt buổi học

**Đang mở → Đang đối soát → Đã chốt.** Backend tự đóng khi hết giờ và tự chốt khi hết hạn đối soát. Hai yêu cầu đóng hoặc chốt chạy đồng thời chỉ được chuyển trạng thái một lần.

1. **Đóng phiên.** Giảng viên bấm đóng sớm hoặc hệ thống tự đóng. Dừng đổi mã, từ chối điểm danh mới theo thời hạn server, xử lý **Chờ đối chiếu** và lưu danh sách còn cần xử lý. Giảng viên có thể tiếp tục đối soát sau khi tải lại hoặc đăng nhập lại.
2. **Phân tích tương quan.** Phân tích khi có đủ bản ghi **Chờ đối chiếu** (ví dụ từ 3). Tính tâm cụm bằng trung vị từng tọa độ của tập bản ghi xét. Tính khoảng cách từ từng điểm đến tâm cụm. MAD là trung vị của độ lệch tuyệt đối giữa các khoảng cách và trung vị của chúng. Cụm chặt khi MAD nhỏ hơn ngưỡng phân tán.

| Kết quả phân tích | Xử lý |
|---|---|
| Thiếu số bản ghi hoặc MAD lớn hơn hay bằng ngưỡng | Chuyển **Chờ duyệt** |
| Cụm chặt và tâm cụm trong bán kính | Chuyển **Có mặt** |
| Cụm chặt và tâm cụm ngoài bán kính, nguồn tâm điểm là bản đồ | Dịch tâm điểm về tâm cụm, lưu tọa độ trước/sau và cờ **Tâm điểm đã được hệ thống dịch**, chuyển **Có mặt** |
| Cụm chặt và tâm cụm ngoài bán kính, nguồn tâm điểm là GPS điện thoại | Giữ tâm điểm, chuyển **Chờ duyệt** với cờ **Nghi vấn tâm điểm lệch** |

3. **Duyệt và điểm danh thủ công.** Hiển thị bản ghi **Chờ duyệt** và sinh viên trong whitelist chưa có bản ghi. Sắp xếp độ lệch giảm dần khi có GPS. Giảng viên phê duyệt thành **Có mặt**, từ chối thành **Vắng mặt**, hoặc ghi **Có mặt** thủ công khi xác nhận sinh viên có mặt. Cho phép xử lý khi phiên còn mở và trong giai đoạn đối soát. Chỉ khi giảng viên trực tiếp phê duyệt bản ghi nghi vấn đổi/dùng chung thiết bị mới gắn hoặc đổi thiết bị gốc theo yêu cầu đã duyệt.
4. **Chốt kết quả.** Giảng viên chốt hoặc hệ thống tự chốt khi quá hạn (ví dụ 24 giờ sau khi đóng). Sinh viên chưa có bản ghi được ghi **Vắng mặt**. Bản ghi **Chờ duyệt** chưa xử lý chuyển **Có mặt** kèm “Tự động ghi nhận do chưa được duyệt”. Riêng cờ **Nghi vấn gian lận thiết bị**, **Từ chối quyền vị trí** hoặc **Nghi vấn tâm điểm lệch** chuyển **Vắng mặt** kèm “Quá hạn đối soát”. Việc chốt không cập nhật thiết bị gốc.
5. **Sau chốt.** Giảng viên xem và sửa trạng thái qua màn hình kết quả đã chốt. Lưu người sửa, thời điểm và trạng thái trước/sau. Việc sửa giữ nguyên thiết bị gốc. Lịch sử sinh viên và dữ liệu xuất Excel đọc kết quả mới nhất.

## 6. Xuất Excel theo mẫu trường

- Giảng viên tải mẫu và cấu hình dòng bắt đầu, cột MSSV, cột điểm danh đầu tiên và ký hiệu **Có mặt**. Kiểm tra file và cấu hình. Hiển thị thông báo khi lớp chưa có buổi đã chốt.
- Mỗi lần xuất, lấy tất cả buổi **Đã chốt** của lớp từ đầu học kỳ theo thứ tự thời gian. Đối chiếu từng hàng theo MSSV, điền ký hiệu cho **Có mặt** và để ô trống cho **Vắng mặt**. Giữ định dạng và các vùng khác của mẫu. Tạo file tải về và giữ nguyên tệp mẫu gốc.

## 7. Mô hình dữ liệu và yêu cầu triển khai

- Mô hình tối thiểu gồm trường và cấu hình, tài khoản và hồ sơ sinh viên, OTP/liên kết kích hoạt, lịch sử nạp định danh, lớp, whitelist, ghi danh, phiên, mã QR/PIN, bản ghi điểm danh, thiết bị gốc và lịch sử fingerprint, mẫu Excel và nhật ký thao tác.
- Áp dụng ràng buộc duy nhất cho mã trường, Admin theo trường, email giảng viên, MSSV theo trường, MSSV trong whitelist, ghi danh theo lớp/sinh viên và bản ghi theo phiên/sinh viên. Dùng transaction cho ghi điểm danh và chuyển trạng thái phiên. Tác vụ hết hạn phải tiếp tục xử lý được sau khi dịch vụ khởi động lại.
- Lưu thời gian UTC, hiển thị theo múi giờ cấu hình, mặc định Việt Nam. Lưu hash mật khẩu, giới hạn số lần thử mã, kiểm tra hạn và một lần sử dụng cho OTP/liên kết kích hoạt. Chỉ thu GPS trong thao tác cần định vị và giới hạn việc xem vị trí/IP/fingerprint theo quyền đối soát.
- Dùng dữ liệu giả để demo. Cung cấp biến môi trường mẫu cho DB, SMTP, cấu hình bootstrap và URL web. Dùng HTTPS khi kiểm thử camera/GPS trên điện thoại. Nếu môi trường thiếu SMTP hoặc HTTPS, ghi rõ luồng nào đã chạy local và luồng nào cần xác minh với cấu hình thật.

## 8. Thứ tự làm và tiêu chí nghiệm thu

| Bước | Công việc | Nghiệm thu |
|---|---|---|
| 1 | Migration, xác thực, phân quyền, khởi tạo trường và Admin | Kích hoạt một lần, OTP sai/hết hạn, đổi mật khẩu lần đầu, chặn truy cập khác trường/lớp |
| 2 | Nhập định danh, tạo lớp và ghi danh | Nạp lại giữ tài khoản/mật khẩu. Hai trường dùng được cùng một MSSV. File tạo lớp không cấp tài khoản. QR tĩnh chỉ ghi danh |
| 3 | Tâm điểm, trình chiếu QR/PIN và điểm danh trực tiếp | Cả QR/PIN hoạt động. Mã cũ hết hạn. Kiểm thử Wi-Fi trường, 4G/5G, GPS xa/thiếu, từ chối quyền, sai số lớn, đổi thiết bị, dùng chung khác trường và gửi lặp đồng thời |
| 4 | Đóng phiên, đối soát, chốt và sửa kết quả | Kiểm thử bốn nhánh tương quan và thiếu dữ liệu, duyệt/từ chối/thủ công, đóng sớm/tự động, chốt tay/tự động, các cờ quá hạn và khôi phục sau reload |
| 5 | Lịch sử và Excel | Sinh viên chỉ thấy kết quả cá nhân. Xuất ít nhất hai buổi đúng thứ tự, bỏ buổi đang đối soát, đúng hàng MSSV/cột và phản ánh chỉnh sửa sau chốt |

Bàn giao mã nguồn chạy được, lệnh cài/chạy local, biến môi trường mẫu, migration, dữ liệu demo đủ bốn vai trò và hai trường, file Excel mẫu cho luồng nhập/xuất, lệnh test và kết quả kiểm tra đã thực hiện. Ưu tiên test tự động cho bảng quyết định điểm danh, logic đóng/chốt, phân quyền và chống ghi trùng. Khi tài liệu chưa định rõ chi tiết, ghi giả định trong phần bàn giao và triển khai các ngưỡng bằng cấu hình.
