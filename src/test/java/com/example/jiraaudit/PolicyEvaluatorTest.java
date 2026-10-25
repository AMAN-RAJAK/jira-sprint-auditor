package com.example.jiraaudit;

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
    void dayOneChecksOnlyConfiguredFields() throws Exception {
        var issue = issue("""
                {"status":{"name":"In Progress"},"coding":"Started","st":"Not Started",
                 "attachment":[]}
                """);

        var results = evaluator.evaluate(issue, settings(), 1, List.of());

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
        assertTrue(results.stream().noneMatch(result -> result.checkType().equals("ARTIFACT")));
    }

    @Test
    void dayThreeChecksFieldsAndOnlyWbs() throws Exception {
        var issue = issue("""
                {"status":{"name":"In Progress"},"coding":"In Progress","st":"In Progress",
                 "attachment":[{"filename":"WBS_scope.xlsx"}]}
                """);

        var results = evaluator.evaluate(issue, settings(), 3, List.of());

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
        assertEquals(List.of("WBS_"), results.stream()
                .filter(result -> result.checkType().equals("ARTIFACT"))
                .map(CheckResult::checkName).toList());
        assertFalse(evaluator.requiresComments(issue, settings(), 3));
    }

    @Test
    void dayThreeMissingWbsFailsWithoutCheckingOtherArtifactsOrComments() throws Exception {
        var issue = issue("""
                {"status":{"name":"In Progress"},"coding":"In Progress","st":"In Progress",
                 "attachment":[]}
                """);

        var results = evaluator.evaluate(issue, settings(), 3, List.of());

        assertEquals(1, results.stream().filter(result -> result.outcome() == FAIL).count());
        assertTrue(results.stream().anyMatch(result -> result.checkName().equals("WBS_") &&
                result.reason().contains("not attached")));
        assertTrue(results.stream().noneMatch(result -> result.checkName().equals("CWC_")));
        assertFalse(evaluator.requiresComments(issue, settings(), 3));
    }

    @Test
    void daySevenChecksOnlyExactDaySevenFieldValues() throws Exception {
        var issue = issue("""
                {"status":{"name":"In Progress"},"coding":"Done","st":"Done","attachment":[]}
                """);

        var results = evaluator.evaluate(issue, settings(), 7, List.of());

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
    }

    @Test
    void dayTenPassesWhenEveryFieldAndArtifactMatches() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"Done","st":"Done",
                 "attachment":[{"filename":"WBS_scope"},{"filename":"CWC_review"},
                   {"filename":"UT_results"},{"filename":"ATDD_evidence"},{"filename":"ST_results"}]}
                """);

        var results = evaluator.evaluate(issue, settings(), 10, List.of());

        assertEquals(7, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
        assertFalse(evaluator.requiresComments(issue, settings(), 10));
    }

    @Test
    void missingWbsAlwaysFailsEvenWhenACommentClaimsNotApplicable() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"Done","st":"Done",
                 "attachment":[{"filename":"CWC_review"},{"filename":"UT_results"},
                   {"filename":"ATDD_evidence"},{"filename":"ST_results"}]}
                """);

        var results = evaluator.evaluate(issue, settings(), 10,
                List.of(comment("9", "WBS Not Applicable", "2026-10-25T08:00:00Z")));

        assertTrue(results.stream().anyMatch(result -> result.checkName().equals("WBS_") &&
                result.outcome() == FAIL));
        assertFalse(evaluator.requiresComments(issue, settings(), 10));
    }

    @Test
    void missingCwcPassesOnlyWithNoCodeChangesAndNewestMatchingComment() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"No Code Changes","st":"Done",
                 "attachment":[{"filename":"WBS_scope"},{"filename":"UT_results"},
                   {"filename":"ATDD_evidence"},{"filename":"ST_results"}]}
                """);
        List<JiraClient.JiraComment> comments = List.of(
                comment("new", "Unrelated newest comment", "2026-10-25T09:00:00Z"),
                adfComment("match", "CWC", "Not Applicable", "2026-10-25T08:00:00Z"));

        assertTrue(evaluator.requiresComments(issue, settings(), 10));
        CheckResult cwc = evaluator.evaluate(issue, settings(), 10, comments).stream()
                .filter(result -> result.checkName().equals("CWC_")).findFirst().orElseThrow();

        assertEquals(PASS, cwc.outcome());
        assertTrue(cwc.actual().contains("comment=match"));
        assertTrue(cwc.actual().contains("Coding Status=No Code Changes"));
    }

    @Test
    void missingCwcFailsWithoutBothRequiredEvidenceParts() throws Exception {
        var noComment = issue("""
                {"status":{"name":"Done"},"coding":"No Code Changes","st":"Done",
                 "attachment":[{"filename":"WBS_scope"},{"filename":"UT_results"},
                   {"filename":"ATDD_evidence"},{"filename":"ST_results"}]}
                """);
        CheckResult commentFailure = evaluator.evaluate(noComment, settings(), 10, List.of()).stream()
                .filter(result -> result.checkName().equals("CWC_")).findFirst().orElseThrow();
        assertEquals(FAIL, commentFailure.outcome());
        assertTrue(commentFailure.reason().contains("comments do not contain"));

        var wrongField = issue("""
                {"status":{"name":"Done"},"coding":"Done","st":"Done",
                 "attachment":[{"filename":"WBS_scope"},{"filename":"UT_results"},
                   {"filename":"ATDD_evidence"},{"filename":"ST_results"}]}
                """);
        CheckResult fieldFailure = evaluator.evaluate(wrongField, settings(), 10,
                        List.of(comment("1", "cwc not applicable", "2026-10-25T08:00:00Z"))).stream()
                .filter(result -> result.checkName().equals("CWC_")).findFirst().orElseThrow();
        assertEquals(FAIL, fieldFailure.outcome());
        assertTrue(fieldFailure.reason().contains("Coding Status must be No Code Changes"));
    }

    @Test
    void missingUtAtddAndStPassWithConfiguredNotApplicableEvidence() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"Done","st":"Not Applicable",
                 "attachment":[{"filename":"WBS_scope"},{"filename":"CWC_review"}]}
                """);
        var comment = comment("10", "UT Not Applicable; ATDD NOT APPLICABLE; ST Not Applicable",
                "2026-10-25T10:00:00Z");

        var results = evaluator.evaluate(issue, settings(), 10, List.of(comment));

        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
        assertEquals(3, results.stream().filter(result -> result.checkType().equals("ARTIFACT") &&
                result.actual().contains("attachment=<missing>")).count());
    }

    @Test
    void dayAfterTenUsesTheDayTenPolicy() throws Exception {
        var issue = issue("""
                {"status":{"name":"Done"},"coding":"Done","st":"Done",
                 "attachment":[{"filename":"WBS_scope"},{"filename":"CWC_review"},
                   {"filename":"UT_results"},{"filename":"ATDD_evidence"},{"filename":"ST_results"}]}
                """);

        var results = evaluator.evaluate(issue, settings(), 12, List.of());

        assertTrue(results.stream().allMatch(result -> result.outcome() == PASS));
        assertEquals(7, results.size());
    }

    @Test
    void missingConfiguredFieldFailsAndUnassignedUserIsReported() throws Exception {
        var issue = issue("""
                {"assignee":null,"status":{"name":"In Progress"},"st":"Not Started","attachment":[]}
                """);

        var results = evaluator.evaluate(issue, settings(), 1, List.of());

        assertTrue(results.stream().anyMatch(result -> result.checkName().equals("Coding Status") &&
                result.outcome() == FAIL && result.actual().equals("<not returned>")));
        assertEquals("Unassigned", results.get(0).issue().assigneeName());
    }

    @Test
    void rejectsMissingEssentialJiraMetadata() throws Exception {
        var fields = MAPPER.readTree("""
                {"summary":"Story","assignee":null,"coding":"Started","st":"Not Started",
                 "attachment":[]}
                """);
        var issue = new JiraClient.JiraIssue("10002", "PAY-102", fields);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(issue, settings(), 1, List.of()));

        assertTrue(error.getMessage().contains("status"));
    }

    private AuditProperties settings() {
        Map<String, AuditProperties.FieldRule> fields = new LinkedHashMap<>();
        fields.put("coding-status", new AuditProperties.FieldRule("Coding Status", "coding",
                Map.of(1, List.of("Started"), 3, List.of("In Progress"), 7, List.of("Done"),
                        10, List.of("Done", "No Code Changes"))));
        fields.put("st-status", new AuditProperties.FieldRule("ST Status", "st",
                Map.of(1, List.of("Not Started"), 3, List.of("In Progress"), 7, List.of("Done"),
                        10, List.of("Done", "Not Applicable"))));

        Map<String, AuditProperties.MissingArtifactRule> exceptions = new LinkedHashMap<>();
        exceptions.put("CWC_", new AuditProperties.MissingArtifactRule(
                "coding-status", List.of("No Code Changes"), "CWC Not Applicable"));
        exceptions.put("UT_", new AuditProperties.MissingArtifactRule(
                null, null, "UT Not Applicable"));
        exceptions.put("ATDD_", new AuditProperties.MissingArtifactRule(
                null, null, "ATDD Not Applicable"));
        exceptions.put("ST_", new AuditProperties.MissingArtifactRule(
                "st-status", List.of("Not Applicable"), "ST Not Applicable"));

        return new AuditProperties(
                new AuditProperties.Jira("https://test.atlassian.net", "audit@example.com", "token"),
                "project = TEST", 1, 100, fields,
                new AuditProperties.Artifacts(Map.of(
                        1, List.of(), 3, List.of("WBS_"), 7, List.of(),
                        10, List.of("WBS_", "CWC_", "UT_", "ATDD_", "ST_")), exceptions),
                new AuditProperties.Report("target/reports", true, true));
    }

    private JiraClient.JiraIssue issue(String json) throws Exception {
        ObjectNode fields = (ObjectNode) MAPPER.readTree(json);
        if (!fields.has("summary")) fields.put("summary", "Implement payment audit");
        if (!fields.has("assignee")) {
            fields.set("assignee", MAPPER.readTree(
                    "{\"displayName\":\"Asha Rao\",\"accountId\":\"abc-123\"}"));
        }
        return new JiraClient.JiraIssue("10002", "PAY-102", fields);
    }

    private JiraClient.JiraComment comment(String id, String text, String created) throws Exception {
        return new JiraClient.JiraComment(id, MAPPER.getNodeFactory().textNode(text), Instant.parse(created));
    }

    private JiraClient.JiraComment adfComment(String id, String first, String second,
                                               String created) throws Exception {
        JsonNodeBuilder body = new JsonNodeBuilder(first, second);
        return new JiraClient.JiraComment(id, body.value(), Instant.parse(created));
    }

    private record JsonNodeBuilder(String first, String second) {
        com.fasterxml.jackson.databind.JsonNode value() throws Exception {
            return MAPPER.readTree("""
                    {"type":"doc","content":[{"type":"paragraph","content":[
                      {"type":"text","text":"%s"},{"type":"text","text":"%s"}
                    ]}]}
                    """.formatted(first, second));
        }
    }
}
