package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.jiraaudit.CheckResult.Outcome.FAIL;
import static com.example.jiraaudit.CheckResult.Outcome.PASS;

@Component
public class PolicyEvaluator {
    public List<CheckResult> evaluate(JiraClient.JiraIssue issue, AuditProperties properties,
                                      int runDay, List<JiraClient.JiraComment> commentsNewestFirst) {
        JsonNode fields = issue.fields();
        validateMetadata(fields, issue.key());
        IssueDetails details = issueDetails(issue, fields);
        int policyDay = AuditDay.policyDay(runDay);
        AuditProperties.Checkpoint checkpoint = properties.checkpoints().get(policyDay);
        List<CheckResult> results = new ArrayList<>();

        for (Map.Entry<String, AuditProperties.FieldRule> entry : checkpoint.fields().entrySet()) {
            AuditProperties.FieldRule rule = entry.getValue();
            String label = displayLabel(entry.getKey(), rule.label());
            List<String> acceptedValues = rule.acceptedValueList();
            JsonNode value = fields.get(rule.jiraField());
            if (value == null) {
                results.add(new CheckResult(details, "FIELD", entry.getKey(), label, rule.jiraField(),
                        String.join(" | ", acceptedValues), "<not returned>", FAIL,
                        "Jira field was not returned: " + rule.jiraField()));
                continue;
            }
            List<String> actual = flattenValues(value);
            boolean matches = matchesAny(actual, acceptedValues);
            results.add(new CheckResult(details, "FIELD", entry.getKey(), label, rule.jiraField(),
                    String.join(" | ", acceptedValues), displayValues(actual),
                    matches ? PASS : FAIL,
                    matches ? "One configured value matched" : label +
                            " does not match any configured Day " + policyDay + " value"));
        }

        addArtifactChecks(results, details, fields, properties, policyDay,
                commentsNewestFirst == null ? List.of() : commentsNewestFirst);
        return results;
    }

    public boolean requiresComments(JiraClient.JiraIssue issue, AuditProperties properties, int runDay) {
        if (AuditDay.policyDay(runDay) == 3 || issue.fields() == null) return false;
        JsonNode attachments = issue.fields().get("attachment");
        if (attachments == null || !attachments.isArray()) return false;
        List<String> filenames = attachmentNames(attachments);
        Map<String, AuditProperties.FieldRule> fields = properties.checkpoints().get(10).fields();
        for (AuditProperties.ArtifactRule rule : dayTenRules(properties.artifacts().day10())) {
            if (!hasAttachment(filenames, rule.prefix()) &&
                    !hasNotApplicableFieldEvidence(issue.fields(), fields, rule)) {
                return true;
            }
        }
        return false;
    }

    private static void addArtifactChecks(List<CheckResult> results, IssueDetails details,
                                          JsonNode fields, AuditProperties properties, int policyDay,
                                          List<JiraClient.JiraComment> comments) {
        JsonNode attachments = fields.get("attachment");
        if (policyDay == 3) {
            results.add(requiredArtifact(details, attachments, properties.artifacts().day3WbsPrefix()));
            return;
        }

        AuditProperties.Day10Artifacts dayTen = properties.artifacts().day10();
        results.add(requiredArtifact(details, attachments, dayTen.wbsPrefix()));
        Map<String, AuditProperties.FieldRule> dayTenFields = properties.checkpoints().get(10).fields();
        results.add(configurableArtifact(details, fields, attachments, dayTenFields,
                "CWC", dayTen.cwc(), comments));
        results.add(configurableArtifact(details, fields, attachments, dayTenFields,
                "UT", dayTen.ut(), comments));
        results.add(configurableArtifact(details, fields, attachments, dayTenFields,
                "ATDD", dayTen.atdd(), comments));
        results.add(configurableArtifact(details, fields, attachments, dayTenFields,
                "ST", dayTen.st(), comments));
        results.add(utAtddCombination(details, attachments, dayTen.ut().prefix(), dayTen.atdd().prefix()));
    }

