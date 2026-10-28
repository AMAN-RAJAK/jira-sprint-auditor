package com.example.jiraaudit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditConfigurationValidatorTest {
    @Test
    void acceptsBearerPatCredentialsAndRequiresHttps() {
        AuditProperties valid = TestAuditProperties.settings(3);
        assertDoesNotThrow(() -> AuditConfigurationValidator.validateCredentials(valid));

        AuditProperties missingPat = copy(valid,
                new AuditProperties.Jira("https://jira.company.example", " "),
                valid.runDay(), valid.checkpoints(), valid.artifacts());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validateCredentials(missingPat))
                .getMessage().contains("JIRA_PERSONAL_ACCESS_TOKEN"));

        AuditProperties insecure = copy(valid,
                new AuditProperties.Jira("http://jira.company.example", "test-pat"),
                valid.runDay(), valid.checkpoints(), valid.artifacts());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validateCredentials(insecure))
                .getMessage().contains("HTTPS"));
    }

    @Test
    void supportsOnlyDayThreeAndDayTen() {
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                TestAuditProperties.settings(3)));
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                TestAuditProperties.settings(10)));

        for (Integer day : List.of(1, 7, 11)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AuditConfigurationValidator.validatePolicy(
                            TestAuditProperties.settings(day)));
            assertTrue(error.getMessage().contains("exactly 3 or 10"));
        }
    }

    @Test
    void mailSettingsAreValidatedOnlyWhenMailIsEnabled() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.AuditContext context = new AuditProperties.AuditContext(
                "Payments", "9.5-GA", "Sprint-5");
        AuditProperties.Smtp smtp = new AuditProperties.Smtp(
                "mail.company.example", 2525, "smtp", true,
                "audit-user", "test-password", true);
        AuditProperties.Mail enabled = new AuditProperties.Mail(
                true, true, "audit@company.example",
                List.of("dev@company.example"), List.of("pqm@company.example"),
                "Payments - 9.5-GA Audit Report for Sprint-5", smtp);
        AuditProperties valid = withMail(base, context, enabled);

        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(valid));

        AuditProperties.Mail missingHost = new AuditProperties.Mail(
                true, true, enabled.from(), enabled.to(), enabled.cc(), enabled.subject(),
                new AuditProperties.Smtp(" ", 2525, "smtp", true,
                        "audit-user", "test-password", true));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        withMail(base, context, missingHost)))
                .getMessage().contains("smtpHostip"));

        AuditProperties.Mail badRecipient = new AuditProperties.Mail(
                true, true, enabled.from(), List.of("not-an-email"), enabled.cc(),
                enabled.subject(), smtp);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        withMail(base, context, badRecipient)))
                .getMessage().contains("Invalid email address"));

        AuditProperties.Mail missingCredentials = new AuditProperties.Mail(
                true, true, enabled.from(), enabled.to(), enabled.cc(), enabled.subject(),
                new AuditProperties.Smtp("mail.company.example", 2525, "smtp", true,
                        " ", " ", true));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        withMail(base, context, missingCredentials)))
                .getMessage().contains("smtpUsername"));

        AuditProperties.Mail disabled = new AuditProperties.Mail(
                false, true, null, null, null, null, null);
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                withMail(base, context, disabled)));
    }

    @Test
    void mailSubjectMustBeOneLine() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.AuditContext context = new AuditProperties.AuditContext(
                "Payments", "9.5-GA", "Sprint-5");
        AuditProperties.Smtp smtp = new AuditProperties.Smtp(
                "mail.company.example", 2525, "smtp", true,
                "audit-user", "test-password", true);
        AuditProperties.Mail invalid = new AuditProperties.Mail(
                true, true, "audit@company.example", List.of("dev@company.example"),
                List.of(), "Audit report\nBcc: unexpected@company.example", smtp);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        withMail(base, context, invalid)));

        assertTrue(error.getMessage().contains("subject must be a single line"));
    }

    @Test
    void checkpointsMustDefineExactlyThreeAndTen() {
        AuditProperties base = TestAuditProperties.settings(3);
        Map<Integer, AuditProperties.Checkpoint> onlyDayThree = Map.of(
                3, base.checkpoints().get(3));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(copy(base, base.jira(), 3,
                        onlyDayThree, base.artifacts())));
        assertTrue(error.getMessage().contains("exactly Day 3 and Day 10"));
    }

    @Test
    void dayTenRequiresConfiguredResolvedStatusesForLinkedBugs() {
        AuditProperties base = TestAuditProperties.settings(10);
        Map<Integer, AuditProperties.Checkpoint> checkpoints =
                new LinkedHashMap<>(base.checkpoints());
        checkpoints.put(10, new AuditProperties.Checkpoint(
                base.checkpoints().get(10).fields(), null, null, null));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(missing.getMessage().contains("linked-bugs is required"));

        checkpoints.put(10, new AuditProperties.Checkpoint(
                base.checkpoints().get(10).fields(),
                new AuditProperties.LinkedBugRule("Bug", "Resolved$$Done"), null, null));
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(empty.getMessage().contains("empty $-separated value"));
    }

    @Test
    void completionCommentMustReferenceAConfiguredFieldAndValidCompletedValues() {
        AuditProperties base = TestAuditProperties.settings(10);
        Map<Integer, AuditProperties.Checkpoint> checkpoints =
                new LinkedHashMap<>(base.checkpoints());
        AuditProperties.Checkpoint dayTen = base.checkpoints().get(10);
        checkpoints.put(10, new AuditProperties.Checkpoint(dayTen.fields(),
                dayTen.linkedBugs(), new AuditProperties.CompletionCommentRule(
                "unknown-field", "Completed", "Merged feature file link:"), null));

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(unknown.getMessage().contains("Unknown field-rule"));

        checkpoints.put(10, new AuditProperties.Checkpoint(dayTen.fields(),
                dayTen.linkedBugs(), new AuditProperties.CompletionCommentRule(
                "coding-status", "Completed$$Done", "Merged feature file link:"), null));
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(empty.getMessage().contains("empty $-separated"));
    }

    @Test
    void scenarioCountRuleRequiresAUniqueFieldAndResponsibleTeam() {
        AuditProperties base = TestAuditProperties.settings(10);
        Map<Integer, AuditProperties.Checkpoint> checkpoints =
                new LinkedHashMap<>(base.checkpoints());
        AuditProperties.Checkpoint dayTen = base.checkpoints().get(10);
        checkpoints.put(10, new AuditProperties.Checkpoint(dayTen.fields(),
                dayTen.linkedBugs(), dayTen.completionComment(),
                new AuditProperties.ScenarioCountRule(
                        "Scenario Count", "coding", "PQM")));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(duplicate.getMessage().contains("must not duplicate"));

        checkpoints.put(10, new AuditProperties.Checkpoint(dayTen.fields(),
                dayTen.linkedBugs(), dayTen.completionComment(),
                new AuditProperties.ScenarioCountRule(
                        "Scenario Count", "scenarioCount", " ")));
        IllegalArgumentException team = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(team.getMessage().contains("responsible-team"));
    }

    @Test
    void eachDayChoosesItsOwnFieldsAndAnyNonEmptyAcceptedValueList() {
        AuditProperties base = TestAuditProperties.settings(10);
        Map<String, AuditProperties.FieldRule> dayTen =
                new LinkedHashMap<>(base.checkpoints().get(10).fields());
        dayTen.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "coding", "DEV",
                "Done - On Time$Not Done$Not Applicable"));
        Map<Integer, AuditProperties.Checkpoint> checkpoints = new LinkedHashMap<>(base.checkpoints());
        checkpoints.put(10, new AuditProperties.Checkpoint(dayTen,
                base.checkpoints().get(10).linkedBugs(),
                base.checkpoints().get(10).completionComment(),
                base.checkpoints().get(10).atddScenarioCount()));
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                copy(base, base.jira(), 10, checkpoints, base.artifacts())));

        dayTen.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "coding", " ", "Done - On Time"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(error.getMessage().contains("responsible-team"));

        dayTen.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "coding", "DEV", ""));
        error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(error.getMessage().contains("accepted-values"));

        dayTen.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "coding", "DEV", "Done - On Time$$Not Applicable"));
        error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(error.getMessage().contains("empty $-separated value"));
    }

    @Test
    void nonEmptyMarkerMustBeTheOnlyAcceptedValue() {
        AuditProperties base = TestAuditProperties.settings(3);
        Map<String, AuditProperties.FieldRule> fields =
                new LinkedHashMap<>(base.checkpoints().get(3).fields());
        fields.put("revised-sp", new AuditProperties.FieldRule(
                "Revised SP", "revisedSp", "PMG", "__NON_EMPTY__$Done"));
        Map<Integer, AuditProperties.Checkpoint> checkpoints =
                new LinkedHashMap<>(base.checkpoints());
        checkpoints.put(3, new AuditProperties.Checkpoint(fields,
                base.checkpoints().get(3).linkedBugs(),
                base.checkpoints().get(3).completionComment(),
                base.checkpoints().get(3).atddScenarioCount()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 3, checkpoints, base.artifacts())));

        assertTrue(error.getMessage().contains("__NON_EMPTY__ must be the only accepted value"));
    }

    @Test
    void artifactFieldRulesMustReferenceDayTenFields() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.Day10Artifacts dayTen = base.artifacts().day10();
        AuditProperties.Artifacts invalid = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(),
                        new AuditProperties.ArtifactRule("CWC_", "unknown-field",
                                "N/A", "CWC Not Applicable"),
                        dayTen.ut(), dayTen.atdd(), dayTen.st()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), invalid)));
        assertTrue(error.getMessage().contains("Unknown Day 10 field-rule"));
    }

    @Test
    void notApplicableValuesAreRequiredButCanUseCompanySpecificText() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.Day10Artifacts dayTen = base.artifacts().day10();
        AuditProperties.Artifacts custom = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(),
                        new AuditProperties.ArtifactRule("CWC_", "coding-status",
                                "No Coding Required$Not Applicable (POC, Backporting, NoCodeChange)$N/A",
                                "CWC Not Applicable"),
                        dayTen.ut(), dayTen.atdd(), dayTen.st()));
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                copy(base, base.jira(), 10, base.checkpoints(), custom)));
        assertEquals(List.of("No Coding Required",
                        "Not Applicable (POC, Backporting, NoCodeChange)", "N/A"),
                custom.day10().cwc().notApplicableValueList());

        AuditProperties.Artifacts missingValues = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(),
                        new AuditProperties.ArtifactRule("CWC_", "coding-status",
                                "", "CWC Not Applicable"),
                        dayTen.ut(), dayTen.atdd(), dayTen.st()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), missingValues)))
                .getMessage().contains("not-applicable-values"));

        AuditProperties.Artifacts emptySeparatedValue = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(),
                        new AuditProperties.ArtifactRule("CWC_", "coding-status",
                                "Not Applicable$$N/A", "CWC Not Applicable"),
                        dayTen.ut(), dayTen.atdd(), dayTen.st()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), emptySeparatedValue)))
                .getMessage().contains("empty $-separated value"));
    }

    @Test
    void utUsesAtddStatusAndDoesNotConfigureItsOwnFieldRule() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.Day10Artifacts dayTen = base.artifacts().day10();
        AuditProperties.Artifacts invalid = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(), dayTen.cwc(),
                        new AuditProperties.ArtifactRule("UT_", "coding-status",
                                "Not Applicable", "UT Not Applicable"),
                        dayTen.atdd(), dayTen.st()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), invalid)));
        assertTrue(error.getMessage().contains("controlled by ATDD Status"));
    }

    @Test
    void artifactPrefixesMustBeUnique() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.Day10Artifacts dayTen = base.artifacts().day10();
        AuditProperties.Artifacts invalid = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(), dayTen.cwc(),
                        new AuditProperties.ArtifactRule("CWC_", null, null,
                                "UT Not Applicable"),
                        dayTen.atdd(), dayTen.st()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), invalid)))
                .getMessage().contains("Duplicate Day 10 artifact prefix"));
    }

    @Test
    void wbsPrefixesAllowAlternativesAndRejectEmptyOrDuplicateEntries() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.Day10Artifacts dayTen = base.artifacts().day10();
        AuditProperties.Artifacts alternatives = new AuditProperties.Artifacts(
                "WBS_$WORK_BREAKDOWN_",
                new AuditProperties.Day10Artifacts("WBS_$WORK_BREAKDOWN_",
                        dayTen.cwc(), dayTen.ut(), dayTen.atdd(), dayTen.st()));

        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                copy(base, base.jira(), 10, base.checkpoints(), alternatives)));
        assertEquals(List.of("WBS_", "WORK_BREAKDOWN_"),
                alternatives.day3WbsPrefixList());

        AuditProperties.Artifacts empty = new AuditProperties.Artifacts("WBS_$$ESTIMATE_",
                dayTen);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), empty)))
                .getMessage().contains("empty $-separated prefix"));

        AuditProperties.Artifacts duplicate = new AuditProperties.Artifacts("WBS_$wbs_",
                dayTen);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), duplicate)))
                .getMessage().contains("duplicate prefix"));
    }

    @Test
    void everyDayTenWbsAlternativeMustBeUniqueAcrossArtifactPrefixes() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.Day10Artifacts dayTen = base.artifacts().day10();
        AuditProperties.Artifacts invalid = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts("WBS_$CWC_", dayTen.cwc(),
                        dayTen.ut(), dayTen.atdd(), dayTen.st()));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), invalid)))
                .getMessage().contains("Duplicate Day 10 artifact prefix"));
    }

    private static AuditProperties copy(AuditProperties base,
                                        AuditProperties.Jira jira,
                                        Integer day,
                                        Map<Integer, AuditProperties.Checkpoint> checkpoints,
                                        AuditProperties.Artifacts artifacts) {
        return new AuditProperties(jira, base.jql(), day, base.pageSize(), checkpoints,
                artifacts, base.report(), base.context(), base.mail());
    }

    private static AuditProperties withMail(AuditProperties base,
                                            AuditProperties.AuditContext context,
                                            AuditProperties.Mail mail) {
        return new AuditProperties(base.jira(), base.jql(), base.runDay(), base.pageSize(),
                base.checkpoints(), base.artifacts(), base.report(), context, mail);
    }
}
