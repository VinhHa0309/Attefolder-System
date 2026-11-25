package vn.attendance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
class AuthController {
    private static final Set<String> VALID_GENDERS = Set.of("MALE", "FEMALE", "OTHER");
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder;
    private final MailService mailService;

    AuthController(JdbcTemplate db, BCryptPasswordEncoder encoder, MailService mailService) {
        this.db = db;
        this.encoder = encoder;
        this.mailService = mailService;
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password, String schoolId, String schoolCode) {}
    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    record ActivateRequest(@NotBlank String token, @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    record LecturerRegisterRequest(
            @NotBlank String fullName,
            @NotBlank String gender,
            @NotBlank String dateOfBirth,
            @Email @NotBlank String email,
            @NotBlank @Size(min = 12, max = 128) String password) {}
    record VerifyOtpRequest(@Email @NotBlank String email, @NotBlank String otp) {}
    record ResendOtpRequest(@Email @NotBlank String email) {}
    record ForgotPasswordRequest(String role, String email, String schoolId, String studentNo) {}
    record ResetPasswordRequest(
            String role,
            String email,
            String schoolId,
            String studentNo,
            @NotBlank String otp,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}

    @GetMapping("/public/schools")
    List<Map<String, Object>> publicSchools() {
        return db.queryForList("SELECT id, code, name FROM school ORDER BY name");
    }

    @PostMapping("/auth/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest body) {
        String identifier = body.email().trim();
        Map<String, Object> u;
        if ((body.schoolId() != null && !body.schoolId().isBlank()) || (body.schoolCode() != null && !body.schoolCode().isBlank())) {
            u = SecurityUtil.one(
                    db,
                    """
                    SELECT u.id, u.email, u.full_name, u.password_hash, u.role, u.school_id, u.student_no,
                           u.status, u.must_change_password, s.code AS school_code, s.name AS school_name
                    FROM app_user u
                    JOIN school s ON s.id = u.school_id
                    WHERE u.role = 'STUDENT'
                      AND u.student_no = ?
                      AND ((? <> '' AND u.school_id::text = ?) OR (? <> '' AND upper(s.code) = upper(?)))
                    LIMIT 1
                    """,
                    identifier,
                    body.schoolId() == null ? "" : body.schoolId().trim(),
                    body.schoolId() == null ? "" : body.schoolId().trim(),
                    body.schoolCode() == null ? "" : body.schoolCode().trim(),
                    body.schoolCode() == null ? "" : body.schoolCode().trim());
        } else {
            u = SecurityUtil.one(
                    db,
                    """
                    SELECT u.id, u.email, u.full_name, u.password_hash, u.role, u.school_id, u.student_no,
                           u.status, u.must_change_password, s.code AS school_code, s.name AS school_name
                    FROM app_user u
                    LEFT JOIN school s ON s.id = u.school_id
                    WHERE lower(u.email) = lower(?)
                    LIMIT 1
                    """,
                    identifier);
        }
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Thông tin đăng nhập không đúng");
        }
        String status = String.valueOf(u.get("status"));
        if ("INACTIVE".equals(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản chưa được kích hoạt");
        }
        if ("LOCKED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa");
        }
        String hash = String.valueOf(u.get("password_hash"));
        if (hash.startsWith("!") || !encoder.matches(body.password(), hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Thông tin đăng nhập không đúng");
        }

        String token = SecurityUtil.randomToken(32);
        db.update(
                "INSERT INTO auth_token(token_hash, user_id, expires_at) VALUES(?,?,?)",
                SecurityUtil.sha256(token), u.get("id"), Timestamp.from(Instant.now().plus(Duration.ofHours(12))));
        Map<String, Object> device = SecurityUtil.one(
                db, "SELECT confirmed_at FROM device_registry WHERE student_id = ?", u.get("id"));
        u.remove("password_hash");
        u.put("accessToken", token);
        u.put("hasBaselineDevice", device != null);
        u.put("deviceBoundAt", device == null ? null : device.get("confirmed_at"));
        return u;
    }

    @GetMapping("/auth/me")
    Map<String, Object> me(HttpServletRequest req) {
        String uid = SecurityUtil.uid(req);
        Map<String, Object> u = SecurityUtil.one(
                db,
                """
                SELECT u.id, u.email, u.full_name, u.role, u.school_id, u.student_no, u.gender,
                       u.date_of_birth, u.status, u.must_change_password, s.code AS school_code, s.name AS school_name
                FROM app_user u
                LEFT JOIN school s ON s.id = u.school_id
                WHERE u.id = ?::uuid
                """,
                uid);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Không tìm thấy tài khoản");
        Map<String, Object> device = SecurityUtil.one(
                db, "SELECT confirmed_at FROM device_registry WHERE student_id = ?::uuid", uid);
        u.put("hasBaselineDevice", device != null);
        u.put("deviceBoundAt", device == null ? null : device.get("confirmed_at"));
        return u;
    }

    @PostMapping("/auth/logout")
    Map<String, String> logout(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            db.update("DELETE FROM auth_token WHERE token_hash = ?", SecurityUtil.sha256(auth.substring(7).trim()));
        }
        return Map.of("status", "ok");
    }

