package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class JiraSearchClient implements JiraClient {
    private final RestClient restClient;

    public JiraSearchClient(RestClient jiraRestClient) {
        this.restClient = jiraRestClient;
    }

    @Override
    public List<JiraIssue> searchAll(String jql, Set<String> fields, int pageSize) {
        return searchAll(jql, fields, pageSize, Set.of());
    }

    @Override
    public List<JiraIssue> searchAll(String jql, Set<String> fields, int pageSize,
                                     Set<Long> reconcileIssueIds) {
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
            if (reconcileIssueIds != null && !reconcileIssueIds.isEmpty()) {
                body.put("reconcileIssues", new ArrayList<>(reconcileIssueIds));
            }
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
}
