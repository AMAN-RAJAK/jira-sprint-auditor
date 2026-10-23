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
        LinkedIssueDetails linkedIssue
) {
    public enum Outcome { PASS, FAIL, SKIP }

    public CheckResult(IssueDetails issue, String checkType, String ruleKey, String checkName,
                       String source, String expected, String actual, Outcome outcome, String reason) {
        this(issue, checkType, ruleKey, checkName, source, expected, actual, outcome, reason, null);
    }

    public String issueKey() {
        return issue.issueKey();
    }

    public boolean rollup() {
        return "FINAL_GATE".equals(checkType);
    }
}
