package com.example.jiraaudit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.jiraaudit.CheckResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyEvaluatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PolicyEvaluator evaluator = new PolicyEvaluator();

    private AuditProperties settings() {
        Map<String, AuditProperties.FieldRule> fieldRules = new LinkedHashMap<>();
        fieldRules.put("download", new AuditProperties.FieldRule("Download", "customfield_10101",
                Map.of(1, List.of("Started"), 3, List.of("In Progress"), 7, List.of("Done"),
                        10, List.of("Done")), List.of("Done")));
        fieldRules.put("atdd", new AuditProperties.FieldRule("ATDD_STATUS", "customfield_10102",
                Map.of(1, List.of("Started"), 7, List.of("Done"), 10, List.of("Done")),
                List.of("Done")));
        return new AuditProperties(new AuditProperties.Jira("https://test.atlassian.net", "x@y.com", "secret"),
                "project = TEST", 1, 100, fieldRules,
                new AuditProperties.Artifacts(true, List.of("WBS_", "CWC_", "UT_")),
                new AuditProperties.FinalGate(10, List.of("Bug", "Task"),
                        List.of("completed", "done")),
                new AuditProperties.Report("target/report", true, true));
    }

    private JiraClient.JiraIssue issue(String json) throws Exception {
        ObjectNode fields = (ObjectNode) MAPPER.readTree(json);
        if (!fields.has("summary")) fields.put("summary", "Implement payments audit");
        if (!fields.has("issuetype")) fields.set("issuetype", MAPPER.readTree("{\"name\":\"Story\"}"));
        if (!fields.has("assignee")) {
            fields.set("assignee", MAPPER.readTree(
                    "{\"displayName\":\"Asha Rao\",\"accountId\":\"abc-123\"}"));
        }
        return new JiraClient.JiraIssue("10002", "PAY-102", fields);
    }

    @Test
    void dayOneUnresolvedAllowsStartedAndSkipsArtifacts() throws Exception {
        var issue = issue("""
            {"status":{"name":"In Progress"},"resolution":null,
            "customfield_10101":{"value":"Started"},"customfield_10102":"Started",
            "attachment":[]}
            """);
        var result = evaluator.evaluate(issue, settings(), 1);
        assertEquals(5, result.size());
        assertEquals(2, result.stream().filter(r -> r.outcome() == PASS).count());
        assertEquals(3, result.stream().filter(r -> r.outcome() == SKIP).count());
    }

    @Test
    void daySevenUnresolvedRequiresDoneAndArtifactRulesStillSkipped() throws Exception {
        var issue = issue("""
            {"status":{"name":"In Progress"},"resolution":null,
            "customfield_10101":{"value":"Started"},"customfield_10102":{"value":"Done"},
            "attachment":[]}
            """);
        var result = evaluator.evaluate(issue, settings(), 7);
        assertEquals(1, result.stream().filter(r -> r.outcome() == FAIL).count());
        assertEquals("Download", result.stream().filter(r -> r.outcome() == FAIL).findFirst().orElseThrow().checkName());
    }

    @Test
    void dayThreeUsesExplicitDayRule() throws Exception {
        var issue = issue("""
            {"status":{"name":"In Progress"},"resolution":null,
            "customfield_10101":{"value":"In Progress"},"customfield_10102":"Started",
            "attachment":[]}
            """);
        var result = evaluator.evaluate(issue, settings(), 3);
        assertEquals(2, result.stream().filter(r -> r.outcome() == PASS).count());
    }

    @Test
    void resolvedTicketOverridesDayOneAndFlagsMissingUT() throws Exception {
        var issue = issue("""
            {"status":{"name":"Done"},"resolution":{"name":"Fixed"},
            "customfield_10101":{"value":"Done"},"customfield_10102":{"value":"Started"},
            "attachment":[{"filename":"wbs_SCOPE.pdf"},{"filename":"CWC_tests.xlsx"}]}
            """);
        var result = evaluator.evaluate(issue, settings(), 1);
        assertEquals(2, result.stream().filter(r -> r.outcome() == FAIL).count());
        assertTrue(result.stream().anyMatch(r -> r.checkName().equals("ATDD_STATUS") && r.outcome() == FAIL));
        assertTrue(result.stream().anyMatch(r -> r.checkName().equals("UT_") && r.outcome() == FAIL));
        assertTrue(result.stream().anyMatch(r -> r.checkName().equals("WBS_") && r.outcome() == PASS));
        assertEquals("Asha Rao", result.get(0).issue().assigneeName());
        assertEquals("10002", result.get(0).issue().jiraId());
    }

    @Test
    void missingJiraFieldMustFailRatherThanSilentlyPass() throws Exception {
        var issue = issue("""
            {"status":{"name":"Done"},"resolution":{"name":"Fixed"},
            "customfield_10101":"Done","attachment":[]}
            """);
        var result = evaluator.evaluate(issue, settings(), 7);
        assertTrue(result.stream().anyMatch(r -> r.checkName().equals("ATDD_STATUS") && r.outcome() == FAIL));
    }

    @Test
    void supportsJiraMultiSelectAndCaseInsensitiveValues() throws Exception {
        var issue = issue("""
            {"status":{"name":"In Progress"},"resolution":null,
            "customfield_10101":[{"value":"Other"},{"value":"started"}],
            "customfield_10102":" STARTED ","attachment":[]}
            """);
        var result = evaluator.evaluate(issue, settings(), 1);
        assertEquals(2, result.stream().filter(r -> r.outcome() == PASS).count());
    }

    @Test
    void reportsNullAssigneeAsUnassigned() throws Exception {
        var issue = issue("""
            {"summary":"Unowned story","issuetype":{"name":"Story"},"assignee":null,
            "status":{"name":"In Progress"},"resolution":null,
            "customfield_10101":"Started","customfield_10102":"Started","attachment":[]}
            """);
        var result = evaluator.evaluate(issue, settings(), 1);
        assertEquals("Unassigned", result.get(0).issue().assigneeName());
        assertEquals("", result.get(0).issue().assigneeAccountId());
    }

    @Test
    void refusesToSkipArtifactChecksWhenResolutionWasNotReturned() throws Exception {
        var issue = issue("""
            {"status":{"name":"In Progress"},
            "customfield_10101":"Started","customfield_10102":"Started","attachment":[]}
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(issue, settings(), 1));
        assertTrue(error.getMessage().contains("resolution"));
    }

    @Test
    void rejectsMalformedTrackingMetadata() throws Exception {
        var issue = issue("""
            {"status":null,"resolution":null,
            "customfield_10101":"Started","customfield_10102":"Started","attachment":[]}
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(issue, settings(), 1));
        assertTrue(error.getMessage().contains("status"));
        assertTrue(error.getMessage().contains("malformed"));
    }

    @Test
    void dayNineDoesNotApplyFinalGate() throws Exception {
        var issue = issue("""
            {"status":{"name":"In Progress"},"resolution":null,
            "customfield_10101":{"value":"Done"},"customfield_10102":{"value":"Done"},
            "attachment":[],"issuelinks":[
              {"outwardIssue":{"id":"20007","key":"BUG-7"}}
            ]}
            """);

        var result = evaluator.evaluate(issue, settings(), 9);

        assertEquals(3, result.stream().filter(r -> r.outcome() == SKIP).count());
        assertTrue(result.stream().noneMatch(r -> r.checkType().equals("LINKED_ISSUE")));
        assertTrue(result.stream().noneMatch(r -> r.checkType().equals("FINAL_GATE")));
    }

    @Test
    void dayTenAndLaterRequireEveryCheckToPassWithoutSkips() throws Exception {
        var issue = issue("""
            {"status":{"name":"In Progress"},"resolution":null,
            "customfield_10101":{"value":"Done"},"customfield_10102":{"value":"Done"},
            "attachment":[{"filename":"WBS_scope.xlsx"},{"filename":"CWC_review.pdf"},
                          {"filename":"UT_results.xlsx"}],
            "issuelinks":[]}
            """);

        for (int day : List.of(10, 11)) {
            var result = evaluator.evaluate(issue, settings(), day, Map.of());
            assertTrue(result.stream().allMatch(r -> r.outcome() == PASS), "day " + day);
            assertTrue(result.stream().anyMatch(r -> r.checkType().equals("LINKED_ISSUE") &&
                    r.actual().equals("No visible linked target issues")));
            assertTrue(result.stream().anyMatch(r -> r.checkType().equals("FINAL_GATE") &&
                    r.outcome() == PASS));
            assertEquals(0, result.stream().filter(r -> r.outcome() == SKIP).count());
        }
    }

    @Test
    void dayTenChecksArtifactsEvenWhenParentIsUnresolved() throws Exception {
        var issue = issue("""
            {"status":{"name":"In Progress"},"resolution":null,
            "customfield_10101":"Done","customfield_10102":"Done",
            "attachment":[],"issuelinks":[]}
            """);

        var result = evaluator.evaluate(issue, settings(), 10, Map.of());

        assertEquals(3, result.stream()
                .filter(r -> r.checkType().equals("ARTIFACT") && r.outcome() == FAIL).count());
        assertEquals(0, result.stream().filter(r -> r.outcome() == SKIP).count());
        assertTrue(result.stream().anyMatch(r -> r.checkType().equals("FINAL_GATE") &&
                r.outcome() == FAIL));
    }

    @Test
    void finalGateAcceptsClosedInwardAndOutwardBugAndTask() throws Exception {
        var parent = issue("""
            {"status":{"name":"Done"},"resolution":{"name":"Fixed"},
            "customfield_10101":"Done","customfield_10102":"Done",
            "attachment":[{"filename":"WBS_scope"},{"filename":"CWC_review"},{"filename":"UT_result"}],
            "issuelinks":[
              {"type":{"name":"blocks"},"inwardIssue":{"id":"20007","key":"BUG-7"}},
              {"type":{"name":"relates"},"outwardIssue":{"id":"20008","key":"TASK-8"}}
            ]}
            """);
        Map<String, JiraClient.JiraIssue> linked = Map.of(
                "BUG-7", linkedIssue("20007", "BUG-7", """
                    {"summary":"Fix payment defect","issuetype":{"name":"Bug"},
                     "assignee":{"displayName":"Dev One","accountId":"dev-1"},
                     "status":{"name":"Closed","statusCategory":{"key":"completed"}},
                     "resolution":{"name":"Fixed"}}
                    """),
                "TASK-8", linkedIssue("20008", "TASK-8", """
                    {"summary":"Complete deployment task","issuetype":{"name":"Task"},
                     "assignee":null,
                     "status":{"name":"Done","statusCategory":{"key":"done"}},
                     "resolution":null}
                    """));

        var result = evaluator.evaluate(parent, settings(), 10, linked);

        assertEquals(2, result.stream().filter(r -> r.checkType().equals("LINKED_ISSUE") &&
                r.outcome() == PASS).count());
        assertTrue(result.stream().allMatch(r -> r.outcome() == PASS));
        CheckResult bugCheck = result.stream().filter(r -> r.checkName().equals("BUG-7 (Bug)"))
                .findFirst().orElseThrow();
        assertNotNull(bugCheck.linkedIssue());
        assertEquals("20007", bugCheck.linkedIssue().jiraId());
        assertEquals("Dev One", bugCheck.linkedIssue().assigneeName());
    }

    @Test
    void finalGateFailsForOpenOrUnretrievableLinkedTargets() throws Exception {
        var parent = issue("""
            {"status":{"name":"Done"},"resolution":{"name":"Fixed"},
            "customfield_10101":"Done","customfield_10102":"Done",
            "attachment":[{"filename":"WBS_scope"},{"filename":"CWC_review"},{"filename":"UT_result"}],
            "issuelinks":[
              {"outwardIssue":{"id":"20007","key":"BUG-7"}},
              {"inwardIssue":{"id":"20008","key":"TASK-8"}}
            ]}
            """);
        Map<String, JiraClient.JiraIssue> linked = Map.of(
                "BUG-7", linkedIssue("20007", "BUG-7", """
                    {"summary":"Fix payment defect","issuetype":{"name":"Bug"},
                     "assignee":{"displayName":"Dev One","accountId":"dev-1"},
                     "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}},
                     "resolution":null}
                    """));

        var result = evaluator.evaluate(parent, settings(), 10, linked);

        assertTrue(result.stream().anyMatch(r -> r.checkType().equals("LINKED_ISSUE") &&
                r.checkName().equals("BUG-7 (Bug)") && r.outcome() == FAIL &&
                r.actual().contains("In Progress")));
        assertTrue(result.stream().anyMatch(r -> r.checkType().equals("LINKED_ISSUE") &&
                r.checkName().equals("TASK-8") && r.outcome() == FAIL &&
                r.reason().contains("permissions")));
        assertTrue(result.stream().anyMatch(r -> r.checkType().equals("FINAL_GATE") &&
                r.outcome() == FAIL));
    }

    @Test
    void finalGateFailsClosedWhenIssueLinksWereNotReturned() throws Exception {
        var issue = issue("""
            {"status":{"name":"Done"},"resolution":{"name":"Fixed"},
            "customfield_10101":"Done","customfield_10102":"Done",
            "attachment":[{"filename":"WBS_scope"},{"filename":"CWC_review"},{"filename":"UT_result"}]}
            """);

        var result = evaluator.evaluate(issue, settings(), 10, Map.of());

        assertTrue(result.stream().anyMatch(r -> r.checkType().equals("LINKED_ISSUE") &&
                r.outcome() == FAIL && r.reason().contains("was not returned")));
        assertTrue(result.stream().anyMatch(r -> r.checkType().equals("FINAL_GATE") &&
                r.outcome() == FAIL));
    }

    @Test
    void finalGateIgnoresLinkedIssueTypesOutsideTheConfiguredScope() throws Exception {
        var parent = issue("""
            {"status":{"name":"Done"},"resolution":{"name":"Fixed"},
            "customfield_10101":"Done","customfield_10102":"Done",
            "attachment":[{"filename":"WBS_scope"},{"filename":"CWC_review"},{"filename":"UT_result"}],
            "issuelinks":[{"outwardIssue":{"id":"20009","key":"STORY-9"}}]}
            """);
        var linkedStory = linkedIssue("20009", "STORY-9", """
            {"issuetype":{"name":"Story"}}
            """);

        var result = evaluator.evaluate(parent, settings(), 10, Map.of("STORY-9", linkedStory));

        assertTrue(result.stream().anyMatch(r -> r.checkType().equals("LINKED_ISSUE") &&
                r.actual().equals("No visible linked target issues") && r.outcome() == PASS));
        assertTrue(result.stream().allMatch(r -> r.outcome() == PASS));
    }

    @Test
    void finalGateDistinguishesMissingAndMalformedLinkedResolutionFromUnresolved() throws Exception {
        var parent = issue("""
            {"status":{"name":"Done"},"resolution":{"name":"Fixed"},
            "customfield_10101":"Done","customfield_10102":"Done",
            "attachment":[{"filename":"WBS_scope"},{"filename":"CWC_review"},{"filename":"UT_result"}],
            "issuelinks":[{"outwardIssue":{"id":"20007","key":"BUG-7"}}]}
            """);
        var linkedBug = linkedIssue("20007", "BUG-7", """
            {"summary":"Fix payment defect","issuetype":{"name":"Bug"},
             "assignee":null,
             "status":{"name":"Closed","statusCategory":{"key":"completed"}}}
            """);

        var result = evaluator.evaluate(parent, settings(), 10, Map.of("BUG-7", linkedBug));

        CheckResult linkCheck = result.stream().filter(r -> r.checkName().equals("BUG-7 (Bug)"))
                .findFirst().orElseThrow();
        assertEquals(FAIL, linkCheck.outcome());
        assertEquals("<not returned>", linkCheck.linkedIssue().resolution());
        assertTrue(linkCheck.reason().contains("resolution metadata"));

        var malformedBug = linkedIssue("20007", "BUG-7", """
            {"summary":"Fix payment defect","issuetype":{"name":"Bug"},
             "assignee":null,
             "status":{"name":"Closed","statusCategory":{"key":"completed"}},
             "resolution":"Fixed"}
            """);
        CheckResult malformedCheck = evaluator.evaluate(
                        parent, settings(), 10, Map.of("BUG-7", malformedBug)).stream()
                .filter(r -> r.checkName().equals("BUG-7 (Bug)"))
                .findFirst().orElseThrow();
        assertEquals(FAIL, malformedCheck.outcome());
        assertEquals("<malformed>", malformedCheck.linkedIssue().resolution());
    }

    private JiraClient.JiraIssue linkedIssue(String id, String key, String fieldsJson) throws Exception {
        return new JiraClient.JiraIssue(id, key, MAPPER.readTree(fieldsJson));
    }
}
