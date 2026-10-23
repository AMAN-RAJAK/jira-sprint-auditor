package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.example.jiraaudit.CheckResult.Outcome.*;

@Component
public class PolicyEvaluator {
    public List<CheckResult> evaluate(JiraClient.JiraIssue issue, AuditProperties properties, int day) {
        return evaluate(issue, properties, day, Map.of());
    }

    public List<CheckResult> evaluate(JiraClient.JiraIssue issue, AuditProperties properties, int day,
                                      Map<String, JiraClient.JiraIssue> linkedIssuesByKey) {
        JsonNode fields = issue.fields();
        validateMetadata(fields, issue.key());
        // Jira Cloud's canonical "resolved" signal is a non-null resolution field.
        JsonNode resolution = fields.get("resolution");
        boolean resolved = resolution != null && !resolution.isNull() &&
                !(resolution.isObject() && resolution.isEmpty());
        IssueDetails details = issueDetails(issue, fields, resolution, resolved);
        List<CheckResult> result = new ArrayList<>();
        boolean finalGateActive = isFinalGateActive(properties, day);

        for (Map.Entry<String, AuditProperties.FieldRule> item : properties.fields().entrySet()) {
            AuditProperties.FieldRule rule = item.getValue();
            String label = rule.label() == null || rule.label().isBlank() ? item.getKey() : rule.label();
            Integer finalGateDay = finalGateActive ? properties.finalGate().fromDay() : null;
            List<String> expected = expected(rule, day, resolved, finalGateDay);
            JsonNode value = fields.get(rule.jiraField());
            if (value == null) {
                result.add(new CheckResult(details, "FIELD", item.getKey(), label, rule.jiraField(),
                        String.join(" | ", expected), "<not returned>", FAIL,
                        "Jira field absent from response: " + rule.jiraField()));
                continue;
            }
            List<String> actual = flattenValues(value);
            boolean matches = actual.stream().anyMatch(v -> expected.stream().anyMatch(e -> equalsIgnoreCase(v, e)));
            result.add(new CheckResult(details, "FIELD", item.getKey(), label, rule.jiraField(),
                    String.join(" | ", expected), actual.isEmpty() ? "<empty>" : String.join(" | ", actual),
                    matches ? PASS : FAIL,
                    matches ? "Expected value matched" : "Expected value not present"));
        }

        boolean onlyForResolved = !Boolean.FALSE.equals(properties.artifacts().onlyForResolved());
        JsonNode attachments = fields.get("attachment");
        List<String> names = new ArrayList<>();
        if (attachments != null && attachments.isArray()) {
            attachments.forEach(attachment -> {
                String filename = attachment.path("filename").asText("");
                if (!filename.isBlank()) names.add(filename);
            });
        }
        for (String prefix : properties.artifacts().prefixes()) {
            if (onlyForResolved && !resolved && !finalGateActive) {
                result.add(new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                        "Filename starts with " + prefix, "Not checked", SKIP,
                        "Issue has no resolution"));
            } else if (attachments == null || !attachments.isArray()) {
                result.add(new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                        "Filename starts with " + prefix, "<not returned>", FAIL,
                        "Jira attachment field absent or malformed"));
            } else {
                List<String> matches = names.stream()
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                        .toList();
                result.add(new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                        "Filename starts with " + prefix,
                        matches.isEmpty() ? "<missing>" : String.join(" | ", matches),
                        matches.isEmpty() ? FAIL : PASS,
                        matches.isEmpty() ? "No matching attachment; available: " +
                                (names.isEmpty() ? "none" : String.join(" | ", names)) : "Attachment found"));
            }
        }

        if (finalGateActive) {
            addLinkedIssueChecks(result, issue, details, properties.finalGate(), linkedIssuesByKey);
            requirePassForEveryCheck(result);
            addFinalGateResult(result, details, properties.finalGate().fromDay());
        }
        return result;
    }

    static boolean isFinalGateActive(AuditProperties properties, int day) {
        return properties.finalGate() != null && properties.finalGate().fromDay() != null &&
                day >= properties.finalGate().fromDay();
    }

    private static void addLinkedIssueChecks(List<CheckResult> results,
                                             JiraClient.JiraIssue parent,
                                             IssueDetails parentDetails,
                                             AuditProperties.FinalGate finalGate,
                                             Map<String, JiraClient.JiraIssue> linkedIssuesByKey) {
        JiraIssueLinks.ParseResult parsed = JiraIssueLinks.parse(parent);
        String targetTypes = joinConfigured(finalGate.linkedIssueTypes());
        String completedCategories = joinConfigured(finalGate.completedStatusCategoryKeys());
        String expected = "Every linked " + targetTypes +
                " issue has status category " + completedCategories;

        for (String error : parsed.errors()) {
            results.add(new CheckResult(parentDetails, "LINKED_ISSUE", "linked-issues-closed",
                    "Linked issue data", "issuelinks", expected, "<unverifiable>", FAIL, error));
        }

        int targets = 0;
        int unverifiable = parsed.errors().size();
        Map<String, JiraClient.JiraIssue> available = linkedIssuesByKey == null ? Map.of() : linkedIssuesByKey;
        for (JiraIssueLinks.LinkReference reference : parsed.references()) {
            String normalizedKey = JiraIssueLinks.normalizeKey(reference.issueKey());
            JiraClient.JiraIssue linked = available.get(normalizedKey);
            String source = "issuelinks / " + reference.linkType() + " / " + reference.direction();
            if (linked == null) {
                unverifiable++;
                results.add(new CheckResult(parentDetails, "LINKED_ISSUE", "linked-issues-closed",
                        reference.issueKey(), source, expected, "<not returned>", FAIL,
                        "Linked issue could not be retrieved; check Jira permissions and issue security",
                        unavailableLinkedIssue(reference)));
                continue;
            }
            LinkedIssueDetails linkedDetails = linkedIssueDetails(reference, linked);
            if (!reference.issueId().equals(linked.id()) ||
                    !normalizedKey.equals(JiraIssueLinks.normalizeKey(linked.key()))) {
                unverifiable++;
                results.add(new CheckResult(parentDetails, "LINKED_ISSUE", "linked-issues-closed",
                        reference.issueKey(), source, expected,
                        "returned id=" + linked.id() + ", key=" + linked.key(), FAIL,
                        "Linked issue identity does not match the issue link", linkedDetails));
                continue;
            }
            JsonNode linkedFields = linked.fields();
            JsonNode issueTypeNode = linkedFields == null ? null : linkedFields.get("issuetype");
            String issueType = namedValue(issueTypeNode);
            if (issueType == null) {
                unverifiable++;
                results.add(new CheckResult(parentDetails, "LINKED_ISSUE", "linked-issues-closed",
                        reference.issueKey(), source, expected, "issuetype=<missing or malformed>", FAIL,
                        "Cannot determine whether the linked issue is a configured Bug/Task type", linkedDetails));
                continue;
            }
            if (!containsIgnoreCase(finalGate.linkedIssueTypes(), issueType)) {
                continue;
            }

            targets++;
            String summary = textOrNull(linkedFields.get("summary"));
            JsonNode assigneeNode = linkedFields.get("assignee");
            boolean assigneeValid = assigneeNode != null && (assigneeNode.isNull() ||
                    (assigneeNode.isObject() && !assigneeNode.path("displayName").asText("").isBlank()));
            JsonNode resolutionNode = linkedFields.get("resolution");
            boolean resolutionValid = resolutionNode != null && (resolutionNode.isNull() ||
                    (resolutionNode.isObject() && !resolutionNode.path("name").asText("").isBlank()));
            JsonNode statusNode = linkedFields.get("status");
            String statusName = namedValue(statusNode);
            String categoryKey = statusNode != null && statusNode.isObject()
                    ? textOrNull(statusNode.path("statusCategory").path("key")) : null;
            String actual = "id=" + reference.issueId() + " | key=" + reference.issueKey() +
                    " | summary=" + display(summary) + " | type=" + issueType +
                    " | assignee=" + linkedDetails.assigneeName() +
                    " | status=" + display(statusName) +
                    " | category=" + display(categoryKey) +
                    " | resolution=" + linkedDetails.resolution();
            boolean reportMetadataComplete = summary != null && assigneeValid && resolutionValid;
            boolean completed = reportMetadataComplete && statusName != null && categoryKey != null &&
                    containsIgnoreCase(finalGate.completedStatusCategoryKeys(), categoryKey);
            results.add(new CheckResult(parentDetails, "LINKED_ISSUE", "linked-issues-closed",
                    reference.issueKey() + " (" + issueType + ")", source, expected, actual,
                    completed ? PASS : FAIL,
                    completed ? "Linked issue is in a completed status category" :
                            !reportMetadataComplete
                                    ? "Linked Bug/Task summary, assignee, or resolution metadata is unavailable or malformed"
                                    : "Linked Bug/Task is not closed or its status category is unavailable",
                    linkedDetails));
        }

        if (targets == 0 && unverifiable == 0) {
            results.add(new CheckResult(parentDetails, "LINKED_ISSUE", "linked-issues-closed",
                    "Linked Bug/Task scope", "issuelinks", expected, "No visible linked target issues", PASS,
                    "No visible linked issue has a configured target issue type"));
        }
    }

    private static LinkedIssueDetails unavailableLinkedIssue(JiraIssueLinks.LinkReference reference) {
        return new LinkedIssueDetails(reference.issueId(), reference.issueKey(), "<not returned>",
                "<not returned>", "<not returned>", "", "<not returned>", "<not returned>",
                "<not returned>", reference.direction(), reference.linkType());
    }

    private static LinkedIssueDetails linkedIssueDetails(JiraIssueLinks.LinkReference reference,
                                                          JiraClient.JiraIssue linked) {
        JsonNode fields = linked.fields();
        String summary = fields == null ? null : textOrNull(fields.get("summary"));
        String issueType = fields == null ? null : namedValue(fields.get("issuetype"));
        JsonNode assignee = fields == null ? null : fields.get("assignee");
        String assigneeName;
        String assigneeAccountId;
        if (assignee == null) {
            assigneeName = "<not returned>";
            assigneeAccountId = "";
        } else if (assignee.isNull()) {
            assigneeName = "Unassigned";
            assigneeAccountId = "";
        } else if (assignee.isObject()) {
            assigneeName = display(textOrNull(assignee.get("displayName")));
            assigneeAccountId = displayOrEmpty(textOrNull(assignee.get("accountId")));
        } else {
            assigneeName = "<malformed>";
            assigneeAccountId = "";
        }
        JsonNode status = fields == null ? null : fields.get("status");
        String statusName = namedValue(status);
        String categoryKey = status != null && status.isObject()
                ? textOrNull(status.path("statusCategory").path("key")) : null;
        String resolution;
        if (fields == null || !fields.has("resolution")) {
            resolution = "<not returned>";
        } else if (fields.get("resolution").isNull()) {
            resolution = "Unresolved";
        } else {
            String resolutionName = namedValue(fields.get("resolution"));
            resolution = resolutionName == null ? "<malformed>" : resolutionName;
        }
        return new LinkedIssueDetails(linked.id(), linked.key(), display(summary), display(issueType),
                assigneeName, assigneeAccountId, display(statusName), display(categoryKey),
                resolution, reference.direction(), reference.linkType());
    }

    private static void requirePassForEveryCheck(List<CheckResult> results) {
        for (int index = 0; index < results.size(); index++) {
            CheckResult check = results.get(index);
            if (check.outcome() == SKIP) {
                results.set(index, new CheckResult(check.issue(), check.checkType(), check.ruleKey(),
                        check.checkName(), check.source(), check.expected(), check.actual(), FAIL,
                        "Final gate requires every check to PASS; original result: " + check.reason(),
                        check.linkedIssue()));
            }
        }
    }

    private static void addFinalGateResult(List<CheckResult> results, IssueDetails details, int fromDay) {
        long passes = results.stream().filter(check -> check.outcome() == PASS).count();
        long failures = results.stream().filter(check -> check.outcome() == FAIL).count();
        long skips = results.stream().filter(check -> check.outcome() == SKIP).count();
        boolean passed = failures == 0 && skips == 0;
        results.add(new CheckResult(details, "FINAL_GATE", "all-checks-pass", "Final compliance gate",
                "Day " + fromDay + "+ policy", "Every field, artifact and linked-issue check is PASS",
                "PASS=" + passes + " | FAIL=" + failures + " | SKIP=" + skips,
                passed ? PASS : FAIL,
                passed ? "All final-day compliance checks passed" :
                        "Final gate failed because one or more checks did not pass"));
    }

    private static String namedValue(JsonNode value) {
        if (value == null || value.isNull() || !value.isObject()) return null;
        return textOrNull(value.get("name"));
    }

    private static String textOrNull(JsonNode value) {
        if (value == null || value.isNull() || !value.isValueNode()) return null;
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        return values != null && candidate != null && values.stream()
                .filter(value -> value != null)
                .anyMatch(value -> equalsIgnoreCase(value, candidate));
    }

    private static String joinConfigured(List<String> values) {
        return values == null ? "<not configured>" : String.join(" | ", values);
    }

    private static String display(String value) {
        return value == null ? "<missing>" : value;
    }

    private static String displayOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void validateMetadata(JsonNode fields, String issueKey) {
        requireReturned(fields, issueKey, "summary", "issuetype", "assignee", "status", "resolution");
        requireNonBlankText(fields.get("summary"), issueKey, "summary");
        requireNamedObject(fields.get("issuetype"), issueKey, "issuetype");
        requireNamedObject(fields.get("status"), issueKey, "status");

        JsonNode assignee = fields.get("assignee");
        if (!assignee.isNull() && (!assignee.isObject() ||
                assignee.path("displayName").asText("").isBlank())) {
            throw malformed(issueKey, "assignee", "expected null or an object with displayName");
        }
        JsonNode resolution = fields.get("resolution");
        if (!resolution.isNull() && (!resolution.isObject() ||
                resolution.path("name").asText("").isBlank())) {
            throw malformed(issueKey, "resolution", "expected null or an object with name");
        }
    }

    private static void requireReturned(JsonNode fields, String issueKey, String... names) {
        for (String name : names) {
            if (!fields.has(name)) {
                throw new IllegalStateException("Jira field '" + name + "' was not returned for " + issueKey);
            }
        }
    }

    private static void requireNonBlankText(JsonNode value, String issueKey, String field) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw malformed(issueKey, field, "expected non-blank text");
        }
    }

    private static void requireNamedObject(JsonNode value, String issueKey, String field) {
        if (value == null || !value.isObject() || value.path("name").asText("").isBlank()) {
            throw malformed(issueKey, field, "expected an object with name");
        }
    }

    private static IllegalStateException malformed(String issueKey, String field, String expected) {
        return new IllegalStateException("Jira field '" + field + "' is malformed for " + issueKey +
                "; " + expected);
    }

    private static IssueDetails issueDetails(JiraClient.JiraIssue issue, JsonNode fields,
                                             JsonNode resolution, boolean resolved) {
        String status = fields.path("status").path("name").asText("UNKNOWN");
        String summary = fields.path("summary").asText("");
        String issueType = fields.path("issuetype").path("name").asText("");
        JsonNode assignee = fields.get("assignee");
        String assigneeName = assignee == null || assignee.isNull()
                ? "Unassigned" : assignee.path("displayName").asText("Unassigned");
        String assigneeAccountId = assignee == null || assignee.isNull()
                ? "" : assignee.path("accountId").asText("");
        List<String> resolutionValues = flattenValues(resolution);
        String resolutionName = resolved && !resolutionValues.isEmpty()
                ? String.join(" | ", resolutionValues) : "Unresolved";
        return new IssueDetails(issue.id(), issue.key(), summary, issueType, assigneeName,
                assigneeAccountId, status, resolutionName, resolved);
    }

    private static List<String> expected(AuditProperties.FieldRule rule, int day, boolean resolved,
                                         Integer finalGateDay) {
        if (finalGateDay == null && resolved && rule.whenResolved() != null && !rule.whenResolved().isEmpty()) {
            return rule.whenResolved();
        }
        int expectationDay = finalGateDay == null ? day : finalGateDay;
        Map.Entry<Integer, List<String>> dayRule = new TreeMap<>(rule.days()).floorEntry(expectationDay);
        if (dayRule == null) throw new IllegalArgumentException("No rule active for day " + day);
        return dayRule.getValue();
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

    private static boolean equalsIgnoreCase(String left, String right) {
        return left.trim().equalsIgnoreCase(right.trim());
    }
}
