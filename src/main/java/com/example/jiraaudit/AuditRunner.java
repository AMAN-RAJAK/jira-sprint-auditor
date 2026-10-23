package com.example.jiraaudit;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AuditRunner implements ApplicationRunner {
    private static final int LINKED_ISSUE_BATCH_SIZE = 50;
    private static final Set<String> LINKED_ISSUE_FIELDS = Set.of(
            "summary", "issuetype", "assignee", "status", "resolution");

    private final AuditProperties properties;
    private final JiraClient jiraClient;
    private final PolicyEvaluator evaluator;
    private final ExcelReportWriter reportWriter;
    private int exitCode;

    public AuditRunner(AuditProperties properties, JiraClient jiraClient,
                       PolicyEvaluator evaluator, ExcelReportWriter reportWriter) {
        this.properties = properties;
        this.jiraClient = jiraClient;
        this.evaluator = evaluator;
        this.reportWriter = reportWriter;
    }

    @Override
    public void run(ApplicationArguments ignored) throws Exception {
        AuditConfigurationValidator.validatePolicy(properties);
        int day = properties.runDay();

        Set<String> requestedFields = new LinkedHashSet<>(List.of(
                "summary", "issuetype", "assignee", "status", "resolution", "attachment"));
        properties.fields().values().forEach(r -> requestedFields.add(r.jiraField()));
        boolean finalGateActive = PolicyEvaluator.isFinalGateActive(properties, day);
        if (finalGateActive) requestedFields.add("issuelinks");
        System.out.printf("Jira audit | Declared Day %d | JQL: %s%n", day, properties.jql());
        List<JiraClient.JiraIssue> issues = jiraClient.searchAll(properties.jql(), requestedFields,
                properties.pageSize() == null ? 100 : properties.pageSize());
        if (issues.isEmpty() && !Boolean.FALSE.equals(properties.report().failOnEmpty())) {
            throw new IllegalStateException("JQL returned no issues. Check the sprint ID, permissions and query, " +
                    "or set audit.report.fail-on-empty=false when an empty scope is expected.");
        }
        Map<String, JiraClient.JiraIssue> linkedIssues = finalGateActive
                ? loadLinkedIssues(issues, properties.pageSize() == null ? 100 : properties.pageSize())
                : Map.of();
        List<CheckResult> checks = new ArrayList<>();
        for (JiraClient.JiraIssue issue : issues) {
            checks.addAll(evaluator.evaluate(issue, properties, day, linkedIssues));
        }

        Path workbook = reportWriter.write(checks, new ExcelReportWriter.ReportContext(
                day, properties.jql(), properties.jira().baseUrl(), properties.report().directory()));
        long passes = checks.stream().filter(c -> !c.rollup())
                .filter(c -> c.outcome() == CheckResult.Outcome.PASS).count();
        long failures = checks.stream().filter(c -> !c.rollup())
                .filter(c -> c.outcome() == CheckResult.Outcome.FAIL).count();
        long skips = checks.stream().filter(c -> !c.rollup())
                .filter(c -> c.outcome() == CheckResult.Outcome.SKIP).count();
        Set<String> failedTickets = checks.stream()
                .filter(c -> !c.rollup())
                .filter(c -> c.outcome() == CheckResult.Outcome.FAIL)
                .map(CheckResult::issueKey).collect(Collectors.toSet());
        System.out.printf("Issues: %d | PASS: %d | FAIL: %d | SKIP: %d | Failing issues: %d%n",
                issues.size(), passes, failures, skips, failedTickets.size());
        checks.stream().filter(c -> !c.rollup())
                .filter(c -> c.outcome() == CheckResult.Outcome.FAIL).forEach(c ->
                System.out.printf("FAIL  %s | %s: %s | actual=%s | expected=%s%n",
                        c.issueKey(), c.checkType(), c.checkName(), c.actual(), c.expected()));
        System.out.println("Excel report: " + workbook);
        boolean anyFailure = checks.stream().anyMatch(c -> c.outcome() == CheckResult.Outcome.FAIL);
        if (anyFailure && !Boolean.FALSE.equals(properties.report().failOnViolations())) {
            exitCode = 2;
        }
    }

    public int exitCode() {
        return exitCode;
    }

    private Map<String, JiraClient.JiraIssue> loadLinkedIssues(List<JiraClient.JiraIssue> primaryIssues,
                                                               int pageSize) {
        Map<String, JiraClient.JiraIssue> available = new LinkedHashMap<>();
        for (JiraClient.JiraIssue issue : primaryIssues) {
            available.put(JiraIssueLinks.normalizeKey(issue.key()), issue);
        }

        Map<String, Long> linkedIssueIds = new LinkedHashMap<>();
        for (JiraClient.JiraIssue issue : primaryIssues) {
            for (JiraIssueLinks.LinkReference reference : JiraIssueLinks.parse(issue).references()) {
                String key = JiraIssueLinks.normalizeKey(reference.issueKey());
                if (!available.containsKey(key)) {
                    linkedIssueIds.putIfAbsent(key, Long.parseLong(reference.issueId()));
                }
            }
        }

        List<String> keys = new ArrayList<>(linkedIssueIds.keySet());
        for (int start = 0; start < keys.size(); start += LINKED_ISSUE_BATCH_SIZE) {
            int end = Math.min(start + LINKED_ISSUE_BATCH_SIZE, keys.size());
            List<String> batch = keys.subList(start, end);
            String linkedJql = "key in (" + String.join(", ", batch) + ") ORDER BY key ASC";
            Set<Long> reconcileIssueIds = batch.stream().map(linkedIssueIds::get)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<JiraClient.JiraIssue> fetched = jiraClient.searchAll(
                    linkedJql, LINKED_ISSUE_FIELDS, pageSize, reconcileIssueIds);
            for (JiraClient.JiraIssue linked : fetched) {
                String normalizedKey = JiraIssueLinks.normalizeKey(linked.key());
                if (!linkedIssueIds.containsKey(normalizedKey)) {
                    throw new IllegalStateException("Jira linked-issue query returned an unexpected issue: " +
                            linked.key());
                }
                JiraClient.JiraIssue previous = available.putIfAbsent(normalizedKey, linked);
                if (previous != null && (!previous.id().equals(linked.id()) ||
                        !previous.key().equalsIgnoreCase(linked.key()))) {
                    throw new IllegalStateException("Jira returned conflicting linked issue identities for " +
                            linked.key());
                }
            }
        }
        return Map.copyOf(available);
    }
}
