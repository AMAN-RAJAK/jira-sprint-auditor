package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

public interface JiraClient {
    List<JiraIssue> searchAll(String jql, Set<String> fields, int pageSize);

    default List<JiraIssue> searchAll(String jql, Set<String> fields, int pageSize,
                                      Set<Long> reconcileIssueIds) {
        return searchAll(jql, fields, pageSize);
    }

    record JiraIssue(String id, String key, JsonNode fields) { }
}
