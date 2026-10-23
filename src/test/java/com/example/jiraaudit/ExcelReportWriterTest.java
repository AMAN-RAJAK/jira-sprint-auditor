package com.example.jiraaudit;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelReportWriterTest {
    @TempDir
    Path tempDirectory;

    @Test
    void writesRealWorkbookWithIssueAndDetailedComplianceData() throws Exception {
        IssueDetails issue = new IssueDetails("10002", "PAY-102", "=unsafe-looking summary",
                "Story", "Asha Rao", "abc-123", "Done", "Fixed", true);
        List<CheckResult> checks = List.of(
                new CheckResult(issue, "FIELD", "download", "Download", "customfield_10101",
                        "Done", "Started", CheckResult.Outcome.FAIL, "Expected value not present"),
                new CheckResult(issue, "ARTIFACT", "UT_", "UT_", "attachment",
                        "Filename starts with UT_", "UT_results.xlsx", CheckResult.Outcome.PASS,
                        "Attachment found"),
                new CheckResult(issue, "LINKED_ISSUE", "linked-issues-closed", "BUG-7 (Bug)",
                        "issuelinks / blocks / outward", "Completed status category",
                        "BUG-7 | Closed | completed", CheckResult.Outcome.PASS,
                        "Linked issue is in a completed status category",
                        new LinkedIssueDetails("20007", "BUG-7", "Fix payment defect", "Bug",
                                "Dev One", "dev-1", "Closed", "completed", "Fixed",
                                "outward", "blocks")),
                new CheckResult(issue, "FINAL_GATE", "all-checks-pass", "Final compliance gate",
                        "Day 10+ policy", "Every check is PASS", "PASS=2 | FAIL=1 | SKIP=0",
                        CheckResult.Outcome.FAIL, "One underlying check failed")
        );
        // The clock uses a non-UTC zone near midnight; report evidence must still be UTC.
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T23:30:00Z"),
                ZoneId.of("Asia/Kolkata"));
        ExcelReportWriter writer = new ExcelReportWriter(clock);
        ExcelReportWriter.ReportContext context = new ExcelReportWriter.ReportContext(
                7, "project = PAY AND sprint = 123",
                "https://example.atlassian.net", tempDirectory.toString());

        Path report = writer.write(checks, context);
        Path secondReport = writer.write(checks, context);

        assertTrue(report.getFileName().toString().endsWith(".xlsx"));
        assertTrue(report.getFileName().toString().contains("day7-20261022-233000-000-"));
        assertNotEquals(report, secondReport);
        assertTrue(Files.size(report) > 0);
        try (var files = Files.list(tempDirectory)) {
            assertEquals(2, files.filter(path -> path.getFileName().toString().endsWith(".xlsx")).count());
        }
        try (InputStream input = Files.newInputStream(report);
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            assertEquals(4, workbook.getNumberOfSheets());
            assertNotNull(workbook.getSheet("Run Summary"));
            assertNotNull(workbook.getSheet("Issues"));
            assertNotNull(workbook.getSheet("Linked Issues"));
            assertNotNull(workbook.getSheet("Check Details"));

            var issueRow = workbook.getSheet("Issues").getRow(1);
            assertEquals("10002", issueRow.getCell(0).getStringCellValue());
            assertEquals("PAY-102", issueRow.getCell(1).getStringCellValue());
            assertNotNull(issueRow.getCell(1).getHyperlink());
            assertEquals("Asha Rao", issueRow.getCell(5).getStringCellValue());
            assertEquals("NON-COMPLIANT", issueRow.getCell(14).getStringCellValue());
            assertEquals(3, issueRow.getCell(10).getNumericCellValue());
            assertEquals(1, issueRow.getCell(12).getNumericCellValue());
            assertTrue(!issueRow.getCell(15).getStringCellValue().contains("FINAL_GATE"));
            assertEquals(CellType.STRING, issueRow.getCell(3).getCellType());
            assertEquals("=unsafe-looking summary", issueRow.getCell(3).getStringCellValue());

            var summarySheet = workbook.getSheet("Run Summary");
            assertEquals("Generated at (UTC)", summarySheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("2026-10-22T23:30Z", summarySheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("Declared sprint day", summarySheet.getRow(3).getCell(0).getStringCellValue());
            assertEquals(7, summarySheet.getRow(3).getCell(1).getNumericCellValue());

            Set<String> obsoleteLabels = Set.of("Execution Date", "Effective Policy Date",
                    "Sprint Start Date", "Sprint Time Zone");
            for (Row row : summarySheet) {
                if (row.getCell(0) != null && row.getCell(0).getCellType() == CellType.STRING) {
                    assertTrue(!obsoleteLabels.contains(row.getCell(0).getStringCellValue()));
                }
            }

            var detailSheet = workbook.getSheet("Check Details");
            var detailHeader = detailSheet.getRow(0);
            assertEquals(20, detailHeader.getLastCellNum());
            assertEquals("Generated At (UTC)", detailHeader.getCell(0).getStringCellValue());
            assertEquals("Declared Sprint Day", detailHeader.getCell(1).getStringCellValue());
            var detailRow = detailSheet.getRow(1);
            assertEquals("2026-10-22T23:30Z", detailRow.getCell(0).getStringCellValue());
            assertEquals(7, detailRow.getCell(1).getNumericCellValue());
            assertEquals("customfield_10101", detailRow.getCell(15).getStringCellValue());
            assertEquals("FAIL", detailRow.getCell(18).getStringCellValue());

            var linkedRow = workbook.getSheet("Linked Issues").getRow(1);
            assertEquals("10002", linkedRow.getCell(0).getStringCellValue());
            assertEquals("20007", linkedRow.getCell(4).getStringCellValue());
            assertEquals("BUG-7", linkedRow.getCell(5).getStringCellValue());
            assertEquals("Dev One", linkedRow.getCell(9).getStringCellValue());
            assertEquals("completed", linkedRow.getCell(12).getStringCellValue());
            assertEquals("PASS", linkedRow.getCell(16).getStringCellValue());

            assertEquals(1,
                    summarySheet.getRow(11).getCell(1).getNumericCellValue());
        }
    }
}
