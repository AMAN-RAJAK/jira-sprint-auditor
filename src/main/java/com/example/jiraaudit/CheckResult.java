package com.example.jiraaudit;

public record CheckResult(
        IssueDetails issue,
        String checkType,
        String ruleKey,
        String checkName,
        String source,
        String expected,
        String actual,
        Outcome outcome,
        String reason
) {
    public enum Outcome { PASS, FAIL }

    public String issueKey() {
        return issue.issueKey();
    }

}
