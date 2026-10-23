package com.example.jiraaudit;

public record LinkedIssueDetails(
        String jiraId,
        String issueKey,
        String summary,
        String issueType,
        String assigneeName,
        String assigneeAccountId,
        String status,
        String statusCategory,
        String resolution,
        String direction,
        String linkType
) { }
