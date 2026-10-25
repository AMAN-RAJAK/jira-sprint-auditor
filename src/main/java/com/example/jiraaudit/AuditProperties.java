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
    public record Checkpoint(Map<String, FieldRule> fields, LinkedBugRule linkedBugs) { }
    public record FieldRule(String label, String jiraField, String acceptedValues) {
        public static final String NON_EMPTY_VALUE = "__NON_EMPTY__";

        public List<String> acceptedValueList() {
            return dollarSeparatedValues(acceptedValues);
        }

        public boolean requiresNonEmptyValue() {
            return acceptedValueList().stream()
                    .anyMatch(value -> NON_EMPTY_VALUE.equalsIgnoreCase(value));
        }
    }
    public record LinkedBugRule(String issueType, String resolvedStatuses) {
        public List<String> resolvedStatusList() {
            return dollarSeparatedValues(resolvedStatuses);
        }
    }
    public record Artifacts(String day3WbsPrefix, Day10Artifacts day10) {
        public List<String> day3WbsPrefixList() {
            return dollarSeparatedValues(day3WbsPrefix);
        }
    }
    public record Day10Artifacts(String wbsPrefix, ArtifactRule cwc, ArtifactRule ut,
                                 ArtifactRule atdd, ArtifactRule st) {
        public List<String> wbsPrefixList() {
            return dollarSeparatedValues(wbsPrefix);
        }
    }
    public record ArtifactRule(String prefix, String fieldRule,
                               String notApplicableValues, String commentContains) {
        public List<String> notApplicableValueList() {
            return dollarSeparatedValues(notApplicableValues);
        }
    }
    public record Report(String directory, Boolean failOnViolations, Boolean failOnEmpty) { }

    private static List<String> dollarSeparatedValues(String configuredValues) {
        if (configuredValues == null) return List.of();
        return Arrays.stream(configuredValues.split("\\$", -1))
                .map(String::trim)
                .toList();
    }
}
