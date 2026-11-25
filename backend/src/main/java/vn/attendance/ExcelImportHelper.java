package vn.attendance;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Shared Excel spreadsheet parser for Vietnamese university rosters that often include
 * multi-row title banners, STT columns, merged headers, split surname/given-name columns,
 * optional email columns, and Vietnamese date formats.
 */
final class ExcelImportHelper {
    private static final Set<String> MSSV_HEADERS = Set.of(
            "mssv", "ma sv", "masv", "ma sinh vien", "ma so sv", "ma so sinh vien",
            "student id", "student no", "student code"
    );
    private static final Set<String> FULL_NAME_HEADERS = Set.of(
            "ho ten", "ho va ten", "ho & ten", "ho ten sv", "ho ten sinh vien",
            "ten sinh vien", "full name", "fullname"
    );
    private static final Set<String> SURNAME_HEADERS = Set.of(
            "ho", "ho dem", "ho lot", "ho va ten dem", "last name"
    );
    private static final Set<String> GIVEN_NAME_HEADERS = Set.of(
            "ten", "ten sv", "first name"
    );
    private static final Set<String> EMAIL_HEADERS = Set.of(
            "email truong", "email sv", "email sinh vien", "email", "e mail", "dia chi email", "mail"
    );
    private static final Set<String> DOB_HEADERS = Set.of(
            "ngay sinh", "nam sinh", "sinh ngay", "date of birth", "dob"
    );
    private static final Set<String> STT_HEADERS = Set.of("stt", "tt", "so thu tu");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/M/d")
    );

    private ExcelImportHelper() {}

    record DetectedTable(
            int headerRowIdx,
            int dataStartRowIdx,
            Integer mssvCol,
            Integer fullNameCol,
            Integer surnameCol,
            Integer givenNameCol,
            Integer emailCol,
            Integer dobCol,
            List<String> detectedHeaders
    ) {}

    static String normalizeHeader(String raw) {
        if (raw == null) return "";
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        return nfd.toLowerCase(Locale.ROOT)
                .replaceAll("[\\r\\n\\t:]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String readCellText(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            double val = cell.getNumericCellValue();
            if (val == Math.rint(val) && Math.abs(val) < 1e15) {
                return Long.toString((long) val);
            }
        }
        return fmt.formatCellValue(cell).replace('\u00A0', ' ').trim();
    }

    static LocalDate readDateCell(Cell cell, DataFormatter fmt) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String raw = readCellText(cell, fmt);
        if (raw.isBlank()) return null;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, f);
            } catch (Exception ignored) {
                // try next pattern
            }
        }
        return null;
    }

    static DetectedTable detectTable(Sheet sheet, DataFormatter fmt, boolean requireName) {
        int first = Math.max(0, sheet.getFirstRowNum());
        int lastScan = Math.min(sheet.getLastRowNum(), first + 40);
        DetectedTable best = null;
        int bestScore = -1;

        for (int r = first; r <= lastScan; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            Integer mssvCol = null, fullNameCol = null, surnameCol = null, givenNameCol = null;
            Integer emailCol = null, dobCol = null, sttCol = null;
            List<String> headers = new ArrayList<>();

            int maxCell = row.getLastCellNum();
            for (int c = 0; c < maxCell; c++) {
                String raw = readCellText(row.getCell(c), fmt);
                if (raw.isEmpty()) continue;
                headers.add(raw);
                String norm = normalizeHeader(raw);
                if (MSSV_HEADERS.contains(norm) && mssvCol == null) {
                    mssvCol = c;
                } else if (FULL_NAME_HEADERS.contains(norm) && fullNameCol == null) {
                    fullNameCol = c;
                } else if (SURNAME_HEADERS.contains(norm) && surnameCol == null) {
                    surnameCol = c;
                } else if (GIVEN_NAME_HEADERS.contains(norm) && givenNameCol == null) {
                    givenNameCol = c;
                } else if (EMAIL_HEADERS.contains(norm) && emailCol == null) {
                    emailCol = c;
                } else if (DOB_HEADERS.contains(norm) && dobCol == null) {
                    dobCol = c;
                } else if (STT_HEADERS.contains(norm) && sttCol == null) {
                    sttCol = c;
                }
            }

            if (mssvCol == null) continue;

            int dataStart = r + 1;
            // Handle 2-row merged table headers like "Họ và tên" split across subcolumns below
            if (fullNameCol != null && surnameCol == null && givenNameCol == null) {
                CellRangeAddress merged = findMergedRegion(sheet, r, fullNameCol);
                if (merged != null && merged.getLastColumn() > merged.getFirstColumn()) {
                    surnameCol = merged.getFirstColumn();
                    givenNameCol = merged.getFirstColumn() + 1;
                    fullNameCol = null;
                }
            }
            // Handle vertically merged header spanning r..r+1 or sub-header row (e.g. Họ đệm | Tên) so data starts after sub-header
            CellRangeAddress mssvMerged = findMergedRegion(sheet, r, mssvCol);
            if (mssvMerged != null && mssvMerged.getLastRow() > r) {
                dataStart = mssvMerged.getLastRow() + 1;
            } else {
                Row nextRow = sheet.getRow(r + 1);
                if (nextRow != null && readCellText(nextRow.getCell(mssvCol), fmt).isEmpty()) {
                    for (int c = 0; c < nextRow.getLastCellNum(); c++) {
                        String sub = normalizeHeader(readCellText(nextRow.getCell(c), fmt));
                        if (SURNAME_HEADERS.contains(sub) || GIVEN_NAME_HEADERS.contains(sub)) {
                            dataStart = r + 2;
                            break;
                        }
                    }
                }
            }

            boolean hasName = fullNameCol != null || (surnameCol != null && givenNameCol != null);
            int score = 10 + (hasName ? 10 : 0) + (emailCol != null ? 5 : 0) + (sttCol != null ? 3 : 0);
            if (!requireName || hasName) {
                if (score > bestScore) {
                    bestScore = score;
                    best = new DetectedTable(r, dataStart, mssvCol, fullNameCol, surnameCol, givenNameCol, emailCol, dobCol, headers);
                }
            } else if (best == null) {
                best = new DetectedTable(r, dataStart, mssvCol, fullNameCol, surnameCol, givenNameCol, emailCol, dobCol, headers);
            }
        }
        return best;
    }

    static String extractFullName(Row row, DetectedTable table, DataFormatter fmt) {
        if (table.surnameCol() != null && table.givenNameCol() != null) {
            String left = readCellText(row.getCell(table.surnameCol()), fmt);
            String right = readCellText(row.getCell(table.givenNameCol()), fmt);
            return (left + " " + right).trim().replaceAll("\\s+", " ");
        }
        if (table.fullNameCol() != null) {
            String name = readCellText(row.getCell(table.fullNameCol()), fmt);
            // Some university sheets export "Họ và tên" with surname in col C and given name in unlabelled col D without Excel merge
            Integer nextCol = table.fullNameCol() + 1;
            if (!name.isEmpty()
                    && !Objects.equals(nextCol, table.emailCol())
                    && !Objects.equals(nextCol, table.dobCol())
                    && !Objects.equals(nextCol, table.mssvCol())) {
                String maybeGiven = readCellText(row.getCell(nextCol), fmt);
                if (!maybeGiven.isEmpty()
                        && maybeGiven.length() <= 20
                        && !maybeGiven.contains("@")
                        && !maybeGiven.matches(".*\\d{2,}.*")
                        && maybeGiven.split("\\s+").length <= 2) {
                    Sheet sheet = row.getSheet();
                    Row hdr = sheet.getRow(table.headerRowIdx());
                    String adjHdr = hdr == null ? "" : readCellText(hdr.getCell(nextCol), fmt);
                    if (adjHdr.isEmpty()) {
                        return (name + " " + maybeGiven).trim().replaceAll("\\s+", " ");
                    }
                }
            }
            return name.replaceAll("\\s+", " ");
        }
        return "";
    }

    static boolean isLikelyStudentNo(String value) {
        if (value == null || value.isBlank()) return false;
        String v = value.trim();
        if (v.length() < 3 || v.length() > 32) return false;
        if (v.contains(" ")) return false;
        String norm = normalizeHeader(v);
        if (MSSV_HEADERS.contains(norm) || STT_HEADERS.contains(norm)) return false;
        return v.matches("^[A-Za-z0-9._-]+$");
    }

    static boolean isFooterOrBannerText(String value) {
        if (value == null || value.isBlank()) return false;
        String norm = normalizeHeader(value);
        return norm.startsWith("tong")
                || norm.startsWith("danh sach")
                || norm.startsWith("ghi chu")
                || norm.startsWith("giang vien")
                || norm.startsWith("truong khoa")
                || norm.startsWith("nguoi lap")
                || norm.contains("ngay ") && norm.contains("thang");
    }

    private static CellRangeAddress findMergedRegion(Sheet sheet, int rowIdx, int colIdx) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress r = sheet.getMergedRegion(i);
            if (r.isInRange(rowIdx, colIdx)) return r;
        }
        return null;
    }
}
