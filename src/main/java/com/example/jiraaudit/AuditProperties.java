package com.example.jiraaudit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "audit", ignoreUnknownFields = false)
public record AuditProperties(
        Jira jira,
        String jql,
        Integer runDay,
        Integer pageSize,
        Map<String, FieldRule> fields,
        Artifacts artifacts,
        FinalGate finalGate,
        Report report
) {
    public record Jira(String baseUrl, String email, String apiToken) { }
    public record FieldRule(String label, String jiraField,
                            Map<Integer, List<String>> days,
                            List<String> whenResolved) { }
    public record Artifacts(Boolean onlyForResolved, List<String> prefixes) { }
    public record FinalGate(Integer fromDay, List<String> linkedIssueTypes,
                            List<String> completedStatusCategoryKeys) { }
    public record Report(String directory, Boolean failOnViolations, Boolean failOnEmpty) { }
}
