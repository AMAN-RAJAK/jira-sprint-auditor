package com.example.jiraaudit;

/** Immutable Jira metadata repeated on each detailed compliance result. */
public record IssueDetails(
        String jiraId,
        String issueKey,
        String summary,
        String assigneeName,
        String status
) { }