    private static CheckResult requiredArtifact(IssueDetails details, JsonNode attachments, String prefix) {
        if (attachments == null || !attachments.isArray()) {
            return malformedAttachment(details, prefix);
        }
        List<String> matches = matchingAttachments(attachments, prefix);
        boolean passed = !matches.isEmpty();
        return new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                "Attached filename starts with " + prefix,
                passed ? String.join(" | ", matches) : "<missing>", passed ? PASS : FAIL,
                passed ? "Required artifact is attached" :
                        "Required artifact " + prefix + " is not attached and cannot be waived");
    }

    private static CheckResult configurableArtifact(
            IssueDetails details,
            JsonNode fields,
            JsonNode attachments,
            Map<String, AuditProperties.FieldRule> configuredFields,
            String artifactName,
            AuditProperties.ArtifactRule artifact,
            List<JiraClient.JiraComment> comments) {
        if (attachments == null || !attachments.isArray()) {
            return malformedAttachment(details, artifact.prefix());
        }

        List<String> attached = matchingAttachments(attachments, artifact.prefix());
        boolean fieldNotApplicable = hasNotApplicableFieldEvidence(fields, configuredFields, artifact);
        String fieldEvidence = fieldEvidence(fields, configuredFields, artifact);
        if (fieldNotApplicable) {
            boolean passed = attached.isEmpty();
            return new CheckResult(details, "ARTIFACT", artifact.prefix(), artifactName,
                    "attachment + " + artifact.fieldRule(),
                    artifactName + " must not be attached when " + fieldEvidence,
                    attached.isEmpty() ? "attachment=<missing>; " + fieldEvidence :
                            "attachment=" + String.join(" | ", attached) + "; " + fieldEvidence,
                    passed ? PASS : FAIL,
                    passed ? artifactName + " correctly omitted because its configured field is Not Applicable" :
                            artifactName + " must not be attached because its configured field is Not Applicable");
        }

        if (!attached.isEmpty()) {
            return new CheckResult(details, "ARTIFACT", artifact.prefix(), artifactName, "attachment",
                    "Attached filename starts with " + artifact.prefix(), String.join(" | ", attached), PASS,
                    artifactName + " artifact is attached");
        }

        JiraClient.JiraComment matchingComment = comments.stream()
                .filter(comment -> containsPhrase(commentText(comment.body()), artifact.commentContains()))
                .findFirst().orElse(null);
        boolean passed = matchingComment != null;
        String commentEvidence = passed
                ? "comment=" + matchingComment.id() + " (" + matchingComment.created() + ")"
                : "comment=<not found>";
        return new CheckResult(details, "ARTIFACT", artifact.prefix(), artifactName,
                "attachment + comments",
                "Attachment " + artifact.prefix() + " or comment containing \"" +
                        artifact.commentContains() + "\"",
                "attachment=<missing>; " + commentEvidence +
                        (fieldEvidence.isBlank() ? "" : "; " + fieldEvidence),
                passed ? PASS : FAIL,
                passed ? "Missing " + artifactName + " accepted by its standard Not Applicable comment" :
                        artifactName + " is not attached and newest-first comments do not contain \"" +
                                artifact.commentContains() + "\"");
    }

    private static CheckResult utAtddCombination(IssueDetails details, JsonNode attachments,
                                                  String utPrefix, String atddPrefix) {
        if (attachments == null || !attachments.isArray()) {
            return new CheckResult(details, "ARTIFACT_COMBINATION", "UT_OR_ATDD",
                    "UT/ATDD combination", "attachment", "Exactly one of UT or ATDD is attached",
                    "<not returned>", FAIL, "Jira attachment data was not returned or is malformed");
        }
        List<String> filenames = attachmentNames(attachments);
        boolean hasUt = hasAttachment(filenames, utPrefix);
        boolean hasAtdd = hasAttachment(filenames, atddPrefix);
        boolean passed = hasUt ^ hasAtdd;
        String actual = "UT=" + (hasUt ? "attached" : "missing") +
                "; ATDD=" + (hasAtdd ? "attached" : "missing");
        return new CheckResult(details, "ARTIFACT_COMBINATION", "UT_OR_ATDD",
                "UT/ATDD combination", "attachment",
                "Exactly one of " + utPrefix + " or " + atddPrefix + " is attached",
                actual, passed ? PASS : FAIL,
                passed ? "Valid final artifact combination" :
                        "Exactly one of UT or ATDD must be attached; both attached or both missing is invalid");
    }

    private static CheckResult malformedAttachment(IssueDetails details, String prefix) {
        return new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                "Valid Jira attachment data", "<not returned>", FAIL,
                "Jira attachment data was not returned or is malformed");
    }

    private static boolean hasNotApplicableFieldEvidence(
            JsonNode fields,
            Map<String, AuditProperties.FieldRule> configuredFields,
            AuditProperties.ArtifactRule artifact) {
        if (artifact.fieldRule() == null || artifact.fieldRule().isBlank()) return false;
        AuditProperties.FieldRule rule = configuredFields.get(artifact.fieldRule());
        return matchesAny(flattenValues(fields.get(rule.jiraField())), artifact.notApplicableValues());
    }

    private static String fieldEvidence(JsonNode fields,
                                        Map<String, AuditProperties.FieldRule> configuredFields,
                                        AuditProperties.ArtifactRule artifact) {
        if (artifact.fieldRule() == null || artifact.fieldRule().isBlank()) return "";
        AuditProperties.FieldRule rule = configuredFields.get(artifact.fieldRule());
        return displayLabel(artifact.fieldRule(), rule.label()) + "=" +
                displayValues(flattenValues(fields.get(rule.jiraField())));
    }

    private static List<AuditProperties.ArtifactRule> dayTenRules(
            AuditProperties.Day10Artifacts artifacts) {
        return List.of(artifacts.cwc(), artifacts.ut(), artifacts.atdd(), artifacts.st());
    }

    private static List<String> matchingAttachments(JsonNode attachments, String prefix) {
        return attachmentNames(attachments).stream()
                .filter(name -> normalize(name).startsWith(normalize(prefix))).toList();
    }

    private static List<String> attachmentNames(JsonNode attachments) {
        List<String> names = new ArrayList<>();
        attachments.forEach(attachment -> {
            String filename = attachment.path("filename").asText("").trim();
            if (!filename.isEmpty()) names.add(filename);
        });
        return names;
    }

    private static boolean hasAttachment(List<String> filenames, String prefix) {
        return filenames.stream().anyMatch(name -> normalize(name).startsWith(normalize(prefix)));
    }

    private static boolean matchesAny(List<String> actual, List<String> expected) {
        return actual.stream().anyMatch(actualValue -> expected.stream()
                .anyMatch(expectedValue -> equalsIgnoreCase(actualValue, expectedValue)));
    }

    private static String commentText(JsonNode body) {
        if (body == null || body.isNull()) return "";
        if (body.isTextual()) return body.asText();
        StringBuilder text = new StringBuilder();
        appendAdfText(body, text);
        return text.toString().trim();
    }

    private static void appendAdfText(JsonNode node, StringBuilder text) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> appendAdfText(child, text));
            return;
        }
        if (!node.isObject()) return;
        JsonNode value = node.get("text");
        if (value != null && value.isTextual()) {
            if (!text.isEmpty()) text.append(' ');
            text.append(value.asText());
        }
        appendAdfText(node.get("content"), text);
    }

    private static boolean containsPhrase(String text, String phrase) {
        return normalizeText(text).contains(normalizeText(phrase));
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static List<String> flattenValues(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (node.isArray()) {
            List<String> all = new ArrayList<>();
            node.forEach(element -> all.addAll(flattenValues(element)));
            return all;
        }
        if (node.isObject()) {
            for (String field : List.of("value", "name", "displayName", "id")) {
                JsonNode child = node.get(field);
                if (child != null && !child.isNull()) return flattenValues(child);
            }
            return List.of(node.toString());
        }
        return List.of(node.asText());
    }

    private static void validateMetadata(JsonNode fields, String issueKey) {
        if (fields == null || !fields.isObject()) {
            throw new IllegalStateException("Jira fields are missing for " + issueKey);
        }
        requireReturned(fields, issueKey, "summary", "assignee", "status", "attachment");
        if (!fields.get("summary").isTextual() || fields.get("summary").asText().isBlank()) {
            throw malformed(issueKey, "summary", "expected non-blank text");
        }
        JsonNode status = fields.get("status");
        if (!status.isObject() || status.path("name").asText("").isBlank()) {
            throw malformed(issueKey, "status", "expected an object with name");
        }
        JsonNode assignee = fields.get("assignee");
        if (!assignee.isNull() && (!assignee.isObject() ||
                assignee.path("displayName").asText("").isBlank())) {
            throw malformed(issueKey, "assignee", "expected null or an object with displayName");
        }
    }

    private static void requireReturned(JsonNode fields, String issueKey, String... names) {
        for (String name : names) {
            if (!fields.has(name)) {
                throw new IllegalStateException("Jira field '" + name + "' was not returned for " + issueKey);
            }
        }
    }

    private static IllegalStateException malformed(String issueKey, String field, String expected) {
        return new IllegalStateException("Jira field '" + field + "' is malformed for " + issueKey +
                "; " + expected);
    }

    private static IssueDetails issueDetails(JiraClient.JiraIssue issue, JsonNode fields) {
        JsonNode assignee = fields.get("assignee");
        String assigneeName = assignee.isNull() ? "Unassigned" :
                assignee.path("displayName").asText("Unassigned");
        return new IssueDetails(issue.id(), issue.key(), fields.path("summary").asText(""),
                assigneeName, fields.path("status").path("name").asText("UNKNOWN"));
    }

    private static String displayLabel(String key, String label) {
        return label == null || label.isBlank() ? key : label;
    }

    private static String displayValues(List<String> values) {
        return values.isEmpty() ? "<empty>" : String.join(" | ", values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }
}
