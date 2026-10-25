package com.example.jiraaudit;

import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ExcelReportWriter {
    private static final int EXCEL_CELL_LIMIT = 32_767;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Clock clock;

    public ExcelReportWriter(Clock clock) {
        this.clock = clock;
    }

    public record ReportContext(int runDay, String jiraBaseUrl, String outputDirectory) { }

    public Path write(List<CheckResult> results, ReportContext context) throws IOException {
        Path directory = Path.of(context.outputDirectory());
        Files.createDirectories(directory);
        ZonedDateTime generatedAt = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        Path report = directory.resolve("jira-audit-day" + context.runDay() + "-" +
                generatedAt.format(FILE_STAMP) + "-" +
                UUID.randomUUID().toString().substring(0, 8) + ".xlsx");
        Path temporary = Files.createTempFile(directory, ".jira-audit-", ".xlsx.tmp");

        try {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                writeGrid(workbook, results, context);
                try (OutputStream output = Files.newOutputStream(temporary,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    workbook.write(output);
                }
            }
            moveCompletedReport(temporary, report);
            return report.toAbsolutePath();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeGrid(XSSFWorkbook workbook, List<CheckResult> results,
                                  ReportContext context) {
        Styles styles = new Styles(workbook);
        Sheet sheet = workbook.createSheet("Audit Results");
        String[] headers = {"Run Day", "Jira ID", "Jira Key", "Summary", "Assignee",
                "Jira Status", "Audit Result", "Remarks"};
        Row header = sheet.createRow(0);
        header.setHeightInPoints(28);
        for (int index = 0; index < headers.length; index++) {
            cell(header, index, headers[index], styles.header);
        }

        int rowIndex = 1;
        for (List<CheckResult> issueChecks : groupByIssue(results).values()) {
            IssueDetails issue = issueChecks.get(0).issue();
            List<CheckResult> failures = issueChecks.stream()
                    .filter(check -> check.outcome() == CheckResult.Outcome.FAIL).toList();
            boolean passed = failures.isEmpty();
            String remarks = passed ? passRemarks(issueChecks) : failureRemarks(failures);
            String issueUrl = issueUrl(context.jiraBaseUrl(), issue.issueKey());

            Row row = sheet.createRow(rowIndex++);
            numericCell(row, 0, context.runDay(), styles.normal);
            cell(row, 1, issue.jiraId(), styles.normal);
            hyperlinkCell(workbook, row, 2, issue.issueKey(), issueUrl, styles.hyperlink);
            cell(row, 3, issue.summary(), styles.wrap);
            cell(row, 4, issue.assigneeName(), styles.normal);
            cell(row, 5, issue.status(), styles.normal);
            cell(row, 6, passed ? "PASS" : "FAIL", passed ? styles.pass : styles.fail);
            cell(row, 7, remarks, styles.wrap);
        }

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.length - 1));
        int[] widths = {12, 16, 16, 60, 26, 20, 16, 100};
        for (int index = 0; index < widths.length; index++) {
            sheet.setColumnWidth(index, widths[index] * 256);
        }
    }

    private static Map<String, List<CheckResult>> groupByIssue(List<CheckResult> results) {
        Map<String, List<CheckResult>> grouped = new LinkedHashMap<>();
        for (CheckResult result : results) {
            grouped.computeIfAbsent(result.issue().jiraId(), ignored -> new ArrayList<>()).add(result);
        }
        return grouped;
    }

    private static String failureRemarks(List<CheckResult> failures) {
        return failures.stream().map(check -> check.checkName() + ": " + check.reason() +
                " [expected=" + check.expected() + "; actual=" + check.actual() + "]")
                .collect(Collectors.joining("; "));
    }

    private static String passRemarks(List<CheckResult> checks) {
        List<String> exceptions = checks.stream()
                .filter(check -> check.checkType().equals("ARTIFACT"))
                .filter(check -> check.actual().contains("attachment=<missing>"))
                .map(check -> check.checkName() + ": " + check.reason())
                .toList();
        return exceptions.isEmpty() ? "All applicable checks passed" :
                "PASS with Not Applicable evidence: " + String.join("; ", exceptions);
    }

    private static void moveCompletedReport(Path temporary, Path report) throws IOException {
        try {
            Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, report);
        }
    }

    private static Cell cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(excelText(value));
        cell.setCellStyle(style);
        return cell;
    }

    private static Cell numericCell(Row row, int column, double value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
        return cell;
    }

    private static void hyperlinkCell(XSSFWorkbook workbook, Row row, int column, String display,
                                      String address, CellStyle style) {
        Cell cell = cell(row, column, display, style);
        Hyperlink hyperlink = workbook.getCreationHelper().createHyperlink(HyperlinkType.URL);
        hyperlink.setAddress(address);
        cell.setHyperlink(hyperlink);
    }

    private static String issueUrl(String jiraBaseUrl, String issueKey) {
        return jiraBaseUrl.replaceAll("/+$", "") + "/browse/" + issueKey;
    }

    private static String excelText(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= EXCEL_CELL_LIMIT) return text;
        return text.substring(0, EXCEL_CELL_LIMIT - 14) + "...[truncated]";
    }

    private static final class Styles {
        private final CellStyle header;
        private final CellStyle normal;
        private final CellStyle wrap;
        private final CellStyle pass;
        private final CellStyle fail;
        private final CellStyle hyperlink;

        private Styles(XSSFWorkbook workbook) {
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            border(header);

            normal = workbook.createCellStyle();
            normal.setVerticalAlignment(VerticalAlignment.TOP);
            border(normal);

            wrap = workbook.createCellStyle();
            wrap.cloneStyleFrom(normal);
            wrap.setWrapText(true);

            pass = outcomeStyle(workbook, IndexedColors.LIGHT_GREEN);
            fail = outcomeStyle(workbook, IndexedColors.ROSE);

            Font linkFont = workbook.createFont();
            linkFont.setUnderline(Font.U_SINGLE);
            linkFont.setColor(IndexedColors.BLUE.getIndex());
            hyperlink = workbook.createCellStyle();
            hyperlink.cloneStyleFrom(normal);
            hyperlink.setFont(linkFont);
        }

        private static CellStyle outcomeStyle(XSSFWorkbook workbook, IndexedColors color) {
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            border(style);
            return style;
        }

        private static void border(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
