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
        Report report
) {
    public record Jira(String baseUrl, String email, String apiToken) { }
    public record FieldRule(String label, String jiraField,
                            Map<Integer, List<String>> days) { }
    public record Artifacts(Map<Integer, List<String>> requiredByDay,
                            Map<String, MissingArtifactRule> missingExceptions) { }
    public record MissingArtifactRule(String fieldRule, List<String> fieldValues,
                                      String commentContains) { }
    public record Report(String directory, Boolean failOnViolations, Boolean failOnEmpty) { }
}
