package com.example.jiraaudit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void eachDayChoosesItsOwnFieldsAndAnyNonEmptyAcceptedValueList() {
        AuditProperties base = TestAuditProperties.settings(10);
        Map<String, AuditProperties.FieldRule> dayTen =
                new LinkedHashMap<>(base.checkpoints().get(10).fields());
        dayTen.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "coding",
                List.of("Done - On Time", "Not Done", "Not Applicable")));
        Map<Integer, AuditProperties.Checkpoint> checkpoints = new LinkedHashMap<>(base.checkpoints());
        checkpoints.put(10, new AuditProperties.Checkpoint(dayTen));
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                copy(base, base.jira(), 10, checkpoints, base.artifacts())));

        dayTen.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "coding", List.of()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, checkpoints, base.artifacts())));
        assertTrue(error.getMessage().contains("accepted-values"));
    }

    @Test
    void artifactFieldRulesMustReferenceDayTenFields() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.Day10Artifacts dayTen = base.artifacts().day10();
        AuditProperties.Artifacts invalid = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(),
                        new AuditProperties.ArtifactRule("CWC_", "unknown-field",
                                List.of("N/A"), "CWC Not Applicable"),
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
                                List.of("No Coding Required", "N/A"), "CWC Not Applicable"),
                        dayTen.ut(), dayTen.atdd(), dayTen.st()));
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                copy(base, base.jira(), 10, base.checkpoints(), custom)));

        AuditProperties.Artifacts missingValues = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(),
                        new AuditProperties.ArtifactRule("CWC_", "coding-status",
                                List.of(), "CWC Not Applicable"),
                        dayTen.ut(), dayTen.atdd(), dayTen.st()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), missingValues)))
                .getMessage().contains("not-applicable-values"));
    }

    @Test
    void utUsesOnlyCommentAndUtAtddAlternativeRule() {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.Day10Artifacts dayTen = base.artifacts().day10();
        AuditProperties.Artifacts invalid = new AuditProperties.Artifacts("WBS_",
                new AuditProperties.Day10Artifacts(dayTen.wbsPrefix(), dayTen.cwc(),
                        new AuditProperties.ArtifactRule("UT_", "coding-status",
                                List.of("Not Applicable"), "UT Not Applicable"),
                        dayTen.atdd(), dayTen.st()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        copy(base, base.jira(), 10, base.checkpoints(), invalid)));
        assertTrue(error.getMessage().contains("UT/ATDD alternative"));
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

    private static AuditProperties copy(AuditProperties base,
                                        AuditProperties.Jira jira,
                                        Integer day,
                                        Map<Integer, AuditProperties.Checkpoint> checkpoints,
                                        AuditProperties.Artifacts artifacts) {
        return new AuditProperties(jira, base.jql(), day, base.pageSize(), checkpoints,
                artifacts, base.report());
    }
}
