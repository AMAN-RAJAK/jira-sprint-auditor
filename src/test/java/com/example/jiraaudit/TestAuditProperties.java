package com.example.jiraaudit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TestAuditProperties {
    private TestAuditProperties() { }

    static AuditProperties settings(int day) {
        return settings(day, new AuditProperties.Report("target/reports", true, true));
    }

    static AuditProperties settings(int day, AuditProperties.Report report) {
        return new AuditProperties(
                new AuditProperties.Jira("https://jira.company.example", "test-pat"),
                "project = PAY AND sprint = 123", day, 100,
                checkpoints(), artifacts(), report);
    }

    static Map<Integer, AuditProperties.Checkpoint> checkpoints() {
        Map<String, AuditProperties.FieldRule> dayThree = new LinkedHashMap<>();
        dayThree.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "coding",
                List.of("In Progress", "Done - On Time", "Not Applicable")));
        dayThree.put("download", new AuditProperties.FieldRule(
                "Download", "download", List.of("Started", "In Progress", "Done - On Time")));

        Map<String, AuditProperties.FieldRule> dayTen = new LinkedHashMap<>();
        dayTen.put("coding-status", new AuditProperties.FieldRule(
                "Coding Status", "coding", List.of("Done - On Time", "Not Applicable")));
        dayTen.put("st-status", new AuditProperties.FieldRule(
                "ST Status", "st", List.of("Done - On Time", "Not Applicable")));
        dayTen.put("atdd-status", new AuditProperties.FieldRule(
                "ATDD Status", "atdd", List.of("Done - On Time", "Not Applicable")));

        return Map.of(
                3, new AuditProperties.Checkpoint(dayThree),
                10, new AuditProperties.Checkpoint(dayTen));
    }

    static AuditProperties.Artifacts artifacts() {
        return new AuditProperties.Artifacts("WBS_", new AuditProperties.Day10Artifacts(
                "WBS_",
                new AuditProperties.ArtifactRule(
                        "CWC_", "coding-status", List.of("Not Applicable"),
                        "CWC Not Applicable"),
                new AuditProperties.ArtifactRule(
                        "UT_", null, null, "UT Not Applicable"),
                new AuditProperties.ArtifactRule(
                        "ATDD_", "atdd-status", List.of("Not Applicable"),
                        "ATDD Not Applicable"),
                new AuditProperties.ArtifactRule(
                        "ST_", "st-status", List.of("Not Applicable"),
                        "ST Not Applicable")));
    }
}
