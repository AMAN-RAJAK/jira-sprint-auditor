package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
            boolean nonEmptyRule = rule.requiresNonEmptyValue();
            String expected = nonEmptyRule
                    ? "Any non-empty value"
                    : String.join(" | ", acceptedValues);
            JsonNode value = fields.get(rule.jiraField());
            if (value == null) {
                results.add(new CheckResult(details, "FIELD", entry.getKey(), label, rule.jiraField(),
                        expected, "<not returned>", FAIL,
                        "Jira field was not returned: " + rule.jiraField(),
                        rule.responsibleTeam()));
                continue;
            }
            List<String> actual = flattenValues(value);
            boolean matches = nonEmptyRule
                    ? hasMeaningfulValue(value)
                    : matchesAny(actual, acceptedValues);
            String reason = matches
                    ? nonEmptyRule ? "Field contains a non-empty value" : "One configured value matched"
                    : nonEmptyRule ? label + " must not be empty on Day " + policyDay
                    : label + " does not match any configured Day " + policyDay + " value";
            results.add(new CheckResult(details, "FIELD", entry.getKey(), label, rule.jiraField(),
                    expected, displayValues(actual),
                    matches ? PASS : FAIL,
                    reason, rule.responsibleTeam()));
        }

        addArtifactChecks(results, details, fields, properties, policyDay,
                commentsNewestFirst == null ? List.of() : commentsNewestFirst);
        addCompletionCommentCheck(results, details, fields, checkpoint,
                commentsNewestFirst == null ? List.of() : commentsNewestFirst);
        if (policyDay == 10) {
            addLinkedBugChecks(results, details, fields, checkpoint.linkedBugs());
        }
        return results;
    }

    public boolean requiresComments(JiraClient.JiraIssue issue, AuditProperties properties, int runDay) {
        int policyDay = AuditDay.policyDay(runDay);
        if (issue.fields() == null) return false;
        AuditProperties.Checkpoint checkpoint = properties.checkpoints().get(policyDay);
        if (completionCommentRequired(issue.fields(), checkpoint)) return true;
        if (policyDay == 3) return false;
        JsonNode attachments = issue.fields().get("attachment");
        if (attachments == null || !attachments.isArray()) return false;
        List<String> filenames = attachmentNames(attachments);
        Map<String, AuditProperties.FieldRule> fields = properties.checkpoints().get(10).fields();
        AuditProperties.Day10Artifacts artifacts = properties.artifacts().day10();
        for (AuditProperties.ArtifactRule rule :
                List.of(artifacts.cwc(), artifacts.st())) {
            if (!hasAttachment(filenames, rule.prefix()) &&
                    !hasNotApplicableFieldEvidence(issue.fields(), fields, rule)) {
                return true;
            }
        }
        boolean atddNotApplicable = hasNotApplicableFieldEvidence(
                issue.fields(), fields, artifacts.atdd());
        return atddNotApplicable && !hasAttachment(filenames, artifacts.ut().prefix());
    }

    private static void addCompletionCommentCheck(
            List<CheckResult> results,
            IssueDetails details,
            JsonNode fields,
            AuditProperties.Checkpoint checkpoint,
            List<JiraClient.JiraComment> comments) {
        AuditProperties.CompletionCommentRule commentRule = checkpoint.completionComment();
        if (commentRule == null || !completionCommentRequired(fields, checkpoint)) return;

        JiraClient.JiraComment matchingComment = comments.stream()
                .filter(comment -> hasValueAfterPrefix(
                        commentText(comment.body()), commentRule.commentPrefix()))
                .findFirst().orElse(null);
        boolean passed = matchingComment != null;
        String evidence = passed
                ? "comment=" + matchingComment.id() + " (" + matchingComment.created() + ")"
                : "comment=<not found>";
        results.add(new CheckResult(details, "COMMENT", "MERGED_FEATURE_FILE_LINK",
                "Merged Feature File Link", "comments + " + commentRule.fieldRule(),
                "Newest-first comment containing \"" + commentRule.commentPrefix() +
                        "\" followed by a non-empty link/reference",
                evidence, passed ? PASS : FAIL,
                passed
                        ? "Merged feature file link comment is present for completed GWT Status"
                        : "GWT Status is completed, but no comment contains \"" +
                                commentRule.commentPrefix() +
                                "\" followed by a non-empty link/reference"));
    }

    private static boolean completionCommentRequired(
            JsonNode fields, AuditProperties.Checkpoint checkpoint) {
        AuditProperties.CompletionCommentRule commentRule = checkpoint.completionComment();
        if (commentRule == null) return false;
        AuditProperties.FieldRule fieldRule = checkpoint.fields().get(commentRule.fieldRule());
        if (fieldRule == null) return false;
        return matchesAny(flattenValues(fields.get(fieldRule.jiraField())),
                commentRule.completedValueList());
    }

    private static void addArtifactChecks(List<CheckResult> results, IssueDetails details,
                                          JsonNode fields, AuditProperties properties, int policyDay,
                                          List<JiraClient.JiraComment> comments) {
        JsonNode attachments = fields.get("attachment");
        if (policyDay == 3) {
            results.add(requiredArtifact(details, attachments, "WBS",
                    properties.artifacts().day3WbsPrefixList()));
            return;
        }

        AuditProperties.Day10Artifacts dayTen = properties.artifacts().day10();
        results.add(requiredArtifact(details, attachments, "WBS", dayTen.wbsPrefixList()));
        Map<String, AuditProperties.FieldRule> dayTenFields = properties.checkpoints().get(10).fields();
        results.add(configurableArtifact(details, fields, attachments, dayTenFields,
                "CWC", dayTen.cwc(), comments));
        results.add(atddArtifact(details, fields, attachments, dayTenFields, dayTen.atdd()));
        results.add(utArtifact(details, fields, attachments, dayTenFields,
                dayTen.ut(), dayTen.atdd(), comments));
        results.add(configurableArtifact(details, fields, attachments, dayTenFields,
                "ST", dayTen.st(), comments));
    }

    private static CheckResult requiredArtifact(IssueDetails details, JsonNode attachments,
                                                String artifactName, List<String> prefixes) {
        if (attachments == null || !attachments.isArray()) {
            return malformedAttachment(details, artifactName);
        }
        List<String> matches = attachmentsContainingAny(attachments, prefixes);
        boolean passed = !matches.isEmpty();
        String expectedPrefixes = String.join(" | ", prefixes);
        return new CheckResult(details, "ARTIFACT", artifactName, artifactName, "attachment",
                "Attached filename contains any of: " + expectedPrefixes,
                passed ? String.join(" | ", matches) : "<missing>", passed ? PASS : FAIL,
                passed ? "Required " + artifactName + " artifact is attached" :
                        "Required " + artifactName + " artifact is not attached; " +
                                "expected a filename containing one of " +
                                expectedPrefixes + " and it cannot be waived");
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

    private static CheckResult atddArtifact(
            IssueDetails details,
            JsonNode fields,
            JsonNode attachments,
            Map<String, AuditProperties.FieldRule> configuredFields,
            AuditProperties.ArtifactRule atdd) {
        if (attachments == null || !attachments.isArray()) {
            return malformedAttachment(details, atdd.prefix());
        }
        List<String> attached = matchingAttachments(attachments, atdd.prefix());
        boolean fieldNotApplicable = hasNotApplicableFieldEvidence(fields, configuredFields, atdd);
        String fieldEvidence = fieldEvidence(fields, configuredFields, atdd);
        boolean passed = fieldNotApplicable || !attached.isEmpty();
        String expected = fieldNotApplicable
                ? "ATDD is optional when " + fieldEvidence
                : "ATDD attachment filename starts with " + atdd.prefix();
        String actual = attached.isEmpty()
                ? "attachment=<missing>; " + fieldEvidence
                : "attachment=" + String.join(" | ", attached) + "; " + fieldEvidence;
        String reason = fieldNotApplicable
                ? "ATDD is not required because ATDD Status is Not Applicable"
                : passed
                        ? "ATDD artifact is attached for the applicable ATDD Status"
                        : "ATDD is required for the applicable ATDD Status and cannot be waived by a comment";
        return new CheckResult(details, "ARTIFACT", atdd.prefix(), "ATDD",
                "attachment + " + atdd.fieldRule(), expected, actual,
                passed ? PASS : FAIL, reason);
    }

    private static CheckResult utArtifact(
            IssueDetails details,
            JsonNode fields,
            JsonNode attachments,
            Map<String, AuditProperties.FieldRule> configuredFields,
            AuditProperties.ArtifactRule ut,
            AuditProperties.ArtifactRule atdd,
            List<JiraClient.JiraComment> comments) {
        if (attachments == null || !attachments.isArray()) {
            return malformedAttachment(details, ut.prefix());
        }
        List<String> attached = matchingAttachments(attachments, ut.prefix());
        boolean atddNotApplicable = hasNotApplicableFieldEvidence(fields, configuredFields, atdd);
        String atddEvidence = fieldEvidence(fields, configuredFields, atdd);
        if (!atddNotApplicable) {
            String actual = attached.isEmpty()
                    ? "attachment=<optional>; " + atddEvidence
                    : "attachment=" + String.join(" | ", attached) + "; " + atddEvidence;
            return new CheckResult(details, "ARTIFACT", ut.prefix(), "UT",
                    "attachment + " + atdd.fieldRule(),
                    "UT is optional when " + atddEvidence, actual, PASS,
                    "UT is not required when ATDD Status is applicable");
        }

        if (!attached.isEmpty()) {
            return new CheckResult(details, "ARTIFACT", ut.prefix(), "UT",
                    "attachment + " + atdd.fieldRule(),
                    "UT attachment or comment containing \"" + ut.commentContains() + "\"",
                    "attachment=" + String.join(" | ", attached) + "; " + atddEvidence,
                    PASS, "UT artifact is attached because ATDD Status is Not Applicable");
        }

        JiraClient.JiraComment matchingComment = comments.stream()
                .filter(comment -> containsPhrase(commentText(comment.body()), ut.commentContains()))
                .findFirst().orElse(null);
        boolean passed = matchingComment != null;
        String commentEvidence = passed
                ? "comment=" + matchingComment.id() + " (" + matchingComment.created() + ")"
                : "comment=<not found>";
        return new CheckResult(details, "ARTIFACT", ut.prefix(), "UT",
                "attachment + comments + " + atdd.fieldRule(),
                "UT attachment or comment containing \"" + ut.commentContains() + "\"",
                "attachment=<missing>; " + commentEvidence + "; " + atddEvidence,
                passed ? PASS : FAIL,
                passed ? "Missing UT accepted by its standard Not Applicable comment" :
                        "ATDD Status is Not Applicable, but UT is not attached and newest-first " +
                                "comments do not contain \"" + ut.commentContains() + "\"");
    }

    private static CheckResult malformedAttachment(IssueDetails details, String prefix) {
        return new CheckResult(details, "ARTIFACT", prefix, prefix, "attachment",
                "Valid Jira attachment data", "<not returned>", FAIL,
                "Jira attachment data was not returned or is malformed");
    }

    private static void addLinkedBugChecks(List<CheckResult> results,
                                           IssueDetails details,
                                           JsonNode fields,
                                           AuditProperties.LinkedBugRule rule) {
        JsonNode links = fields.get("issuelinks");
        String expectedStatuses = String.join(" | ", rule.resolvedStatusList());
        if (links == null || !links.isArray()) {
            results.add(new CheckResult(details, "LINKED_BUG", "LINKED_BUGS", "Linked Bugs",
                    "issuelinks", "Every linked " + rule.issueType() +
                    " has status " + expectedStatuses, "<not returned>", FAIL,
                    "Jira issue-link data was not returned or is malformed"));
            return;
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        boolean linkedBugFound = false;
        boolean incompleteLinkFound = false;
        int malformedLinkNumber = 0;
        for (JsonNode link : links) {
            JsonNode linkedIssue = linkedIssue(link);
            if (linkedIssue == null) {
                incompleteLinkFound = true;
                malformedLinkNumber++;
                results.add(malformedLinkedIssue(details, rule, expectedStatuses,
                        "link-" + malformedLinkNumber, "Linked issue data is missing"));
                continue;
            }

            String linkedKey = linkedIssue.path("key").asText("").trim();
            if (linkedKey.isEmpty()) {
                incompleteLinkFound = true;
                malformedLinkNumber++;
                results.add(malformedLinkedIssue(details, rule, expectedStatuses,
                        "link-" + malformedLinkNumber, "Linked issue key is missing"));
                continue;
            }
            if (!seenKeys.add(linkedKey)) continue;

            JsonNode linkedFields = linkedIssue.get("fields");
            if (linkedFields == null || !linkedFields.isObject()) {
                incompleteLinkFound = true;
                results.add(malformedLinkedIssue(details, rule, expectedStatuses,
                        linkedKey, "Linked issue fields are missing"));
                continue;
            }
            List<String> issueTypes = flattenValues(linkedFields.get("issuetype"));
            if (issueTypes.isEmpty()) {
                incompleteLinkFound = true;
                results.add(malformedLinkedIssue(details, rule, expectedStatuses,
                        linkedKey, "Linked issue type was not returned"));
                continue;
            }
            if (!matchesAny(issueTypes, List.of(rule.issueType()))) continue;

            linkedBugFound = true;
            List<String> statuses = flattenValues(linkedFields.get("status"));
            boolean resolved = !statuses.isEmpty() && matchesAny(statuses, rule.resolvedStatusList());
            String actualStatus = displayValues(statuses);
            results.add(new CheckResult(details, "LINKED_BUG", "LINKED_BUG:" + linkedKey,
                    "Linked Bug " + linkedKey, "issuelinks.status",
                    expectedStatuses, actualStatus, resolved ? PASS : FAIL,
                    resolved ? "Linked Bug " + linkedKey + " is resolved" :
                            "Linked Bug " + linkedKey + " is not resolved; status=" + actualStatus));
        }

        if (!linkedBugFound && !incompleteLinkFound) {
            results.add(new CheckResult(details, "LINKED_BUG", "LINKED_BUGS", "Linked Bugs",
                    "issuelinks", "Every linked " + rule.issueType() +
                    " has status " + expectedStatuses, "<none>", PASS,
                    "No linked " + rule.issueType() + " issues found"));
        }
    }

    private static JsonNode linkedIssue(JsonNode link) {
        if (link == null || !link.isObject()) return null;
        for (String direction : List.of("outwardIssue", "inwardIssue")) {
            JsonNode issue = link.get(direction);
            if (issue != null && issue.isObject()) return issue;
        }
        return null;
    }

    private static CheckResult malformedLinkedIssue(IssueDetails details,
                                                     AuditProperties.LinkedBugRule rule,
                                                     String expectedStatuses,
                                                     String linkedKey,
                                                     String reason) {
        return new CheckResult(details, "LINKED_BUG", "LINKED_BUG:" + linkedKey,
                "Linked issue " + linkedKey, "issuelinks",
                "Linked " + rule.issueType() + " status is " + expectedStatuses,
                "<incomplete>", FAIL, reason + "; cannot verify linked Bug compliance");
    }

    private static boolean hasNotApplicableFieldEvidence(
            JsonNode fields,
            Map<String, AuditProperties.FieldRule> configuredFields,
            AuditProperties.ArtifactRule artifact) {
        if (artifact.fieldRule() == null || artifact.fieldRule().isBlank()) return false;
        AuditProperties.FieldRule rule = configuredFields.get(artifact.fieldRule());
        return matchesAny(flattenValues(fields.get(rule.jiraField())),
                artifact.notApplicableValueList());
    }

    private static String fieldEvidence(JsonNode fields,
                                        Map<String, AuditProperties.FieldRule> configuredFields,
                                        AuditProperties.ArtifactRule artifact) {
        if (artifact.fieldRule() == null || artifact.fieldRule().isBlank()) return "";
        AuditProperties.FieldRule rule = configuredFields.get(artifact.fieldRule());
        return displayLabel(artifact.fieldRule(), rule.label()) + "=" +
                displayValues(flattenValues(fields.get(rule.jiraField())));
    }

    private static List<String> matchingAttachments(JsonNode attachments, String prefix) {
        return attachmentNames(attachments).stream()
                .filter(name -> normalize(name).startsWith(normalize(prefix))).toList();
    }

    private static List<String> attachmentsContainingAny(JsonNode attachments, List<String> markers) {
        return attachmentNames(attachments).stream()
                .filter(name -> markers.stream()
                        .anyMatch(marker -> normalize(name).contains(normalize(marker))))
                .distinct()
                .toList();
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

    private static boolean hasMeaningfulValue(JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isTextual()) return !node.asText().isBlank();
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (hasMeaningfulValue(element)) return true;
            }
            return false;
        }
        if (node.isObject()) {
            if (node.isEmpty()) return false;
            for (String field : List.of("value", "name", "displayName", "id")) {
                JsonNode child = node.get(field);
                if (child != null && !child.isNull()) return hasMeaningfulValue(child);
            }
            var values = node.elements();
            while (values.hasNext()) {
                if (hasMeaningfulValue(values.next())) return true;
            }
            return false;
        }
        return !node.asText("").isBlank();
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

    private static boolean hasValueAfterPrefix(String text, String prefix) {
        String normalizedText = normalizeText(text);
        String normalizedPrefix = normalizeText(prefix);
        int prefixStart = normalizedText.indexOf(normalizedPrefix);
        if (prefixStart < 0) return false;
        return !normalizedText.substring(prefixStart + normalizedPrefix.length()).trim().isEmpty();
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
        return values.isEmpty() ? "<empty>" : String.join(" | ", values.stream()
                .map(value -> value == null || value.isBlank() ? "<blank>" : value)
                .toList());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }
}
