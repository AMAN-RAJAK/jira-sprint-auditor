package com.example.jiraaudit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "audit", ignoreUnknownFields = false)
public record AuditProperties(
        Jira jira,
        String jql,
        Integer runDay,
        Integer pageSize,
        Map<Integer, Checkpoint> checkpoints,
        Artifacts artifacts,
        Report report
) {
    public record Jira(String baseUrl, String personalAccessToken) { }
    public record Checkpoint(Map<String, FieldRule> fields) { }
    public record FieldRule(String label, String jiraField, String acceptedValues) {
        public static final String NON_EMPTY_VALUE = "__NON_EMPTY__";

        public List<String> acceptedValueList() {
            if (acceptedValues == null) return List.of();
            return Arrays.stream(acceptedValues.split("\\$", -1))
                    .map(String::trim)
                    .toList();
        }

        public boolean requiresNonEmptyValue() {
            return acceptedValueList().stream()
                    .anyMatch(value -> NON_EMPTY_VALUE.equalsIgnoreCase(value));
        }
    }
    public record Artifacts(String day3WbsPrefix, Day10Artifacts day10) { }
    public record Day10Artifacts(String wbsPrefix, ArtifactRule cwc, ArtifactRule ut,
                                 ArtifactRule atdd, ArtifactRule st) { }
    public record ArtifactRule(String prefix, String fieldRule,
                               List<String> notApplicableValues, String commentContains) { }
    public record Report(String directory, Boolean failOnViolations, Boolean failOnEmpty) { }
}
