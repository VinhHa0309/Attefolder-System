package vn.attendance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
class AdminController {
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder;
    private final MailService mailService;
    private final String webUrl;

    AdminController(
            JdbcTemplate db,
            BCryptPasswordEncoder encoder,
            MailService mailService,
            @Value("${app.web-url}") String webUrl) {
        this.db = db;
        this.encoder = encoder;
        this.mailService = mailService;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    record CreateSchoolRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String adminName,
            @Email @NotBlank String recoveryEmail) {}

    record SchoolSettingsRequest(
            @Min(5) @Max(1000) int radiusMeters,
            @Min(5) @Max(1000) int radiusMinMeters,
            @Min(5) @Max(3000) int radiusMaxMeters,
            @Min(1) @Max(500) int gpsAccuracyMeters,
            @Min(1) @Max(500) int gpsAccuracyMinMeters,
            @Min(1) @Max(2000) int gpsAccuracyMaxMeters,
            @Min(50) @Max(10000) int maxDeviationMeters,
            @Min(5) @Max(500) int clusterMadMeters,
            @Min(3) @Max(100) int minClusterRecords,
            @Min(1) @Max(168) int reviewHours,
            @Min(30) @Max(14400) int defaultSessionDurationSeconds,
            @Min(5) @Max(300) int codeIntervalSeconds,
            List<String> allowedCidrs,
            String defaultStudentPassword) {}

    record LockRequest(@NotBlank String reason, boolean confirmActiveSession) {}
    record ReasonRequest(String reason, String temporaryPassword) {}

    @GetMapping("/schools")
    List<Map<String, Object>> schools(HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SYSTEM_ADMIN");
        return db.queryForList(
                """
                SELECT s.id, s.code, s.name, COALESCE(s.recovery_email, u.email) AS recovery_email,
                       u.full_name AS admin_name, u.status AS admin_status, s.created_at
                FROM school s
                LEFT JOIN app_user u ON u.id = s.admin_user_id
                ORDER BY s.name
                """);
    }

    @PostMapping("/system/schools")
    @Transactional
    Map<String, Object> createSchool(@Valid @RequestBody CreateSchoolRequest body, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SYSTEM_ADMIN");
        String code = body.code().trim().toUpperCase(Locale.ROOT);
        String email = body.recoveryEmail().trim().toLowerCase(Locale.ROOT);
        if (SecurityUtil.one(db, "SELECT id FROM school WHERE upper(code) = ?", code) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã trường đã tồn tại");
        }
        if (SecurityUtil.one(db, "SELECT id FROM app_user WHERE lower(email) = ?", email) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email khôi phục đã được gắn với tài khoản khác");
        }

        UUID userId = UUID.randomUUID();
        UUID schoolId = UUID.randomUUID();
        db.update(
                "INSERT INTO app_user(id, email, recovery_email, full_name, password_hash, role, status, must_change_password, email_verified) VALUES(?,?,?,?,?,'SCHOOL_ADMIN','INACTIVE',false,false)",
                userId, email, email, body.adminName().trim(), "!PENDING_ACTIVATION!");
        db.update(
                "INSERT INTO school(id, code, name, admin_user_id, recovery_email) VALUES(?,?,?,?,?)",
                schoolId, code, body.name().trim(), userId, email);
        db.update("UPDATE app_user SET school_id = ? WHERE id = ?", schoolId, userId);

        Map<String, Object> activation = issueActivationLink(userId, schoolId, code, body.name().trim(), email);
        db.update(
                "INSERT INTO account_audit_log(school_id, target_user_id, actor_id, action, reason) VALUES(?,?,?,'CREATE_SCHOOL',?)",
                schoolId, userId, UUID.fromString(SecurityUtil.uid(req)), "Khởi tạo trường " + code);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", schoolId.toString());
        res.put("code", code);
        res.put("name", body.name().trim());
        res.put("recoveryEmail", email);
        res.put("adminStatus", "INACTIVE");
        res.putAll(activation);
        return res;
    }

