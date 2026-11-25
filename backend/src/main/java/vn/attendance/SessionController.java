package vn.attendance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
class SessionController {
    private record ActiveCode(String qrToken, String pin, Instant validFrom, Instant validUntil) {}

    private final JdbcTemplate db;
    private final AttendanceDecisionService decisionService;
    private final String trustedProxies;
    private final String sessionSecret;
    private final String webUrl;
    private final Map<String, ActiveCode> activeCodes = new ConcurrentHashMap<>();

    SessionController(
            JdbcTemplate db,
            AttendanceDecisionService decisionService,
            @Value("${app.trusted-proxies}") String trustedProxies,
            @Value("${app.session-secret}") String sessionSecret,
            @Value("${app.web-url}") String webUrl) {
        this.db = db;
        this.decisionService = decisionService;
        this.trustedProxies = trustedProxies;
        this.sessionSecret = sessionSecret;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    record CreateProbeRequest(@NotBlank String classId) {}
    record SubmitProbeLocationRequest(double latitude, double longitude, Double accuracyMeters) {}
    record CreateSessionRequest(
            @NotBlank String classId,
            Integer durationSeconds,
            Double centerLat,
            Double centerLng,
            String centerSource,
            Double centerAccuracyMeters,
            String probeToken,
            Integer radiusMeters,
            Integer gpsAccuracyMeters) {}
    record ReviewRequest(@NotBlank String decision, String reason) {}
    record ManualAttendanceRequest(@NotBlank String studentId, String result, String reason) {}
    record AmendFinalizedRequest(@NotBlank String decision, @NotBlank String reason) {}
    record CheckInRequest(
            String qrToken,
            String pin,
            Double latitude,
            Double longitude,
            Double accuracyMeters,
            boolean locationDenied,
            @NotBlank String deviceFingerprint,
            boolean confirmDeviceBinding) {}

    @PostMapping("/sessions/center-probe")
    Map<String, Object> createCenterProbe(@Valid @RequestBody CreateProbeRequest body, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        String uid = SecurityUtil.uid(req);
        Map<String, Object> clazz = ownsClassForLecturer(body.classId(), uid);
        String token = SecurityUtil.randomToken(20);
        Instant expires = Instant.now().plus(Duration.ofMinutes(15));
        db.update(
                "INSERT INTO session_center_probe(class_id, lecturer_id, probe_token, expires_at) VALUES(?::uuid, ?::uuid, ?, ?)",
                clazz.get("id").toString(), uid, token, Timestamp.from(expires));
        return Map.of(
                "probeToken", token,
                "probeUrl", webUrl + "/?probe=" + token,
                "expiresAt", expires.toString(),
                "className", clazz.get("name"),
                "classCode", clazz.get("code"));
    }

    @GetMapping("/sessions/center-probe/{probeToken}")
    Map<String, Object> getCenterProbe(@PathVariable String probeToken, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        Map<String, Object> row = SecurityUtil.one(
                db,
                """
                SELECT p.probe_token, p.lecturer_id, p.latitude, p.longitude, p.accuracy_m, p.captured_at, p.expires_at,
                       c.name AS class_name, c.code AS class_code, s.name AS school_name
                FROM session_center_probe p
                JOIN course_class c ON c.id = p.class_id
                JOIN school s ON s.id = c.school_id
                WHERE p.probe_token = ?
                """,
                probeToken);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mã định vị phiên không tồn tại");
        if (!Objects.equals(SecurityUtil.uid(req), String.valueOf(row.get("lecturer_id")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không phải Giảng viên đã tạo phiên điểm danh này");
        }
        if (SecurityUtil.toInstant(row.get("expires_at")).isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Mã định vị phiên đã hết hạn");
        }
        return row;
    }

    @PostMapping("/sessions/center-probe/{probeToken}/location")
    Map<String, Object> submitCenterProbeLocation(
            @PathVariable String probeToken,
            @Valid @RequestBody SubmitProbeLocationRequest body,
            HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        Map<String, Object> row = SecurityUtil.one(
                db, "SELECT id, lecturer_id, expires_at FROM session_center_probe WHERE probe_token = ?", probeToken);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mã định vị phiên không tồn tại");
        if (!Objects.equals(SecurityUtil.uid(req), String.valueOf(row.get("lecturer_id")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không phải Giảng viên đã tạo phiên điểm danh này");
        }
        if (SecurityUtil.toInstant(row.get("expires_at")).isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Mã định vị phiên đã hết hạn");
        }
        if (body.latitude() < -90 || body.latitude() > 90 || body.longitude() < -180 || body.longitude() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tọa độ GPS không hợp lệ");
        }
        db.update(
                "UPDATE session_center_probe SET latitude = ?, longitude = ?, accuracy_m = ?, captured_at = now() WHERE probe_token = ?",
                body.latitude(), body.longitude(), body.accuracyMeters(), probeToken);
        return Map.of(
                "status", "CAPTURED",
                "latitude", body.latitude(),
                "longitude", body.longitude(),
                "accuracyMeters", body.accuracyMeters() == null ? 0 : body.accuracyMeters(),
                "message", "Đã ghi nhận tọa độ GPS điện thoại làm tâm điểm cho phiên điểm danh");
    }

    @PostMapping("/sessions")
    @Transactional
    Map<String, Object> openSession(@Valid @RequestBody CreateSessionRequest body, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        String lecturerId = SecurityUtil.uid(req);
        Map<String, Object> clazz = ownsClassForLecturer(body.classId(), lecturerId);
        if (!Boolean.TRUE.equals(clazz.get("active"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lớp học phần đã ngừng hoạt động");
        }

        Map<String, Object> lock = SecurityUtil.one(
                db,
                "SELECT status, lock_reason FROM school_lecturer_access WHERE school_id = ?::uuid AND lecturer_id = ?::uuid",
                clazz.get("school_id").toString(), lecturerId);
        if (lock != null && "LOCKED".equals(lock.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản giảng viên đang bị khóa tại trường này");
        }

        Map<String, Object> openExisting = SecurityUtil.one(
                db,
                "SELECT id FROM attendance_session WHERE class_id = ?::uuid AND status = 'OPEN'",
                clazz.get("id").toString());
        if (openExisting != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lớp học phần đang có một phiên điểm danh mở");
        }

        Map<String, Object> school = SecurityUtil.one(
                db, "SELECT * FROM school WHERE id = ?::uuid", clazz.get("school_id").toString());
        if (school == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy cấu hình trường");

        int durationS = body.durationSeconds() != null && body.durationSeconds() > 0
                ? body.durationSeconds()
                : ((Number) school.get("default_session_duration_s")).intValue();
        if (durationS < 30 || durationS > 14400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời lượng đếm ngược phải từ 30 đến 14400 giây");
        }

        int rMin = ((Number) school.get("radius_min_m")).intValue();
        int rMax = ((Number) school.get("radius_max_m")).intValue();
        int radiusM = body.radiusMeters() != null ? body.radiusMeters() : ((Number) school.get("radius_m")).intValue();
        if (radiusM < rMin || radiusM > rMax) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    String.format(Locale.ROOT, "Bán kính phải nằm trong biên cho phép của trường (%d - %dm)", rMin, rMax));
        }

        int gMin = ((Number) school.get("gps_accuracy_min_m")).intValue();
        int gMax = ((Number) school.get("gps_accuracy_max_m")).intValue();
        int gpsAccM = body.gpsAccuracyMeters() != null
                ? body.gpsAccuracyMeters()
                : ((Number) school.get("gps_accuracy_m")).intValue();
        if (gpsAccM < gMin || gpsAccM > gMax) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    String.format(Locale.ROOT, "Ngưỡng sai số GPS phải nằm trong biên cho phép (%d - %dm)", gMin, gMax));
        }

        Double lat = body.centerLat();
        Double lng = body.centerLng();
        Double acc = body.centerAccuracyMeters();
        String source = body.centerSource();
        if (body.probeToken() != null && !body.probeToken().isBlank()) {
            Map<String, Object> probe = SecurityUtil.one(
                    db,
                    "SELECT latitude, longitude, accuracy_m, captured_at, class_id, lecturer_id FROM session_center_probe WHERE probe_token = ?",
                    body.probeToken().trim());
            if (probe == null
                    || !Objects.equals(probe.get("class_id").toString(), clazz.get("id").toString())
                    || !Objects.equals(probe.get("lecturer_id").toString(), lecturerId)
                    || probe.get("latitude") == null
                    || probe.get("longitude") == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Chưa nhận được tọa độ GPS từ điện thoại. Vui lòng quét QR chốt tâm điểm hoặc chọn trên bản đồ");
            }
            lat = ((Number) probe.get("latitude")).doubleValue();
            lng = ((Number) probe.get("longitude")).doubleValue();
            acc = probe.get("accuracy_m") == null ? null : ((Number) probe.get("accuracy_m")).doubleValue();
            source = "PHONE_GPS";
        }

        if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không xác định được tâm điểm (thiếu GPS điện thoại hoặc chưa chọn trên bản đồ) nên không thể mở phiên");
        }
        String normalizedSource = "PHONE_GPS".equalsIgnoreCase(source) ? "PHONE_GPS" : "MAP";

        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant endsAt = now.plusSeconds(durationS);
        int codeInterval = ((Number) school.get("code_interval_seconds")).intValue();

        db.update(
                """
                INSERT INTO attendance_session(
                    id, class_id, lecturer_id, status, center_lat, center_lng, center_source, center_accuracy_m,
                    radius_m, gps_accuracy_m, max_deviation_m, cluster_mad_m, min_cluster_records,
                    started_at, ends_at, code_interval_seconds, review_hours
                ) VALUES(?,?,?,'OPEN',?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                sessionId, UUID.fromString(clazz.get("id").toString()), UUID.fromString(lecturerId),
                lat, lng, normalizedSource, acc,
                radiusM, gpsAccM,
                ((Number) school.get("max_deviation_m")).intValue(),
                ((Number) school.get("cluster_mad_m")).intValue(),
                ((Number) school.get("min_cluster_records")).intValue(),
                Timestamp.from(now), Timestamp.from(endsAt),
                codeInterval,
                ((Number) school.get("review_hours")).intValue());

        ActiveCode code = rotateSessionCode(sessionId.toString(), codeInterval);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", sessionId.toString());
        out.put("classId", clazz.get("id").toString());
        out.put("status", "OPEN");
        out.put("centerLat", lat);
        out.put("centerLng", lng);
        out.put("centerSource", normalizedSource);
        out.put("radiusMeters", radiusM);
        out.put("gpsAccuracyMeters", gpsAccM);
        out.put("startedAt", now.toString());
        out.put("endsAt", endsAt.toString());
        out.put("codeIntervalSeconds", codeInterval);
        out.put("qrToken", code.qrToken());
        out.put("pin", code.pin());
        out.put("codeValidUntil", code.validUntil().toString());
        return out;
    }

    @GetMapping("/sessions/{sessionId}")
    Map<String, Object> getSession(@PathVariable String sessionId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER", "SCHOOL_ADMIN");
        Map<String, Object> s = ownsSession(sessionId, req);
        return s;
    }

    @GetMapping("/sessions/{sessionId}/code")
    Map<String, Object> getCurrentCode(@PathVariable String sessionId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        Map<String, Object> s = ownsSession(sessionId, req);
        if (!"OPEN".equals(s.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên điểm danh không còn mở");
        }
        Instant endsAt = SecurityUtil.toInstant(s.get("ends_at"));
        if (!endsAt.isAfter(Instant.now())) {
            decisionService.closeSession(sessionId, null, "Hệ thống tự động đóng phiên khi hết giờ");
            activeCodes.remove(sessionId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên điểm danh đã hết giờ và được đóng");
        }
        int interval = ((Number) s.get("code_interval_seconds")).intValue();
        ActiveCode existing = activeCodes.get(sessionId);
        Instant now = Instant.now();
        if (existing == null || !existing.validUntil().isAfter(now)) {
            existing = rotateSessionCode(sessionId, interval);
        }
        return Map.of(
                "qrToken", existing.qrToken(),
                "pin", existing.pin(),
                "validFrom", existing.validFrom().toString(),
                "validUntil", existing.validUntil().toString(),
                "endsAt", endsAt.toString(),
                "serverNow", now.toString(),
                "intervalSeconds", interval);
    }

    @GetMapping("/sessions/{sessionId}/records")
    Map<String, Object> getSessionRecords(@PathVariable String sessionId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER", "SCHOOL_ADMIN");
        Map<String, Object> session = ownsSession(sessionId, req);

        List<Map<String, Object>> records = db.queryForList(
                """
                SELECT ar.id, ar.session_id, ar.student_id, ar.result, ar.flags, ar.latitude, ar.longitude,
                       ar.gps_accuracy_m, ar.distance_m, host(ar.client_ip) AS client_ip, ar.ip_in_school,
                       ar.message, ar.checked_in_at, ar.reviewed_by, ar.reviewed_at,
                       u.student_no, u.full_name, u.email, rev.full_name AS reviewed_by_name
                FROM attendance_record ar
                JOIN app_user u ON u.id = ar.student_id
                LEFT JOIN app_user rev ON rev.id = ar.reviewed_by
                WHERE ar.session_id = ?::uuid
                ORDER BY
                    CASE ar.result WHEN 'PENDING' THEN 0 WHEN 'REVIEW' THEN 1 WHEN 'PRESENT' THEN 2 ELSE 3 END,
                    ar.distance_m DESC NULLS LAST,
                    u.student_no ASC
                """,
                sessionId);

        List<Map<String, Object>> unrecorded = db.queryForList(
                """
                SELECT cw.student_no,
                       u.id AS student_id,
                       COALESCE(u.full_name, ir.full_name, '(Chưa tạo tài khoản)') AS full_name,
                       u.email AS account_email,
                       (u.id IS NOT NULL) AS has_account
                FROM course_class c
                JOIN class_whitelist cw ON cw.class_id = c.id
                LEFT JOIN app_user u ON u.school_id = c.school_id AND u.student_no = cw.student_no AND u.role = 'STUDENT'
                LEFT JOIN LATERAL (
                    SELECT full_name FROM identity_import_row r
                    WHERE r.school_id = c.school_id AND r.student_no = cw.student_no
                    ORDER BY created_at DESC LIMIT 1
                ) ir ON TRUE
                LEFT JOIN attendance_record ar ON ar.session_id = ?::uuid AND ar.student_id = u.id
                WHERE c.id = ?::uuid AND ar.id IS NULL
                ORDER BY cw.student_no
                """,
                sessionId, session.get("class_id").toString());

        List<Map<String, Object>> audits = db.queryForList(
                """
                SELECT aa.id, aa.record_id, aa.action, aa.before_value::text AS before_value,
                       aa.after_value::text AS after_value, aa.reason, aa.created_at,
                       COALESCE(u.full_name, 'Hệ thống') AS actor_name
                FROM attendance_audit aa
                LEFT JOIN app_user u ON u.id = aa.actor_id
                WHERE aa.session_id = ?::uuid
                ORDER BY aa.created_at DESC
                """,
                sessionId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", session);
        out.put("records", records);
        out.put("unrecordedStudents", unrecorded);
        out.put("audits", audits);
        out.put("serverNow", Instant.now().toString());
        return out;
    }

    @PostMapping("/sessions/{sessionId}/close")
    Map<String, Object> closeSession(@PathVariable String sessionId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        ownsSession(sessionId, req);
        activeCodes.remove(sessionId);
        return decisionService.closeSession(sessionId, SecurityUtil.uid(req), "Giảng viên đóng phiên điểm danh");
    }

    @PostMapping("/sessions/{sessionId}/reconcile")
    Map<String, Object> reconcileAlias(@PathVariable String sessionId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        Map<String, Object> s = ownsSession(sessionId, req);
        if ("OPEN".equals(s.get("status"))) {
            activeCodes.remove(sessionId);
            return decisionService.closeSession(sessionId, SecurityUtil.uid(req), "Đóng phiên và phân tích tương quan MAD");
        }
        return Map.of(
                "sessionId", sessionId,
                "status", s.get("status"),
                "reconcileOutcome", Objects.toString(s.get("reconcile_outcome"), ""),
                "madMeters", s.get("reconcile_mad_m"));
    }

    @PostMapping("/sessions/{sessionId}/records/{recordId}/review")
    @Transactional
    Map<String, Object> reviewRecord(
            @PathVariable String sessionId,
            @PathVariable String recordId,
            @Valid @RequestBody ReviewRequest body,
            HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        ownsSession(sessionId, req);
        Map<String, Object> locked = SecurityUtil.one(
                db, "SELECT status FROM attendance_session WHERE id = ?::uuid FOR UPDATE", sessionId);
        if (locked != null && "FINALIZED".equals(locked.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Buổi học đã chốt, chỉ có thể sửa qua chức năng Xem lại và chỉnh sửa kết quả");
        }

        Map<String, Object> rec = SecurityUtil.one(
                db, "SELECT * FROM attendance_record WHERE id::text = ? AND session_id::text = ?", recordId, sessionId);
        if (rec == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi điểm danh");

        String decision = body.decision().trim().toLowerCase(Locale.ROOT);
        String nextResult = switch (decision) {
            case "approve", "present" -> "PRESENT";
            case "reject", "absent" -> "ABSENT";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thao tác duyệt phải là approve hoặc reject");
        };

        String reason = (body.reason() == null || body.reason().isBlank())
                ? ("PRESENT".equals(nextResult) ? "Giảng viên phê duyệt có mặt" : "Giảng viên từ chối điểm danh")
                : body.reason().trim();

        String lecturerId = SecurityUtil.uid(req);
        db.update(
                "UPDATE attendance_record SET result = ?, message = ?, reviewed_by = ?::uuid, reviewed_at = now() WHERE id::text = ?",
                nextResult, reason, lecturerId, recordId);

        boolean deviceUpdated = false;
        if ("PRESENT".equals(nextResult) && rec.get("device_fingerprint_hash") != null) {
            List<String> flags = Arrays.asList(SecurityUtil.textArray(rec.get("flags")));
            if (flags.contains("NEW_DEVICE") && !flags.contains("SHARED_DEVICE")) {
                db.update(
                        """
                        INSERT INTO device_registry(student_id, fingerprint_hash, confirmed_at)
                        VALUES(?::uuid, ?, now())
                        ON CONFLICT (student_id) DO UPDATE SET fingerprint_hash = EXCLUDED.fingerprint_hash, confirmed_at = now()
                        """,
                        rec.get("student_id").toString(), rec.get("device_fingerprint_hash").toString());
                deviceUpdated = true;
            }
        }

        db.update(
                """
                INSERT INTO attendance_audit(session_id, record_id, actor_id, action, before_value, after_value, reason)
                VALUES(?::uuid, ?::uuid, ?::uuid, ?, jsonb_build_object('result', ?), jsonb_build_object('result', ?, 'deviceUpdated', ?), ?)
                """,
                sessionId, recordId, lecturerId,
                "PRESENT".equals(nextResult) ? "REVIEW_APPROVE" : "REVIEW_REJECT",
                rec.get("result"), nextResult, deviceUpdated, reason);

        return Map.of("id", recordId, "result", nextResult, "deviceUpdated", deviceUpdated);
    }

    @PostMapping("/sessions/{sessionId}/records/manual")
    @Transactional
    Map<String, Object> manualAttendance(
            @PathVariable String sessionId,
            @Valid @RequestBody ManualAttendanceRequest body,
            HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        Map<String, Object> s = ownsSession(sessionId, req);
        Map<String, Object> locked = SecurityUtil.one(
                db, "SELECT status FROM attendance_session WHERE id = ?::uuid FOR UPDATE", sessionId);
        if (locked != null && "FINALIZED".equals(locked.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Buổi học đã chốt, chỉ có thể sửa qua chức năng Xem lại và chỉnh sửa kết quả");
        }
        String result = "ABSENT".equalsIgnoreCase(body.result()) ? "ABSENT" : "PRESENT";
        String classId = s.get("class_id").toString();

        Map<String, Object> student = SecurityUtil.one(
                db,
                """
                SELECT u.id, u.student_no, u.full_name
                FROM app_user u
                JOIN course_class c ON c.school_id = u.school_id
                JOIN class_whitelist cw ON cw.class_id = c.id AND cw.student_no = u.student_no
                WHERE c.id = ?::uuid AND u.id::text = ? AND u.role = 'STUDENT'
                """,
                classId, body.studentId());
        if (student == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sinh viên không thuộc danh sách hợp lệ của lớp hoặc chưa có tài khoản");
        }

        if (SecurityUtil.one(db, "SELECT 1 FROM attendance_record WHERE session_id = ?::uuid AND student_id = ?::uuid",
                sessionId, student.get("id").toString()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sinh viên đã có bản ghi trong phiên này, vui lòng dùng Duyệt điểm danh");
        }

        decisionService.enrollStudentSafely(classId, student.get("id").toString());
        UUID recordId = UUID.randomUUID();
        String lecturerId = SecurityUtil.uid(req);
        String msg = (body.reason() == null || body.reason().isBlank())
                ? "Điểm danh thủ công bởi Giảng viên"
                : "Điểm danh thủ công: " + body.reason().trim();

        db.update(
                """
                INSERT INTO attendance_record(id, session_id, student_id, result, flags, message, reviewed_by, reviewed_at)
                VALUES(?::uuid, ?::uuid, ?::uuid, ?, ARRAY['MANUAL']::text[], ?, ?::uuid, now())
                """,
                recordId.toString(), sessionId, student.get("id").toString(), result, msg, lecturerId);

        db.update(
                """
                INSERT INTO attendance_audit(session_id, record_id, actor_id, action, after_value, reason)
                VALUES(?::uuid, ?::uuid, ?::uuid, 'MANUAL_MARK', jsonb_build_object('result', ?), ?)
                """,
                sessionId, recordId.toString(), lecturerId, result, msg);

        return Map.of("id", recordId.toString(), "studentNo", student.get("student_no"), "result", result);
    }

    @PostMapping("/sessions/{sessionId}/finalize")
    Map<String, Object> finalizeSession(@PathVariable String sessionId, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        ownsSession(sessionId, req);
        activeCodes.remove(sessionId);
        return decisionService.finalizeSession(sessionId, SecurityUtil.uid(req), "Giảng viên chốt kết quả buổi học");
    }

    @PatchMapping("/sessions/{sessionId}/records/{recordId}/finalized-result")
    @Transactional
    Map<String, Object> amendFinalized(
            @PathVariable String sessionId,
            @PathVariable String recordId,
            @Valid @RequestBody AmendFinalizedRequest body,
            HttpServletRequest req) {
        SecurityUtil.requireRole(req, "LECTURER");
        Map<String, Object> s = ownsSession(sessionId, req);
        if (!"FINALIZED".equals(s.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ chỉnh sửa được kết quả của buổi học đã chốt");
        }
        String result = switch (body.decision().trim().toLowerCase(Locale.ROOT)) {
            case "approve", "present" -> "PRESENT";
            case "reject", "absent" -> "ABSENT";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quyết định chỉnh sửa không hợp lệ");
        };
        Map<String, Object> rec = SecurityUtil.one(
                db, "SELECT id, result FROM attendance_record WHERE id::text = ? AND session_id::text = ?", recordId, sessionId);
        if (rec == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bản ghi");

        String lecturerId = SecurityUtil.uid(req);
        String reason = body.reason().trim();
        db.update(
                "UPDATE attendance_record SET result = ?, message = ?, reviewed_by = ?::uuid, reviewed_at = now() WHERE id::text = ?",
                result, "Đã chỉnh sửa sau chốt: " + reason, lecturerId, recordId);

        db.update(
                """
                INSERT INTO attendance_audit(session_id, record_id, actor_id, action, before_value, after_value, reason)
                VALUES(?::uuid, ?::uuid, ?::uuid, 'AMEND_FINALIZED',
                       jsonb_build_object('result', ?), jsonb_build_object('result', ?), ?)
                """,
                sessionId, recordId, lecturerId, rec.get("result"), result, reason);

        return Map.of("id", recordId, "result", result, "message", "Đã chỉnh sửa sau chốt: " + reason);
    }

    @PostMapping("/checkins")
    ResponseEntity<Map<String, Object>> submitCheckIn(@Valid @RequestBody CheckInRequest body, HttpServletRequest req) {
        SecurityUtil.requireRole(req, "STUDENT");
        String studentId = SecurityUtil.uid(req);

        String qr = body.qrToken() == null ? "" : body.qrToken().trim();
        String pin = body.pin() == null ? "" : body.pin().trim();
        if (qr.isEmpty() && pin.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng quét mã QR hoặc nhập mã PIN 6 chữ số");
        }
        if (qr.contains("?join=")
                || (!qr.isEmpty() && SecurityUtil.one(db, "SELECT 1 FROM course_class WHERE static_qr_token = ?", qr) != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không thành công: Đây là mã QR tĩnh để tham gia lớp, không dùng để điểm danh. Hãy quét QR động của phiên!");
        }
        if (qr.contains("?qr=")) {
            qr = qr.substring(qr.indexOf("?qr=") + 4).split("&")[0].trim();
        }

        Map<String, Object> student = SecurityUtil.one(
                db,
                "SELECT id, school_id, student_no, full_name FROM app_user WHERE id = ?::uuid",
                studentId);
        if (student == null || student.get("school_id") == null || student.get("student_no") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thành công: Tài khoản thiếu thông tin định danh");
        }

        // Enforce PIN rate limit: max 3 wrong attempts / 60s
        if (!pin.isEmpty() && isRateLimited(studentId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã nhập sai mã PIN quá 3 lần trong 60 giây. Vui lòng đợi một lát");
        }

        // Lookup active session by QR/PIN code
        Map<String, Object> session = findSessionByCode(qr, pin);
        if (session == null) {
            if (!pin.isEmpty()) recordFailedPinAttempt(studentId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thành công: Mã QR hoặc mã PIN không đúng");
        }

        String sessionId = session.get("session_id").toString();
        Instant sessionEnds = SecurityUtil.toInstant(session.get("ends_at"));
        Instant codeUntil = SecurityUtil.toInstant(session.get("valid_until"));
        Instant now = Instant.now();
        if (!"OPEN".equals(session.get("status")) || !sessionEnds.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thành công: Phiên điểm danh đã đóng hoặc hết giờ");
        }
        if (codeUntil.isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thành công: Mã xác thực đã hết hạn, vui lòng quét mã mới");
        }

        // Step 1: Already has record in this session?
        if (SecurityUtil.one(db, "SELECT 1 FROM attendance_record WHERE session_id = ?::uuid AND student_id = ?::uuid",
                sessionId, studentId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không thành công: Bạn đã có bản ghi điểm danh trong phiên này");
        }

        // Step 3: Student belongs to class school & whitelist?
        String studentSchoolId = student.get("school_id").toString();
        String classSchoolId = session.get("school_id").toString();
        String studentNo = student.get("student_no").toString();
        String classId = session.get("class_id").toString();
        if (!studentSchoolId.equals(classSchoolId)
                || !Boolean.TRUE.equals(session.get("class_active"))
                || SecurityUtil.one(db, "SELECT 1 FROM class_whitelist WHERE class_id = ?::uuid AND student_no = ?",
                        classId, studentNo) == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Không thành công: MSSV của bạn không thuộc danh sách hợp lệ của lớp học phần này");
        }

        // Step 4: Verify full name against admin identity batch
        ClassController.verifyIdentityName(db, studentSchoolId, studentNo, Objects.toString(student.get("full_name"), ""));

        // Step 3 post-check: Enrollment is independent of attendance & location result!
        decisionService.enrollStudentSafely(classId, studentId);

        // Step 5: Global Device Fingerprint lookup (device_registry + historical attendance_record across all schools)
        String fpHash = SecurityUtil.hmacSha256(sessionSecret, body.deviceFingerprint().trim());
        Map<String, Object> existingBaseline = SecurityUtil.one(
                db, "SELECT fingerprint_hash FROM device_registry WHERE student_id = ?::uuid", studentId);
        boolean hasBaseline = existingBaseline != null;
        boolean matchesBaseline = hasBaseline && fpHash.equals(existingBaseline.get("fingerprint_hash"));
        boolean usedByOther = hasDeviceBeenUsedByAnotherStudent(fpHash, studentId);

        if (!hasBaseline && !usedByOther) {
            if (!body.confirmDeviceBinding()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "code", "DEVICE_CONFIRM_REQUIRED",
                        "error", "Lần đầu bạn điểm danh trên thiết bị này. Xác nhận gắn làm thiết bị gốc cố định để tiếp tục."));
            }
            // Immediate baseline bind independent of location check or >maxDeviation rejection!
            decisionService.bindBaselineDeviceSafely(studentId, fpHash);
            matchesBaseline = true;
            hasBaseline = true;
        }

        // Step 6: IP + GPS validation
        String clientIp = SecurityUtil.clientIp(req, trustedProxies);
        boolean ipInSchool = AttendanceDecisionService.isInCidrs(clientIp, session.get("allowed_cidrs"));

        AttendanceDecisionService.CheckInDecision decision = AttendanceDecisionService.classifyCheckIn(
                new AttendanceDecisionService.CheckInInput(
                        body.latitude(),
                        body.longitude(),
                        body.accuracyMeters(),
                        body.locationDenied(),
                        ((Number) session.get("center_lat")).doubleValue(),
                        ((Number) session.get("center_lng")).doubleValue(),
                        ((Number) session.get("radius_m")).intValue(),
                        ((Number) session.get("gps_accuracy_m")).intValue(),
                        ((Number) session.get("max_deviation_m")).intValue(),
                        ipInSchool,
                        hasBaseline,
                        matchesBaseline,
                        usedByOther));

        if (decision.rejected()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, decision.message());
        }

        String recordId = decisionService.insertCheckInAtomically(
                sessionId,
                studentId,
                decision,
                body.latitude(),
                body.longitude(),
                body.accuracyMeters(),
                clientIp,
                ipInSchool,
                fpHash);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("recordId", recordId);
        resp.put("sessionId", sessionId);
        resp.put("classId", classId);
        resp.put("className", session.get("class_name"));
        resp.put("classCode", session.get("class_code"));
        resp.put("result", decision.result());
        resp.put("statusLabel", decision.statusLabel());
        resp.put("flags", decision.flags());
        resp.put("message", decision.message());
        resp.put("distanceMeters", decision.distanceMeters());
        resp.put("ipInSchool", ipInSchool);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/student/history")
    List<Map<String, Object>> studentHistory(HttpServletRequest req) {
        SecurityUtil.requireRole(req, "STUDENT");
        return db.queryForList(
                """
                SELECT s.id AS session_id, s.status AS session_status, s.started_at, s.ends_at,
                       c.id AS class_id, c.name AS class_name, c.code AS class_code,
                       sc.name AS school_name, ar.result, ar.flags, ar.message, ar.checked_in_at
                FROM attendance_record ar
                JOIN attendance_session s ON s.id = ar.session_id
                JOIN course_class c ON c.id = s.class_id
                JOIN school sc ON sc.id = c.school_id
                WHERE ar.student_id = ?::uuid
                ORDER BY s.started_at DESC
                """,
                SecurityUtil.uid(req));
    }

    @Scheduled(fixedDelay = 5000)
    void autoCloseAndFinalize() {
        List<Map<String, Object>> expiredOpen = db.queryForList(
                "SELECT id FROM attendance_session WHERE status = 'OPEN' AND ends_at <= now()");
        for (Map<String, Object> row : expiredOpen) {
            String sid = row.get("id").toString();
            activeCodes.remove(sid);
            try {
                decisionService.closeSession(sid, null, "Hệ thống tự động đóng phiên khi hết thời gian đếm ngược");
            } catch (Exception ignored) {}
        }

        List<Map<String, Object>> overdueReconciling = db.queryForList(
                "SELECT id FROM attendance_session WHERE status = 'RECONCILING' AND reconcile_until <= now()");
        for (Map<String, Object> row : overdueReconciling) {
            try {
                decisionService.finalizeSession(row.get("id").toString(), null, "Hệ thống tự động chốt khi quá hạn đối soát");
            } catch (Exception ignored) {}
        }
    }

    private Map<String, Object> findSessionByCode(String qr, String pin) {
        String sql = """
                SELECT ss.id AS session_id, ss.class_id, ss.status, ss.center_lat, ss.center_lng,
                       ss.radius_m, ss.gps_accuracy_m, ss.max_deviation_m, ss.ends_at,
                       sc.valid_until,
                       c.school_id, c.name AS class_name, c.code AS class_code, c.active AS class_active,
                       sch.allowed_cidrs::text[] AS allowed_cidrs
                FROM session_code sc
                JOIN attendance_session ss ON ss.id = sc.session_id
                JOIN course_class c ON c.id = ss.class_id
                JOIN school sch ON sch.id = c.school_id
                WHERE %s
                ORDER BY ss.started_at DESC
                LIMIT 1
                """;
        if (!qr.isEmpty()) {
            return SecurityUtil.one(db, sql.formatted("sc.qr_hash = ?"), SecurityUtil.sha256(qr));
        }
        return SecurityUtil.one(db, sql.formatted("sc.pin_hash = ?"), SecurityUtil.sha256(pin));
    }

    private boolean hasDeviceBeenUsedByAnotherStudent(String fpHash, String studentId) {
        Integer regCount = db.queryForObject(
                "SELECT count(*) FROM device_registry WHERE fingerprint_hash = ? AND student_id::text <> ?",
                Integer.class, fpHash, studentId);
        if (regCount != null && regCount > 0) return true;
        Integer recCount = db.queryForObject(
                "SELECT count(*) FROM attendance_record WHERE device_fingerprint_hash = ? AND student_id::text <> ?",
                Integer.class, fpHash, studentId);
        return recCount != null && recCount > 0;
    }

    private boolean isRateLimited(String studentId) {
        Map<String, Object> w = SecurityUtil.one(
                db, "SELECT window_started_at, attempts FROM pin_attempt_window WHERE student_id = ?::uuid", studentId);
        if (w == null) return false;
        Instant started = SecurityUtil.toInstant(w.get("window_started_at"));
        int attempts = ((Number) w.get("attempts")).intValue();
        return started.isAfter(Instant.now().minusSeconds(60)) && attempts >= 3;
    }

    private void recordFailedPinAttempt(String studentId) {
        db.update(
                """
                INSERT INTO pin_attempt_window(student_id, window_started_at, attempts)
                VALUES(?::uuid, now(), 1)
                ON CONFLICT (student_id) DO UPDATE SET
                    attempts = CASE
                        WHEN pin_attempt_window.window_started_at < now() - interval '60 seconds' THEN 1
                        ELSE pin_attempt_window.attempts + 1
                    END,
                    window_started_at = CASE
                        WHEN pin_attempt_window.window_started_at < now() - interval '60 seconds' THEN now()
                        ELSE pin_attempt_window.window_started_at
                    END
                """,
                studentId);
    }

    private ActiveCode rotateSessionCode(String sessionId, int intervalSeconds) {
        String qrToken = "ATT_" + SecurityUtil.randomToken(18);
        String pin;
        String pinHash;
        int guard = 0;
        do {
            pin = SecurityUtil.randomOtp6();
            pinHash = SecurityUtil.sha256(pin);
            guard++;
            Integer collision = db.queryForObject(
                    """
                    SELECT count(*) FROM session_code sc
                    JOIN attendance_session ss ON ss.id = sc.session_id
                    WHERE sc.pin_hash = ? AND ss.status = 'OPEN' AND ss.id::text <> ?
                    """,
                    Integer.class, pinHash, sessionId);
            if (collision == null || collision == 0 || guard >= 10) break;
        } while (true);

        Instant now = Instant.now();
        Instant until = now.plusSeconds(intervalSeconds);
        db.update(
                """
                INSERT INTO session_code(session_id, qr_hash, pin_hash, valid_from, valid_until)
                VALUES(?::uuid, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO UPDATE SET
                    qr_hash = EXCLUDED.qr_hash,
                    pin_hash = EXCLUDED.pin_hash,
                    valid_from = EXCLUDED.valid_from,
                    valid_until = EXCLUDED.valid_until
                """,
                sessionId, SecurityUtil.sha256(qrToken), pinHash, Timestamp.from(now), Timestamp.from(until));
        ActiveCode code = new ActiveCode(qrToken, pin, now, until);
        activeCodes.put(sessionId, code);
        return code;
    }

    private Map<String, Object> ownsClassForLecturer(String classId, String lecturerId) {
        Map<String, Object> c = SecurityUtil.one(
                db, "SELECT id, school_id, lecturer_id, name, code, active FROM course_class WHERE id::text = ?", classId);
        if (c == null || !Objects.equals(String.valueOf(c.get("lecturer_id")), lecturerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp học phần");
        }
        return c;
    }

    private Map<String, Object> ownsSession(String sessionId, HttpServletRequest req) {
        Map<String, Object> s = SecurityUtil.one(
                db,
                """
                SELECT ss.*, c.school_id, c.name AS class_name, c.code AS class_code, c.lecturer_id AS class_lecturer_id
                FROM attendance_session ss
                JOIN course_class c ON c.id = ss.class_id
                WHERE ss.id::text = ?
                """,
                sessionId);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên điểm danh");
        String role = SecurityUtil.role(req);
        boolean ok = "LECTURER".equals(role)
                ? Objects.equals(String.valueOf(s.get("class_lecturer_id")), SecurityUtil.uid(req))
                : Objects.equals(String.valueOf(s.get("school_id")), SecurityUtil.schoolId(req));
        if (!ok) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên điểm danh");
        return s;
    }
}
