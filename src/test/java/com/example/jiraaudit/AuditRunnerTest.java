package com.example.jiraaudit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void requestsTrackingMetadataAndWritesPassingDaySevenWorkbook() throws Exception {
        AuditProperties properties = properties(true);

        var fields = MAPPER.readTree("""
                {"summary":"Ship audit","issuetype":{"name":"Story"},
                 "assignee":{"displayName":"Asha Rao","accountId":"abc-123"},
                 "status":{"name":"Done"},"resolution":{"name":"Fixed"},
                 "customfield_10101":{"value":"Done"},
                 "attachment":[{"filename":"WBS_scope.xlsx"},{"filename":"CWC_review.pdf"},
                               {"filename":"UT_results.xlsx"}]}
                """);
        AtomicReference<Set<String>> requested = new AtomicReference<>();
        JiraClient client = (jql, requestedFields, pageSize) -> {
            requested.set(Set.copyOf(requestedFields));
            return List.of(new JiraClient.JiraIssue("10002", "PAY-102", fields));
        };
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T06:30:00Z"), ZoneOffset.UTC);
        AuditRunner runner = new AuditRunner(properties, client, new PolicyEvaluator(),
                new ExcelReportWriter(clock));

        runner.run(null);

        assertEquals(0, runner.exitCode());
        assertTrue(requested.get().containsAll(Set.of("summary", "issuetype", "assignee", "status",
                "resolution", "attachment", "customfield_10101")));
        try (var files = Files.list(tempDirectory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".xlsx")).count());
        }
    }

    @Test
    void usesExplicitDayThreeWithoutAnyCalendarCalculation() throws Exception {
        AuditProperties properties = properties(true, 3);
        var fields = MAPPER.readTree("""
                {"summary":"Ship audit","issuetype":{"name":"Story"},
                 "assignee":{"displayName":"Asha Rao","accountId":"abc-123"},
                 "status":{"name":"In Progress"},"resolution":null,
                 "customfield_10101":{"value":"In Progress"},"attachment":[]}
                """);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Set<String>> requested = new AtomicReference<>();
        JiraClient client = (jql, requestedFields, pageSize) -> {
            calls.incrementAndGet();
            requested.set(Set.copyOf(requestedFields));
            return List.of(new JiraClient.JiraIssue("10002", "PAY-102", fields));
        };
        // This timestamp intentionally has no relationship to the declared sprint day.
        Clock clock = Clock.fixed(Instant.parse("2040-01-01T23:59:59Z"), ZoneOffset.UTC);
        AuditRunner runner = new AuditRunner(properties, client, new PolicyEvaluator(),
                new ExcelReportWriter(clock));

        runner.run(null);

        assertEquals(0, runner.exitCode());
        assertEquals(1, calls.get());
        assertTrue(!requested.get().contains("issuelinks"));
        try (var files = Files.list(tempDirectory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString()
                    .startsWith("jira-audit-day3-")).count());
        }
    }

    @Test
    void missingRunDayStopsBeforeJiraAccessOrWorkbookCreation() throws Exception {
        AuditProperties properties = properties(true, null);
        AtomicInteger calls = new AtomicInteger();
        JiraClient client = (jql, requestedFields, pageSize) -> {
            calls.incrementAndGet();
            return List.of();
        };
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:30:00Z"), ZoneOffset.UTC);
        AuditRunner runner = new AuditRunner(properties, client, new PolicyEvaluator(),
                new ExcelReportWriter(clock));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runner.run(null));

        assertTrue(error.getMessage().contains("audit.run-day is required"));
        assertEquals(0, calls.get());
        try (var files = Files.list(tempDirectory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".xlsx")).count());
        }
    }

    @Test
    void rejectsEmptyJqlScopeByDefault() {
        AuditProperties properties = properties(true);
        JiraClient emptyClient = (jql, requestedFields, pageSize) -> List.of();
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:30:00Z"), ZoneOffset.UTC);
        AuditRunner runner = new AuditRunner(properties, emptyClient, new PolicyEvaluator(),
                new ExcelReportWriter(clock));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> runner.run(null));

        assertTrue(error.getMessage().contains("no issues"));
    }

    @Test
    void dayTenLoadsLinkedIssuesAndFailsForAnOpenBugWhileStillWritingWorkbook() throws Exception {
        AuditProperties properties = properties(true, 10);
        var parentFields = MAPPER.readTree("""
                {"summary":"Ship audit","issuetype":{"name":"Story"},
                 "assignee":{"displayName":"Asha Rao","accountId":"abc-123"},
                 "status":{"name":"Done"},"resolution":{"name":"Fixed"},
                 "customfield_10101":{"value":"Done"},
                 "attachment":[{"filename":"WBS_scope.xlsx"},{"filename":"CWC_review.pdf"},
                               {"filename":"UT_results.xlsx"}],
                 "issuelinks":[{"type":{"name":"blocks"},
                   "outwardIssue":{"id":"20007","key":"BUG-7"}}]}
                """);
        var bugFields = MAPPER.readTree("""
                {"summary":"Fix payment defect","issuetype":{"name":"Bug"},
                 "assignee":{"displayName":"Dev One","accountId":"dev-1"},
                 "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}},
                 "resolution":null}
                """);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Set<String>> primaryFields = new AtomicReference<>();
        AtomicReference<String> linkedJql = new AtomicReference<>();
        AtomicReference<Set<Long>> reconciledIds = new AtomicReference<>();
        JiraClient client = new JiraClient() {
            @Override
            public List<JiraClient.JiraIssue> searchAll(String jql, Set<String> requestedFields, int pageSize) {
                calls.incrementAndGet();
                assertEquals(properties.jql(), jql);
                primaryFields.set(Set.copyOf(requestedFields));
                return List.of(new JiraClient.JiraIssue("10002", "PAY-102", parentFields));
            }

            @Override
            public List<JiraClient.JiraIssue> searchAll(String jql, Set<String> requestedFields, int pageSize,
                                                        Set<Long> reconcileIssueIds) {
                calls.incrementAndGet();
                linkedJql.set(jql);
                reconciledIds.set(Set.copyOf(reconcileIssueIds));
                assertEquals(Set.of("summary", "issuetype", "assignee", "status", "resolution"),
                        requestedFields);
                return List.of(new JiraClient.JiraIssue("20007", "BUG-7", bugFields));
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T06:30:00Z"), ZoneOffset.UTC);
        AuditRunner runner = new AuditRunner(properties, client, new PolicyEvaluator(),
                new ExcelReportWriter(clock));

        runner.run(null);

        assertEquals(2, calls.get());
        assertTrue(primaryFields.get().contains("issuelinks"));
        assertEquals("key in (BUG-7) ORDER BY key ASC", linkedJql.get());
        assertEquals(Set.of(20007L), reconciledIds.get());
        assertEquals(2, runner.exitCode());
        try (var files = Files.list(tempDirectory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".xlsx")).count());
        }
    }

    private AuditProperties properties(boolean failOnEmpty) {
        return properties(failOnEmpty, 7);
    }

    private AuditProperties properties(boolean failOnEmpty, Integer runDay) {
        Map<String, AuditProperties.FieldRule> rules = new LinkedHashMap<>();
        rules.put("download", new AuditProperties.FieldRule("Download", "customfield_10101",
                Map.of(1, List.of("Started"), 3, List.of("In Progress"),
                        7, List.of("Done"), 10, List.of("Done")),
                List.of("Done")));
        return new AuditProperties(
                new AuditProperties.Jira("https://example.atlassian.net", "audit@example.com", "token"),
                "project = PAY AND sprint = 123",
                runDay, 100, rules,
                new AuditProperties.Artifacts(true, List.of("WBS_", "CWC_", "UT_")),
                new AuditProperties.FinalGate(10, List.of("Bug", "Task"),
                        List.of("completed", "done")),
                new AuditProperties.Report(tempDirectory.toString(), true, failOnEmpty));
    }
}
