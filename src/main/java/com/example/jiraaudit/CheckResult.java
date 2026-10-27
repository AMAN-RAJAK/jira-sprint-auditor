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
        String reason,
        String responsibleTeam
) {
    public CheckResult(IssueDetails issue, String checkType, String ruleKey, String checkName,
                       String source, String expected, String actual, Outcome outcome,
                       String reason) {
        this(issue, checkType, ruleKey, checkName, source, expected, actual, outcome,
                reason, "");
    }

    public enum Outcome { PASS, FAIL }

    public String issueKey() {
        return issue.issueKey();
    }

}
