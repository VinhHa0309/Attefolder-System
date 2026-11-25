package vn.attendance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api")
class ClassController {
    private final JdbcTemplate db;

    ClassController(JdbcTemplate db) {
        this.db = db;
    }

    record CreateClassRequest(@NotBlank String name, @NotBlank String code, @NotBlank String schoolId, String term, String schoolYear) {}
    record JoinStaticQrRequest(@NotBlank String staticToken) {}

    @PostMapping(value = "/classes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    Map<String, Object> createClassMultipart(
            @RequestParam("name") String name,
            @RequestParam("code") String code,
            @RequestParam("schoolId") String schoolId,
            @RequestParam(value = "term", required = false) String term,
            @RequestParam(value = "schoolYear", required = false) String schoolYear,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest req) throws IOException {
        return createClassInternal(new CreateClassRequest(name, code, schoolId, term, schoolYear), file, req);
    }

    @PostMapping(value = "/classes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    Map<String, Object> createClassJson(@Valid @RequestBody CreateClassRequest body, HttpServletRequest req) throws IOException {
        return createClassInternal(body, null, req);
    }

    private Map<String, Object> createClassInternal(CreateClassRequest body, MultipartFile file, HttpServletRequest req) throws IOException {
        SecurityUtil.requireRole(req, "LECTURER");
        if (body.name() == null || body.name().isBlank() || body.code() == null || body.code().isBlank() || body.schoolId() == null || body.schoolId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần nhập tên lớp, mã lớp và chọn trường");
        }
        String lecturerId = SecurityUtil.uid(req);
        Map<String, Object> school = SecurityUtil.one(db, "SELECT id, code, name FROM school WHERE id::text = ?", body.schoolId().trim());
        if (school == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy trường đã chọn");
        }
        Map<String, Object> lock = SecurityUtil.one(
                db,
                "SELECT status, lock_reason FROM school_lecturer_access WHERE school_id = ? AND lecturer_id::text = ?",
                school.get("id"), lecturerId);
        if (lock != null && "LOCKED".equals(lock.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Quyền giảng dạy của bạn tại trường này đã bị khóa");
        }

        UUID classId = UUID.randomUUID();
        String staticToken = SecurityUtil.randomToken(18);
        db.update(
                "INSERT INTO course_class(id, school_id, lecturer_id, name, code, term, school_year, static_qr_token) VALUES(?,?,?,?,?,?,?,?)",
                classId, school.get("id"), UUID.fromString(lecturerId),
                body.name().trim(), body.code().trim(),
                body.term() == null ? null : body.term().trim(),
                body.schoolYear() == null ? null : body.schoolYear().trim(),
                staticToken);

        int whitelistAdded = 0;
        if (file != null && !file.isEmpty()) {
            whitelistAdded = importWhitelistFromExcel(classId, file);
        }

        return Map.of(
                "id", classId.toString(),
                "name", body.name().trim(),
                "code", body.code().trim(),
                "schoolId", school.get("id").toString(),
                "schoolName", school.get("name"),
                "staticQrToken", staticToken,
                "whitelistCount", whitelistAdded);
    }

    @GetMapping("/classes")
    List<Map<String, Object>> classes(HttpServletRequest req) {
        String role = SecurityUtil.role(req);
        String uid = SecurityUtil.uid(req);
        String school = SecurityUtil.schoolId(req);
        if ("LECTURER".equals(role)) {
            return db.queryForList(
                    """
                    SELECT c.*, s.code AS school_code, s.name AS school_name,
                           (SELECT count(*) FROM class_whitelist cw WHERE cw.class_id = c.id) AS whitelist_count,
                           (SELECT count(*) FROM class_student cs WHERE cs.class_id = c.id) AS student_count,
                           (SELECT count(*) FROM attendance_session ss WHERE ss.class_id = c.id AND ss.status IN ('OPEN', 'RECONCILING')) AS pending_session_count,
                           (SELECT count(*) FROM attendance_session ss WHERE ss.class_id = c.id AND ss.status = 'FINALIZED') AS finalized_session_count
                    FROM course_class c
                    JOIN school s ON s.id = c.school_id
                    WHERE c.lecturer_id::text = ?
                    ORDER BY c.created_at DESC
                    """,
                    uid);
        }
        if ("SCHOOL_ADMIN".equals(role)) {
            return db.queryForList(
                    """
                    SELECT c.*, s.code AS school_code, s.name AS school_name, u.full_name AS lecturer_name,
                           (SELECT count(*) FROM class_whitelist cw WHERE cw.class_id = c.id) AS whitelist_count,
                           (SELECT count(*) FROM class_student cs WHERE cs.class_id = c.id) AS student_count
                    FROM course_class c
                    JOIN school s ON s.id = c.school_id
                    JOIN app_user u ON u.id = c.lecturer_id
                    WHERE c.school_id::text = ?
                    ORDER BY c.created_at DESC
                    """,
                    school);
        }
        if ("STUDENT".equals(role)) {
            return db.queryForList(
                    """
                    SELECT c.id, c.name, c.code, c.term, c.school_year, c.static_qr_token, s.code AS school_code, s.name AS school_name,
                           u.full_name AS lecturer_name,
                           (SELECT count(*) FROM attendance_session ss WHERE ss.class_id = c.id AND ss.status = 'FINALIZED') AS finalized_session_count,
                           (SELECT count(*) FROM attendance_record ar JOIN attendance_session ss ON ss.id = ar.session_id
                            WHERE ss.class_id = c.id AND ar.student_id::text = ? AND ar.result = 'PRESENT') AS present_count
                    FROM course_class c
                    JOIN class_student mine ON mine.class_id = c.id AND mine.student_id::text = ?
                    JOIN school s ON s.id = c.school_id
                    JOIN app_user u ON u.id = c.lecturer_id
                    WHERE c.active = true AND c.school_id::text = ?
                    ORDER BY c.name
                    """,
                    uid, uid, school);
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không đủ quyền");
    }

    @GetMapping("/classes/{classId}")
    Map<String, Object> classDetail(@PathVariable String classId, HttpServletRequest req) {
        String role = SecurityUtil.role(req);
        String uid = SecurityUtil.uid(req);
        Map<String, Object> clazz = SecurityUtil.one(
                db,
                """
                SELECT c.*, s.code AS school_code, s.name AS school_name,
                       s.radius_m, s.radius_min_m, s.radius_max_m,
                       s.gps_accuracy_m, s.gps_accuracy_min_m, s.gps_accuracy_max_m,
                       s.max_deviation_m, s.default_session_duration_s, s.code_interval_seconds,
                       u.full_name AS lecturer_name
                FROM course_class c
                JOIN school s ON s.id = c.school_id
                JOIN app_user u ON u.id = c.lecturer_id
                WHERE c.id::text = ?
                """,
                classId);
        if (clazz == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp học phần");

        if ("LECTURER".equals(role) && !uid.equals(String.valueOf(clazz.get("lecturer_id")))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp học phần");
        } else if ("SCHOOL_ADMIN".equals(role) && !Objects.equals(SecurityUtil.schoolId(req), String.valueOf(clazz.get("school_id")))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp học phần");
        } else if ("STUDENT".equals(role)) {
            if (!Boolean.TRUE.equals(clazz.get("active"))
                    || !Objects.equals(SecurityUtil.schoolId(req), String.valueOf(clazz.get("school_id")))
                    || SecurityUtil.one(db, "SELECT 1 FROM class_student WHERE class_id = ? AND student_id::text = ?", clazz.get("id"), uid) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bạn chưa ghi danh vào lớp học phần này");
            }
            List<Map<String, Object>> studentSessions = db.queryForList(
                    """
                    SELECT ss.id AS session_id, ss.status AS session_status, ss.started_at, ss.ends_at,
                           ar.result, ar.flags, ar.message, ar.checked_in_at
                    FROM attendance_session ss
                    LEFT JOIN attendance_record ar ON ar.session_id = ss.id AND ar.student_id::text = ?
                    WHERE ss.class_id = ?
                    ORDER BY ss.started_at DESC
                    """,
                    uid, clazz.get("id"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("classInfo", Map.of(
                    "id", clazz.get("id"),
                    "name", clazz.get("name"),
                    "code", clazz.get("code"),
                    "term", clazz.get("term") == null ? "" : clazz.get("term"),
                    "schoolYear", clazz.get("school_year") == null ? "" : clazz.get("school_year"),
                    "schoolName", clazz.get("school_name"),
                    "lecturerName", clazz.get("lecturer_name")));
            out.put("records", studentSessions);
            return out;
        } else if (!"LECTURER".equals(role) && !"SCHOOL_ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không đủ quyền");
        }

        List<Map<String, Object>> whitelist = db.queryForList(
                """
                SELECT cw.student_no, cw.created_at AS added_at,
                       u.id AS student_id, u.full_name, u.email, u.status AS account_status,
                       cs.enrolled_at
                FROM class_whitelist cw
                LEFT JOIN app_user u ON u.school_id = ? AND u.student_no = cw.student_no AND u.role = 'STUDENT'
                LEFT JOIN class_student cs ON cs.class_id = cw.class_id AND cs.student_id = u.id
                WHERE cw.class_id = ?
                ORDER BY cw.student_no
                """,
                clazz.get("school_id"), clazz.get("id"));

        List<Map<String, Object>> sessions = db.queryForList(
                """
                SELECT ss.*,
                       (SELECT count(*) FROM attendance_record ar WHERE ar.session_id = ss.id AND ar.result = 'PRESENT') AS present_count,
                       (SELECT count(*) FROM attendance_record ar WHERE ar.session_id = ss.id AND ar.result = 'PENDING') AS pending_count,
                       (SELECT count(*) FROM attendance_record ar WHERE ar.session_id = ss.id AND ar.result = 'REVIEW') AS review_count,
                       (SELECT count(*) FROM attendance_record ar WHERE ar.session_id = ss.id AND ar.result = 'ABSENT') AS absent_count
                FROM attendance_session ss
                WHERE ss.class_id = ?
                ORDER BY ss.started_at DESC
                """,
                clazz.get("id"));

        List<Map<String, Object>> templates = db.queryForList(
                """
                SELECT id, name, original_filename, start_row, mssv_col, first_attendance_col, present_symbol, created_at
                FROM export_template
                WHERE lecturer_id::text = ? AND (class_id IS NULL OR class_id = ?)
                ORDER BY created_at DESC
                """,
                clazz.get("lecturer_id").toString(), clazz.get("id"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classInfo", clazz);
        out.put("whitelist", whitelist);
        out.put("sessions", sessions);
        out.put("templates", templates);
        return out;
    }

    @PostMapping("/classes/join-by-qr")
    @Transactional
    Map<String, Object> joinByStaticQr(@Valid @RequestBody JoinStaticQrRequest body, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "STUDENT");
        String uid = SecurityUtil.uid(req);
        Map<String, Object> student = SecurityUtil.one(
                db, "SELECT id, school_id, student_no, full_name FROM app_user WHERE id::text = ?", uid);
        if (student == null || student.get("school_id") == null || student.get("student_no") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản sinh viên chưa có đủ thông tin định danh");
        }
        Map<String, Object> clazz = SecurityUtil.one(
                db,
                "SELECT id, school_id, name, code, active FROM course_class WHERE static_qr_token = ?",
                body.staticToken().trim());
        if (clazz == null || !Boolean.TRUE.equals(clazz.get("active"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lớp học phần không tồn tại hoặc đã ngừng hoạt động");
        }
        if (!Objects.equals(String.valueOf(student.get("school_id")), String.valueOf(clazz.get("school_id")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Lớp học phần không thuộc trường của bạn");
        }
        String studentNo = String.valueOf(student.get("student_no"));
        if (SecurityUtil.one(db, "SELECT 1 FROM class_whitelist WHERE class_id = ? AND student_no = ?", clazz.get("id"), studentNo) == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MSSV của bạn chưa nằm trong danh sách hợp lệ của lớp");
        }
        verifyIdentityName(clazz.get("school_id"), studentNo, String.valueOf(student.get("full_name")));

        db.update(
                "INSERT INTO class_student(class_id, student_id) VALUES(?,?) ON CONFLICT DO NOTHING",
                clazz.get("id"), student.get("id"));
        return Map.of(
                "classId", clazz.get("id").toString(),
                "className", clazz.get("name"),
                "classCode", clazz.get("code"),
                "message", "Đã liên kết lớp học phần qua QR tĩnh (chưa tạo bản ghi điểm danh)");
    }

    static void verifyIdentityName(JdbcTemplate db, Object schoolId, String studentNo, String accountFullName) {
        Map<String, Object> idRow = SecurityUtil.one(
                db,
                "SELECT full_name FROM identity_import_row WHERE school_id = ? AND student_no = ? ORDER BY created_at DESC LIMIT 1",
                schoolId, studentNo);
        if (idRow != null) {
            String expected = String.valueOf(idRow.get("full_name")).trim().replaceAll("\\s+", " ");
            String actual = accountFullName == null ? "" : accountFullName.trim().replaceAll("\\s+", " ");
            if (!expected.equalsIgnoreCase(actual)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Họ tên tài khoản không khớp hồ sơ định danh nhà trường");
            }
        }
    }

    private void verifyIdentityName(Object schoolId, String studentNo, String accountFullName) {
        verifyIdentityName(db, schoolId, studentNo, accountFullName);
    }

    @PostMapping("/classes/{classId}/students/import")
    @Transactional
    Map<String, Object> importRoster(
            @PathVariable String classId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "replace", defaultValue = "false") boolean replace,
            HttpServletRequest req) throws IOException {
        SecurityUtil.requireRole(req, "LECTURER", "SCHOOL_ADMIN");
        ownsClass(classId, req);
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tệp rỗng");
        UUID cid = UUID.fromString(classId);
        if (replace) {
            db.update("DELETE FROM class_whitelist WHERE class_id = ?", cid);
        }
        int added = importWhitelistFromExcel(cid, file);
        Integer total = db.queryForObject("SELECT count(*) FROM class_whitelist WHERE class_id::text = ?", Integer.class, classId);
        return Map.of("added", added, "totalWhitelist", total == null ? 0 : total);
    }

    @DeleteMapping("/classes/{classId}/students/{studentNo}")
    @Transactional
    Map<String, String> removeWhitelistStudent(@PathVariable String classId, @PathVariable String studentNo, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER", "SCHOOL_ADMIN");
        ownsClass(classId, req);
        db.update("DELETE FROM class_whitelist WHERE class_id = ?::uuid AND student_no = ?", classId, studentNo.trim());
        return Map.of("status", "ok");
    }

    @DeleteMapping("/classes/{classId}/students")
    @Transactional
    Map<String, String> clearWhitelist(@PathVariable String classId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER", "SCHOOL_ADMIN");
        ownsClass(classId, req);
        db.update("DELETE FROM class_whitelist WHERE class_id = ?::uuid", classId);
        return Map.of("status", "ok");
    }

    private int importWhitelistFromExcel(UUID classId, MultipartFile file) throws IOException {
        int added = 0;
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            ExcelImportHelper.DetectedTable table = ExcelImportHelper.detectTable(sheet, fmt, false);

            int start;
            int col;
            if (table != null && table.mssvCol() != null) {
                col = table.mssvCol();
                start = table.dataStartRowIdx();
            } else {
                // Allow a bare single-column file containing only student numbers without title banners
                int first = Math.max(0, sheet.getFirstRowNum());
                Row r0 = sheet.getRow(first);
                String firstVal = r0 == null ? "" : ExcelImportHelper.readCellText(r0.getCell(0), fmt);
                if (ExcelImportHelper.isLikelyStudentNo(firstVal)) {
                    col = 0;
                    start = first;
                } else {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Không tìm thấy cột MSSV / Mã SV trong file Excel. Đảm bảo file có tiêu đề cột MSSV (hoặc Mã SV / Mã sinh viên).");
                }
            }

            for (int i = start; i <= sheet.getLastRowNum(); i++) {
                Row rr = sheet.getRow(i);
                if (rr == null) continue;
                String no = ExcelImportHelper.readCellText(rr.getCell(col), fmt);
                if (no.isEmpty()) continue;
                if (!ExcelImportHelper.isLikelyStudentNo(no)) {
                    if (ExcelImportHelper.isFooterOrBannerText(no)) break;
                    continue;
                }
                added += db.update("INSERT INTO class_whitelist(class_id, student_no) VALUES(?,?) ON CONFLICT DO NOTHING", classId, no);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không đọc được file Excel chứa danh sách MSSV");
        }
        if (added == 0) {
            Integer existing = db.queryForObject("SELECT count(*) FROM class_whitelist WHERE class_id = ?", Integer.class, classId);
            if (existing == null || existing == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy dòng MSSV hợp lệ nào trong file Excel");
            }
        }
        return added;
    }

    @GetMapping("/classes/{classId}/students")
    List<Map<String, Object>> roster(@PathVariable String classId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER", "SCHOOL_ADMIN");
        Map<String, Object> clazz = ownsClass(classId, req);
        return db.queryForList(
                """
                SELECT cw.student_no, u.id, u.full_name, u.email, cs.enrolled_at
                FROM class_whitelist cw
                LEFT JOIN app_user u ON u.school_id = ? AND u.student_no = cw.student_no AND u.role = 'STUDENT'
                LEFT JOIN class_student cs ON cs.class_id = cw.class_id AND cs.student_id = u.id
                WHERE cw.class_id = ?
                ORDER BY cw.student_no
                """,
                clazz.get("school_id"), clazz.get("id"));
    }

    @GetMapping(value = "/classes/{classId}/students/template.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ResponseEntity<byte[]> rosterTemplate(@PathVariable String classId, HttpServletRequest req) throws IOException {
        SecurityUtil.requireRole(req, "LECTURER", "SCHOOL_ADMIN");
        ownsClass(classId, req);
        return xlsxTemplate("Whitelist lớp", new String[]{"MSSV"}, "class-roster-template.xlsx");
    }

    @GetMapping("/classes/{classId}/templates")
    List<Map<String, Object>> listTemplates(@PathVariable String classId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        ownsClass(classId, req);
        return db.queryForList(
                """
                SELECT id, name, original_filename, start_row, mssv_col, first_attendance_col, present_symbol, created_at
                FROM export_template
                WHERE lecturer_id::text = ? AND (class_id IS NULL OR class_id::text = ?)
                ORDER BY created_at DESC
                """,
                SecurityUtil.uid(req), classId);
    }

    @PostMapping(value = "/classes/{classId}/templates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadTemplate(
            @PathVariable String classId,
            @RequestParam("name") String name,
            @RequestParam("startRow") int startRow,
            @RequestParam("mssvCol") String mssvColRaw,
            @RequestParam("firstAttendanceCol") String firstAttendanceColRaw,
            @RequestParam("presentSymbol") String presentSymbol,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest req) throws IOException {
        SecurityUtil.requireRole(req, "LECTURER");
        ownsClass(classId, req);
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần nhập tên mẫu");
        if (presentSymbol == null || presentSymbol.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần nhập ký hiệu có mặt");
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tệp mẫu Excel rỗng");

        int mssvCol = parseExcelColumn(mssvColRaw);
        int firstAttCol = parseExcelColumn(firstAttendanceColRaw);
        if (startRow < 1 || mssvCol < 1 || firstAttCol < 1 || mssvCol == firstAttCol) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cấu hình dòng/cột mẫu Excel không hợp lệ");
        }
        byte[] bytes = file.getBytes();
        try (Workbook ignored = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            // Validate readable Excel workbook.
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tệp tải lên không phải Excel hợp lệ");
        }
        UUID id = UUID.randomUUID();
        String filename = file.getOriginalFilename() == null ? "template.xlsx" : file.getOriginalFilename();
        db.update(
                "INSERT INTO export_template(id, lecturer_id, class_id, name, original_filename, start_row, mssv_col, first_attendance_col, present_symbol, file_data) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id, UUID.fromString(SecurityUtil.uid(req)), UUID.fromString(classId), name.trim(), filename,
                startRow, mssvCol, firstAttCol, presentSymbol.trim(), bytes);
        return Map.of(
                "id", id.toString(),
                "name", name.trim(),
                "originalFilename", filename,
                "startRow", startRow,
                "mssvCol", mssvCol,
                "firstAttendanceCol", firstAttCol,
                "presentSymbol", presentSymbol.trim());
    }

    @DeleteMapping("/classes/{classId}/templates/{templateId}")
    Map<String, String> deleteTemplate(@PathVariable String classId, @PathVariable String templateId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        ownsClass(classId, req);
        db.update("DELETE FROM export_template WHERE id::text = ? AND lecturer_id::text = ?", templateId, SecurityUtil.uid(req));
        return Map.of("status", "ok");
    }

    @GetMapping(value = "/classes/{classId}/attendance.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ResponseEntity<byte[]> exportExcel(
            @PathVariable String classId,
            @RequestParam(value = "templateId", required = false) String templateId,
            HttpServletRequest req) throws IOException {
        SecurityUtil.requireRole(req, "LECTURER", "SCHOOL_ADMIN");
        Map<String, Object> clazz = ownsClass(classId, req);
        List<Map<String, Object>> sessions = db.queryForList(
                "SELECT id, started_at FROM attendance_session WHERE class_id::text = ? AND status = 'FINALIZED' ORDER BY started_at ASC",
                classId);
        if (sessions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lớp chưa có buổi điểm danh nào đã chốt kết quả");
        }

        Map<String, Set<String>> presentMssvBySession = new HashMap<>();
        for (Map<String, Object> ss : sessions) {
            Set<String> presentNos = new HashSet<>();
            for (Map<String, Object> rec : db.queryForList(
                    """
                    SELECT u.student_no
                    FROM attendance_record ar
                    JOIN app_user u ON u.id = ar.student_id
                    WHERE ar.session_id = ? AND ar.result = 'PRESENT' AND u.student_no IS NOT NULL
                    """,
                    ss.get("id"))) {
                presentNos.add(String.valueOf(rec.get("student_no")).trim());
            }
            presentMssvBySession.put(ss.get("id").toString(), presentNos);
        }

        byte[] payload;
        if (templateId != null && !templateId.isBlank()) {
            Map<String, Object> tpl = SecurityUtil.one(
                    db,
                    "SELECT * FROM export_template WHERE id::text = ? AND lecturer_id::text = ?",
                    templateId, SecurityUtil.uid(req));
            if (tpl == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy mẫu báo cáo");
            payload = renderByTemplate(tpl, sessions, presentMssvBySession);
        } else {
            payload = renderDefaultWorkbook(clazz, sessions, presentMssvBySession);
        }

        String filename = URLEncoder.encode(clazz.get("code") + "-attendance.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(payload);
    }

    static byte[] renderByTemplate(
            Map<String, Object> tpl,
            List<Map<String, Object>> sessions,
            Map<String, Set<String>> presentMssvBySession) throws IOException {
        byte[] sourceBytes = (byte[]) tpl.get("file_data");
        int startRowIdx = ((Number) tpl.get("start_row")).intValue() - 1;
        int mssvColIdx = ((Number) tpl.get("mssv_col")).intValue() - 1;
        int firstAttColIdx = ((Number) tpl.get("first_attendance_col")).intValue() - 1;
        String symbol = String.valueOf(tpl.get("present_symbol"));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            if (startRowIdx > 0) {
                Row headerRow = sheet.getRow(startRowIdx - 1);
                if (headerRow != null) {
                    for (int j = 0; j < sessions.size(); j++) {
                        int colIdx = firstAttColIdx + j;
                        if (colIdx == mssvColIdx) continue;
                        Cell hc = headerRow.getCell(colIdx);
                        if (hc == null) hc = headerRow.createCell(colIdx);
                        if (fmt.formatCellValue(hc).isBlank()) {
                            String dateStr = SecurityUtil.toInstant(sessions.get(j).get("started_at"))
                                    .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString();
                            hc.setCellValue("B" + (j + 1) + " (" + dateStr + ")");
                        }
                    }
                }
            }
            for (int r = startRowIdx; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String studentNo = ExcelImportHelper.readCellText(row.getCell(mssvColIdx), fmt);
                if (studentNo.isEmpty()) continue;
                for (int j = 0; j < sessions.size(); j++) {
                    int colIdx = firstAttColIdx + j;
                    if (colIdx == mssvColIdx) continue;
                    Cell cell = row.getCell(colIdx);
                    if (cell == null) cell = row.createCell(colIdx);
                    boolean present = presentMssvBySession.get(sessions.get(j).get("id").toString()).contains(studentNo);
                    if (present) {
                        cell.setCellValue(symbol);
                    } else {
                        cell.setBlank();
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private byte[] renderDefaultWorkbook(
            Map<String, Object> clazz,
            List<Map<String, Object>> sessions,
            Map<String, Set<String>> presentMssvBySession) throws IOException {
        List<Map<String, Object>> students = db.queryForList(
                """
                SELECT cw.student_no, COALESCE(u.full_name, '') AS full_name
                FROM class_whitelist cw
                LEFT JOIN app_user u ON u.school_id = ? AND u.student_no = cw.student_no AND u.role = 'STUDENT'
                WHERE cw.class_id = ?
                ORDER BY cw.student_no
                """,
                clazz.get("school_id"), clazz.get("id"));
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Chuyên cần");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("MSSV");
            head.createCell(1).setCellValue("Họ tên");
            for (int j = 0; j < sessions.size(); j++) {
                String dateStr = SecurityUtil.toInstant(sessions.get(j).get("started_at"))
                        .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString();
                head.createCell(j + 2).setCellValue("Buổi " + (j + 1) + " - " + dateStr);
            }
            for (int i = 0; i < students.size(); i++) {
                Map<String, Object> st = students.get(i);
                String studentNo = String.valueOf(st.get("student_no"));
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(studentNo);
                row.createCell(1).setCellValue(String.valueOf(st.get("full_name")));
                for (int j = 0; j < sessions.size(); j++) {
                    if (presentMssvBySession.get(sessions.get(j).get("id").toString()).contains(studentNo)) {
                        row.createCell(j + 2).setCellValue("P");
                    }
                }
            }
            sheet.setColumnWidth(0, 18 * 256);
            sheet.setColumnWidth(1, 32 * 256);
            for (int j = 2; j < sessions.size() + 2; j++) sheet.setColumnWidth(j, 18 * 256);
            wb.write(out);
            return out.toByteArray();
        }
    }

    static int parseExcelColumn(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.matches("\\d+")) return Integer.parseInt(s);
        if (!s.matches("[A-Z]{1,3}")) return -1;
        int result = 0;
        for (int i = 0; i < s.length(); i++) {
            result = result * 26 + (s.charAt(i) - 'A' + 1);
        }
        return result;
    }

    Map<String, Object> ownsClass(String classId, HttpServletRequest req) {
        Map<String, Object> c = SecurityUtil.one(
                db, "SELECT id, lecturer_id, school_id, code, name FROM course_class WHERE id::text = ?", classId);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp");
        boolean allowed = "LECTURER".equals(SecurityUtil.role(req))
                ? c.get("lecturer_id").toString().equals(SecurityUtil.uid(req))
                : Objects.equals(c.get("school_id").toString(), SecurityUtil.schoolId(req));
        if (!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp");
        return c;
    }

    static ResponseEntity<byte[]> xlsxTemplate(String sheetName, String[] headers, String filename) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row head = sheet.createRow(0);
            CellStyle style = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            style.setFont(font);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, 24 * 256);
            }
            sheet.createRow(1);
            wb.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }
}
