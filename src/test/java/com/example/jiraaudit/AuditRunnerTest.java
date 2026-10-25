package com.example.jiraaudit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRunnerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void dayThreeRequestsOnlyItsFieldsAndWritesOneGridWorkbook() throws Exception {
        AuditProperties properties = properties(3, true);
        var fields = MAPPER.readTree("""
                {"summary":"Ship audit","assignee":{"displayName":"Asha Rao","name":"arao"},
                 "status":{"name":"In Progress"},"coding":"In Progress","download":"Started",
                 "attachment":[{"filename":"WBS_scope.xlsx"}]}
                """);
        AtomicReference<Set<String>> requested = new AtomicReference<>();
        JiraClient client = (jql, requestedFields, pageSize) -> {
            requested.set(Set.copyOf(requestedFields));
            return List.of(new JiraClient.JiraIssue("10002", "PAY-102", fields));
        };
        AuditRunner runner = runner(properties, client);

        runner.run(null);

        assertEquals(0, runner.exitCode());
        assertEquals(Set.of("summary", "assignee", "status", "attachment", "coding", "download"),
                requested.get());
        assertFalse(requested.get().contains("st"));
        try (InputStream input = Files.newInputStream(onlyWorkbook());
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals("Audit Results", workbook.getSheetAt(0).getSheetName());
            assertEquals("PASS", workbook.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());
        }
    }

    @Test
    void dayTenFetchesCommentsOnlyWhenMissingArtifactNeedsEvidence() throws Exception {
        AuditProperties properties = properties(10, true);
        var fields = MAPPER.readTree("""
                {"summary":"ATDD story","assignee":{"displayName":"Asha Rao"},
                 "status":{"name":"Done"},"coding":"Done - On Time","st":"Done - On Time",
                 "atdd":"Done - On Time","attachment":[{"filename":"WBS_scope"},
                 {"filename":"CWC_review"},{"filename":"ATDD_evidence"},
                 {"filename":"ST_results"}],"issuelinks":[]}
                """);
        AtomicInteger commentCalls = new AtomicInteger();
        AtomicReference<Set<String>> requested = new AtomicReference<>();
        JiraClient client = new JiraClient() {
            @Override
            public List<JiraIssue> searchAll(String jql, Set<String> requestedFields, int pageSize) {
                requested.set(Set.copyOf(requestedFields));
                return List.of(new JiraIssue("10002", "PAY-102", fields));
            }

            @Override
            public List<JiraComment> getCommentsNewestFirst(String issueKey, int pageSize) {
                commentCalls.incrementAndGet();
                return List.of(new JiraComment("9", MAPPER.getNodeFactory()
                        .textNode("UT Not Applicable"), Instant.parse("2026-10-25T08:00:00Z")));
            }
        };
        AuditRunner runner = runner(properties, client);

        runner.run(null);

        assertEquals(1, commentCalls.get());
        assertTrue(requested.get().contains("issuelinks"));
        assertEquals(0, runner.exitCode());
        try (InputStream input = Files.newInputStream(onlyWorkbook());
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            var row = workbook.getSheet("Audit Results").getRow(1);
            assertEquals("PASS", row.getCell(6).getStringCellValue());
            assertTrue(row.getCell(7).getStringCellValue().contains("Not Applicable evidence"));
        }
    }

    @Test
    void missingRunDayStopsBeforeJiraOrReportAccess() throws Exception {
        AuditProperties properties = properties(null, true);
        AtomicInteger searchCalls = new AtomicInteger();
        JiraClient client = (jql, fields, pageSize) -> {
            searchCalls.incrementAndGet();
            return List.of();
        };
        AuditRunner runner = runner(properties, client);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runner.run(null));

        assertTrue(error.getMessage().contains("audit.run-day is required"));
        assertEquals(0, searchCalls.get());
        try (var files = Files.list(tempDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void failedCheckWritesWorkbookAndSetsExitCodeTwo() throws Exception {
        AuditProperties properties = properties(3, true);
        var fields = MAPPER.readTree("""
                {"summary":"Missing WBS","assignee":null,"status":{"name":"In Progress"},
                 "coding":"Blocked","download":"Started","attachment":[]}
                """);
        JiraClient client = (jql, requestedFields, pageSize) ->
                List.of(new JiraClient.JiraIssue("10003", "PAY-103", fields));
        AuditRunner runner = runner(properties, client);

        runner.run(null);

        assertEquals(2, runner.exitCode());
        try (InputStream input = Files.newInputStream(onlyWorkbook());
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            var row = workbook.getSheet("Audit Results").getRow(1);
            assertEquals("FAIL", row.getCell(6).getStringCellValue());
            assertTrue(row.getCell(7).getStringCellValue().contains("WBS_"));
            assertTrue(row.getCell(7).getStringCellValue().contains("Coding Status"));
        }
    }

    @Test
    void emptyJqlScopeFailsByDefault() {
        AuditRunner runner = runner(properties(3, true),
                (jql, requestedFields, pageSize) -> List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runner.run(null));

        assertTrue(error.getMessage().contains("no issues"));
    }

    private AuditRunner runner(AuditProperties properties, JiraClient client) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T06:30:00Z"), ZoneOffset.UTC);
        return new AuditRunner(properties, client, new PolicyEvaluator(), new ExcelReportWriter(clock));
    }

    private Path onlyWorkbook() throws Exception {
        try (var files = Files.list(tempDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".xlsx"))
                    .findFirst().orElseThrow();
        }
    }

    private AuditProperties properties(Integer runDay, boolean failOnEmpty) {
        AuditProperties base = TestAuditProperties.settings(
                runDay == null ? 3 : runDay,
                new AuditProperties.Report(tempDirectory.toString(), true, failOnEmpty));
        if (runDay != null) return base;
        return new AuditProperties(base.jira(), base.jql(), null, base.pageSize(),
                base.checkpoints(), base.artifacts(), base.report());
    }
}
