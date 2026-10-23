package com.example.jiraaudit;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AuditConfigurationValidator {
    private AuditConfigurationValidator() { }

    public static void validateCredentials(AuditProperties cfg) {
        if (cfg.jira() == null) throw new IllegalArgumentException("audit.jira is required");
        required(cfg.jira().baseUrl(), "audit.jira.base-url");
        required(cfg.jira().email(), "JIRA_EMAIL / audit.jira.email");
        required(cfg.jira().apiToken(), "JIRA_API_TOKEN / audit.jira.api-token");
        URI url = URI.create(cfg.jira().baseUrl());
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
            throw new IllegalArgumentException("Jira base URL must be an HTTPS URL");
        }
    }

    public static void validatePolicy(AuditProperties cfg) {
        required(cfg.jql(), "audit.jql");
        if (cfg.runDay() == null)
            throw new IllegalArgumentException(
                    "audit.run-day is required; pass --audit.run-day=N (for example 1, 3, 7, or 10)");
        if (cfg.runDay() < 1)
            throw new IllegalArgumentException("audit.run-day must be >= 1");
        if (cfg.pageSize() != null && (cfg.pageSize() < 1 || cfg.pageSize() > 100))
            throw new IllegalArgumentException("audit.page-size must be 1..100");
        validateFinalGate(cfg.finalGate());
        if (cfg.fields() == null || cfg.fields().isEmpty())
            throw new IllegalArgumentException("Configure at least one audit.fields entry");
        Set<String> jiraFields = new HashSet<>();
        for (Map.Entry<String, AuditProperties.FieldRule> entry : cfg.fields().entrySet()) {
            AuditProperties.FieldRule rule = entry.getValue();
            if (rule == null) throw new IllegalArgumentException("Empty field rule: " + entry.getKey());
            required(rule.jiraField(), "jira-field for " + entry.getKey());
            if (!jiraFields.add(rule.jiraField())) {
                throw new IllegalArgumentException("Duplicate jira-field: " + rule.jiraField());
            }
            if (rule.days() == null || !rule.days().containsKey(1)) {
                throw new IllegalArgumentException("Day 1 expectation required for " + entry.getKey());
            }
            if (!rule.days().containsKey(cfg.finalGate().fromDay())) {
                throw new IllegalArgumentException("Day " + cfg.finalGate().fromDay() +
                        " final-gate expectation required for " + entry.getKey());
            }
            for (Map.Entry<Integer, List<String>> dayRule : rule.days().entrySet()) {
                if (dayRule.getKey() == null || dayRule.getKey() < 1 ||
                        dayRule.getValue() == null || dayRule.getValue().isEmpty()) {
                    throw new IllegalArgumentException("Invalid day rule for " + entry.getKey());
                }
                if (dayRule.getKey() > cfg.finalGate().fromDay()) {
                    throw new IllegalArgumentException("Day rules after the final gate are not allowed for " +
                            entry.getKey() + ": day " + dayRule.getKey());
                }
                dayRule.getValue().forEach(v -> required(v, "expected value for " + entry.getKey()));
            }
            if (rule.whenResolved() != null) {
                rule.whenResolved().forEach(v -> required(v, "when-resolved for " + entry.getKey()));
            }
        }
        if (cfg.artifacts() == null || cfg.artifacts().prefixes() == null ||
                cfg.artifacts().prefixes().isEmpty()) {
            throw new IllegalArgumentException("audit.artifacts.prefixes must not be empty");
        }
        cfg.artifacts().prefixes().forEach(v -> required(v, "artifact prefix"));
        if (cfg.report() == null) throw new IllegalArgumentException("audit.report is required");
        required(cfg.report().directory(), "audit.report.directory");
    }

    private static void validateFinalGate(AuditProperties.FinalGate finalGate) {
        if (finalGate == null) {
            throw new IllegalArgumentException("audit.final-gate is required");
        }
        if (finalGate.fromDay() == null || finalGate.fromDay() < 1) {
            throw new IllegalArgumentException("audit.final-gate.from-day must be >= 1");
        }
        validateDistinctValues(finalGate.linkedIssueTypes(),
                "audit.final-gate.linked-issue-types");
        validateDistinctValues(finalGate.completedStatusCategoryKeys(),
                "audit.final-gate.completed-status-category-keys");
    }

    private static void validateDistinctValues(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        Set<String> normalizedValues = new HashSet<>();
        for (String value : values) {
            required(value, name + " entry");
            if (!normalizedValues.add(value.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate value in " + name + ": " + value);
            }
        }
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing setting: " + name);
    }
}
