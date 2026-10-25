package com.example.jiraaudit;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AuditConfigurationValidator {
    private static final List<String> REQUIRED_DAY_TEN_ARTIFACTS =
            List.of("wbs_", "cwc_", "ut_", "atdd_", "st_");
    private static final Set<String> WAIVABLE_ARTIFACTS =
            Set.of("cwc_", "ut_", "atdd_", "st_");

    private AuditConfigurationValidator() { }

    public static void validateCredentials(AuditProperties cfg) {
        if (cfg.jira() == null) throw new IllegalArgumentException("audit.jira is required");
        required(cfg.jira().baseUrl(), "audit.jira.base-url");
        required(cfg.jira().personalAccessToken(),
                "JIRA_PERSONAL_ACCESS_TOKEN / audit.jira.personal-access-token");
        URI url = URI.create(cfg.jira().baseUrl());
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
            throw new IllegalArgumentException(
                    "Jira base URL must be an HTTPS URL so the personal access token is protected");
        }
    }

    public static void validatePolicy(AuditProperties cfg) {
        required(cfg.jql(), "audit.jql");
        if (cfg.runDay() == null) {
            throw new IllegalArgumentException(
                    "audit.run-day is required; pass --audit.run-day=1, 3, 7, or 10");
        }
        if (!AuditDay.isAllowedRunDay(cfg.runDay())) {
            throw new IllegalArgumentException(
                    "audit.run-day must be 1, 3, 7, or 10 and later");
        }
        if (cfg.pageSize() != null && (cfg.pageSize() < 1 || cfg.pageSize() > 100)) {
            throw new IllegalArgumentException("audit.page-size must be 1..100");
        }
        validateFields(cfg.fields());
        validateArtifacts(cfg.artifacts(), cfg.fields());
        if (cfg.report() == null) throw new IllegalArgumentException("audit.report is required");
        required(cfg.report().directory(), "audit.report.directory");
    }

    private static void validateFields(Map<String, AuditProperties.FieldRule> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Configure at least one audit.fields entry");
        }
        Set<String> jiraFields = new HashSet<>();
        for (Map.Entry<String, AuditProperties.FieldRule> entry : fields.entrySet()) {
            required(entry.getKey(), "audit.fields rule key");
            AuditProperties.FieldRule rule = entry.getValue();
            if (rule == null) throw new IllegalArgumentException("Empty field rule: " + entry.getKey());
            required(rule.jiraField(), "jira-field for " + entry.getKey());
            if (!jiraFields.add(normalize(rule.jiraField()))) {
                throw new IllegalArgumentException("Duplicate jira-field: " + rule.jiraField());
            }
            if (rule.days() == null) {
                throw new IllegalArgumentException("Day expectations required for " + entry.getKey());
            }
            if (!rule.days().keySet().equals(Set.copyOf(AuditDay.CONFIGURED_DAYS))) {
                throw new IllegalArgumentException("Field " + entry.getKey() +
                        " must define exactly Day 1, 3, 7 and 10 expectations");
            }
            for (Map.Entry<Integer, List<String>> dayRule : rule.days().entrySet()) {
                if (dayRule.getValue() == null || dayRule.getValue().isEmpty()) {
                    throw new IllegalArgumentException("Expected values must not be empty for " +
                            entry.getKey() + " on Day " + dayRule.getKey());
                }
                dayRule.getValue().forEach(value -> required(value,
                        "expected value for " + entry.getKey() + " on Day " + dayRule.getKey()));
            }
        }
    }

    private static void validateArtifacts(AuditProperties.Artifacts artifacts,
                                          Map<String, AuditProperties.FieldRule> fields) {
        if (artifacts == null || artifacts.requiredByDay() == null) {
            throw new IllegalArgumentException("audit.artifacts.required-by-day is required");
        }
        if (!artifacts.requiredByDay().keySet().equals(Set.copyOf(AuditDay.CONFIGURED_DAYS))) {
            throw new IllegalArgumentException(
                    "audit.artifacts.required-by-day must define exactly Days 1, 3, 7 and 10");
        }
        Set<String> configuredPrefixes = new HashSet<>();
        for (Map.Entry<Integer, List<String>> entry : artifacts.requiredByDay().entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Artifact list is required for Day " + entry.getKey());
            }
            Set<String> dayPrefixes = new HashSet<>();
            for (String prefix : entry.getValue()) {
                required(prefix, "artifact prefix for Day " + entry.getKey());
                String normalized = normalize(prefix);
                if (!dayPrefixes.add(normalized)) {
                    throw new IllegalArgumentException("Duplicate artifact prefix on Day " +
                            entry.getKey() + ": " + prefix);
                }
                configuredPrefixes.add(normalized);
            }
        }
        if (!artifacts.requiredByDay().get(1).isEmpty() || !artifacts.requiredByDay().get(7).isEmpty()) {
            throw new IllegalArgumentException("Days 1 and 7 must not configure artifact checks");
        }
        if (artifacts.requiredByDay().get(3).size() != 1) {
            throw new IllegalArgumentException("Configure exactly one Day 3 WBS artifact prefix");
        }
        String dayThreePrefix = normalize(artifacts.requiredByDay().get(3).get(0));
        if (!"wbs_".equals(dayThreePrefix)) {
            throw new IllegalArgumentException("Day 3 must check only the WBS_ artifact prefix");
        }
        Set<String> dayTenPrefixes = new HashSet<>();
        artifacts.requiredByDay().get(10).stream()
                .map(AuditConfigurationValidator::normalize)
                .forEach(dayTenPrefixes::add);
        List<String> missingDayTenPrefixes = REQUIRED_DAY_TEN_ARTIFACTS.stream()
                .filter(prefix -> !dayTenPrefixes.contains(prefix))
                .map(String::toUpperCase)
                .toList();
        if (!missingDayTenPrefixes.isEmpty()) {
            throw new IllegalArgumentException("Day 10 must check WBS_, CWC_, UT_, ATDD_ and ST_; missing: " +
                    String.join(", ", missingDayTenPrefixes));
        }

        Map<String, AuditProperties.MissingArtifactRule> exceptions = artifacts.missingExceptions() == null
                ? Map.of() : artifacts.missingExceptions();
        Map<String, AuditProperties.MissingArtifactRule> normalizedExceptions = new LinkedHashMap<>();
        for (Map.Entry<String, AuditProperties.MissingArtifactRule> entry : exceptions.entrySet()) {
            required(entry.getKey(), "missing artifact exception prefix");
            String normalizedPrefix = normalize(entry.getKey());
            if (normalizedExceptions.putIfAbsent(normalizedPrefix, entry.getValue()) != null) {
                throw new IllegalArgumentException("Duplicate missing artifact exception: " + entry.getKey());
            }
            if (!configuredPrefixes.contains(normalizedPrefix)) {
                throw new IllegalArgumentException("Missing artifact exception is not required on any day: " +
                        entry.getKey());
            }
            if (normalizedPrefix.equals(dayThreePrefix)) {
                throw new IllegalArgumentException("The Day 3 WBS artifact cannot have a missing exception");
            }
            if (!WAIVABLE_ARTIFACTS.contains(normalizedPrefix)) {
                throw new IllegalArgumentException(
                        "Only CWC_, UT_, ATDD_ and ST_ can have missing artifact exceptions");
            }
            AuditProperties.MissingArtifactRule rule = entry.getValue();
            if (rule == null) {
                throw new IllegalArgumentException("Empty missing artifact exception: " + entry.getKey());
            }
            required(rule.commentContains(), "comment-contains for " + entry.getKey());
            boolean hasField = rule.fieldRule() != null && !rule.fieldRule().isBlank();
            boolean hasValues = rule.fieldValues() != null && !rule.fieldValues().isEmpty();
            if (hasField != hasValues) {
                throw new IllegalArgumentException("Both field-rule and field-values are required for " +
                        entry.getKey() + " when either is configured");
            }
            if (hasField) {
                if (!fields.containsKey(rule.fieldRule())) {
                    throw new IllegalArgumentException("Unknown field-rule for " + entry.getKey() + ": " +
                            rule.fieldRule());
                }
                rule.fieldValues().forEach(value -> required(value,
                        "field-values entry for " + entry.getKey()));
            }
        }

        validateRequiredException(normalizedExceptions, "cwc_", "CWC Not Applicable",
                "coding-status", List.of("No Code Changes"));
        validateRequiredException(normalizedExceptions, "ut_", "UT Not Applicable",
                null, List.of());
        validateRequiredException(normalizedExceptions, "atdd_", "ATDD Not Applicable",
                null, List.of());
        validateRequiredException(normalizedExceptions, "st_", "ST Not Applicable",
                "st-status", List.of("Not Applicable"));
    }

    private static void validateRequiredException(
            Map<String, AuditProperties.MissingArtifactRule> exceptions,
            String prefix,
            String requiredComment,
            String requiredFieldRule,
            List<String> requiredFieldValues) {
        AuditProperties.MissingArtifactRule rule = exceptions.get(prefix);
        if (rule == null) {
            throw new IllegalArgumentException("Missing required " + prefix.toUpperCase() +
                    " not-applicable rule");
        }
        if (!normalizePhrase(requiredComment).equals(normalizePhrase(rule.commentContains()))) {
            throw new IllegalArgumentException(prefix.toUpperCase() + " comment-contains must be: " +
                    requiredComment);
        }

        String configuredFieldRule = rule.fieldRule() == null ? null : normalize(rule.fieldRule());
        if (requiredFieldRule == null) {
            if (configuredFieldRule != null || (rule.fieldValues() != null && !rule.fieldValues().isEmpty())) {
                throw new IllegalArgumentException(prefix.toUpperCase() +
                        " must use only its not-applicable comment (no field condition)");
            }
            return;
        }
        if (!normalize(requiredFieldRule).equals(configuredFieldRule)) {
            throw new IllegalArgumentException(prefix.toUpperCase() + " field-rule must be: " +
                    requiredFieldRule);
        }
        Set<String> configuredValues = new HashSet<>();
        if (rule.fieldValues() != null) {
            rule.fieldValues().stream().map(AuditConfigurationValidator::normalize)
                    .forEach(configuredValues::add);
        }
        Set<String> expectedValues = new HashSet<>();
        requiredFieldValues.stream().map(AuditConfigurationValidator::normalize)
                .forEach(expectedValues::add);
        if (!configuredValues.equals(expectedValues)) {
            throw new IllegalArgumentException(prefix.toUpperCase() + " field-values must be exactly: " +
                    String.join(", ", requiredFieldValues));
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePhrase(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing setting: " + name);
        }
    }
}
