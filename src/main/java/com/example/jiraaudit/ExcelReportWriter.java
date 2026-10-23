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

@Component
public class ExcelReportWriter {
    private static final int EXCEL_CELL_LIMIT = 32_767;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Clock clock;

    public ExcelReportWriter(Clock clock) {
        this.clock = clock;
    }

    public record ReportContext(
            int runDay,
            String jql,
            String jiraBaseUrl,
            String outputDirectory
    ) { }

    public Path write(List<CheckResult> results, ReportContext context) throws IOException {
        Path directory = Path.of(context.outputDirectory());
        Files.createDirectories(directory);
        ZonedDateTime generatedAt = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        Path file = directory.resolve("jira-audit-day" + context.runDay() + "-" +
                generatedAt.format(FILE_STAMP) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".xlsx");
        Path temporaryFile = Files.createTempFile(directory, ".jira-audit-", ".xlsx.tmp");

        try {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Styles styles = new Styles(workbook);
                Map<String, List<CheckResult>> byIssue = groupByIssue(results);
                writeRunSummary(workbook, styles, results, byIssue, context, generatedAt);
                writeIssues(workbook, styles, byIssue, context.jiraBaseUrl());
                writeLinkedIssues(workbook, styles, results, context.jiraBaseUrl());
                writeChecks(workbook, styles, results, context, generatedAt);
                workbook.setActiveSheet(0);
                try (OutputStream output = Files.newOutputStream(temporaryFile,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    workbook.write(output);
                }
            }
            moveCompletedReport(temporaryFile, file);
            return file.toAbsolutePath();
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static void moveCompletedReport(Path temporaryFile, Path finalFile) throws IOException {
        try {
            Files.move(temporaryFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporaryFile, finalFile);
        }
    }

    private static Map<String, List<CheckResult>> groupByIssue(List<CheckResult> results) {
        Map<String, List<CheckResult>> grouped = new LinkedHashMap<>();
        for (CheckResult result : results) {
            grouped.computeIfAbsent(result.issue().jiraId(), ignored -> new ArrayList<>()).add(result);
        }
        return grouped;
    }

    private static void writeRunSummary(XSSFWorkbook workbook, Styles styles,
                                        List<CheckResult> results,
                                        Map<String, List<CheckResult>> byIssue,
                                        ReportContext context,
                                        ZonedDateTime generatedAt) {
        Sheet sheet = workbook.createSheet("Run Summary");
        Row title = sheet.createRow(0);
        cell(title, 0, "Jira Sprint Compliance Audit", styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        List<CheckResult> controlResults = results.stream().filter(result -> !result.rollup()).toList();
        long passes = controlResults.stream().filter(r -> r.outcome() == CheckResult.Outcome.PASS).count();
        long failures = controlResults.stream().filter(r -> r.outcome() == CheckResult.Outcome.FAIL).count();
        long skips = controlResults.stream().filter(r -> r.outcome() == CheckResult.Outcome.SKIP).count();
        long nonCompliantIssues = byIssue.values().stream()
                .filter(checks -> checks.stream().filter(c -> !c.rollup())
                        .anyMatch(c -> c.outcome() == CheckResult.Outcome.FAIL))
                .count();

        int row = 2;
        row = summaryRow(sheet, styles, row, "Generated at (UTC)", generatedAt.toString());
        row = summaryRow(sheet, styles, row, "Declared sprint day", context.runDay());
        row = summaryRow(sheet, styles, row, "JQL", context.jql());
        row++;
        row = summaryRow(sheet, styles, row, "Issues", byIssue.size());
        row = summaryRow(sheet, styles, row, "Compliant issues", byIssue.size() - nonCompliantIssues);
        row = summaryRow(sheet, styles, row, "Non-compliant issues", nonCompliantIssues);
        row = summaryRow(sheet, styles, row, "Checks", controlResults.size());
        row = summaryRow(sheet, styles, row, "Passed", passes);
        row = summaryRow(sheet, styles, row, "Failed", failures);
        summaryRow(sheet, styles, row, "Skipped", skips);

        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 100 * 256);
        sheet.createFreezePane(0, 2);
    }

    private static int summaryRow(Sheet sheet, Styles styles, int rowIndex, String label, Object value) {
        Row row = sheet.createRow(rowIndex);
        cell(row, 0, label, styles.label);
        if (value instanceof Number number) {
            numericCell(row, 1, number.doubleValue(), styles.normal);
        } else {
            cell(row, 1, String.valueOf(value), styles.wrap);
        }
        return rowIndex + 1;
    }

    private static void writeIssues(XSSFWorkbook workbook, Styles styles,
                                    Map<String, List<CheckResult>> byIssue,
                                    String jiraBaseUrl) {
        Sheet sheet = workbook.createSheet("Issues");
        String[] headers = {"Jira ID", "Jira Key", "Jira URL", "Summary", "Issue Type", "Assignee",
                "Assignee Account ID", "Status", "Resolution", "Resolved", "Checks", "Passed", "Failed",
                "Skipped", "Compliance", "Violations"};
        header(sheet, headers, styles.header);

        int rowIndex = 1;
        for (List<CheckResult> checks : byIssue.values()) {
            CheckResult first = checks.get(0);
            IssueDetails issue = first.issue();
            List<CheckResult> controlChecks = checks.stream().filter(check -> !check.rollup()).toList();
            long passes = controlChecks.stream().filter(c -> c.outcome() == CheckResult.Outcome.PASS).count();
            long failures = controlChecks.stream().filter(c -> c.outcome() == CheckResult.Outcome.FAIL).count();
            long skips = controlChecks.stream().filter(c -> c.outcome() == CheckResult.Outcome.SKIP).count();
            String violations = controlChecks.stream()
                    .filter(c -> c.outcome() == CheckResult.Outcome.FAIL)
                    .map(c -> c.checkType() + ": " + c.checkName() + " (" + c.reason() + ")")
                    .collect(java.util.stream.Collectors.joining("; "));
            String issueUrl = issueUrl(jiraBaseUrl, issue.issueKey());

            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, issue.jiraId(), styles.normal);
            hyperlinkCell(workbook, row, 1, issue.issueKey(), issueUrl, styles.hyperlink);
            hyperlinkCell(workbook, row, 2, issueUrl, issueUrl, styles.hyperlink);
            cell(row, 3, issue.summary(), styles.wrap);
            cell(row, 4, issue.issueType(), styles.normal);
            cell(row, 5, issue.assigneeName(), styles.normal);
            cell(row, 6, issue.assigneeAccountId(), styles.normal);
            cell(row, 7, issue.status(), styles.normal);
            cell(row, 8, issue.resolution(), styles.normal);
            cell(row, 9, Boolean.toString(issue.resolved()), styles.normal);
            numericCell(row, 10, controlChecks.size(), styles.normal);
            numericCell(row, 11, passes, styles.pass);
            numericCell(row, 12, failures, failures > 0 ? styles.fail : styles.normal);
            numericCell(row, 13, skips, styles.skip);
            cell(row, 14, failures == 0 ? "COMPLIANT" : "NON-COMPLIANT",
                    failures == 0 ? styles.pass : styles.fail);
            cell(row, 15, violations, styles.wrap);
        }
        finishTabularSheet(sheet, headers.length, rowIndex - 1,
                new int[]{16, 15, 44, 60, 18, 24, 30, 18, 18, 12, 10, 10, 10, 10, 18, 70});
    }

    private static void writeLinkedIssues(XSSFWorkbook workbook, Styles styles,
                                          List<CheckResult> results, String jiraBaseUrl) {
        Sheet sheet = workbook.createSheet("Linked Issues");
        String[] headers = {"Parent Jira ID", "Parent Jira Key", "Parent Jira URL", "Parent Assignee",
                "Linked Jira ID", "Linked Jira Key", "Linked Jira URL", "Linked Summary",
                "Linked Issue Type", "Linked Assignee", "Linked Assignee Account ID", "Linked Status",
                "Status Category", "Resolution", "Direction", "Link Type", "Outcome", "Reason"};
        header(sheet, headers, styles.header);

        int rowIndex = 1;
        for (CheckResult result : results) {
            LinkedIssueDetails linked = result.linkedIssue();
            if (linked == null) continue;
            IssueDetails parent = result.issue();
            String parentUrl = issueUrl(jiraBaseUrl, parent.issueKey());
            String linkedUrl = issueUrl(jiraBaseUrl, linked.issueKey());
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, parent.jiraId(), styles.normal);
            hyperlinkCell(workbook, row, 1, parent.issueKey(), parentUrl, styles.hyperlink);
            hyperlinkCell(workbook, row, 2, parentUrl, parentUrl, styles.hyperlink);
            cell(row, 3, parent.assigneeName(), styles.normal);
            cell(row, 4, linked.jiraId(), styles.normal);
            hyperlinkCell(workbook, row, 5, linked.issueKey(), linkedUrl, styles.hyperlink);
            hyperlinkCell(workbook, row, 6, linkedUrl, linkedUrl, styles.hyperlink);
            cell(row, 7, linked.summary(), styles.wrap);
            cell(row, 8, linked.issueType(), styles.normal);
            cell(row, 9, linked.assigneeName(), styles.normal);
            cell(row, 10, linked.assigneeAccountId(), styles.normal);
            cell(row, 11, linked.status(), styles.normal);
            cell(row, 12, linked.statusCategory(), styles.normal);
            cell(row, 13, linked.resolution(), styles.normal);
            cell(row, 14, linked.direction(), styles.normal);
            cell(row, 15, linked.linkType(), styles.normal);
            CellStyle outcomeStyle = switch (result.outcome()) {
                case PASS -> styles.pass;
                case FAIL -> styles.fail;
                case SKIP -> styles.skip;
            };
            cell(row, 16, result.outcome().name(), outcomeStyle);
            cell(row, 17, result.reason(), styles.wrap);
        }
        finishTabularSheet(sheet, headers.length, rowIndex - 1,
                new int[]{16, 16, 44, 24, 16, 16, 44, 55, 18, 24, 30, 18, 18, 18, 12, 20, 12, 55});
    }