    @PostMapping("/auth/change-password")
    Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest req) {
        String uid = SecurityUtil.uid(req);
        Map<String, Object> u = SecurityUtil.one(db, "SELECT password_hash FROM app_user WHERE id = ?::uuid", uid);
        if (u == null || !encoder.matches(body.currentPassword(), String.valueOf(u.get("password_hash")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng");
        }
        if (body.currentPassword().equals(body.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mới phải khác mật khẩu hiện tại");
        }
        db.update(
                "UPDATE app_user SET password_hash = ?, must_change_password = false WHERE id = ?::uuid",
                encoder.encode(body.newPassword()), uid);
        return Map.of("status", "ok", "message", "Đổi mật khẩu thành công");
    }

    @GetMapping("/auth/activation-info")
    Map<String, Object> activationInfo(@RequestParam("token") String rawToken) {
        Map<String, Object> row = loadValidActivationToken(rawToken, false);
        return Map.of(
                "schoolCode", row.get("school_code"),
                "schoolName", row.get("school_name"),
                "adminName", row.get("full_name"),
                "email", row.get("email"),
                "expiresAt", row.get("expires_at").toString());
    }

    @PostMapping("/auth/activate")
    @Transactional
    Map<String, String> activate(@Valid @RequestBody ActivateRequest body) {
        Map<String, Object> row = loadValidActivationToken(body.token(), true);
        db.update(
                "UPDATE app_user SET password_hash = ?, status = 'ACTIVE', email_verified = true, must_change_password = false WHERE id = ?",
                encoder.encode(body.newPassword()), row.get("user_id"));
        db.update("UPDATE activation_token SET used_at = now() WHERE id = ?", row.get("id"));
        db.update("DELETE FROM auth_token WHERE user_id = ?", row.get("user_id"));
        return Map.of("status", "ok", "message", "Kích hoạt tài khoản Admin trường thành công");
    }

    private Map<String, Object> loadValidActivationToken(String rawToken, boolean lock) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu mã kích hoạt");
        }
        String sql = """
                SELECT t.id, t.user_id, t.school_id, t.expires_at, t.used_at, t.invalidated_at,
                       u.email, u.full_name, s.code AS school_code, s.name AS school_name
                FROM activation_token t
                JOIN app_user u ON u.id = t.user_id
                JOIN school s ON s.id = t.school_id
                WHERE t.token_hash = ?
                """ + (lock ? " FOR UPDATE OF t" : "");
        Map<String, Object> row = SecurityUtil.one(db, sql, SecurityUtil.sha256(rawToken.trim()));
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Liên kết kích hoạt không hợp lệ");
        if (row.get("invalidated_at") != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Liên kết kích hoạt cũ đã bị vô hiệu hóa");
        }
        if (row.get("used_at") != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Liên kết kích hoạt này đã được sử dụng");
        }
        if (!Instant.now().isBefore(SecurityUtil.toInstant(row.get("expires_at")))) {
            throw new ResponseStatusException(HttpStatus.GONE, "Liên kết kích hoạt đã hết hạn");
        }
        return row;
    }

    @PostMapping("/auth/lecturer/register")
    @Transactional
    Map<String, Object> registerLecturer(@Valid @RequestBody LecturerRegisterRequest body) {
        String gender = body.gender().trim().toUpperCase(Locale.ROOT);
        if (!VALID_GENDERS.contains(gender)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giới tính phải là MALE, FEMALE hoặc OTHER");
        }
        LocalDate dob;
        try {
            dob = LocalDate.parse(body.dateOfBirth().trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngày sinh phải đúng định dạng YYYY-MM-DD");
        }
        if (!dob.isBefore(LocalDate.now().minusYears(16))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngày sinh giảng viên không hợp lệ");
        }
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> existing = SecurityUtil.one(
                db, "SELECT id, role, status FROM app_user WHERE lower(email) = ?", email);
        UUID userId;
        if (existing != null) {
            if (!"LECTURER".equals(existing.get("role")) || !"INACTIVE".equals(existing.get("status"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được đăng ký tài khoản khác");
            }
            userId = UUID.fromString(existing.get("id").toString());
            db.update(
                    "UPDATE app_user SET full_name = ?, gender = ?, date_of_birth = ?, password_hash = ? WHERE id = ?",
                    body.fullName().trim(), gender, Date.valueOf(dob), encoder.encode(body.password()), userId);
        } else {
            userId = UUID.randomUUID();
            db.update(
                    "INSERT INTO app_user(id, school_id, email, full_name, gender, date_of_birth, password_hash, role, status, email_verified, must_change_password) VALUES(?,NULL,?,?,?,?,?,'LECTURER','INACTIVE',false,false)",
                    userId, email, body.fullName().trim(), gender, Date.valueOf(dob), encoder.encode(body.password()));
        }

        return issueOtp(userId, email, "LECTURER_REGISTER",
                "Mã OTP kích hoạt tài khoản Giảng viên",
                "Mã OTP kích hoạt tài khoản Giảng viên của bạn là: %s (có hiệu lực trong 15 phút).");
    }

    @PostMapping("/auth/lecturer/resend-otp")
    @Transactional
    Map<String, Object> resendLecturerOtp(@Valid @RequestBody ResendOtpRequest body) {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> u = SecurityUtil.one(
                db, "SELECT id, role, status FROM app_user WHERE lower(email) = ?", email);
        if (u == null || !"LECTURER".equals(u.get("role"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản giảng viên chờ kích hoạt");
        }
        if (!"INACTIVE".equals(u.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tài khoản giảng viên đã được kích hoạt hoặc bị khóa");
        }
        Map<String, Object> latest = SecurityUtil.one(
                db,
                "SELECT created_at FROM otp_token WHERE lower(email) = ? AND purpose = 'LECTURER_REGISTER' ORDER BY created_at DESC LIMIT 1",
                email);
        if (latest != null && SecurityUtil.toInstant(latest.get("created_at")).plusSeconds(30).isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Vui lòng chờ 30 giây trước khi gửi lại OTP");
        }
        return issueOtp(UUID.fromString(u.get("id").toString()), email, "LECTURER_REGISTER",
                "Mã OTP kích hoạt tài khoản Giảng viên",
                "Mã OTP mới của bạn là: %s (có hiệu lực trong 15 phút).");
    }

    @PostMapping("/auth/lecturer/verify-otp")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    Map<String, String> verifyLecturerOtp(@Valid @RequestBody VerifyOtpRequest body) {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> u = SecurityUtil.one(
                db, "SELECT id, role, status FROM app_user WHERE lower(email) = ?", email);
        if (u == null || !"LECTURER".equals(u.get("role"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản giảng viên");
        }
        if ("ACTIVE".equals(u.get("status"))) {
            return Map.of("status", "ok", "message", "Tài khoản đã được kích hoạt trước đó");
        }
        if (!"INACTIVE".equals(u.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa");
        }
        verifyOtpInternal(email, "LECTURER_REGISTER", body.otp().trim());
        db.update("UPDATE app_user SET status = 'ACTIVE', email_verified = true WHERE id = ?", u.get("id"));
        return Map.of("status", "ok", "message", "Kích hoạt tài khoản giảng viên thành công");
    }

    @PostMapping("/auth/forgot-password")
    @Transactional
    Map<String, Object> forgotPassword(@RequestBody ForgotPasswordRequest body) {
        if ("SYSTEM_ADMIN".equals(body.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Khôi phục mật khẩu Quản trị hệ thống nằm ngoài phạm vi hỗ trợ");
        }
        Map<String, Object> u = resolveUserForPasswordReset(body.role(), body.email(), body.schoolId(), body.studentNo());
        if ("SYSTEM_ADMIN".equals(u.get("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Khôi phục mật khẩu Quản trị hệ thống nằm ngoài phạm vi hỗ trợ");
        }
        String status = String.valueOf(u.get("status"));
        if ("LOCKED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản đang bị khóa; hãy liên hệ quản trị trường");
        }
        if (!"ACTIVE".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản chưa được kích hoạt");
        }
        String targetEmail = u.get("recovery_email") != null && !String.valueOf(u.get("recovery_email")).isBlank()
                ? String.valueOf(u.get("recovery_email"))
                : String.valueOf(u.get("email"));
        if (targetEmail == null || targetEmail.isBlank() || "null".equals(targetEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản chưa có email khôi phục");
        }
        Map<String, Object> res = issueOtp(
                UUID.fromString(u.get("id").toString()),
                targetEmail.toLowerCase(Locale.ROOT),
                "PASSWORD_RESET",
                "Mã khôi phục mật khẩu Campus Attendance",
                "Mã khôi phục mật khẩu của bạn là: %s (có hiệu lực trong 15 phút).");
        res.put("maskedEmail", SecurityUtil.maskEmail(targetEmail));
        return res;
    }

    @PostMapping("/auth/reset-password")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        if ("SYSTEM_ADMIN".equals(body.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Khôi phục mật khẩu Quản trị hệ thống nằm ngoài phạm vi hỗ trợ");
        }
        Map<String, Object> u = resolveUserForPasswordReset(body.role(), body.email(), body.schoolId(), body.studentNo());
        if ("SYSTEM_ADMIN".equals(u.get("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Khôi phục mật khẩu Quản trị hệ thống nằm ngoài phạm vi hỗ trợ");
        }
        String targetEmail = u.get("recovery_email") != null && !String.valueOf(u.get("recovery_email")).isBlank()
                ? String.valueOf(u.get("recovery_email"))
                : String.valueOf(u.get("email"));
        verifyOtpInternal(targetEmail.toLowerCase(Locale.ROOT), "PASSWORD_RESET", body.otp().trim());
        db.update(
                "UPDATE app_user SET password_hash = ?, must_change_password = false WHERE id = ?",
                encoder.encode(body.newPassword()), u.get("id"));
        db.update("DELETE FROM auth_token WHERE user_id = ?", u.get("id"));
        return Map.of("status", "ok", "message", "Đặt lại mật khẩu thành công");
    }

    @GetMapping("/auth/dev-outbox")
    List<Map<String, Object>> devOutbox() {
        if (!mailService.isExposeDevMail()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không khả dụng ở chế độ production");
        }
        return db.queryForList(
                "SELECT id, recipient_email, subject, body, delivered, error_message, created_at FROM dev_mail_outbox ORDER BY created_at DESC LIMIT 15");
    }

    private Map<String, Object> resolveUserForPasswordReset(String role, String email, String schoolId, String studentNo) {
        Map<String, Object> u = null;
        if ("STUDENT".equals(role) && schoolId != null && !schoolId.isBlank() && studentNo != null && !studentNo.isBlank()) {
            u = SecurityUtil.one(
                    db,
                    "SELECT id, email, recovery_email, role, status FROM app_user WHERE role = 'STUDENT' AND school_id::text = ? AND student_no = ? LIMIT 1",
                    schoolId.trim(), studentNo.trim());
        } else if (email != null && !email.isBlank()) {
            u = SecurityUtil.one(
                    db,
                    "SELECT id, email, recovery_email, role, status FROM app_user WHERE lower(email) = lower(?) OR lower(recovery_email) = lower(?) LIMIT 1",
                    email.trim(), email.trim());
        }
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản tương ứng");
        }
        if (role != null && !role.isBlank() && !role.equals(u.get("role"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vai trò tài khoản không khớp");
        }
        return u;
    }

    private Map<String, Object> issueOtp(UUID userId, String email, String purpose, String subject, String bodyTemplate) {
        db.update(
                "UPDATE otp_token SET used_at = now() WHERE lower(email) = ? AND purpose = ? AND used_at IS NULL",
                email, purpose);
        String otp = SecurityUtil.randomOtp6();
        Instant expires = Instant.now().plus(Duration.ofMinutes(15));
        db.update(
                "INSERT INTO otp_token(user_id, email, purpose, code_hash, expires_at) VALUES(?,?,?,?,?)",
                userId, email, purpose, SecurityUtil.sha256(purpose + ":" + email + ":" + otp), Timestamp.from(expires));
        MailService.DeliveryResult delivery = mailService.send(email, subject, String.format(bodyTemplate, otp), otp);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("email", email);
        res.put("expiresAt", expires.toString());
        res.put("mailDelivered", delivery.delivered());
        if (delivery.devSecretPreview() != null) {
            res.put("devOtp", delivery.devSecretPreview());
        }
        res.put("message", delivery.delivered()
                ? "Đã gửi mã OTP tới email " + SecurityUtil.maskEmail(email)
                : "Chưa cấu hình SMTP; xem mã OTP trong hộp thư dev cục bộ");
        return res;
    }

    private void verifyOtpInternal(String email, String purpose, String rawOtp) {
        Map<String, Object> row = SecurityUtil.one(
                db,
                """
                SELECT id, code_hash, attempts, expires_at
                FROM otp_token
                WHERE lower(email) = ? AND purpose = ? AND used_at IS NULL
                ORDER BY created_at DESC LIMIT 1
                FOR UPDATE
                """,
                email, purpose);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có mã OTP hợp lệ; hãy gửi lại mã");
        }
        if (!Instant.now().isBefore(SecurityUtil.toInstant(row.get("expires_at")))) {
            throw new ResponseStatusException(HttpStatus.GONE, "Mã OTP đã hết hạn");
        }
        int attempts = ((Number) row.get("attempts")).intValue();
        if (attempts >= 5) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Đã vượt quá số lần thử OTP; hãy yêu cầu mã mới");
        }
        String expected = SecurityUtil.sha256(purpose + ":" + email + ":" + rawOtp);
        if (!expected.equals(row.get("code_hash"))) {
            db.update("UPDATE otp_token SET attempts = attempts + 1 WHERE id = ?", row.get("id"));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP không đúng");
        }
        db.update("UPDATE otp_token SET used_at = now() WHERE id = ?", row.get("id"));
    }
}