    @PostMapping("/system/schools/{schoolId}/reissue-admin")
    @Transactional
    Map<String, Object> reissueAdmin(@PathVariable String schoolId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SYSTEM_ADMIN");
        Map<String, Object> s = SecurityUtil.one(
                db,
                """
                SELECT s.id, s.code, s.name, s.admin_user_id, COALESCE(s.recovery_email, u.email) AS recovery_email
                FROM school s
                JOIN app_user u ON u.id = s.admin_user_id
                WHERE s.id::text = ?
                """,
                schoolId);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy trường");

        UUID adminId = UUID.fromString(s.get("admin_user_id").toString());
        UUID sid = UUID.fromString(s.get("id").toString());
        String recoveryEmail = String.valueOf(s.get("recovery_email"));

        db.update("UPDATE activation_token SET invalidated_at = now() WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL", adminId);
        db.update("UPDATE app_user SET status = 'INACTIVE', password_hash = '!PENDING_ACTIVATION!', email_verified = false WHERE id = ?", adminId);
        db.update("DELETE FROM auth_token WHERE user_id = ?", adminId);

        Map<String, Object> activation = issueActivationLink(adminId, sid, String.valueOf(s.get("code")), String.valueOf(s.get("name")), recoveryEmail);
        db.update(
                "INSERT INTO account_audit_log(school_id, target_user_id, actor_id, action, reason) VALUES(?,?,?,'REISSUE_ADMIN',?)",
                sid, adminId, UUID.fromString(SecurityUtil.uid(req)), "Cấp lại liên kết kích hoạt Admin trường");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("schoolId", sid.toString());
        res.put("recoveryEmail", recoveryEmail);
        res.put("adminStatus", "INACTIVE");
        res.putAll(activation);
        return res;
    }

    private Map<String, Object> issueActivationLink(UUID userId, UUID schoolId, String schoolCode, String schoolName, String email) {
        String rawToken = SecurityUtil.randomToken(32);
        Instant expires = Instant.now().plus(Duration.ofHours(24));
        db.update(
                "INSERT INTO activation_token(user_id, school_id, token_hash, expires_at) VALUES(?,?,?,?)",
                userId, schoolId, SecurityUtil.sha256(rawToken), Timestamp.from(expires));
        String link = webUrl + "/?activate=" + rawToken;
        String body = String.format(
                "Trường %s (%s) đã được khởi tạo trên hệ thống điểm danh.%nMở liên kết một lần sau trong vòng 24 giờ để đặt mật khẩu và kích hoạt tài khoản Admin:%n%s",
                schoolName, schoolCode, link);
        MailService.DeliveryResult delivery = mailService.send(email, "Kích hoạt tài khoản Admin - " + schoolCode, body, link);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("expiresAt", expires.toString());
        res.put("mailDelivered", delivery.delivered());
        if (delivery.devSecretPreview() != null) {
            res.put("activationLink", delivery.devSecretPreview());
        }
        return res;
    }

    @GetMapping("/school/settings")
    Map<String, Object> settings(HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        return SecurityUtil.one(
                db,
                """
                SELECT code, name, recovery_email AS recoveryEmail,
                       radius_m AS radiusMeters, radius_min_m AS radiusMinMeters, radius_max_m AS radiusMaxMeters,
                       gps_accuracy_m AS gpsAccuracyMeters, gps_accuracy_min_m AS gpsAccuracyMinMeters, gps_accuracy_max_m AS gpsAccuracyMaxMeters,
                       max_deviation_m AS maxDeviationMeters, cluster_mad_m AS clusterMadMeters,
                       min_cluster_records AS minClusterRecords, review_hours AS reviewHours,
                       default_session_duration_s AS defaultSessionDurationSeconds,
                       code_interval_seconds AS codeIntervalSeconds,
                       default_student_password AS defaultStudentPassword,
                       array_to_string(allowed_cidrs, ',') AS allowedCidrs, timezone
                FROM school WHERE id::text = ?
                """,
                SecurityUtil.schoolId(req));
    }

