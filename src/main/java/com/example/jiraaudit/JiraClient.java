package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface JiraClient {
    List<JiraIssue> searchAll(String jql, Set<String> fields, int pageSize);

    /**
     * Loads the complete comment history for one issue, ordered from newest to oldest.
     * Implementations must fail rather than return a partial history.
     */
    default List<JiraComment> getCommentsNewestFirst(String issueKey, int pageSize) {
        throw new IllegalStateException("Jira comment retrieval is not supported by this client");
    }

    record JiraIssue(String id, String key, JsonNode fields) { }

    record JiraComment(String id, JsonNode body, Instant created) { }
}
