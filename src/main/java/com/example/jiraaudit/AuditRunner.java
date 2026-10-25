package com.example.jiraaudit;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AuditRunner implements ApplicationRunner {
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
        int runDay = properties.runDay();
        int policyDay = AuditDay.policyDay(runDay);
        int pageSize = properties.pageSize() == null ? 100 : properties.pageSize();

        Set<String> requestedFields = new LinkedHashSet<>(
                List.of("summary", "assignee", "status", "attachment"));
        properties.fields().values().forEach(rule -> requestedFields.add(rule.jiraField()));

        System.out.printf("Jira audit | Declared Day %d | Policy Day %d | JQL: %s%n",
                runDay, policyDay, properties.jql());
        List<JiraClient.JiraIssue> issues = jiraClient.searchAll(
                properties.jql(), requestedFields, pageSize);
        if (issues.isEmpty() && !Boolean.FALSE.equals(properties.report().failOnEmpty())) {
            throw new IllegalStateException("JQL returned no issues. Check the sprint ID, permissions and query, " +
                    "or set audit.report.fail-on-empty=false when an empty scope is expected.");
        }

        List<CheckResult> checks = new ArrayList<>();
        for (JiraClient.JiraIssue issue : issues) {
            List<JiraClient.JiraComment> comments = evaluator.requiresComments(issue, properties, runDay)
                    ? jiraClient.getCommentsNewestFirst(issue.key(), pageSize)
                    : List.of();
            checks.addAll(evaluator.evaluate(issue, properties, runDay, comments));
        }

        Path workbook = reportWriter.write(checks, new ExcelReportWriter.ReportContext(
                runDay, properties.jira().baseUrl(), properties.report().directory()));
        Set<String> failedIssues = checks.stream()
                .filter(check -> check.outcome() == CheckResult.Outcome.FAIL)
                .map(CheckResult::issueKey).collect(Collectors.toSet());
        long passedIssues = issues.stream().filter(issue -> !failedIssues.contains(issue.key())).count();
        System.out.printf("Issues: %d | PASS: %d | FAIL: %d%n",
                issues.size(), passedIssues, failedIssues.size());
        checks.stream().filter(check -> check.outcome() == CheckResult.Outcome.FAIL).forEach(check ->
                System.out.printf("FAIL  %s | %s: %s%n",
                        check.issueKey(), check.checkName(), check.reason()));
        System.out.println("Excel report: " + workbook);
        if (!failedIssues.isEmpty() && !Boolean.FALSE.equals(properties.report().failOnViolations())) {
            exitCode = 2;
        }
    }

    public int exitCode() {
        return exitCode;
    }
}
