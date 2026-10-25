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
        AuditProperties valid = properties(3);
        assertDoesNotThrow(() -> AuditConfigurationValidator.validateCredentials(valid));

        AuditProperties missingPat = new AuditProperties(
                new AuditProperties.Jira("https://jira.company.example", " "),
                valid.jql(), valid.runDay(), valid.pageSize(), valid.fields(), valid.artifacts(), valid.report());
        IllegalArgumentException tokenError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validateCredentials(missingPat));
        assertTrue(tokenError.getMessage().contains("JIRA_PERSONAL_ACCESS_TOKEN"));

        AuditProperties insecure = new AuditProperties(
                new AuditProperties.Jira("http://jira.company.example", "test-pat"),
                valid.jql(), valid.runDay(), valid.pageSize(), valid.fields(), valid.artifacts(), valid.report());
        IllegalArgumentException urlError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validateCredentials(insecure));
        assertTrue(urlError.getMessage().contains("HTTPS"));
    }

    @Test
    void acceptsSimpleCheckpointConfiguration() {
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(properties(3)));
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(properties(10)));
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(properties(12)));
    }

    @Test
    void requiresExplicitSupportedRunDay() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(null)));
        assertTrue(missing.getMessage().contains("audit.run-day is required"));

        for (int day : List.of(-1, 0, 2, 4, 8, 9)) {
            IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                    () -> AuditConfigurationValidator.validatePolicy(properties(day)));
            assertTrue(invalid.getMessage().contains("must be 1, 3, 7"));
        }
    }

    @Test
    void everyFieldMustDefineAllFourExactCheckpoints() {
        Map<String, AuditProperties.FieldRule> fields = Map.of("coding-status",
                new AuditProperties.FieldRule("Coding Status", "customfield_10101",
                        Map.of(1, List.of("Started"), 3, List.of("In Progress"),
                                10, List.of("Done"))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(3, fields, validArtifacts())));

        assertTrue(error.getMessage().contains("exactly Day 1, 3, 7 and 10"));
    }

    @Test
    void artifactRequirementsMustDefineEveryCheckpoint() {
        AuditProperties.Artifacts artifacts = new AuditProperties.Artifacts(
                Map.of(3, List.of("WBS_"), 10, List.of("WBS_")), Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(3, validFields(), artifacts)));

        assertTrue(error.getMessage().contains("required-by-day must define exactly"));
    }

    @Test
    void dayThreeAndDayTenArtifactsMustBeConfigured() {
        AuditProperties.Artifacts missingDayThree = new AuditProperties.Artifacts(
                Map.of(1, List.of(), 3, List.of(), 7, List.of(), 10, List.of("WBS_")), Map.of());
        IllegalArgumentException dayThreeError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(3, validFields(), missingDayThree)));
        assertTrue(dayThreeError.getMessage().contains("Day 3 WBS"));

        AuditProperties.Artifacts missingDayTen = new AuditProperties.Artifacts(
                Map.of(1, List.of(), 3, List.of("WBS_"), 7, List.of(), 10, List.of()), Map.of());
        IllegalArgumentException dayTenError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(10, validFields(), missingDayTen)));
        assertTrue(dayTenError.getMessage().contains("Day 10 must check"));
    }

    @Test
    void daysOneAndSevenHaveNoArtifactsAndWbsHasNoException() {
        AuditProperties.Artifacts dayOneArtifact = new AuditProperties.Artifacts(
                Map.of(1, List.of("WBS_"), 3, List.of("WBS_"), 7, List.of(),
                        10, List.of("WBS_")), Map.of());
        IllegalArgumentException earlyError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(1, validFields(), dayOneArtifact)));
        assertTrue(earlyError.getMessage().contains("Days 1 and 7"));

        AuditProperties.Artifacts wbsException = new AuditProperties.Artifacts(
                Map.of(1, List.of(), 3, List.of("WBS_"), 7, List.of(),
                        10, List.of("WBS_", "CWC_", "UT_", "ATDD_", "ST_")),
                Map.of("WBS_", new AuditProperties.MissingArtifactRule(
                        null, null, "WBS Not Applicable")));
        IllegalArgumentException wbsError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(10, validFields(), wbsException)));
        assertTrue(wbsError.getMessage().contains("cannot have a missing exception"));
    }

    @Test
    void missingArtifactExceptionMustReferenceARequiredPrefix() {
        var exceptions = Map.of("UNKNOWN_", new AuditProperties.MissingArtifactRule(
                null, null, "Unknown Not Applicable"));
        AuditProperties.Artifacts artifacts = new AuditProperties.Artifacts(
                validArtifactDays(), exceptions);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(10, validFields(), artifacts)));

        assertTrue(error.getMessage().contains("not required on any day"));
    }

    @Test
    void exceptionRequiresCommentAndCompleteOptionalFieldCondition() {
        Map<String, AuditProperties.MissingArtifactRule> missingComment = Map.of("CWC_",
                new AuditProperties.MissingArtifactRule("coding-status",
                        List.of("No Code Changes"), " "));
        IllegalArgumentException commentError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(10, validFields(),
                        new AuditProperties.Artifacts(validArtifactDays(), missingComment))));
        assertTrue(commentError.getMessage().contains("comment-contains"));

        Map<String, AuditProperties.MissingArtifactRule> incompleteField = Map.of("CWC_",
                new AuditProperties.MissingArtifactRule("coding-status", null,
                        "CWC Not Applicable"));
        IllegalArgumentException fieldError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(10, validFields(),
                        new AuditProperties.Artifacts(validArtifactDays(), incompleteField))));
        assertTrue(fieldError.getMessage().contains("Both field-rule and field-values"));
    }

    @Test
    void exceptionFieldRuleMustExist() {
        Map<String, AuditProperties.MissingArtifactRule> exceptions = Map.of("CWC_",
                new AuditProperties.MissingArtifactRule("unknown-field", List.of("N/A"),
                        "CWC Not Applicable"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(10, validFields(),
                        new AuditProperties.Artifacts(validArtifactDays(), exceptions))));

        assertTrue(error.getMessage().contains("Unknown field-rule"));
    }

    @Test
    void dayThreeMustUseWbsAndDayTenCannotOmitNamedArtifacts() {
        AuditProperties.Artifacts wrongDayThree = new AuditProperties.Artifacts(
                Map.of(1, List.of(), 3, List.of("DESIGN_"), 7, List.of(),
                        10, List.of("WBS_", "CWC_", "UT_", "ATDD_", "ST_")),
                validExceptions());
        IllegalArgumentException dayThreeError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(3, validFields(), wrongDayThree)));
        assertTrue(dayThreeError.getMessage().contains("Day 3 must check only the WBS_"));

        AuditProperties.Artifacts missingSt = new AuditProperties.Artifacts(
                Map.of(1, List.of(), 3, List.of("WBS_"), 7, List.of(),
                        10, List.of("WBS_", "CWC_", "UT_", "ATDD_")),
                validExceptions());
        IllegalArgumentException dayTenError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(
                        properties(10, validFields(), missingSt)));
        assertTrue(dayTenError.getMessage().contains("missing: ST_"));
    }

    @Test
    void allFourNotApplicableRulesAreMandatory() {
        Map<String, AuditProperties.MissingArtifactRule> missingAtdd = new LinkedHashMap<>(validExceptions());
        missingAtdd.remove("ATDD_");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(10, validFields(),
                        new AuditProperties.Artifacts(validArtifactDays(), missingAtdd))));

        assertTrue(error.getMessage().contains("ATDD_ not-applicable rule"));
    }

    @Test
    void namedExceptionsCannotWeakenRequiredEvidence() {
        Map<String, AuditProperties.MissingArtifactRule> wrongCwc =
                new LinkedHashMap<>(validExceptions());
        wrongCwc.put("CWC_", new AuditProperties.MissingArtifactRule(
                "coding-status", List.of("Done"), "CWC Not Applicable"));
        IllegalArgumentException cwcError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(10, validFields(),
                        new AuditProperties.Artifacts(validArtifactDays(), wrongCwc))));
        assertTrue(cwcError.getMessage().contains("CWC_ field-values must be exactly"));

        Map<String, AuditProperties.MissingArtifactRule> wrongUt =
                new LinkedHashMap<>(validExceptions());
        wrongUt.put("UT_", new AuditProperties.MissingArtifactRule(
                "coding-status", List.of("No Code Changes"), "UT Not Applicable"));
        IllegalArgumentException utError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(10, validFields(),
                        new AuditProperties.Artifacts(validArtifactDays(), wrongUt))));
        assertTrue(utError.getMessage().contains("UT_ must use only"));

        Map<String, AuditProperties.MissingArtifactRule> wrongSt =
                new LinkedHashMap<>(validExceptions());
        wrongSt.put("ST_", new AuditProperties.MissingArtifactRule(
                "coding-status", List.of("Not Applicable"), "ST Not Applicable"));
        IllegalArgumentException stError = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(10, validFields(),
                        new AuditProperties.Artifacts(validArtifactDays(), wrongSt))));
        assertTrue(stError.getMessage().contains("ST_ field-rule must be"));
    }

    @Test
    void extraCompanyArtifactsAreAllowedButCannotBeWaived() {
        Map<Integer, List<String>> daysWithDesign = Map.of(
                1, List.of(), 3, List.of("WBS_"), 7, List.of(),
                10, List.of("WBS_", "CWC_", "UT_", "ATDD_", "ST_", "DESIGN_"));
        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                properties(10, validFields(),
                        new AuditProperties.Artifacts(daysWithDesign, validExceptions()))));

        Map<String, AuditProperties.MissingArtifactRule> exceptionsWithDesign =
                new LinkedHashMap<>(validExceptions());
        exceptionsWithDesign.put("DESIGN_", new AuditProperties.MissingArtifactRule(
                null, null, "Design Not Applicable"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuditConfigurationValidator.validatePolicy(properties(10, validFields(),
                        new AuditProperties.Artifacts(daysWithDesign, exceptionsWithDesign))));
        assertTrue(error.getMessage().contains("Only CWC_, UT_, ATDD_ and ST_"));
    }

    @Test
    void dayTenFieldValuesCanUseAnyConfiguredAllowedValues() {
        Map<String, AuditProperties.FieldRule> customDayTenValues =
                new LinkedHashMap<>(validFields());
        customDayTenValues.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "customfield_10101",
                Map.of(1, List.of("Started"), 3, List.of("In Progress"),
                        7, List.of("Done"),
                        10, List.of("Done - On Time", "Not Applicable"))));
        customDayTenValues.put("st-status", new AuditProperties.FieldRule(
                "ST Status", "customfield_10102",
                Map.of(1, List.of("Not Started"), 3, List.of("In Progress"),
                        7, List.of("Done"), 10, List.of("Executed - On Time"))));

        assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(
                properties(10, customDayTenValues, validArtifacts())));
    }

    private AuditProperties properties(Integer day) {
        return properties(day, validFields(), validArtifacts());
    }

    private AuditProperties properties(Integer day, Map<String, AuditProperties.FieldRule> fields,
                                       AuditProperties.Artifacts artifacts) {
        return new AuditProperties(
                new AuditProperties.Jira("https://jira.company.example", "test-pat"),
                "project = TEST", day, 100, fields, artifacts,
                new AuditProperties.Report("target/reports", true, true));
    }

    private Map<String, AuditProperties.FieldRule> validFields() {
        return Map.of(
                "coding-status", new AuditProperties.FieldRule(
                        "Coding Status", "customfield_10101",
                        Map.of(1, List.of("Started"), 3, List.of("In Progress"),
                                7, List.of("Done"), 10, List.of("Done", "No Code Changes"))),
                "st-status", new AuditProperties.FieldRule(
                        "ST Status", "customfield_10102",
                        Map.of(1, List.of("Not Started"), 3, List.of("In Progress"),
                                7, List.of("Done"), 10, List.of("Done", "Not Applicable"))));
    }

    private AuditProperties.Artifacts validArtifacts() {
        return new AuditProperties.Artifacts(validArtifactDays(), validExceptions());
    }

    private Map<String, AuditProperties.MissingArtifactRule> validExceptions() {
        Map<String, AuditProperties.MissingArtifactRule> exceptions = new LinkedHashMap<>();
        exceptions.put("CWC_", new AuditProperties.MissingArtifactRule(
                "coding-status", List.of("No Code Changes"), "CWC Not Applicable"));
        exceptions.put("UT_", new AuditProperties.MissingArtifactRule(
                null, null, "UT Not Applicable"));
        exceptions.put("ATDD_", new AuditProperties.MissingArtifactRule(
                null, null, "ATDD Not Applicable"));
        exceptions.put("ST_", new AuditProperties.MissingArtifactRule(
                "st-status", List.of("Not Applicable"), "ST Not Applicable"));
        return exceptions;
    }

    private Map<Integer, List<String>> validArtifactDays() {
        return Map.of(1, List.of(), 3, List.of("WBS_"), 7, List.of(),
                10, List.of("WBS_", "CWC_", "UT_", "ATDD_", "ST_"));
    }
}
