package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Safely extracts native Jira issue links returned in {@code fields.issuelinks}. */
final class JiraIssueLinks {
    private static final Pattern SAFE_ISSUE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]*-[0-9]+");
    private static final Pattern SAFE_ISSUE_ID = Pattern.compile("[0-9]+");

    private JiraIssueLinks() {
    }

    record LinkReference(String issueId, String issueKey, String direction, String linkType) {
    }

    record ParseResult(List<LinkReference> references, List<String> errors) {
        ParseResult {
            references = List.copyOf(references);
            errors = List.copyOf(errors);
        }
    }

    static ParseResult parse(JiraClient.JiraIssue parent) {
        List<String> errors = new ArrayList<>();
        Map<String, LinkReference> referencesByKey = new LinkedHashMap<>();

        if (parent == null) {
            errors.add("Cannot inspect linked issues because the parent issue is null");
            return new ParseResult(List.of(), errors);
        }

        String parentKey = normalizeKey(parent.key());
        String parentLabel = parentKey.isBlank() ? "<unknown issue>" : parentKey;
        JsonNode fields = parent.fields();
        if (fields == null || !fields.isObject()) {
            errors.add("Jira fields are absent or malformed for " + parentLabel);
            return new ParseResult(List.of(), errors);
        }
        if (!fields.has("issuelinks")) {
            errors.add("Jira field 'issuelinks' was not returned for " + parentLabel);
            return new ParseResult(List.of(), errors);
        }

        JsonNode links = fields.get("issuelinks");
        if (links == null || links.isNull()) {
            errors.add("Jira field 'issuelinks' is null for " + parentLabel);
            return new ParseResult(List.of(), errors);
        }
        if (!links.isArray()) {
            errors.add("Jira field 'issuelinks' is malformed for " + parentLabel + "; expected an array");
            return new ParseResult(List.of(), errors);
        }

        for (int index = 0; index < links.size(); index++) {
            JsonNode link = links.get(index);
            String location = "issuelinks[" + index + "] for " + parentLabel;
            if (link == null || !link.isObject()) {
                errors.add(location + " is malformed; expected an object");
                continue;
            }

            boolean hasInward = link.has("inwardIssue");
            boolean hasOutward = link.has("outwardIssue");
            if (!hasInward && !hasOutward) {
                errors.add(location + " is malformed; expected inwardIssue or outwardIssue");
                continue;
            }
            if (hasInward && hasOutward) {
                errors.add(location + " is malformed; both inwardIssue and outwardIssue are present");
            }

            String linkType = link.path("type").path("name").asText("").trim();
            if (linkType.isBlank()) linkType = "<unknown>";
            if (hasInward) {
                addReference(link.get("inwardIssue"), "inward", linkType, location,
                        parentKey, referencesByKey, errors);
            }
            if (hasOutward) {
                addReference(link.get("outwardIssue"), "outward", linkType, location,
                        parentKey, referencesByKey, errors);
            }
        }

        return new ParseResult(new ArrayList<>(referencesByKey.values()), errors);
    }

    static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }

    private static void addReference(JsonNode linkedIssue, String direction, String linkType,
                                     String location, String parentKey,
                                     Map<String, LinkReference> referencesByKey,
                                     List<String> errors) {
        if (linkedIssue == null || linkedIssue.isNull() || !linkedIssue.isObject()) {
            errors.add(location + "." + direction + "Issue is malformed; expected an object");
            return;
        }

        JsonNode idNode = linkedIssue.get("id");
        if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) {
            errors.add(location + "." + direction + "Issue is malformed; expected a non-blank text id");
            return;
        }
        String issueId = idNode.asText().trim();
        if (!SAFE_ISSUE_ID.matcher(issueId).matches()) {
            errors.add(location + "." + direction + "Issue has a malformed Jira id: " + issueId);
            return;
        }
        try {
            if (Long.parseLong(issueId) < 1) {
                errors.add(location + "." + direction + "Issue has a malformed Jira id: " + issueId);
                return;
            }
        } catch (NumberFormatException tooLarge) {
            errors.add(location + "." + direction + "Issue has a malformed Jira id: " + issueId);
            return;
        }

        JsonNode keyNode = linkedIssue.get("key");
        if (keyNode == null || !keyNode.isTextual() || keyNode.asText().isBlank()) {
            errors.add(location + "." + direction + "Issue is malformed; expected a non-blank text key");
            return;
        }
        String suppliedKey = keyNode.asText().trim();
        if (!SAFE_ISSUE_KEY.matcher(suppliedKey).matches()) {
            errors.add(location + "." + direction + "Issue has an unsafe Jira key: " + suppliedKey);
            return;
        }

        String issueKey = normalizeKey(suppliedKey);
        if (!parentKey.isBlank() && issueKey.equals(parentKey)) {
            errors.add(location + "." + direction + "Issue incorrectly references the parent itself: " + issueKey);
            return;
        }

        LinkReference existing = referencesByKey.get(issueKey);
        if (existing == null) {
            referencesByKey.put(issueKey, new LinkReference(issueId, issueKey, direction, linkType));
        } else if (!existing.issueId().equals(issueId)) {
            errors.add("Linked issue " + issueKey + " has conflicting Jira IDs: "
                    + existing.issueId() + " and " + issueId);
        }
    }
}
