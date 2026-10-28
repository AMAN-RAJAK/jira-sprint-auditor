package com.example.jiraaudit;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

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
                    "audit.run-day is required; pass --audit.run-day=3 or 10");
        }
        if (!AuditDay.isAllowedRunDay(cfg.runDay())) {
            throw new IllegalArgumentException("audit.run-day must be exactly 3 or 10");
        }
        if (cfg.pageSize() != null && (cfg.pageSize() < 1 || cfg.pageSize() > 100)) {
            throw new IllegalArgumentException("audit.page-size must be 1..100");
        }
        validateCheckpoints(cfg.checkpoints());
        validateArtifacts(cfg.artifacts(), cfg.checkpoints().get(10).fields());
        if (cfg.report() == null) throw new IllegalArgumentException("audit.report is required");
        required(cfg.report().directory(), "audit.report.directory");
        if (cfg.context() == null) {
            throw new IllegalArgumentException(
                    "audit.context is required for GA version and sprint phase report naming");
        }
        required(cfg.context().gaVersion(), "audit.context.ga-version");
        required(cfg.context().sprintPhase(), "audit.context.sprint-phase");
        validateMail(cfg);
    }

    private static void validateMail(AuditProperties cfg) {
        AuditProperties.Mail mail = cfg.mail();
        if (mail == null || !mail.isEnabled()) return;

        required(cfg.context().teamName(), "audit.context.team-name");
        required(cfg.context().gaVersion(), "audit.context.ga-version");
        required(cfg.context().sprintPhase(), "audit.context.sprint-phase");
        required(mail.from(), "audit.mail.from");
        validateAddress(mail.from(), "audit.mail.from");
        if (mail.to() == null || mail.to().isEmpty()) {
            throw new IllegalArgumentException("audit.mail.to requires at least one recipient");
        }
        validateAddresses(mail.to(), "audit.mail.to");
        if (mail.cc() != null) validateAddresses(mail.cc(), "audit.mail.cc");
        required(mail.subject(), "audit.mail.subject");
        if (mail.subject().contains("\r") || mail.subject().contains("\n")) {
            throw new IllegalArgumentException("audit.mail.subject must be a single line");
        }

        if (mail.smtp() == null) {
            throw new IllegalArgumentException("audit.mail.smtp is required when mail is enabled");
        }
        AuditProperties.Smtp smtp = mail.smtp();
        required(smtp.hostIp(),
                "smtpHostip / mail.smtp.host / audit.mail.smtp.host-ip");
        if (smtp.port() == null || smtp.port() < 1 || smtp.port() > 65_535) {
            throw new IllegalArgumentException(
                    "smtpPort / mail.smtp.port / audit.mail.smtp.port must be 1..65535");
        }
        required(smtp.protocol(), "smtpProtocol / audit.mail.smtp.protocol");
        if (!Set.of("smtp", "smtps").contains(normalize(smtp.protocol()))) {
            throw new IllegalArgumentException("smtpProtocol must be smtp or smtps");
        }
        if (Boolean.TRUE.equals(smtp.authenticationRequired())) {
            required(smtp.username(), "smtpUsername / audit.mail.smtp.username");
            required(smtp.password(), "smtpPassword / audit.mail.smtp.password");
        }
    }

    private static void validateAddresses(List<String> addresses, String settingName) {
        for (String address : addresses) {
            required(address, settingName + " recipient");
            validateAddress(address, settingName);
        }
    }

    private static void validateAddress(String address, String settingName) {
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
        } catch (AddressException error) {
            throw new IllegalArgumentException("Invalid email address in " + settingName + ": " + address,
                    error);
        }
    }

    private static void validateCheckpoints(Map<Integer, AuditProperties.Checkpoint> checkpoints) {
        if (checkpoints == null || !checkpoints.keySet().equals(Set.copyOf(AuditDay.CONFIGURED_DAYS))) {
            throw new IllegalArgumentException(
                    "audit.checkpoints must define exactly Day 3 and Day 10");
        }
        checkpoints.forEach((day, checkpoint) -> {
            if (checkpoint == null || checkpoint.fields() == null || checkpoint.fields().isEmpty()) {
                throw new IllegalArgumentException("Configure at least one field for Day " + day);
            }
            Set<String> jiraFields = new HashSet<>();
            for (Map.Entry<String, AuditProperties.FieldRule> entry : checkpoint.fields().entrySet()) {
                required(entry.getKey(), "field rule key for Day " + day);
                AuditProperties.FieldRule rule = entry.getValue();
                if (rule == null) {
                    throw new IllegalArgumentException("Empty field rule for Day " + day + ": " + entry.getKey());
                }
                required(rule.jiraField(), "jira-field for Day " + day + " / " + entry.getKey());
                required(rule.responsibleTeam(),
                        "responsible-team for Day " + day + " / " + entry.getKey());
                if (!jiraFields.add(normalize(rule.jiraField()))) {
                    throw new IllegalArgumentException("Duplicate jira-field on Day " + day + ": " +
                            rule.jiraField());
                }
                if (rule.acceptedValues() == null || rule.acceptedValues().isBlank()) {
                    throw new IllegalArgumentException("accepted-values must not be empty for Day " + day +
                            " / " + entry.getKey());
                }
                if (rule.acceptedValueList().stream().anyMatch(String::isBlank)) {
                    throw new IllegalArgumentException("accepted-values contains an empty $-separated value " +
                            "for Day " + day + " / " + entry.getKey());
                }
                if (rule.requiresNonEmptyValue() && rule.acceptedValueList().size() != 1) {
                    throw new IllegalArgumentException(
                            AuditProperties.FieldRule.NON_EMPTY_VALUE +
                                    " must be the only accepted value for Day " + day +
                                    " / " + entry.getKey());
                }
            }
            if (day == 3 && checkpoint.linkedBugs() != null) {
                throw new IllegalArgumentException(
                        "linked-bugs is a final Day 10 check and must not be configured for Day 3");
            }
            validateScenarioCount(day, checkpoint.atddScenarioCount(), checkpoint.fields());
            validateCompletionComment(day, checkpoint.completionComment(), checkpoint.fields());
            if (day == 10) validateLinkedBugs(checkpoint.linkedBugs());
        });
    }

    private static void validateScenarioCount(
            int day,
            AuditProperties.ScenarioCountRule rule,
            Map<String, AuditProperties.FieldRule> fields) {
        if (rule == null) return;
        if (day != 10) {
            throw new IllegalArgumentException(
                    "atdd-scenario-count is a final Day 10 check and must not be configured for Day " + day);
        }
        required(rule.label(), "Day 10 atdd-scenario-count.label");
        required(rule.jiraField(), "Day 10 atdd-scenario-count.jira-field");
        required(rule.responsibleTeam(), "Day 10 atdd-scenario-count.responsible-team");
        boolean duplicatesField = fields.values().stream()
                .map(AuditProperties.FieldRule::jiraField)
                .anyMatch(field -> normalize(field).equals(normalize(rule.jiraField())));
        if (duplicatesField) {
            throw new IllegalArgumentException("Day 10 atdd-scenario-count.jira-field must not " +
                    "duplicate a regular field rule: " + rule.jiraField());
        }
    }

    private static void validateCompletionComment(
            int day,
            AuditProperties.CompletionCommentRule rule,
            Map<String, AuditProperties.FieldRule> fields) {
        if (rule == null) return;
        required(rule.fieldRule(), "Day " + day + " completion-comment.field-rule");
        if (!fields.containsKey(rule.fieldRule())) {
            throw new IllegalArgumentException("Unknown field-rule for Day " + day +
                    " completion-comment: " + rule.fieldRule());
        }
        if (rule.completedValues() == null || rule.completedValues().isBlank()) {
            throw new IllegalArgumentException(
                    "completed-values is required for Day " + day + " completion-comment");
        }
        if (rule.completedValueList().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("completed-values contains an empty $-separated " +
                    "value for Day " + day + " completion-comment");
        }
        required(rule.commentPrefix(), "Day " + day + " completion-comment.comment-prefix");
    }

    private static void validateLinkedBugs(AuditProperties.LinkedBugRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("linked-bugs is required for Day 10");
        }
        required(rule.issueType(), "Day 10 linked-bugs.issue-type");
        if (rule.resolvedStatuses() == null || rule.resolvedStatuses().isBlank()) {
            throw new IllegalArgumentException(
                    "resolved-statuses is required for Day 10 linked-bugs");
        }
        if (rule.resolvedStatusList().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "resolved-statuses contains an empty $-separated value for Day 10 linked-bugs");
        }
    }

    private static void validateArtifacts(AuditProperties.Artifacts artifacts,
                                          Map<String, AuditProperties.FieldRule> dayTenFields) {
        if (artifacts == null) throw new IllegalArgumentException("audit.artifacts is required");
        validatePrefixAlternatives(artifacts.day3WbsPrefix(), artifacts.day3WbsPrefixList(),
                "audit.artifacts.day-3-wbs-prefix");
        if (artifacts.day10() == null) {
            throw new IllegalArgumentException("audit.artifacts.day-10 is required");
        }
        validatePrefixAlternatives(artifacts.day10().wbsPrefix(),
                artifacts.day10().wbsPrefixList(), "audit.artifacts.day-10.wbs-prefix");

        String[] names = {"cwc", "ut", "atdd", "st"};
        AuditProperties.ArtifactRule[] rules = {
                artifacts.day10().cwc(), artifacts.day10().ut(),
                artifacts.day10().atdd(), artifacts.day10().st()
        };
        Set<String> prefixes = new HashSet<>();
        artifacts.day10().wbsPrefixList().stream()
                .map(AuditConfigurationValidator::normalize)
                .forEach(prefixes::add);
        for (int index = 0; index < rules.length; index++) {
            String name = names[index];
            AuditProperties.ArtifactRule rule = rules[index];
            if (rule == null) {
                throw new IllegalArgumentException("audit.artifacts.day-10." + name + " is required");
            }
            required(rule.prefix(), "prefix for Day 10 " + name);
            if (!prefixes.add(normalize(rule.prefix()))) {
                throw new IllegalArgumentException("Duplicate Day 10 artifact prefix: " + rule.prefix());
            }
            if (!"atdd".equals(name)) {
                required(rule.commentContains(), "comment-contains for Day 10 " + name);
            }
        }

        validateFieldBasedArtifact("cwc", artifacts.day10().cwc(), dayTenFields);
        validateFieldBasedArtifact("atdd", artifacts.day10().atdd(), dayTenFields);
        validateFieldBasedArtifact("st", artifacts.day10().st(), dayTenFields);

        AuditProperties.ArtifactRule ut = artifacts.day10().ut();
        boolean utHasField = ut.fieldRule() != null && !ut.fieldRule().isBlank();
        boolean utHasValues = ut.notApplicableValues() != null && !ut.notApplicableValues().isBlank();
        if (utHasField || utHasValues) {
            throw new IllegalArgumentException(
                    "Day 10 UT is controlled by ATDD Status and must not configure a field-rule");
        }
    }

    private static void validatePrefixAlternatives(String configuredPrefixes,
                                                   List<String> prefixes,
                                                   String settingName) {
        required(configuredPrefixes, settingName);
        if (prefixes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(settingName +
                    " contains an empty $-separated prefix");
        }
        Set<String> unique = new HashSet<>();
        for (String prefix : prefixes) {
            if (!unique.add(normalize(prefix))) {
                throw new IllegalArgumentException(settingName +
                        " contains a duplicate prefix: " + prefix);
            }
        }
    }

    private static void validateFieldBasedArtifact(String name,
                                                   AuditProperties.ArtifactRule artifact,
                                                   Map<String, AuditProperties.FieldRule> fields) {
        required(artifact.fieldRule(), "field-rule for Day 10 " + name);
        if (!fields.containsKey(artifact.fieldRule())) {
            throw new IllegalArgumentException("Unknown Day 10 field-rule for " + name + ": " +
                    artifact.fieldRule());
        }
        if (artifact.notApplicableValues() == null || artifact.notApplicableValues().isBlank()) {
            throw new IllegalArgumentException("not-applicable-values are required for Day 10 " + name);
        }
        if (artifact.notApplicableValueList().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("not-applicable-values contains an empty $-separated value " +
                    "for Day 10 " + name);
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing setting: " + name);
        }
    }
}
