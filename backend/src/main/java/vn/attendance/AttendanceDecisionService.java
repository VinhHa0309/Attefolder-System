package vn.attendance;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
class AttendanceDecisionService {
    private static final double EARTH_RADIUS_M = 6_371_000.0;
    private static final Set<String> OVERDUE_ABSENT_FLAGS = Set.of(
            "SHARED_DEVICE",
            "LOCATION_DENIED",
            "CENTER_SUSPECT"
    );

    private final JdbcTemplate db;

    AttendanceDecisionService(JdbcTemplate db) {
        this.db = db;
    }

    record CheckInInput(
            Double latitude,
            Double longitude,
            Double accuracyMeters,
            boolean locationDenied,
            double centerLat,
            double centerLng,
            double radiusMeters,
            double gpsAccuracyMeters,
            double maxDeviationMeters,
            boolean ipInSchool,
            boolean hasBaselineDevice,
            boolean matchesBaselineDevice,
            boolean deviceUsedByOtherAccount
    ) {}

    record CheckInDecision(
            boolean rejected,
            String statusLabel,
            String result,
            List<String> flags,
            String message,
            Double distanceMeters
    ) {}

    record PointRecord(String id, double latitude, double longitude) {}

    record ReconcileAnalysis(
            String outcome,
            Double madMeters,
            Double clusterLat,
            Double clusterLng,
            Double clusterDistanceMeters,
            boolean centerShifted,
            List<String> approveIds,
            List<String> pendingIds,
            List<String> appendFlags,
            String summaryMessage
    ) {}

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    static double median(List<Double> values) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    static boolean isInCidrs(String clientIp, Object cidrArrayObj) {
        if (clientIp == null || clientIp.isBlank() || cidrArrayObj == null) return false;
        try {
            Object[] items;
            if (cidrArrayObj instanceof Array a) {
                items = (Object[]) a.getArray();
            } else if (cidrArrayObj instanceof Object[] arr) {
                items = arr;
            } else if (cidrArrayObj instanceof Collection<?> col) {
                items = col.toArray();
            } else {
                return false;
            }
            for (Object item : items) {
                String cidr = Objects.toString(item, "").trim();
                if (!cidr.isEmpty() && SecurityUtil.ipMatchesCidr(clientIp, cidr)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    static CheckInDecision classifyCheckIn(CheckInInput in) {
        Double distance = null;
        if (in.latitude() != null && in.longitude() != null) {
            distance = haversineMeters(in.latitude(), in.longitude(), in.centerLat(), in.centerLng());
            if (distance > in.maxDeviationMeters()) {
                return new CheckInDecision(
                        true,
                        "Không thành công",
                        null,
                        List.of("EXCEEDS_MAX_DEVIATION"),
                        String.format(Locale.ROOT,
                                "Không thành công: Vị trí lệch %.0fm vượt ngưỡng tối đa %.0fm (kể cả khi dùng mạng trường)",
                                distance, in.maxDeviationMeters()),
                        distance);
            }
        }

        List<String> flags = new ArrayList<>();
        if (in.deviceUsedByOtherAccount()) {
            flags.add("SHARED_DEVICE");
        } else if (in.hasBaselineDevice() && !in.matchesBaselineDevice()) {
            flags.add("NEW_DEVICE");
        }

        if (in.locationDenied()) {
            flags.add("LOCATION_DENIED");
        } else if (in.latitude() == null || in.longitude() == null) {
            flags.add("LOCATION_UNAVAILABLE");
        } else if (distance > in.radiusMeters()) {
            flags.add("OUTSIDE_RADIUS");
        } else if (!in.ipInSchool() && (in.accuracyMeters() == null || in.accuracyMeters() > in.gpsAccuracyMeters())) {
            flags.add("GPS_LOW_ACCURACY");
        }

        boolean needsTeacherApproval = flags.stream().anyMatch(f ->
                f.equals("SHARED_DEVICE")
                        || f.equals("NEW_DEVICE")
                        || f.equals("LOCATION_DENIED")
                        || f.equals("LOCATION_UNAVAILABLE")
                        || f.equals("GPS_LOW_ACCURACY"));

        if (needsTeacherApproval) {
            return new CheckInDecision(
                    false,
                    "Chờ duyệt",
                    "PENDING",
                    flags,
                    buildPendingMessage(flags, distance, in.accuracyMeters()),
                    distance);
        }

        if (flags.contains("OUTSIDE_RADIUS")) {
            return new CheckInDecision(
                    false,
                    "Đã ghi nhận – chờ đối chiếu",
                    "REVIEW",
                    flags,
                    String.format(Locale.ROOT,
                            "Đã ghi nhận – chờ đối chiếu: Vị trí cách tâm điểm %.0fm (> %.0fm)%s",
                            distance, in.radiusMeters(), in.ipInSchool() ? " (đang dùng IP trường)" : ""),
                    distance);
        }

        return new CheckInDecision(
                false,
                "Thành công",
                "PRESENT",
                flags,
                in.ipInSchool()
                        ? "Thành công: Hợp lệ theo vị trí và dải IP trường"
                        : "Thành công: Vị trí và sai số GPS nằm trong ngưỡng cho phép",
                distance);
    }

    private static String buildPendingMessage(List<String> flags, Double distance, Double accuracy) {
        List<String> reasons = new ArrayList<>();
        if (flags.contains("SHARED_DEVICE")) reasons.add("Nghi vấn gian lận thiết bị (thiết bị trùng tài khoản khác)");
        if (flags.contains("NEW_DEVICE")) reasons.add("Thiết bị mới chưa trùng thiết bị gốc");
        if (flags.contains("LOCATION_DENIED")) reasons.add("Từ chối quyền truy cập vị trí");
        if (flags.contains("LOCATION_UNAVAILABLE")) reasons.add("Không lấy được tín hiệu GPS");
        if (flags.contains("GPS_LOW_ACCURACY")) {
            reasons.add(accuracy == null
                    ? "Thiếu thông tin sai số định vị"
                    : String.format(Locale.ROOT, "Sai số GPS %.0fm vượt ngưỡng", accuracy));
        }
        if (flags.contains("OUTSIDE_RADIUS") && distance != null) {
            reasons.add(String.format(Locale.ROOT, "Vị trí ngoài bán kính (%.0fm)", distance));
        }
        return "Chờ duyệt: " + String.join("; ", reasons);
    }

    static ReconcileAnalysis analyzeMadCluster(
            List<PointRecord> reviewRecords,
            double centerLat,
            double centerLng,
            String centerSource,
            double radiusMeters,
            double clusterMadMeters,
            int minClusterRecords) {
        if (reviewRecords == null || reviewRecords.isEmpty()) {
            return new ReconcileAnalysis(
                    "NO_REVIEW_RECORDS",
                    null, null, null, null, false,
                    List.of(), List.of(), List.of(),
                    "Không có bản ghi chờ đối chiếu khi đóng phiên");
        }

        List<String> allIds = reviewRecords.stream().map(PointRecord::id).toList();
        if (reviewRecords.size() < minClusterRecords) {
            return new ReconcileAnalysis(
                    "INSUFFICIENT_RECORDS",
                    null, null, null, null, false,
                    List.of(), allIds, List.of(),
                    String.format(Locale.ROOT,
                            "Số bản ghi chờ đối chiếu (%d) nhỏ hơn ngưỡng tối thiểu (%d) — chuyển toàn bộ sang Chờ duyệt",
                            reviewRecords.size(), minClusterRecords));
        }

        List<Double> lats = reviewRecords.stream().map(PointRecord::latitude).toList();
        List<Double> lngs = reviewRecords.stream().map(PointRecord::longitude).toList();
        double clusterLat = median(lats);
        double clusterLng = median(lngs);

        List<Double> deviations = new ArrayList<>(reviewRecords.size());
        for (PointRecord rec : reviewRecords) {
            deviations.add(haversineMeters(rec.latitude(), rec.longitude(), clusterLat, clusterLng));
        }
        double mad = median(deviations);
        double clusterDistance = haversineMeters(clusterLat, clusterLng, centerLat, centerLng);

        if (mad >= clusterMadMeters) {
            return new ReconcileAnalysis(
                    "LOOSE_CLUSTER",
                    mad, clusterLat, clusterLng, clusterDistance, false,
                    List.of(), allIds, List.of(),
                    String.format(Locale.ROOT,
                            "Cụm loãng (MAD = %.1fm >= %.1fm) — chuyển %d bản ghi sang Chờ duyệt",
                            mad, clusterMadMeters, allIds.size()));
        }

        if (clusterDistance <= radiusMeters) {
            return new ReconcileAnalysis(
                    "IN_RADIUS_CLUSTER",
                    mad, clusterLat, clusterLng, clusterDistance, false,
                    allIds, List.of(), List.of(),
                    String.format(Locale.ROOT,
                            "Cụm chặt (MAD = %.1fm) và tâm cụm nằm trong bán kính (%.1fm <= %.0fm) — tự động ghi nhận Có mặt %d bản ghi",
                            mad, clusterDistance, radiusMeters, allIds.size()));
        }

        if ("MAP".equalsIgnoreCase(centerSource)) {
            return new ReconcileAnalysis(
                    "CENTER_SHIFTED",
                    mad, clusterLat, clusterLng, clusterDistance, true,
                    allIds, List.of(), List.of("CENTER_SHIFTED"),
                    String.format(Locale.ROOT,
                            "Cụm chặt ngoài bán kính với tâm từ bản đồ — hệ thống đã dịch tâm điểm về tâm cụm (MAD = %.1fm) và ghi nhận Có mặt %d bản ghi",
                            mad, allIds.size()));
        }

        return new ReconcileAnalysis(
                "CENTER_SUSPECT",
                mad, clusterLat, clusterLng, clusterDistance, false,
                List.of(), allIds, List.of("CENTER_SUSPECT"),
                String.format(Locale.ROOT,
                        "Nghi vấn tâm điểm lệch: Cụm chặt (MAD = %.1fm) cách tâm GPS điện thoại %.1fm — giữ Chờ duyệt để Giảng viên xác nhận",
                        mad, clusterDistance));
    }

    static String finalizeOverdueOutcome(List<String> flags) {
        if (flags != null) {
            for (String f : flags) {
                if (OVERDUE_ABSENT_FLAGS.contains(f)) {
                    return "ABSENT";
                }
            }
        }
        return "PRESENT";
    }

    @Transactional
    Map<String, Object> closeSession(String sessionId, String actorId, String triggerLabel) {
        Map<String, Object> session = SecurityUtil.one(
                db,
                "SELECT * FROM attendance_session WHERE id::text = ? FOR UPDATE",
                sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Không tìm thấy phiên điểm danh");
        }
        String status = String.valueOf(session.get("status"));
        if (!"OPEN".equals(status)) {
            return Map.of(
                    "sessionId", sessionId,
                    "status", status,
                    "reconcileOutcome", Objects.toString(session.get("reconcile_outcome"), ""),
                    "alreadyClosed", true);
        }

        int reviewHours = ((Number) session.get("review_hours")).intValue();
        Instant now = Instant.now();
        Instant reconcileUntil = now.plusSeconds(reviewHours * 3600L);
        db.update(
                "UPDATE attendance_session SET status = 'RECONCILING', closed_at = ?, reconcile_until = ? WHERE id::text = ?",
                Timestamp.from(now), Timestamp.from(reconcileUntil), sessionId);

        ReconcileAnalysis analysis = runMadCorrelationLocked(sessionId, session, actorId);

        db.update(
                """
                INSERT INTO attendance_audit(session_id, actor_id, action, after_value, reason)
                VALUES(?::uuid, ?::uuid, 'CLOSE_SESSION', jsonb_build_object('outcome', ?, 'mad', ?), ?)
                """,
                sessionId, actorId, analysis.outcome(), analysis.madMeters(), triggerLabel);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("status", "RECONCILING");
        out.put("reconcileOutcome", analysis.outcome());
        out.put("madMeters", analysis.madMeters());
        out.put("centerShifted", analysis.centerShifted());
        out.put("summary", analysis.summaryMessage());
        return out;
    }

    private ReconcileAnalysis runMadCorrelationLocked(String sessionId, Map<String, Object> session, String actorId) {
        List<Map<String, Object>> reviewRows = db.queryForList(
                "SELECT id, latitude, longitude, flags FROM attendance_record WHERE session_id::text = ? AND result = 'REVIEW'",
                sessionId);

        List<PointRecord> withCoords = new ArrayList<>();
        List<String> withoutCoords = new ArrayList<>();
        for (Map<String, Object> row : reviewRows) {
            String rid = row.get("id").toString();
            Object lat = row.get("latitude");
            Object lng = row.get("longitude");
            if (lat instanceof Number la && lng instanceof Number ln) {
                withCoords.add(new PointRecord(rid, la.doubleValue(), ln.doubleValue()));
            } else {
                withoutCoords.add(rid);
            }
        }
        for (String rid : withoutCoords) {
            db.update(
                    "UPDATE attendance_record SET result = 'PENDING', message = 'Chờ duyệt: Thiếu tọa độ khi phân tích cụm' WHERE id::text = ?",
                    rid);
        }

        double centerLat = ((Number) session.get("center_lat")).doubleValue();
        double centerLng = ((Number) session.get("center_lng")).doubleValue();
        String centerSource = String.valueOf(session.get("center_source"));
        double radiusMeters = ((Number) session.get("radius_m")).doubleValue();
        double clusterMadMeters = ((Number) session.get("cluster_mad_m")).doubleValue();
        int minClusterRecords = ((Number) session.get("min_cluster_records")).intValue();

        ReconcileAnalysis analysis = analyzeMadCluster(
                withCoords, centerLat, centerLng, centerSource, radiusMeters, clusterMadMeters, minClusterRecords);

        if (analysis.centerShifted() && analysis.clusterLat() != null && analysis.clusterLng() != null) {
            db.update(
                    """
                    UPDATE attendance_session
                    SET original_center_lat = COALESCE(original_center_lat, center_lat),
                        original_center_lng = COALESCE(original_center_lng, center_lng),
                        center_lat = ?,
                        center_lng = ?,
                        center_shifted = TRUE,
                        reconcile_outcome = ?,
                        reconcile_mad_m = ?
                    WHERE id::text = ?
                    """,
                    analysis.clusterLat(), analysis.clusterLng(), analysis.outcome(), analysis.madMeters(), sessionId);
        } else {
            db.update(
                    "UPDATE attendance_session SET reconcile_outcome = ?, reconcile_mad_m = ? WHERE id::text = ?",
                    analysis.outcome(), analysis.madMeters(), sessionId);
        }

        for (String rid : analysis.approveIds()) {
            List<String> updatedFlags = mergeFlags(rid, analysis.appendFlags());
            if (analysis.centerShifted()) {
                PointRecord p = withCoords.stream().filter(x -> x.id().equals(rid)).findFirst().orElse(null);
                double shiftedDist = p == null ? 0.0 : haversineMeters(p.latitude(), p.longitude(), analysis.clusterLat(), analysis.clusterLng());
                db.update(
                        """
                        UPDATE attendance_record
                        SET result = 'PRESENT',
                            distance_m = ?,
                            flags = ?::text[],
                            message = 'Có mặt — Tâm điểm đã được hệ thống dịch theo cụm hợp lệ'
                        WHERE id::text = ?
                        """,
                        shiftedDist, SecurityUtil.pgTextArray(updatedFlags), rid);
            } else {
                db.update(
                        """
                        UPDATE attendance_record
                        SET result = 'PRESENT',
                            flags = ?::text[],
                            message = 'Có mặt — Cụm chặt nằm trong bán kính hợp lệ khi đối soát'
                        WHERE id::text = ?
                        """,
                        SecurityUtil.pgTextArray(updatedFlags), rid);
            }
            db.update(
                    """
                    INSERT INTO attendance_audit(session_id, record_id, actor_id, action, before_value, after_value, reason)
                    VALUES(?::uuid, ?::uuid, ?::uuid, 'MAD_AUTO_PRESENT',
                           jsonb_build_object('result', 'REVIEW'),
                           jsonb_build_object('result', 'PRESENT'),
                           ?)
                    """,
                    sessionId, rid, actorId, analysis.summaryMessage());
        }

        for (String rid : analysis.pendingIds()) {
            List<String> updatedFlags = mergeFlags(rid, analysis.appendFlags());
            String msg = updatedFlags.contains("CENTER_SUSPECT")
                    ? "Chờ duyệt — Nghi vấn tâm điểm lệch (GPS điện thoại giảng viên)"
                    : "Chờ duyệt — " + analysis.summaryMessage();
            db.update(
                    "UPDATE attendance_record SET result = 'PENDING', flags = ?::text[], message = ? WHERE id::text = ?",
                    SecurityUtil.pgTextArray(updatedFlags), msg, rid);
        }

        return analysis;
    }

    private List<String> mergeFlags(String recordId, List<String> extra) {
        Map<String, Object> row = SecurityUtil.one(db, "SELECT flags FROM attendance_record WHERE id::text = ?", recordId);
        LinkedHashSet<String> set = new LinkedHashSet<>(Arrays.asList(SecurityUtil.textArray(row == null ? null : row.get("flags"))));
        if (extra != null) set.addAll(extra);
        return new ArrayList<>(set);
    }

    @Transactional
    Map<String, Object> finalizeSession(String sessionId, String actorId, String triggerLabel) {
        Map<String, Object> session = SecurityUtil.one(
                db,
                "SELECT * FROM attendance_session WHERE id::text = ? FOR UPDATE",
                sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Không tìm thấy phiên điểm danh");
        }
        String status = String.valueOf(session.get("status"));
        if ("FINALIZED".equals(status)) {
            return Map.of("sessionId", sessionId, "status", "FINALIZED", "alreadyFinalized", true);
        }
        if ("OPEN".equals(status)) {
            closeSession(sessionId, actorId, "Đóng phiên trước khi chốt");
            session = SecurityUtil.one(db, "SELECT * FROM attendance_session WHERE id::text = ? FOR UPDATE", sessionId);
        }

        db.update(
                """
                INSERT INTO attendance_record(session_id, student_id, result, flags, message)
                SELECT ?::uuid, u.id, 'ABSENT', ARRAY[]::text[], 'Vắng mặt (chưa có bản ghi khi chốt buổi học)'
                FROM course_class c
                JOIN class_whitelist cw ON cw.class_id = c.id
                JOIN app_user u ON u.school_id = c.school_id AND u.student_no = cw.student_no AND u.role = 'STUDENT'
                WHERE c.id = ?::uuid
                ON CONFLICT (session_id, student_id) DO NOTHING
                """,
                sessionId, session.get("class_id").toString());

        List<Map<String, Object>> unresolved = db.queryForList(
                "SELECT id, result, flags FROM attendance_record WHERE session_id::text = ? AND result IN ('PENDING', 'REVIEW')",
                sessionId);

        int autoPresentCount = 0;
        int overdueAbsentCount = 0;
        for (Map<String, Object> rec : unresolved) {
            String rid = rec.get("id").toString();
            List<String> flags = Arrays.asList(SecurityUtil.textArray(rec.get("flags")));
            String nextResult = finalizeOverdueOutcome(flags);
            if ("ABSENT".equals(nextResult)) {
                overdueAbsentCount++;
                db.update(
                        "UPDATE attendance_record SET result = 'ABSENT', message = 'Vắng mặt (Quá hạn đối soát)' WHERE id::text = ?",
                        rid);
            } else {
                autoPresentCount++;
                db.update(
                        "UPDATE attendance_record SET result = 'PRESENT', message = 'Có mặt (Tự động ghi nhận do chưa được duyệt)' WHERE id::text = ?",
                        rid);
            }
            db.update(
                    """
                    INSERT INTO attendance_audit(session_id, record_id, actor_id, action, before_value, after_value, reason)
                    VALUES(?::uuid, ?::uuid, ?::uuid, 'FINALIZE_OVERDUE',
                           jsonb_build_object('result', ?),
                           jsonb_build_object('result', ?),
                           ?)
                    """,
                    sessionId, rid, actorId, rec.get("result"), nextResult, triggerLabel);
        }

        Instant now = Instant.now();
        db.update(
                "UPDATE attendance_session SET status = 'FINALIZED', finalized_at = ? WHERE id::text = ?",
                Timestamp.from(now), sessionId);

        db.update(
                """
                INSERT INTO attendance_audit(session_id, actor_id, action, after_value, reason)
                VALUES(?::uuid, ?::uuid, 'FINALIZE_SESSION',
                       jsonb_build_object('autoPresent', ?, 'overdueAbsent', ?),
                       ?)
                """,
                sessionId, actorId, autoPresentCount, overdueAbsentCount, triggerLabel);

        return Map.of(
                "sessionId", sessionId,
                "status", "FINALIZED",
                "finalizedAt", now.toString(),
                "autoPresentCount", autoPresentCount,
                "overdueAbsentCount", overdueAbsentCount);
    }

    void bindBaselineDeviceSafely(String studentId, String fingerprintHash) {
        try {
            db.update(
                    "INSERT INTO device_registry(student_id, fingerprint_hash, confirmed_at) VALUES(?::uuid, ?, now()) ON CONFLICT (student_id) DO NOTHING",
                    studentId, fingerprintHash);
        } catch (DataAccessException ignored) {
            // Non-blocking safeguard if concurrent insert occurred for same student
        }
    }

    void enrollStudentSafely(Object classId, String studentId) {
        db.update(
                "INSERT INTO class_student(class_id, student_id) VALUES(?::uuid, ?::uuid) ON CONFLICT DO NOTHING",
                classId.toString(), studentId);
    }

    /**
     * Atomically locks the session row in SHARE mode while persisting a check-in record,
     * ensuring concurrent close/auto-close does not cut off in-flight records before MAD runs.
     */
    @Transactional
    String insertCheckInAtomically(
            String sessionId,
            String studentId,
            CheckInDecision decision,
            Double latitude,
            Double longitude,
            Double accuracyMeters,
            String clientIp,
            boolean ipInSchool,
            String fpHash) {
        Map<String, Object> locked = SecurityUtil.one(
                db,
                "SELECT status, ends_at FROM attendance_session WHERE id = ?::uuid FOR SHARE",
                sessionId);
        if (locked == null
                || !"OPEN".equals(locked.get("status"))
                || !SecurityUtil.toInstant(locked.get("ends_at")).isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thành công: Phiên điểm danh đã đóng hoặc hết giờ");
        }
        UUID recordId = UUID.randomUUID();
        try {
            db.update(
                    """
                    INSERT INTO attendance_record(
                        id, session_id, student_id, result, flags, latitude, longitude, gps_accuracy_m,
                        distance_m, client_ip, ip_in_school, device_fingerprint_hash, message
                    ) VALUES(?::uuid, ?::uuid, ?::uuid, ?, ?::text[], ?, ?, ?, ?, ?::inet, ?, ?, ?)
                    """,
                    recordId.toString(), sessionId, studentId, decision.result(),
                    SecurityUtil.pgTextArray(decision.flags()),
                    latitude, longitude, accuracyMeters,
                    decision.distanceMeters(), clientIp, ipInSchool, fpHash, decision.message());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thành công: Bạn đã có bản ghi điểm danh trong phiên này");
        }
        return recordId.toString();
    }
}
