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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRunnerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void dayThreeRunsJqlChecksOnlyWbsAndWritesOneGridWorkbook() throws Exception {
        AuditProperties properties = properties(3, true);
        var fields = MAPPER.readTree("""
                {"summary":"Ship audit","assignee":{"displayName":"Asha Rao","accountId":"abc-123"},
                 "status":{"name":"In Progress"},"coding":"In Progress","st":"In Progress",
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
        assertTrue(requested.get().containsAll(Set.of(
                "summary", "assignee", "status", "attachment", "coding", "st")));
        Path report = onlyWorkbook();
        try (InputStream input = Files.newInputStream(report);
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals("Audit Results", workbook.getSheetAt(0).getSheetName());
            assertEquals("PASS", workbook.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());
        }
    }

    @Test
    void dayTenFetchesCommentsOnlyAfterExceptionEligibleArtifactIsMissing() throws Exception {
        AuditProperties properties = properties(10, true);
        var fields = MAPPER.readTree("""
                {"summary":"No code story","assignee":{"displayName":"Asha Rao"},
                 "status":{"name":"Done"},"coding":"No Code Changes","st":"Done",
                 "attachment":[{"filename":"WBS_scope"},{"filename":"UT_results"},
                   {"filename":"ATDD_evidence"},{"filename":"ST_results"}]}
                """);
        AtomicInteger commentCalls = new AtomicInteger();
        JiraClient client = new JiraClient() {
            @Override
            public List<JiraIssue> searchAll(String jql, Set<String> requestedFields, int pageSize) {
                return List.of(new JiraIssue("10002", "PAY-102", fields));
            }

            @Override
            public List<JiraComment> getCommentsNewestFirst(String issueKey, int pageSize) {
                commentCalls.incrementAndGet();
                return List.of(new JiraComment("9", MAPPER.getNodeFactory()
                        .textNode("CWC Not Applicable"), Instant.parse("2026-10-25T08:00:00Z")));
            }
        };
        AuditRunner runner = runner(properties, client);

        runner.run(null);

        assertEquals(1, commentCalls.get());
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
                 "coding":"Started","st":"In Progress","attachment":[]}
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
        Map<String, AuditProperties.FieldRule> fields = new LinkedHashMap<>();
        fields.put("coding-status", new AuditProperties.FieldRule("Coding Status", "coding",
                Map.of(1, List.of("Started"), 3, List.of("In Progress"), 7, List.of("Done"),
                        10, List.of("Done", "No Code Changes"))));
        fields.put("st-status", new AuditProperties.FieldRule("ST Status", "st",
                Map.of(1, List.of("Not Started"), 3, List.of("In Progress"), 7, List.of("Done"),
                        10, List.of("Done", "Not Applicable"))));

        Map<String, AuditProperties.MissingArtifactRule> exceptions = new LinkedHashMap<>();
        exceptions.put("CWC_", new AuditProperties.MissingArtifactRule(
                "coding-status", List.of("No Code Changes"), "CWC Not Applicable"));
        exceptions.put("UT_", new AuditProperties.MissingArtifactRule(null, null, "UT Not Applicable"));
        exceptions.put("ATDD_", new AuditProperties.MissingArtifactRule(null, null, "ATDD Not Applicable"));
        exceptions.put("ST_", new AuditProperties.MissingArtifactRule(
                "st-status", List.of("Not Applicable"), "ST Not Applicable"));

        return new AuditProperties(
                new AuditProperties.Jira("https://example.atlassian.net", "audit@example.com", "token"),
                "project = PAY AND sprint = 123", runDay, 100, fields,
                new AuditProperties.Artifacts(Map.of(
                        1, List.of(), 3, List.of("WBS_"), 7, List.of(),
                        10, List.of("WBS_", "CWC_", "UT_", "ATDD_", "ST_")), exceptions),
                new AuditProperties.Report(tempDirectory.toString(), true, failOnEmpty));
    }
}
