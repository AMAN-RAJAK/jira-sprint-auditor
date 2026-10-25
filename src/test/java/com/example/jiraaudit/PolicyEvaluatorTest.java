package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.jiraaudit.CheckResult.Outcome.FAIL;
import static com.example.jiraaudit.CheckResult.Outcome.PASS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEvaluatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PolicyEvaluator evaluator = new PolicyEvaluator();

    @Test
    void dayThreeChecksItsConfiguredFieldsAndWbsOnly() throws Exception {
        var issue = issue("""
                {"status":{"name":"In Progress"},"coding":"Done - On Time",
                 "download":"Started","attachment":[{"filename":"WBS_scope.xlsx"}]}
                """);

        var results = evaluator.evaluate(issue, TestAuditProperties.settings(3), 3, List.of());

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
        assertEquals(List.of("WBS_"), results.stream()
                .filter(result -> result.checkType().equals("ARTIFACT"))
                .map(CheckResult::ruleKey).toList());
        assertFalse(evaluator.requiresComments(issue, TestAuditProperties.settings(3), 3));
    }

    @Test
    void dayThreeAcceptsAnyConfiguredValueAndReportsMissingWbs() throws Exception {
        var issue = issue("""
                {"status":{"name":"In Progress"},"coding":"Not Applicable",
                 "download":"In Progress","attachment":[]}
                """);

        var results = evaluator.evaluate(issue, TestAuditProperties.settings(3), 3, List.of());

        assertTrue(results.stream().filter(result -> result.checkType().equals("FIELD"))
                .allMatch(result -> result.outcome() == PASS));
        CheckResult wbs = result(results, "WBS_");
        assertEquals(FAIL, wbs.outcome());
        assertTrue(wbs.reason().contains("cannot be waived"));
    }

    @Test
    void dayTenPassesForUtRouteWhenAtddFieldIsNotApplicable() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"Done - On Time","st":"Done - On Time",
                 "atdd":"Not Applicable","attachment":[{"filename":"WBS_scope"},
                 {"filename":"CWC_review"},{"filename":"UT_results"},{"filename":"ST_results"}]}
                """);

        var results = evaluator.evaluate(issue, TestAuditProperties.settings(10), 10, List.of());

        assertEquals(9, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
        assertEquals(PASS, result(results, "UT_OR_ATDD").outcome());
        assertFalse(evaluator.requiresComments(issue, TestAuditProperties.settings(10), 10));
    }

    @Test
    void dayTenPassesForAtddRouteWhenUtHasStandardComment() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"Done - On Time","st":"Done - On Time",
                 "atdd":"Done - On Time","attachment":[{"filename":"WBS_scope"},
                 {"filename":"CWC_review"},{"filename":"ATDD_results"},{"filename":"ST_results"}]}
                """);
        List<JiraClient.JiraComment> comments = List.of(
                comment("new", "UT Not Applicable", "2026-10-25T09:00:00Z"));

        assertTrue(evaluator.requiresComments(issue, TestAuditProperties.settings(10), 10));
        var results = evaluator.evaluate(issue, TestAuditProperties.settings(10), 10, comments);

        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
        assertTrue(result(results, "UT_").actual().contains("comment=new"));
    }

    @Test
    void atddStatusControlsTheValidUtAndAtddCombination() throws Exception {
        var both = issue("""
                {"status":{"name":"Done"},"coding":"Done - On Time","st":"Done - On Time",
                 "atdd":"Done - On Time","attachment":[{"filename":"WBS_scope"},
                 {"filename":"CWC_review"},{"filename":"UT_results"},
                 {"filename":"ATDD_results"},{"filename":"ST_results"}]}
                """);
        var neither = issue("""
                {"status":{"name":"Done"},"coding":"Done - On Time","st":"Done - On Time",
                 "atdd":"Not Applicable","attachment":[{"filename":"WBS_scope"},
                 {"filename":"CWC_review"},{"filename":"ST_results"}]}
                """);

        assertEquals(FAIL, result(evaluator.evaluate(both, TestAuditProperties.settings(10), 10,
                List.of()), "UT_OR_ATDD").outcome());
        assertTrue(evaluator.requiresComments(neither, TestAuditProperties.settings(10), 10));
        var neitherWithUtComment = evaluator.evaluate(neither, TestAuditProperties.settings(10), 10,
                List.of(comment("1", "UT Not Applicable", "2026-10-25T09:00:00Z")));
        assertTrue(neitherWithUtComment.stream().allMatch(result -> result.outcome() == PASS));

        CheckResult missingUtComment = result(evaluator.evaluate(neither,
                TestAuditProperties.settings(10), 10, List.of()), "UT_");
        assertEquals(FAIL, missingUtComment.outcome());
        assertTrue(missingUtComment.reason().contains("UT Not Applicable"));
    }

    @Test
    void applicableAtddStatusRequiresAttachmentEvenWhenAtddCommentExists() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"Done - On Time","st":"Done - On Time",
                 "atdd":"Done - On Time","attachment":[{"filename":"WBS_scope"},
                 {"filename":"CWC_review"},{"filename":"UT_results"},{"filename":"ST_results"}]}
                """);

        var results = evaluator.evaluate(issue, TestAuditProperties.settings(10), 10,
                List.of(comment("1", "ATDD Not Applicable", "2026-10-25T09:00:00Z")));

        CheckResult atdd = result(results, "ATDD_");
        assertEquals(FAIL, atdd.outcome());
        assertTrue(atdd.reason().contains("cannot be waived by a comment"));
        assertEquals(FAIL, result(results, "UT_OR_ATDD").outcome());
        assertFalse(evaluator.requiresComments(issue, TestAuditProperties.settings(10), 10));
    }

    @Test
    void notApplicableFieldRequiresItsArtifactToBeAbsentWithoutAComment() throws Exception {
        var absent = issue("""
                {"status":{"name":"Done"},"coding":"Not Applicable","st":"Not Applicable",
                 "atdd":"Not Applicable","attachment":[{"filename":"WBS_scope"},
                 {"filename":"UT_results"}]}
                """);
        var wronglyAttached = issue("""
                {"status":{"name":"Done"},"coding":"Not Applicable","st":"Not Applicable",
                 "atdd":"Not Applicable","attachment":[{"filename":"WBS_scope"},
                 {"filename":"CWC_review"},{"filename":"UT_results"}]}
                """);

        var absentResults = evaluator.evaluate(absent, TestAuditProperties.settings(10), 10, List.of());
        assertEquals(PASS, result(absentResults, "CWC_").outcome());
        assertEquals(PASS, result(absentResults, "ST_").outcome());
        assertEquals(PASS, result(absentResults, "ATDD_").outcome());
        assertFalse(evaluator.requiresComments(absent, TestAuditProperties.settings(10), 10));

        CheckResult cwc = result(evaluator.evaluate(wronglyAttached,
                TestAuditProperties.settings(10), 10, List.of()), "CWC_");
        assertEquals(FAIL, cwc.outcome());
        assertTrue(cwc.reason().contains("must not be attached"));
    }

    @Test
    void missingApplicableArtifactNeedsItsConfiguredComment() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"Done - On Time","st":"Done - On Time",
                 "atdd":"Not Applicable","attachment":[{"filename":"WBS_scope"},
                 {"filename":"UT_results"},{"filename":"ST_results"}]}
                """);

        CheckResult missing = result(evaluator.evaluate(issue, TestAuditProperties.settings(10), 10,
                List.of()), "CWC_");
        assertEquals(FAIL, missing.outcome());
        assertTrue(missing.reason().contains("CWC Not Applicable"));

        List<JiraClient.JiraComment> newestFirst = List.of(
                comment("latest", "unrelated", "2026-10-25T10:00:00Z"),
                adfComment("match", "CWC", "Not Applicable", "2026-10-25T09:00:00Z"),
                comment("old", "CWC Not Applicable", "2026-10-24T10:00:00Z"));
        CheckResult waived = result(evaluator.evaluate(issue, TestAuditProperties.settings(10), 10,
                newestFirst), "CWC_");
        assertEquals(PASS, waived.outcome());
        assertTrue(waived.actual().contains("comment=match"));
    }

    @Test
    void aConfiguredFieldMustMatchOneOfItsPossibleValues() throws Exception {
        var issue = issue("""
                {"status":{"name":"In Progress"},"coding":"Blocked",
                 "download":"Started","attachment":[{"filename":"WBS_scope"}]}
                """);

        CheckResult coding = result(evaluator.evaluate(issue,
                TestAuditProperties.settings(3), 3, List.of()), "coding-status");

        assertEquals(FAIL, coding.outcome());
        assertEquals("In Progress | Done - On Time | Not Applicable", coding.expected());
        assertEquals("Blocked", coding.actual());
    }

    @Test
    void dollarSeparatorPreservesCommasInsideAcceptedValues() throws Exception {
        AuditProperties base = TestAuditProperties.settings(3);
        Map<String, AuditProperties.FieldRule> fields =
                new LinkedHashMap<>(base.checkpoints().get(3).fields());
        fields.put("download", new AuditProperties.FieldRule("Download", "download",
                "Not Applicable (POC, Backporting, NoCodeChange)$Suite Pass (90%)"));
        Map<Integer, AuditProperties.Checkpoint> checkpoints =
                new LinkedHashMap<>(base.checkpoints());
        checkpoints.put(3, new AuditProperties.Checkpoint(fields));
        AuditProperties custom = new AuditProperties(base.jira(), base.jql(), 3, base.pageSize(),
                checkpoints, base.artifacts(), base.report());
        var issue = issue("""
                {"status":{"name":"In Progress"},"coding":"In Progress",
                 "download":"Not Applicable (POC, Backporting, NoCodeChange)",
                 "attachment":[{"filename":"WBS_scope"}]}
                """);

        CheckResult result = result(evaluator.evaluate(issue, custom, 3, List.of()), "download");

        assertEquals(PASS, result.outcome());
        assertEquals("Not Applicable (POC, Backporting, NoCodeChange) | Suite Pass (90%)",
                result.expected());
    }

    @Test
    void nonEmptyRuleAcceptsAnyRevisedSpValueAndRejectsEmptyValues() throws Exception {
        AuditProperties base = TestAuditProperties.settings(3);
        Map<String, AuditProperties.FieldRule> fields =
                new LinkedHashMap<>(base.checkpoints().get(3).fields());
        fields.put("revised-sp", new AuditProperties.FieldRule(
                "Revised SP", "revisedSp", AuditProperties.FieldRule.NON_EMPTY_VALUE));
        Map<Integer, AuditProperties.Checkpoint> checkpoints =
                new LinkedHashMap<>(base.checkpoints());
        checkpoints.put(3, new AuditProperties.Checkpoint(fields));
        AuditProperties custom = new AuditProperties(base.jira(), base.jql(), 3, base.pageSize(),
                checkpoints, base.artifacts(), base.report());

        var populated = issue("""
                {"status":{"name":"In Progress"},"coding":"In Progress","download":"Started",
                 "revisedSp":8,"attachment":[{"filename":"WBS_scope"}]}
                """);
        CheckResult populatedResult = result(
                evaluator.evaluate(populated, custom, 3, List.of()), "revised-sp");
        assertEquals(PASS, populatedResult.outcome());
        assertEquals("Any non-empty value", populatedResult.expected());
        assertEquals("8", populatedResult.actual());

        for (String emptyValue : List.of("null", "\"\"", "\"   \"", "[]", "{}")) {
            var empty = issue("""
                    {"status":{"name":"In Progress"},"coding":"In Progress","download":"Started",
                     "revisedSp":%s,"attachment":[{"filename":"WBS_scope"}]}
                    """.formatted(emptyValue));
            CheckResult emptyResult = result(
                    evaluator.evaluate(empty, custom, 3, List.of()), "revised-sp");
            assertEquals(FAIL, emptyResult.outcome(), "Expected empty value to fail: " + emptyValue);
            assertTrue(emptyResult.reason().contains("must not be empty"));
        }

        var missing = issue("""
                {"status":{"name":"In Progress"},"coding":"In Progress","download":"Started",
                 "attachment":[{"filename":"WBS_scope"}]}
                """);
        CheckResult missingResult = result(
                evaluator.evaluate(missing, custom, 3, List.of()), "revised-sp");
        assertEquals(FAIL, missingResult.outcome());
        assertEquals("<not returned>", missingResult.actual());
    }

    @Test
    void missingConfiguredFieldFailsAndUnassignedUserIsReported() throws Exception {
        var issue = issue("""
                {"assignee":null,"status":{"name":"In Progress"},
                 "download":"Started","attachment":[{"filename":"WBS_scope"}]}
                """);

        var results = evaluator.evaluate(issue, TestAuditProperties.settings(3), 3, List.of());

        CheckResult coding = result(results, "coding-status");
        assertEquals(FAIL, coding.outcome());
        assertEquals("<not returned>", coding.actual());
        assertEquals("Unassigned", coding.issue().assigneeName());
    }

    @Test
    void rejectsMissingEssentialJiraMetadata() throws Exception {
        var fields = MAPPER.readTree("""
                {"summary":"Story","assignee":null,"coding":"In Progress",
                 "download":"Started","attachment":[]}
                """);
        var issue = new JiraClient.JiraIssue("10002", "PAY-102", fields);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(issue, TestAuditProperties.settings(3), 3, List.of()));

        assertTrue(error.getMessage().contains("status"));
    }

    private CheckResult result(List<CheckResult> results, String ruleKey) {
        return results.stream().filter(result -> result.ruleKey().equals(ruleKey))
                .findFirst().orElseThrow();
    }

    private JiraClient.JiraIssue issue(String json) throws Exception {
        ObjectNode fields = (ObjectNode) MAPPER.readTree(json);
        if (!fields.has("summary")) fields.put("summary", "Implement payment audit");
        if (!fields.has("assignee")) {
            fields.set("assignee", MAPPER.readTree(
                    "{\"displayName\":\"Asha Rao\",\"name\":\"arao\"}"));
        }
        return new JiraClient.JiraIssue("10002", "PAY-102", fields);
    }

    private JiraClient.JiraComment comment(String id, String text, String created) {
        return new JiraClient.JiraComment(id, MAPPER.getNodeFactory().textNode(text),
                Instant.parse(created));
    }

    private JiraClient.JiraComment adfComment(String id, String first, String second,
                                               String created) throws Exception {
        JsonNode body = MAPPER.readTree("""
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"%s"},{"type":"text","text":"%s"}
                ]}]}
                """.formatted(first, second));
        return new JiraClient.JiraComment(id, body, Instant.parse(created));
    }
}
