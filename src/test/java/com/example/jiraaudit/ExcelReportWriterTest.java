package com.example.jiraaudit;

import org.apache.poi.ss.usermodel.CellType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelReportWriterTest {
    @TempDir
    Path tempDirectory;

    @Test
    void writesOneSimpleTicketGridWithPassFailAndRemarks() throws Exception {
        IssueDetails failedIssue = new IssueDetails("10002", "PAY-102", "=unsafe summary",
                "Asha Rao", "In Progress");
        IssueDetails passedIssue = new IssueDetails("10003", "PAY-103", "No code change",
                "Dev One", "Done");
        List<CheckResult> checks = List.of(
                new CheckResult(failedIssue, "FIELD", "coding-status", "Coding Status",
                        "customfield_10101", "Done", "In Progress", CheckResult.Outcome.FAIL,
                        "Coding Status does not match the Day 10 expected value"),
                new CheckResult(failedIssue, "ARTIFACT", "WBS_", "WBS_", "attachment",
                        "Attached filename starts with WBS_", "WBS_scope.xlsx", CheckResult.Outcome.PASS,
                        "Required artifact is attached"),
                new CheckResult(passedIssue, "FIELD", "coding-status", "Coding Status",
                        "customfield_10101", "Done | No Code Changes", "No Code Changes",
                        CheckResult.Outcome.PASS, "Expected value matched"),
                new CheckResult(passedIssue, "ARTIFACT", "CWC_", "CWC_",
                        "attachment + comments + coding-status",
                        "Attachment CWC_ or comment", "attachment=<missing>; comment=9",
                        CheckResult.Outcome.PASS,
                        "Missing artifact accepted by configured Not Applicable evidence")
        );
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T23:30:00Z"),
                ZoneId.of("Asia/Kolkata"));
        ExcelReportWriter writer = new ExcelReportWriter(clock);

        Path report = writer.write(checks, new ExcelReportWriter.ReportContext(
                10, "https://jira.company.example", tempDirectory.toString()));

        assertTrue(report.getFileName().toString().contains("day10-20261025-233000-000-"));
        assertTrue(report.getFileName().toString().endsWith(".xlsx"));
        try (InputStream input = Files.newInputStream(report);
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            assertEquals(1, workbook.getNumberOfSheets());
            var sheet = workbook.getSheet("Audit Results");
            assertNotNull(sheet);
            assertEquals(8, sheet.getRow(0).getLastCellNum());
            assertEquals("Run Day", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Jira ID", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Assignee", sheet.getRow(0).getCell(4).getStringCellValue());
            assertEquals("Jira Status", sheet.getRow(0).getCell(5).getStringCellValue());
            assertEquals("Audit Result", sheet.getRow(0).getCell(6).getStringCellValue());
            assertEquals("Remarks", sheet.getRow(0).getCell(7).getStringCellValue());

            var failedRow = sheet.getRow(1);
            assertEquals(10, failedRow.getCell(0).getNumericCellValue());
            assertEquals("10002", failedRow.getCell(1).getStringCellValue());
            assertEquals("PAY-102", failedRow.getCell(2).getStringCellValue());
            assertNotNull(failedRow.getCell(2).getHyperlink());
            assertEquals(CellType.STRING, failedRow.getCell(3).getCellType());
            assertEquals("=unsafe summary", failedRow.getCell(3).getStringCellValue());
            assertEquals("Asha Rao", failedRow.getCell(4).getStringCellValue());
            assertEquals("In Progress", failedRow.getCell(5).getStringCellValue());
            assertEquals("FAIL", failedRow.getCell(6).getStringCellValue());
            assertTrue(failedRow.getCell(7).getStringCellValue().contains("Coding Status"));

            var passedRow = sheet.getRow(2);
            assertEquals("PASS", passedRow.getCell(6).getStringCellValue());
            assertTrue(passedRow.getCell(7).getStringCellValue().contains("Not Applicable evidence"));
        }
    }
}