    @PutMapping("/school/settings")
    Map<String, Object> updateSettings(@Valid @RequestBody SchoolSettingsRequest b, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        if (b.radiusMinMeters() > b.radiusMeters() || b.radiusMeters() > b.radiusMaxMeters()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bán kính mặc định phải nằm trong khoảng [min, max]");
        }
        if (b.gpsAccuracyMinMeters() > b.gpsAccuracyMeters() || b.gpsAccuracyMeters() > b.gpsAccuracyMaxMeters()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sai số GPS mặc định phải nằm trong khoảng [min, max]");
        }
        String defaultStudPass = b.defaultStudentPassword() == null ? null : b.defaultStudentPassword().trim();
        if (defaultStudPass != null && !defaultStudPass.isEmpty() && (defaultStudPass.length() < 6 || defaultStudPass.length() > 64)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mặc định sinh viên phải từ 6 đến 64 ký tự");
        }
        if (defaultStudPass != null && defaultStudPass.isEmpty()) defaultStudPass = null;
        String cidrs = b.allowedCidrs() == null ? "" : String.join(",", b.allowedCidrs()).trim();
        if (cidrs.length() > 4000 || cidrs.contains(" ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh sách CIDR không hợp lệ");
        }
        try {
            return SecurityUtil.one(
                    db,
                    """
                    UPDATE school
                    SET radius_m = ?, radius_min_m = ?, radius_max_m = ?,
                        gps_accuracy_m = ?, gps_accuracy_min_m = ?, gps_accuracy_max_m = ?,
                        max_deviation_m = ?, cluster_mad_m = ?, min_cluster_records = ?,
                        review_hours = ?, default_session_duration_s = ?, code_interval_seconds = ?,
                        default_student_password = ?,
                        allowed_cidrs = CASE WHEN ? = '' THEN '{}'::cidr[] ELSE array(SELECT trim(x)::cidr FROM unnest(string_to_array(?, ',')) x) END
                    WHERE id::text = ?
                    RETURNING code, name, recovery_email AS recoveryEmail,
                              radius_m AS radiusMeters, radius_min_m AS radiusMinMeters, radius_max_m AS radiusMaxMeters,
                              gps_accuracy_m AS gpsAccuracyMeters, gps_accuracy_min_m AS gpsAccuracyMinMeters, gps_accuracy_max_m AS gpsAccuracyMaxMeters,
                              max_deviation_m AS maxDeviationMeters, cluster_mad_m AS clusterMadMeters,
                              min_cluster_records AS minClusterRecords, review_hours AS reviewHours,
                              default_session_duration_s AS defaultSessionDurationSeconds,
                              code_interval_seconds AS codeIntervalSeconds,
                              default_student_password AS defaultStudentPassword,
                              array_to_string(allowed_cidrs, ',') AS allowedCidrs, timezone
                    """,
                    b.radiusMeters(), b.radiusMinMeters(), b.radiusMaxMeters(),
                    b.gpsAccuracyMeters(), b.gpsAccuracyMinMeters(), b.gpsAccuracyMaxMeters(),
                    b.maxDeviationMeters(), b.clusterMadMeters(), b.minClusterRecords(),
                    b.reviewHours(), b.defaultSessionDurationSeconds(), b.codeIntervalSeconds(),
                    defaultStudPass, cidrs, cidrs, SecurityUtil.schoolId(req));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Một hoặc nhiều CIDR không hợp lệ");
        }
    }

