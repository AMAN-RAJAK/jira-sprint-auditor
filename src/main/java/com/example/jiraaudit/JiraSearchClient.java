package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class JiraSearchClient implements JiraClient {
    private static final DateTimeFormatter JIRA_OFFSET_DATE_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendOffset("+HHMM", "Z")
            .toFormatter();

    private final RestClient restClient;

    public JiraSearchClient(RestClient jiraRestClient) {
        this.restClient = jiraRestClient;
    }

    @Override
    public List<JiraIssue> searchAll(String jql, Set<String> fields, int pageSize) {
        List<JiraIssue> issues = new ArrayList<>();
        Set<String> seenTokens = new LinkedHashSet<>();
        Set<String> seenIssueIds = new LinkedHashSet<>();
        Set<String> seenIssueKeys = new LinkedHashSet<>();
        String nextToken = null;

        do {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jql", jql);
            body.put("fields", new ArrayList<>(fields));
            body.put("maxResults", pageSize);
            if (nextToken != null) body.put("nextPageToken", nextToken);

            JsonNode response = postWithRetry(body);
            if (response == null || !response.path("issues").isArray()) {
                throw new IllegalStateException("Jira JQL response has no issues array");
            }
            for (JsonNode issue : response.path("issues")) {
                String id = issue.path("id").asText("");
                String key = issue.path("key").asText("");
                if (id.isBlank() || key.isBlank() || !issue.path("fields").isObject()) {
                    throw new IllegalStateException("Jira returned an issue without id/key/fields");
                }
                if (!seenIssueIds.add(id) || !seenIssueKeys.add(key)) {
                    throw new IllegalStateException("Jira returned a duplicate issue across search pages: " + key);
                }
                issues.add(new JiraIssue(id, key, issue.path("fields")));
            }
            if (response.path("isLast").asBoolean(false)) break;
            String token = response.path("nextPageToken").asText("");
            if (token.isBlank() || !seenTokens.add(token)) {
                throw new IllegalStateException("Incomplete/repeated Jira pagination token; refusing a partial report");
            }
            nextToken = token;
        } while (true);
        return issues;
    }

    @Override
    public List<JiraComment> getCommentsNewestFirst(String issueKey, int pageSize) {
        if (issueKey == null || issueKey.isBlank()) {
            throw new IllegalArgumentException("Jira issue key is required to load comments");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("Jira comment page size must be >= 1");
        }

        String normalizedIssueKey = issueKey.trim();
        List<JiraComment> comments = new ArrayList<>();
        Set<String> seenCommentIds = new LinkedHashSet<>();
        Integer expectedTotal = null;
        Instant previousCreated = null;
        int startAt = 0;

        while (true) {
            JsonNode response = getCommentsPageWithRetry(normalizedIssueKey, startAt, pageSize);
            if (response == null || !response.isObject() || !response.path("comments").isArray()) {
                throw incompleteComments(normalizedIssueKey, "response has no comments array");
            }
            if (!response.path("startAt").canConvertToInt()
                    || !response.path("total").canConvertToInt()) {
                throw incompleteComments(normalizedIssueKey, "response has invalid pagination metadata");
            }

            int responseStartAt = response.path("startAt").intValue();
            int responseTotal = response.path("total").intValue();
            if (responseStartAt != startAt || responseTotal < 0) {
                throw incompleteComments(normalizedIssueKey, "response has inconsistent pagination metadata");
            }
            if (expectedTotal == null) {
                expectedTotal = responseTotal;
            } else if (responseTotal != expectedTotal) {
                throw incompleteComments(normalizedIssueKey,
                        "comment total changed while pages were being loaded");
            }

            JsonNode page = response.path("comments");
            if (page.isEmpty() && comments.size() < expectedTotal) {
                throw incompleteComments(normalizedIssueKey, "Jira returned an empty page before the total was reached");
            }

            for (JsonNode commentNode : page) {
                String id = commentNode.path("id").asText("").trim();
                JsonNode body = commentNode.get("body");
                String createdValue = commentNode.path("created").asText("").trim();
                if (id.isBlank() || body == null || body.isNull() || createdValue.isBlank()) {
                    throw incompleteComments(normalizedIssueKey,
                            "Jira returned a comment without id, body or created timestamp");
                }
                if (!seenCommentIds.add(id)) {
                    throw incompleteComments(normalizedIssueKey, "Jira returned duplicate comment " + id);
                }

                Instant created = parseJiraInstant(createdValue, normalizedIssueKey, id);
                if (previousCreated != null && created.isAfter(previousCreated)) {
                    throw incompleteComments(normalizedIssueKey,
                            "Jira did not return comments in newest-first order");
                }
                previousCreated = created;
                comments.add(new JiraComment(id, body.deepCopy(), created));
            }

            if (comments.size() > expectedTotal) {
                throw incompleteComments(normalizedIssueKey, "Jira returned more comments than its declared total");
            }
            if (comments.size() == expectedTotal) {
                return List.copyOf(comments);
            }
            startAt += page.size();
        }
    }

    private JsonNode postWithRetry(Map<String, Object> body) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return restClient.post()
                        .uri("/rest/api/3/search/jql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException error) {
                int status = error.getStatusCode().value();
                if (attempt == 3 || (status != 429 && status < 500)) {
                    throw new IllegalStateException("Jira search failed: HTTP " + status +
                            ". Check URL, token, permissions and JQL.", error);
                }
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying Jira", interrupted);
                }
            }
        }
        throw new IllegalStateException("Jira search exhausted retries");
    }

    private JsonNode getCommentsPageWithRetry(String issueKey, int startAt, int pageSize) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/rest/api/3/issue/{issueKey}/comment")
                                .queryParam("startAt", startAt)
                                .queryParam("maxResults", pageSize)
                                .queryParam("orderBy", "-created")
                                .build(issueKey))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException error) {
                int status = error.getStatusCode().value();
                if (attempt == 3 || (status != 429 && status < 500)) {
                    throw new IllegalStateException("Jira comment retrieval failed for " + issueKey
                            + ": HTTP " + status + ". Check token and issue permissions.", error);
                }
                retryDelay(attempt);
            }
        }
        throw new IllegalStateException("Jira comment retrieval exhausted retries for " + issueKey);
    }

    private static Instant parseJiraInstant(String value, String issueKey, String commentId) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value, JIRA_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException invalid) {
                throw incompleteComments(issueKey,
                        "comment " + commentId + " has an invalid created timestamp");
            }
        }
    }

    private static IllegalStateException incompleteComments(String issueKey, String reason) {
        return new IllegalStateException("Incomplete Jira comments for " + issueKey + ": " + reason
                + "; refusing a partial audit");
    }

    private static void retryDelay(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying Jira", interrupted);
        }
    }
}