    private static void writeChecks(XSSFWorkbook workbook, Styles styles,
                                    List<CheckResult> results, ReportContext context,
                                    ZonedDateTime generatedAt) {
        Sheet sheet = workbook.createSheet("Check Details");
        String[] headers = {"Generated At (UTC)", "Declared Sprint Day", "Jira ID", "Jira Key",
                "Jira URL", "Summary", "Issue Type", "Assignee", "Assignee Account ID", "Status",
                "Resolution", "Resolved", "Check Type", "Rule Key", "Check Name", "Jira Field / Source",
                "Expected", "Actual", "Outcome", "Reason"};
        header(sheet, headers, styles.header);

        int rowIndex = 1;
        for (CheckResult result : results) {
            IssueDetails issue = result.issue();
            String issueUrl = issueUrl(context.jiraBaseUrl(), issue.issueKey());
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, generatedAt.toString(), styles.normal);
            numericCell(row, 1, context.runDay(), styles.normal);
            cell(row, 2, issue.jiraId(), styles.normal);
            hyperlinkCell(workbook, row, 3, issue.issueKey(), issueUrl, styles.hyperlink);
            hyperlinkCell(workbook, row, 4, issueUrl, issueUrl, styles.hyperlink);
            cell(row, 5, issue.summary(), styles.wrap);
            cell(row, 6, issue.issueType(), styles.normal);
            cell(row, 7, issue.assigneeName(), styles.normal);
            cell(row, 8, issue.assigneeAccountId(), styles.normal);
            cell(row, 9, issue.status(), styles.normal);
            cell(row, 10, issue.resolution(), styles.normal);
            cell(row, 11, Boolean.toString(issue.resolved()), styles.normal);
            cell(row, 12, result.checkType(), styles.normal);
            cell(row, 13, result.ruleKey(), styles.normal);
            cell(row, 14, result.checkName(), styles.normal);
            cell(row, 15, result.source(), styles.normal);
            cell(row, 16, result.expected(), styles.wrap);
            cell(row, 17, result.actual(), styles.wrap);
            CellStyle outcomeStyle = switch (result.outcome()) {
                case PASS -> styles.pass;
                case FAIL -> styles.fail;
                case SKIP -> styles.skip;
            };
            cell(row, 18, result.outcome().name(), outcomeStyle);
            cell(row, 19, result.reason(), styles.wrap);
        }
        finishTabularSheet(sheet, headers.length, rowIndex - 1,
                new int[]{28, 18, 16, 15, 44, 55, 18, 24, 30, 18, 18, 12, 14, 18, 22, 24, 28, 45, 12, 55});
    }

    private static void header(Sheet sheet, String[] values, CellStyle style) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(30);
        for (int index = 0; index < values.length; index++) {
            cell(row, index, values[index], style);
        }
    }

    private static void finishTabularSheet(Sheet sheet, int columns, int lastRow, int[] widths) {
        sheet.createFreezePane(0, 1);
        if (lastRow >= 0) {
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(lastRow, 0), 0, columns - 1));
        }
        for (int index = 0; index < widths.length; index++) {
            sheet.setColumnWidth(index, Math.min(widths[index], 255) * 256);
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
        private final CellStyle title;
        private final CellStyle header;
        private final CellStyle label;
        private final CellStyle normal;
        private final CellStyle wrap;
        private final CellStyle pass;
        private final CellStyle fail;
        private final CellStyle skip;
        private final CellStyle hyperlink;

        private Styles(XSSFWorkbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleFont.setColor(IndexedColors.WHITE.getIndex());
            title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            borders(header);

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            label = workbook.createCellStyle();
            label.setFont(boldFont);
            label.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            label.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            normal = workbook.createCellStyle();
            normal.setVerticalAlignment(VerticalAlignment.TOP);

            wrap = workbook.createCellStyle();
            wrap.cloneStyleFrom(normal);
            wrap.setWrapText(true);

            pass = outcomeStyle(workbook, IndexedColors.LIGHT_GREEN, boldFont);
            fail = outcomeStyle(workbook, IndexedColors.ROSE, boldFont);
            skip = outcomeStyle(workbook, IndexedColors.GREY_25_PERCENT, boldFont);

            Font linkFont = workbook.createFont();
            linkFont.setColor(IndexedColors.BLUE.getIndex());
            linkFont.setUnderline(Font.U_SINGLE);
            hyperlink = workbook.createCellStyle();
            hyperlink.cloneStyleFrom(normal);
            hyperlink.setFont(linkFont);
        }

        private static CellStyle outcomeStyle(XSSFWorkbook workbook, IndexedColors color, Font font) {
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            return style;
        }

        private static void borders(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
