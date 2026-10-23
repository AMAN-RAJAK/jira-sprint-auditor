package com.example.jiraaudit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditConfigurationValidatorTest {

    @Test
    void acceptsValidFinalGate() {
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(properties(
                new AuditProperties.FinalGate(10, List.of("Bug", "Task"),
                        List.of("completed", "done")))));
    }

    @Test
    void requiresExplicitRunDay() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(
                        validFinalGate(), validDays(), null)));

        assertTrue(error.getMessage().contains("audit.run-day is required"));
    }

    @Test
    void rejectsNonPositiveRunDay() {
        for (int runDay : List.of(0, -1)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AuditConfigurationValidator.validatePolicy(properties(
                            validFinalGate(), validDays(), runDay)));
            assertTrue(error.getMessage().contains("audit.run-day must be >= 1"));
        }
    }

    @Test
    void acceptsDeclaredCheckpointsAndDaysAfterFinalGate() {
        for (int runDay : List.of(1, 3, 7, 10, 11)) {
            assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(properties(
                    validFinalGate(), validDays(), runDay)), "run day " + runDay);
        }
    }

    @Test
    void requiresFinalGate() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(null)));

        assertTrue(error.getMessage().contains("audit.final-gate is required"));
    }

    @Test
    void requiresPositiveFinalGateStartDay() {
        for (Integer fromDay : new Integer[]{null, 0, -1}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AuditConfigurationValidator.validatePolicy(properties(
                            new AuditProperties.FinalGate(fromDay, List.of("Bug"), List.of("completed")))));
            assertTrue(error.getMessage().contains("from-day must be >= 1"));
        }
    }

    @Test
    void requiresLinkedIssueTypes() {
        assertInvalidList(new AuditProperties.FinalGate(10, List.of(), List.of("completed")),
                "linked-issue-types must not be empty");
        assertInvalidList(new AuditProperties.FinalGate(10, null, List.of("completed")),
                "linked-issue-types must not be empty");
    }

    @Test
    void rejectsBlankAndCaseInsensitiveDuplicateLinkedIssueTypes() {
        assertInvalidList(new AuditProperties.FinalGate(10, List.of("Bug", "  "),
                        List.of("completed")),
                "linked-issue-types entry");
        assertInvalidList(new AuditProperties.FinalGate(10, List.of("Bug", " bug "),
                        List.of("completed")),
                "Duplicate value in audit.final-gate.linked-issue-types");
    }

    @Test
    void requiresCompletedStatusCategoryKeys() {
        assertInvalidList(new AuditProperties.FinalGate(10, List.of("Bug"), null),
                "completed-status-category-keys must not be empty");
        assertInvalidList(new AuditProperties.FinalGate(10, List.of("Bug"), List.of()),
                "completed-status-category-keys must not be empty");
    }

    @Test
    void rejectsBlankAndCaseInsensitiveDuplicateCompletedStatusCategoryKeys() {
        assertInvalidList(new AuditProperties.FinalGate(10, List.of("Bug"),
                        List.of("completed", " ")),
                "completed-status-category-keys entry");
        assertInvalidList(new AuditProperties.FinalGate(10, List.of("Bug"),
                        List.of("completed", " COMPLETED ")),
                "Duplicate value in audit.final-gate.completed-status-category-keys");
    }

    @Test
    void requiresAnExplicitExpectationOnTheFinalGateDay() {
        var finalGate = new AuditProperties.FinalGate(10, List.of("Bug"), List.of("completed"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(finalGate,
                        Map.of(1, List.of("Started"), 7, List.of("Done")))));

        assertTrue(error.getMessage().contains("Day 10 final-gate expectation required for download"));
    }

    @Test
    void rejectsRulesAfterTheFinalGateBecauseTheGateValueIsPinned() {
        var finalGate = new AuditProperties.FinalGate(10, List.of("Bug"), List.of("completed"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(finalGate,
                        Map.of(1, List.of("Started"), 10, List.of("Done"), 11, List.of("Started")))));

        assertTrue(error.getMessage().contains("Day rules after the final gate are not allowed"));
    }

    private void assertInvalidList(AuditProperties.FinalGate finalGate, String messagePart) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(finalGate)));
        assertTrue(error.getMessage().contains(messagePart), error::getMessage);
    }

    private AuditProperties properties(AuditProperties.FinalGate finalGate) {
        return properties(finalGate, validDays());
    }

    private AuditProperties properties(AuditProperties.FinalGate finalGate,
                                       Map<Integer, List<String>> days) {
        return properties(finalGate, days, 1);
    }

    private AuditProperties properties(AuditProperties.FinalGate finalGate,
                                       Map<Integer, List<String>> days,
                                       Integer runDay) {
        return new AuditProperties(
                new AuditProperties.Jira("https://test.atlassian.net", "audit@example.com", "token"),
                "project = TEST",
                runDay,
                100,
                Map.of("download", new AuditProperties.FieldRule(
                        "Download", "customfield_10101", days,
                        List.of("Done"))),
                new AuditProperties.Artifacts(true, List.of("WBS_")),
                finalGate,
                new AuditProperties.Report("target/reports", true, true));
    }

    private AuditProperties.FinalGate validFinalGate() {
        return new AuditProperties.FinalGate(10, List.of("Bug", "Task"),
                List.of("completed", "done"));
    }

    private Map<Integer, List<String>> validDays() {
        return Map.of(1, List.of("Started"), 3, List.of("In Progress"),
                7, List.of("Done"), 10, List.of("Done"));
    }
}