    @PostMapping("/school/students/import")
    @Transactional
    Map<String, Object> importStudents(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "defaultPassword", required = false) String defaultPassword,
            HttpServletRequest req) throws IOException {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        String school = SecurityUtil.schoolId(req);
        if (school == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản chưa thuộc trường");
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tệp rỗng");

        UUID schoolUuid = UUID.fromString(school);
        Map<String, Object> schoolRow = SecurityUtil.one(
                db, "SELECT code, default_student_password FROM school WHERE id = ?", schoolUuid);
        String rawCode = schoolRow == null ? "SV" : String.valueOf(schoolRow.get("code"));
        String schoolCode = rawCode.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String initialPassword = resolveDefaultStudentPassword(
                defaultPassword,
                schoolRow == null ? null : (String) schoolRow.get("default_student_password"),
                rawCode);
        String encodedInitialPassword = encoder.encode(initialPassword);
        UUID batchId = UUID.randomUUID();
        String batchCode = "IMP-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                + "-" + batchId.toString().substring(0, 4).toUpperCase(Locale.ROOT);

        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> credentials = new ArrayList<>();
        Set<String> seenStudentNos = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();
        int created = 0, skipped = 0, totalRows = 0, generatedEmails = 0;

        db.update(
                "INSERT INTO identity_import_batch(id, school_id, imported_by, batch_code, total_rows, created_count, updated_count, error_count) VALUES(?,?,?,?,0,0,0,0)",
                batchId, schoolUuid, UUID.fromString(SecurityUtil.uid(req)), batchCode);

        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            ExcelImportHelper.DetectedTable table = ExcelImportHelper.detectTable(sheet, fmt, true);
            if (table == null || table.mssvCol() == null || (table.fullNameCol() == null && table.givenNameCol() == null)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Không tìm thấy cột MSSV / Mã SV và Họ tên trong 40 dòng đầu của file Excel (chấp nhận MSSV, Mã SV, Họ và tên hoặc Họ đệm + Tên, và tùy chọn Email).");
            }

            for (int i = table.dataStartRowIdx(); i <= sheet.getLastRowNum(); i++) {
                Row rr = sheet.getRow(i);
                if (rr == null) continue;

                String studentNo = ExcelImportHelper.readCellText(rr.getCell(table.mssvCol()), fmt);
                String fullName = ExcelImportHelper.extractFullName(rr, table, fmt);
                String mail = table.emailCol() == null ? "" : ExcelImportHelper.readCellText(rr.getCell(table.emailCol()), fmt).toLowerCase(Locale.ROOT);
                Cell dobCell = table.dobCol() == null ? null : rr.getCell(table.dobCol());
                String dobRaw = dobCell == null ? "" : ExcelImportHelper.readCellText(dobCell, fmt);

                if (studentNo.isBlank() && fullName.isBlank() && mail.isBlank()) continue;
                if (ExcelImportHelper.isFooterOrBannerText(studentNo) || ExcelImportHelper.isFooterOrBannerText(fullName)) {
                    break;
                }
                if (!ExcelImportHelper.isLikelyStudentNo(studentNo)) {
                    if (fullName.isBlank()) continue;
                    totalRows++;
                    errors.add(Map.of("row", i + 1, "error", "MSSV không hợp lệ: " + studentNo));
                    continue;
                }

                totalRows++;
                if (!seenStudentNos.add(studentNo.toUpperCase(Locale.ROOT))) {
                    errors.add(Map.of("row", i + 1, "error", "Trùng MSSV trong cùng file Excel: " + studentNo));
                    continue;
                }
                if (fullName.isBlank()) {
                    errors.add(Map.of("row", i + 1, "error", "Thiếu họ tên cho MSSV " + studentNo));
                    continue;
                }
                if (mail.isBlank()) {
                    String safeNo = studentNo.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
                    mail = safeNo + "@" + schoolCode + ".edu.vn";
                    generatedEmails++;
                } else if (!mail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                    errors.add(Map.of("row", i + 1, "error", "Email không hợp lệ: " + mail));
                    continue;
                }
                if (!seenEmails.add(mail)) {
                    errors.add(Map.of("row", i + 1, "error", "Trùng Email trong cùng file Excel: " + mail));
                    continue;
                }

                LocalDate dob = null;
                if (!dobRaw.isBlank()) {
                    dob = ExcelImportHelper.readDateCell(dobCell, fmt);
                    if (dob == null) {
                        errors.add(Map.of("row", i + 1, "error", "Ngày sinh không đọc được (hỗ trợ YYYY-MM-DD hoặc dd/MM/yyyy): " + dobRaw));
                        continue;
                    }
                }

                Map<String, Object> existing = SecurityUtil.one(
                        db, "SELECT id, email FROM app_user WHERE school_id = ? AND student_no = ?", schoolUuid, studentNo);
                if (existing != null) {
                    // Existing student account: skip updating account and keep baseline device untouched.
                    skipped++;
                    continue;
                }
                if (SecurityUtil.one(db, "SELECT id FROM app_user WHERE lower(email) = lower(?)", mail) != null) {
                    errors.add(Map.of("row", i + 1, "error", "Email trường đã được gán cho tài khoản khác: " + mail));
                    continue;
                }

                db.update(
                        "INSERT INTO identity_import_row(batch_id, school_id, student_no, full_name, school_email, date_of_birth) VALUES(?,?,?,?,?,?)",
                        batchId, schoolUuid, studentNo, fullName, mail, dob == null ? null : Date.valueOf(dob));

                String temp = initialPassword;
                db.update(
                        "INSERT INTO app_user(school_id, student_no, email, full_name, date_of_birth, password_hash, role, status, must_change_password, email_verified) VALUES(?,?,?,?,?,?,'STUDENT','ACTIVE',true,true)",
                        schoolUuid, studentNo, mail, fullName, dob == null ? null : Date.valueOf(dob), encodedInitialPassword);
                credentials.add(Map.of("studentNo", studentNo, "fullName", fullName, "email", mail, "temporaryPassword", temp));
                created++;
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không đọc được tệp Excel");
        }

        db.update(
                "UPDATE identity_import_batch SET total_rows = ?, created_count = ?, updated_count = ?, error_count = ? WHERE id = ?",
                totalRows, created, skipped, errors.size(), batchId);

        return Map.of(
                "batchCode", batchCode,
                "totalRows", totalRows,
                "created", created,
                "skipped", skipped,
                "updated", skipped,
                "generatedEmails", generatedEmails,
                "defaultPassword", initialPassword,
                "errors", errors,
                "initialCredentials", credentials);
    }

    @GetMapping("/school/students/imports")
    List<Map<String, Object>> importHistory(HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        return db.queryForList(
                """
                SELECT b.id, b.batch_code, b.total_rows, b.created_count, b.updated_count, b.error_count,
                       b.created_at, u.full_name AS importer_name
                FROM identity_import_batch b
                JOIN app_user u ON u.id = b.imported_by
                WHERE b.school_id::text = ?
                ORDER BY b.created_at DESC
                LIMIT 20
                """,
                SecurityUtil.schoolId(req));
    }

    @GetMapping(value = "/school/students/template.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ResponseEntity<byte[]> studentTemplate(HttpServletRequest req) throws IOException {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        return ClassController.xlsxTemplate("Danh sách sinh viên", new String[]{"MSSV", "Họ tên", "Email trường", "Ngày sinh"}, "students-import-template.xlsx");
    }

    @GetMapping("/school/accounts")
    List<Map<String, Object>> accounts(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        String school = SecurityUtil.schoolId(req);
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String roleFilter = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        String statusFilter = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);

        return db.queryForList(
                """
                WITH school_users AS (
                    SELECT u.id, u.role, u.student_no, u.full_name, u.email, u.status AS effective_status,
                           u.must_change_password, u.created_at,
                           dr.confirmed_at AS device_bound_at,
                           (SELECT count(*) FROM attendance_record ar
                            JOIN attendance_session s ON s.id = ar.session_id
                            JOIN course_class c ON c.id = s.class_id
                            WHERE ar.student_id = u.id AND c.school_id::text = ? AND cardinality(ar.flags) > 0) AS violation_count,
                           NULL::text AS lock_reason
                    FROM app_user u
                    LEFT JOIN device_registry dr ON dr.student_id = u.id
                    WHERE u.role = 'STUDENT' AND u.school_id::text = ?
                    UNION ALL
                    SELECT DISTINCT u.id, u.role, NULL::varchar AS student_no, u.full_name, u.email,
                           COALESCE(sla.status, u.status) AS effective_status,
                           u.must_change_password, u.created_at,
                           NULL::timestamptz AS device_bound_at,
                           0::bigint AS violation_count,
                           sla.lock_reason
                    FROM app_user u
                    JOIN course_class c ON c.lecturer_id = u.id AND c.school_id::text = ?
                    LEFT JOIN school_lecturer_access sla ON sla.school_id = c.school_id AND sla.lecturer_id = u.id
                    WHERE u.role = 'LECTURER'
                )
                SELECT * FROM school_users
                WHERE (? = '' OR role = ?)
                  AND (? = '' OR effective_status = ?)
                  AND (? = '' OR lower(full_name) LIKE ? OR lower(email) LIKE ? OR lower(COALESCE(student_no, '')) LIKE ?)
                ORDER BY role, student_no NULLS LAST, full_name
                """,
                school, school, school,
                roleFilter, roleFilter,
                statusFilter, statusFilter,
                query, "%" + query + "%", "%" + query + "%", "%" + query + "%");
    }

    @PostMapping("/school/accounts/{userId}/lock")
    @Transactional
    ResponseEntity<Map<String, Object>> lockAccount(
            @PathVariable String userId,
            @Valid @RequestBody LockRequest body,
            HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        String school = SecurityUtil.schoolId(req);
        Map<String, Object> target = loadScopedAccount(userId, school);
        String targetRole = String.valueOf(target.get("role"));

        int openCount = countActiveSessionsForAccount(userId, targetRole, school, String.valueOf(target.get("student_no")));
        if (openCount > 0 && !body.confirmActiveSession()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "requiresConfirmation", true,
                    "activeSessions", openCount,
                    "error", "Tài khoản đang liên quan tới " + openCount + " phiên điểm danh đang mở. Xác nhận khóa?"));
        }

        UUID schoolUuid = UUID.fromString(school);
        UUID actorUuid = UUID.fromString(SecurityUtil.uid(req));
        UUID targetUuid = UUID.fromString(userId);
        if ("STUDENT".equals(targetRole)) {
            db.update("UPDATE app_user SET status = 'LOCKED' WHERE id = ?", targetUuid);
            db.update("DELETE FROM auth_token WHERE user_id = ?", targetUuid);
        } else {
            db.update(
                    """
                    INSERT INTO school_lecturer_access(school_id, lecturer_id, status, lock_reason, updated_by, updated_at)
                    VALUES(?,?,'LOCKED',?,?,now())
                    ON CONFLICT (school_id, lecturer_id)
                    DO UPDATE SET status = 'LOCKED', lock_reason = excluded.lock_reason, updated_by = excluded.updated_by, updated_at = now()
                    """,
                    schoolUuid, targetUuid, body.reason().trim(), actorUuid);
        }
        db.update(
                "INSERT INTO account_audit_log(school_id, target_user_id, actor_id, action, reason) VALUES(?,?,?,'LOCK_ACCOUNT',?)",
                schoolUuid, targetUuid, actorUuid, body.reason().trim());
        return ResponseEntity.ok(Map.of("status", "LOCKED", "userId", userId));
    }

    @PostMapping("/school/accounts/{userId}/unlock")
    @Transactional
    Map<String, Object> unlockAccount(
            @PathVariable String userId,
            @RequestBody(required = false) ReasonRequest body,
            HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        String school = SecurityUtil.schoolId(req);
        Map<String, Object> target = loadScopedAccount(userId, school);
        UUID schoolUuid = UUID.fromString(school);
        UUID actorUuid = UUID.fromString(SecurityUtil.uid(req));
        UUID targetUuid = UUID.fromString(userId);
        if ("STUDENT".equals(target.get("role"))) {
            db.update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", targetUuid);
        } else {
            db.update(
                    """
                    INSERT INTO school_lecturer_access(school_id, lecturer_id, status, lock_reason, updated_by, updated_at)
                    VALUES(?,?,'ACTIVE',NULL,?,now())
                    ON CONFLICT (school_id, lecturer_id)
                    DO UPDATE SET status = 'ACTIVE', lock_reason = NULL, updated_by = excluded.updated_by, updated_at = now()
                    """,
                    schoolUuid, targetUuid, actorUuid);
        }
        String reason = body == null || body.reason() == null || body.reason().isBlank() ? "Mở khóa tài khoản" : body.reason().trim();
        db.update(
                "INSERT INTO account_audit_log(school_id, target_user_id, actor_id, action, reason) VALUES(?,?,?,'UNLOCK_ACCOUNT',?)",
                schoolUuid, targetUuid, actorUuid, reason);
        return Map.of("status", "ACTIVE", "userId", userId);
    }

    @PostMapping("/school/accounts/{userId}/reset-password")
    @Transactional
    Map<String, Object> resetStudentPassword(
            @PathVariable String userId,
            @RequestBody(required = false) ReasonRequest body,
            HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        String school = SecurityUtil.schoolId(req);
        Map<String, Object> target = loadScopedAccount(userId, school);
        if (!"STUDENT".equals(target.get("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin trường chỉ cấp lại mật khẩu cho sinh viên thuộc trường mình");
        }
        Map<String, Object> sch = SecurityUtil.one(db, "SELECT code, default_student_password FROM school WHERE id = ?::uuid", school);
        String temp = resolveDefaultStudentPassword(
                body == null ? null : body.temporaryPassword(),
                sch == null ? null : (String) sch.get("default_student_password"),
                sch == null ? "SV" : String.valueOf(sch.get("code")));
        UUID targetUuid = UUID.fromString(userId);
        db.update(
                "UPDATE app_user SET password_hash = ?, must_change_password = true WHERE id = ?",
                encoder.encode(temp), targetUuid);
        db.update("DELETE FROM auth_token WHERE user_id = ?", targetUuid);
        String reason = body == null || body.reason() == null || body.reason().isBlank() ? "Cấp lại mật khẩu sinh viên" : body.reason().trim();
        db.update(
                "INSERT INTO account_audit_log(school_id, target_user_id, actor_id, action, reason) VALUES(?,?,?,'RESET_PASSWORD',?)",
                UUID.fromString(school), targetUuid, UUID.fromString(SecurityUtil.uid(req)), reason);
        return Map.of("userId", userId, "studentNo", target.get("student_no"), "temporaryPassword", temp);
    }

    @GetMapping("/school/audit-logs")
    List<Map<String, Object>> auditLogs(HttpServletRequest req) {
        SecurityUtil.requireRole(req, "SCHOOL_ADMIN");
        return db.queryForList(
                """
                SELECT l.id, l.action, l.reason, l.created_at,
                       actor.full_name AS actor_name,
                       target.full_name AS target_name,
                       target.student_no AS target_student_no
                FROM account_audit_log l
                JOIN app_user actor ON actor.id = l.actor_id
                LEFT JOIN app_user target ON target.id = l.target_user_id
                WHERE l.school_id::text = ?
                ORDER BY l.created_at DESC
                LIMIT 50
                """,
                SecurityUtil.schoolId(req));
    }

    private Map<String, Object> loadScopedAccount(String userId, String schoolId) {
        Map<String, Object> target = SecurityUtil.one(
                db,
                """
                SELECT u.id, u.role, u.school_id, u.student_no, u.status
                FROM app_user u
                WHERE u.id::text = ?
                  AND (
                    (u.role = 'STUDENT' AND u.school_id::text = ?)
                    OR (u.role = 'LECTURER' AND EXISTS (SELECT 1 FROM course_class c WHERE c.lecturer_id = u.id AND c.school_id::text = ?))
                  )
                """,
                userId, schoolId, schoolId);
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản trong phạm vi trường");
        return target;
    }

    private int countActiveSessionsForAccount(String userId, String role, String schoolId, String studentNo) {
        Integer count;
        if ("LECTURER".equals(role)) {
            count = db.queryForObject(
                    """
                    SELECT count(*) FROM attendance_session s
                    JOIN course_class c ON c.id = s.class_id
                    WHERE s.lecturer_id::text = ? AND c.school_id::text = ? AND s.status = 'OPEN'
                    """,
                    Integer.class, userId, schoolId);
        } else {
            count = db.queryForObject(
                    """
                    SELECT count(*) FROM attendance_session s
                    JOIN course_class c ON c.id = s.class_id
                    JOIN class_whitelist cw ON cw.class_id = c.id
                    WHERE c.school_id::text = ? AND cw.student_no = ? AND s.status = 'OPEN'
                    """,
                    Integer.class, schoolId, studentNo);
        }
        return count == null ? 0 : count;
    }

    static String resolveDefaultStudentPassword(String requested, String schoolSaved, String schoolCode) {
        String candidate = requested != null && !requested.isBlank()
                ? requested.trim()
                : (schoolSaved != null && !schoolSaved.isBlank()
                        ? schoolSaved.trim()
                        : ((schoolCode == null || schoolCode.isBlank() ? "SV" : schoolCode.trim().toUpperCase(Locale.ROOT)) + "@1234321"));
        if (candidate.length() < 6 || candidate.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mặc định sinh viên phải từ 6 đến 64 ký tự");
        }
        return candidate;
    }
}
