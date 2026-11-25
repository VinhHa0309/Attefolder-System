package vn.attendance;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AttendanceDecisionServiceTest {
    private static final double CENTER_LAT = 21.028500;
    private static final double CENTER_LNG = 105.854200;

    @Test
    void validExternalIpInsideRadiusPasses() {
        var in = new AttendanceDecisionService.CheckInInput(
                21.028550, 105.854220, 15.0, false,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                false, true, true, false);
        var dec = AttendanceDecisionService.classifyCheckIn(in);
        assertFalse(dec.rejected());
        assertEquals("PRESENT", dec.result());
        assertEquals("Thành công", dec.statusLabel());
        assertTrue(dec.flags().isEmpty());
    }

    @Test
    void schoolIpInsideRadiusIgnoresHighGpsAccuracy() {
        var in = new AttendanceDecisionService.CheckInInput(
                21.028550, 105.854220, 120.0, false,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                true, true, true, false);
        var dec = AttendanceDecisionService.classifyCheckIn(in);
        assertFalse(dec.rejected());
        assertEquals("PRESENT", dec.result());
        assertEquals("Thành công", dec.statusLabel());
        assertFalse(dec.flags().contains("GPS_LOW_ACCURACY"));
    }

    @Test
    void externalIpInsideRadiusLowAccuracyBecomesPending() {
        var in = new AttendanceDecisionService.CheckInInput(
                21.028550, 105.854220, 95.0, false,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                false, true, true, false);
        var dec = AttendanceDecisionService.classifyCheckIn(in);
        assertEquals("PENDING", dec.result());
        assertEquals("Chờ duyệt", dec.statusLabel());
        assertTrue(dec.flags().contains("GPS_LOW_ACCURACY"));
    }

    @Test
    void exceedsMaxDeviationRejectsEvenWithSchoolIp() {
        // ~1.1km north of center
        var in = new AttendanceDecisionService.CheckInInput(
                21.038500, 105.854200, 10.0, false,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                true, true, true, false);
        var dec = AttendanceDecisionService.classifyCheckIn(in);
        assertTrue(dec.rejected());
        assertEquals("Không thành công", dec.statusLabel());
        assertNull(dec.result());
    }

    @Test
    void outsideRadiusWithinMaxDeviationBecomesReviewBothSchoolAndExternalIp() {
        // ~111m north of center (> 50m radius, <= 500m max deviation)
        for (boolean ipInSchool : List.of(true, false)) {
            var in = new AttendanceDecisionService.CheckInInput(
                    21.029500, 105.854200, 10.0, false,
                    CENTER_LAT, CENTER_LNG, 50, 30, 500,
                    ipInSchool, true, true, false);
            var dec = AttendanceDecisionService.classifyCheckIn(in);
            assertEquals("REVIEW", dec.result());
            assertEquals("Đã ghi nhận – chờ đối chiếu", dec.statusLabel());
            assertTrue(dec.flags().contains("OUTSIDE_RADIUS"));
        }
    }

    @Test
    void sharedAndNewDeviceAndLocationDenialOrUnavailablePrioritizePending() {
        // First-time clean device (!hasBaseline && !usedByOther) does not trigger NEW_DEVICE
        var firstTimeClean = new AttendanceDecisionService.CheckInInput(
                CENTER_LAT, CENTER_LNG, 12.0, false,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                false, false, true, false);
        assertEquals("PRESENT", AttendanceDecisionService.classifyCheckIn(firstTimeClean).result());

        // Shared device takes top priority even inside radius
        var shared = new AttendanceDecisionService.CheckInInput(
                CENTER_LAT, CENTER_LNG, 10.0, false,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                true, false, false, true);
        var sharedDec = AttendanceDecisionService.classifyCheckIn(shared);
        assertEquals("PENDING", sharedDec.result());
        assertTrue(sharedDec.flags().contains("SHARED_DEVICE"));

        // New device combined with outside radius still becomes PENDING, not REVIEW
        var newDeviceOutside = new AttendanceDecisionService.CheckInInput(
                21.029500, 105.854200, 10.0, false,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                false, true, false, false);
        var newDevDec = AttendanceDecisionService.classifyCheckIn(newDeviceOutside);
        assertEquals("PENDING", newDevDec.result());
        assertTrue(newDevDec.flags().contains("NEW_DEVICE"));
        assertTrue(newDevDec.flags().contains("OUTSIDE_RADIUS"));

        // Denied GPS -> LOCATION_DENIED -> PENDING
        var denied = new AttendanceDecisionService.CheckInInput(
                null, null, null, true,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                true, true, true, false);
        var deniedDec = AttendanceDecisionService.classifyCheckIn(denied);
        assertEquals("PENDING", deniedDec.result());
        assertTrue(deniedDec.flags().contains("LOCATION_DENIED"));

        // Timed out GPS -> LOCATION_UNAVAILABLE -> PENDING
        var unavailable = new AttendanceDecisionService.CheckInInput(
                null, null, null, false,
                CENTER_LAT, CENTER_LNG, 50, 30, 500,
                true, true, true, false);
        var unavailDec = AttendanceDecisionService.classifyCheckIn(unavailable);
        assertEquals("PENDING", unavailDec.result());
        assertTrue(unavailDec.flags().contains("LOCATION_UNAVAILABLE"));
    }

    @Test
    void madClusterAnalysisCoversAllFiveCasesAndMedianOutlierResistance() {
        var insufficient = AttendanceDecisionService.analyzeMadCluster(
                List.of(new AttendanceDecisionService.PointRecord("r1", 21.0290, 105.8542)),
                CENTER_LAT, CENTER_LNG, "MAP", 50, 30, 3);
        assertEquals("INSUFFICIENT_RECORDS", insufficient.outcome());
        assertEquals(List.of("r1"), insufficient.pendingIds());

        // Tight cluster just ~45m from center (still had individual slightly > 50m or mixed) -> IN_RADIUS_CLUSTER
        var inRadiusPoints = List.of(
                new AttendanceDecisionService.PointRecord("1", 21.02890, 105.85420),
                new AttendanceDecisionService.PointRecord("2", 21.02891, 105.85421),
                new AttendanceDecisionService.PointRecord("3", 21.02889, 105.85419));
        var branch1 = AttendanceDecisionService.analyzeMadCluster(
                inRadiusPoints, CENTER_LAT, CENTER_LNG, "PHONE_GPS", 50, 30, 3);
        assertEquals("IN_RADIUS_CLUSTER", branch1.outcome());
        assertFalse(branch1.centerShifted());
        assertEquals(3, branch1.approveIds().size());

        // Tight cluster ~200m away + 1 noisy outlier, MAP source -> shifts center & flags CENTER_SHIFTED
        var mapShiftedPoints = List.of(
                new AttendanceDecisionService.PointRecord("a", 21.03030, 105.85420),
                new AttendanceDecisionService.PointRecord("b", 21.03031, 105.85421),
                new AttendanceDecisionService.PointRecord("c", 21.03029, 105.85419),
                new AttendanceDecisionService.PointRecord("outlier", 21.03200, 105.85600));
        var branch2 = AttendanceDecisionService.analyzeMadCluster(
                mapShiftedPoints, CENTER_LAT, CENTER_LNG, "MAP", 50, 30, 3);
        assertEquals("CENTER_SHIFTED", branch2.outcome());
        assertTrue(branch2.centerShifted());
        assertTrue(branch2.appendFlags().contains("CENTER_SHIFTED"));
        assertEquals(4, branch2.approveIds().size());

        // Tight cluster ~200m away, PHONE_GPS source -> CENTER_SUSPECT, keeps pending
        var branch3 = AttendanceDecisionService.analyzeMadCluster(
                mapShiftedPoints.subList(0, 3), CENTER_LAT, CENTER_LNG, "PHONE_GPS", 50, 30, 3);
        assertEquals("CENTER_SUSPECT", branch3.outcome());
        assertFalse(branch3.centerShifted());
        assertTrue(branch3.appendFlags().contains("CENTER_SUSPECT"));
        assertEquals(3, branch3.pendingIds().size());

        // Loose cluster >= 30m MAD
        var loosePoints = List.of(
                new AttendanceDecisionService.PointRecord("x", 21.02850, 105.85420),
                new AttendanceDecisionService.PointRecord("y", 21.02950, 105.85420),
                new AttendanceDecisionService.PointRecord("z", 21.03050, 105.85420));
        var branch4 = AttendanceDecisionService.analyzeMadCluster(
                loosePoints, CENTER_LAT, CENTER_LNG, "MAP", 50, 30, 3);
        assertEquals("LOOSE_CLUSTER", branch4.outcome());
        assertEquals(3, branch4.pendingIds().size());
    }

    @Test
    void finalizeOverdueAppliesExactThreeAbsentExceptions() {
        assertEquals("ABSENT", AttendanceDecisionService.finalizeOverdueOutcome(List.of("SHARED_DEVICE")));
        assertEquals("ABSENT", AttendanceDecisionService.finalizeOverdueOutcome(List.of("LOCATION_DENIED")));
        assertEquals("ABSENT", AttendanceDecisionService.finalizeOverdueOutcome(List.of("OUTSIDE_RADIUS", "CENTER_SUSPECT")));

        assertEquals("PRESENT", AttendanceDecisionService.finalizeOverdueOutcome(List.of("NEW_DEVICE")));
        assertEquals("PRESENT", AttendanceDecisionService.finalizeOverdueOutcome(List.of("LOCATION_UNAVAILABLE")));
        assertEquals("PRESENT", AttendanceDecisionService.finalizeOverdueOutcome(List.of("GPS_LOW_ACCURACY")));
        assertEquals("PRESENT", AttendanceDecisionService.finalizeOverdueOutcome(List.of("OUTSIDE_RADIUS")));
        assertEquals("PRESENT", AttendanceDecisionService.finalizeOverdueOutcome(List.of()));
    }

    @Test
    void helpersParseColumnsAndTrustedProxyHeadersCorrectly() {
        assertEquals(1, ClassController.parseExcelColumn("A"));
        assertEquals(26, ClassController.parseExcelColumn("Z"));
        assertEquals(27, ClassController.parseExcelColumn("AA"));
        assertEquals(5, ClassController.parseExcelColumn("5"));
        assertEquals(-1, ClassController.parseExcelColumn("BAD!"));

        MockHttpServletRequest untrustedReq = new MockHttpServletRequest();
        untrustedReq.setRemoteAddr("203.0.113.5");
        untrustedReq.addHeader("X-Forwarded-For", "10.0.0.10");
        assertEquals("203.0.113.5", SecurityUtil.clientIp(untrustedReq, "127.0.0.1,10.0.0.0/8"));

        MockHttpServletRequest trustedProxyReq = new MockHttpServletRequest();
        trustedProxyReq.setRemoteAddr("127.0.0.1");
        trustedProxyReq.addHeader("X-Forwarded-For", "198.51.100.77, 10.0.0.2");
        assertEquals("198.51.100.77", SecurityUtil.clientIp(trustedProxyReq, "127.0.0.1,10.0.0.0/8"));

        assertEquals(0.0, AttendanceDecisionService.haversineMeters(CENTER_LAT, CENTER_LNG, CENTER_LAT, CENTER_LNG), 1e-6);
    }

    @Test
    void excelImportHelperDetectsUniversityTableBelowBannerRows() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("DSSV");
            sheet.createRow(0).createCell(0).setCellValue("ĐẠI HỌC ĐÀ NẴNG");
            sheet.createRow(1).createCell(0).setCellValue("TRƯỜNG ĐẠI HỌC SƯ PHẠM");
            sheet.createRow(2).createCell(0).setCellValue("DANH SÁCH LỚP HỌC PHẦN");
            sheet.createRow(3).createCell(0).setCellValue("Học kỳ: 1 Năm học 2026-2027");
            sheet.createRow(4).createCell(0).setCellValue("Mã học phần: 31231352");
            sheet.createRow(5).createCell(0).setCellValue("Số tín chỉ: 3");

            Row hdr = sheet.createRow(7);
            hdr.createCell(0).setCellValue("STT");
            hdr.createCell(1).setCellValue("Mã SV");
            hdr.createCell(2).setCellValue("Họ và tên");
            hdr.createCell(4).setCellValue("Ngày sinh");
            sheet.addMergedRegion(new CellRangeAddress(7, 7, 2, 3));

            Row r1 = sheet.createRow(8);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("312310001");
            r1.createCell(2).setCellValue("Nguyễn Văn");
            r1.createCell(3).setCellValue("An");
            r1.createCell(4).setCellValue("15/08/2005");

            Row footer = sheet.createRow(9);
            footer.createCell(1).setCellValue("Tổng số sinh viên: 1");

            DataFormatter fmt = new DataFormatter();
            ExcelImportHelper.DetectedTable detected = ExcelImportHelper.detectTable(sheet, fmt, true);
            assertNotNull(detected);
            assertEquals(7, detected.headerRowIdx());
            assertEquals(8, detected.dataStartRowIdx());
            assertEquals(1, detected.mssvCol());
            assertEquals("312310001", ExcelImportHelper.readCellText(r1.getCell(detected.mssvCol()), fmt));
            assertEquals("Nguyễn Văn An", ExcelImportHelper.extractFullName(r1, detected, fmt));
            assertEquals(LocalDate.of(2005, 8, 15), ExcelImportHelper.readDateCell(r1.getCell(detected.dobCol()), fmt));
            assertTrue(ExcelImportHelper.isLikelyStudentNo("312310001"));
            assertFalse(ExcelImportHelper.isLikelyStudentNo("ĐẠI HỌC ĐÀ NẴNG"));
            assertTrue(ExcelImportHelper.isFooterOrBannerText("Tổng số sinh viên: 1"));
        }
    }

    @Test
    void exportByTemplatePreservesLeadingZerosAndReorderedRows() throws Exception {
        byte[] templateBytes;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Mau");
            Row hdr = sheet.createRow(2);
            hdr.createCell(1).setCellValue("MSSV");
            hdr.createCell(2).setCellValue("Họ tên");
            Row r1 = sheet.createRow(3);
            r1.createCell(1).setCellValue("00299999");
            r1.createCell(2).setCellValue("SV Thu Tu Khac");
            Row r2 = sheet.createRow(4);
            r2.createCell(1).setCellValue("00112345");
            r2.createCell(2).setCellValue("Nguyen Minh Anh");
            wb.write(out);
            templateBytes = out.toByteArray();
        }

        Map<String, Object> tpl = Map.of(
                "file_data", templateBytes,
                "start_row", 4,
                "mssv_col", 2,
                "first_attendance_col", 4,
                "present_symbol", "P");
        List<Map<String, Object>> sessions = List.of(
                Map.of("id", "s1", "started_at", Instant.parse("2026-10-01T01:00:00Z")),
                Map.of("id", "s2", "started_at", Instant.parse("2026-10-08T01:00:00Z")));
        Map<String, Set<String>> presentMap = Map.of(
                "s1", Set.of("00112345"),
                "s2", Set.of("00112345", "00299999"));

        byte[] rendered = ClassController.renderByTemplate(tpl, sessions, presentMap);
        try (Workbook outWb = WorkbookFactory.create(new ByteArrayInputStream(rendered))) {
            Sheet outSheet = outWb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            assertEquals("00299999", fmt.formatCellValue(outSheet.getRow(3).getCell(1)));
            assertEquals("", fmt.formatCellValue(outSheet.getRow(3).getCell(3)));
            assertEquals("P", fmt.formatCellValue(outSheet.getRow(3).getCell(4)));
            assertEquals("00112345", fmt.formatCellValue(outSheet.getRow(4).getCell(1)));
            assertEquals("P", fmt.formatCellValue(outSheet.getRow(4).getCell(3)));
            assertEquals("P", fmt.formatCellValue(outSheet.getRow(4).getCell(4)));
        }
    }

    @Test
    void defaultStudentPasswordFollowsExplicitThenSavedThenSchoolCodePattern() {
        assertEquals("UED@1234321", AdminController.resolveDefaultStudentPassword("UED@1234321", "SAVED@1", "UED"));
        assertEquals("SAVED@1", AdminController.resolveDefaultStudentPassword("", "SAVED@1", "UED"));
        assertEquals("UED@1234321", AdminController.resolveDefaultStudentPassword(null, null, "ued"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> AdminController.resolveDefaultStudentPassword("12345", null, "UED"));
    }
}
