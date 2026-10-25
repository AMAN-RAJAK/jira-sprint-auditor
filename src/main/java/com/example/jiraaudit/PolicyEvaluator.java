package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        List<CheckResult> results = new ArrayList<>();

        for (Map.Entry<String, AuditProperties.FieldRule> entry : properties.fields().entrySet()) {
            AuditProperties.FieldRule rule = entry.getValue();
            String label = displayLabel(entry.getKey(), rule.label());
            List<String> expected = rule.days().get(policyDay);
            JsonNode value = fields.get(rule.jiraField());
            if (value == null) {
                results.add(new CheckResult(details, "FIELD", entry.getKey(), label, rule.jiraField(),
                        String.join(" | ", expected), "<not returned>", FAIL,
                        "Jira field was not returned: " + rule.jiraField()));
                continue;
            }
            List<String> actual = flattenValues(value);
            boolean matches = matchesAny(actual, expected);
            results.add(new CheckResult(details, "FIELD", entry.getKey(), label, rule.jiraField(),
                    String.join(" | ", expected), displayValues(actual), matches ? PASS : FAIL,
                    matches ? "Expected value matched" : label + " does not match the Day " +
                            policyDay + " expected value"));
        }

        addArtifactChecks(results, details, fields, properties, policyDay,
                commentsNewestFirst == null ? List.of() : commentsNewestFirst);
        return results;
    }

    public boolean requiresComments(JiraClient.JiraIssue issue, AuditProperties properties, int runDay) {
        int policyDay = AuditDay.policyDay(runDay);
        JsonNode attachments = issue.fields() == null ? null : issue.fields().get("attachment");
        if (attachments == null || !attachments.isArray()) return false;
        List<String> filenames = attachmentNames(attachments);
        Map<String, AuditProperties.MissingArtifactRule> exceptions = exceptionRules(properties.artifacts());
        for (String prefix : properties.artifacts().requiredByDay().get(policyDay)) {
            if (!hasAttachment(filenames, prefix) && exceptions.containsKey(normalize(prefix))) {
                return true;
            }
        }
        return false;
    }

    private static void addArtifactChecks(List<CheckResult> results, IssueDetails details,
                                          JsonNode fields, AuditProperties properties, int policyDay,
                                          List<JiraClient.JiraComment> commentsNewestFirst) {
        JsonNode attachments = fields.get("attachment");
        List<String> filenames = attachments != null && attachments.isArray()
                ? attachmentNames(attachments) : List.of();
        Map<String, AuditProperties.MissingArtifactRule> exceptions = exceptionRules(properties.artifacts());

        for (String prefix : properties.artifacts().requiredByDay().get(policyDay)) {
            if (attachments == null || !attachments.isArray()) {
                results.add(new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                        "Attached filename starts with " + prefix, "<not returned>", FAIL,
                        "Jira attachment data was not returned or is malformed"));
                continue;
            }
            List<String> matches = filenames.stream()
                    .filter(name -> normalize(name).startsWith(normalize(prefix))).toList();
            if (!matches.isEmpty()) {
                results.add(new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                        "Attached filename starts with " + prefix, String.join(" | ", matches), PASS,
                        "Required artifact is attached"));
                continue;
            }

            AuditProperties.MissingArtifactRule exception = exceptions.get(normalize(prefix));
            if (exception == null) {
                results.add(new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                        "Attached filename starts with " + prefix, "<missing>", FAIL,
                        "Required artifact " + prefix + " is not attached"));
                continue;
            }
            results.add(evaluateMissingArtifact(details, fields, properties, prefix, exception,
                    commentsNewestFirst));
        }
    }

    private static CheckResult evaluateMissingArtifact(IssueDetails details, JsonNode fields,
                                                         AuditProperties properties, String prefix,
                                                         AuditProperties.MissingArtifactRule exception,
                                                         List<JiraClient.JiraComment> comments) {
        JiraClient.JiraComment matchingComment = comments.stream()
                .filter(comment -> containsPhrase(commentText(comment.body()), exception.commentContains()))
                .findFirst().orElse(null);
        boolean commentMatches = matchingComment != null;

        boolean fieldMatches = true;
        String fieldEvidence = "";
        String expected = "Attachment " + prefix + " or comment containing \"" +
                exception.commentContains() + "\"";
        if (exception.fieldRule() != null && !exception.fieldRule().isBlank()) {
            AuditProperties.FieldRule fieldRule = properties.fields().get(exception.fieldRule());
            List<String> actualValues = flattenValues(fields.get(fieldRule.jiraField()));
            fieldMatches = matchesAny(actualValues, exception.fieldValues());
            String label = displayLabel(exception.fieldRule(), fieldRule.label());
            expected += " and " + label + " is " + String.join(" | ", exception.fieldValues());
            fieldEvidence = label + "=" + displayValues(actualValues);
        }

        boolean passed = commentMatches && fieldMatches;
        List<String> failures = new ArrayList<>();
        if (!commentMatches) {
            failures.add("newest-first comments do not contain \"" + exception.commentContains() + "\"");
        }
        if (!fieldMatches) {
            AuditProperties.FieldRule fieldRule = properties.fields().get(exception.fieldRule());
            failures.add(displayLabel(exception.fieldRule(), fieldRule.label()) + " must be " +
                    String.join(" | ", exception.fieldValues()) + ", actual " +
                    displayValues(flattenValues(fields.get(fieldRule.jiraField()))));
        }

        String commentEvidence = matchingComment == null ? "comment=<not found>" :
                "comment=" + matchingComment.id() + " (" + matchingComment.created() + ")";
        String actual = "attachment=<missing>; " + commentEvidence +
                (fieldEvidence.isBlank() ? "" : "; " + fieldEvidence);
        String reason = passed
                ? "Missing artifact accepted by configured Not Applicable evidence"
                : "Required artifact " + prefix + " is not attached; " + String.join("; ", failures);
        return new CheckResult(details, "ARTIFACT", prefix, prefix,
                "attachment + comments" + (exception.fieldRule() == null ? "" : " + " + exception.fieldRule()),
                expected, actual, passed ? PASS : FAIL, reason);
    }

    private static Map<String, AuditProperties.MissingArtifactRule> exceptionRules(
            AuditProperties.Artifacts artifacts) {
        Map<String, AuditProperties.MissingArtifactRule> normalized = new LinkedHashMap<>();
        if (artifacts.missingExceptions() != null) {
            artifacts.missingExceptions().forEach((prefix, rule) -> normalized.put(normalize(prefix), rule));
        }
        return normalized;
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
