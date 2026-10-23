package com.example.jiraaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraIssueLinksTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void extractsBothDirectionsAndPreservesSourceOrder() throws Exception {
        var result = JiraIssueLinks.parse(issue("PAY-10", """
                {"issuelinks":[
                  {"type":{"name":"blocks"},"inwardIssue":{"id":"20001","key":"BUG-7"}},
                  {"type":{"name":"relates to"},"outwardIssue":{"id":"20002","key":"OPS_2-81"}}
                ]}
                """));

        assertEquals(List.of(
                new JiraIssueLinks.LinkReference("20001", "BUG-7", "inward", "blocks"),
                new JiraIssueLinks.LinkReference("20002", "OPS_2-81", "outward", "relates to")
        ), result.references());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void deduplicatesKeysCaseInsensitivelyAndKeepsTheFirstReference() throws Exception {
        var result = JiraIssueLinks.parse(issue("PAY-10", """
                {"issuelinks":[
                  {"outwardIssue":{"id":"20001","key":"bug-7"}},
                  {"inwardIssue":{"id":"20001","key":"BUG-7"}}
                ]}
                """));

        assertEquals(List.of(
                new JiraIssueLinks.LinkReference("20001", "BUG-7", "outward", "<unknown>")
        ), result.references());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void reportsConflictingDuplicateIdsWithoutLosingTheFirstReference() throws Exception {
        var result = JiraIssueLinks.parse(issue("PAY-10", """
                {"issuelinks":[
                  {"outwardIssue":{"id":"20001","key":"BUG-7"}},
                  {"inwardIssue":{"id":"99999","key":"bug-7"}}
                ]}
                """));

        assertEquals(1, result.references().size());
        assertEquals("20001", result.references().getFirst().issueId());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("conflicting Jira IDs")));
    }

    @Test
    void acceptsAnExplicitEmptyLinkArray() throws Exception {
        var result = JiraIssueLinks.parse(issue("PAY-10", "{\"issuelinks\":[]}"));

        assertTrue(result.references().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void rejectsAbsentNullAndNonArrayLinkFields() throws Exception {
        var absent = JiraIssueLinks.parse(issue("PAY-10", "{}"));
        var nullValue = JiraIssueLinks.parse(issue("PAY-10", "{\"issuelinks\":null}"));
        var objectValue = JiraIssueLinks.parse(issue("PAY-10", "{\"issuelinks\":{}}"));

        assertTrue(absent.errors().getFirst().contains("was not returned"));
        assertTrue(nullValue.errors().getFirst().contains("is null"));
        assertTrue(objectValue.errors().getFirst().contains("expected an array"));
    }

    @Test
    void returnsValidReferencesAlongsideErrorsForMalformedEntries() throws Exception {
        var result = JiraIssueLinks.parse(issue("PAY-10", """
                {"issuelinks":[
                  null,
                  {},
                  {"inwardIssue":{"id":"","key":"BUG-1"}},
                  {"outwardIssue":{"id":"20002","key":"BUG-1 OR project = SECRET"}},
                  {"inwardIssue":{"id":"20003","key":"BUG-3"},
                   "outwardIssue":{"id":"20004","key":"TASK-4"}}
                ]}
                """));

        assertEquals(List.of("BUG-3", "TASK-4"),
                result.references().stream().map(JiraIssueLinks.LinkReference::issueKey).toList());
        assertEquals(5, result.errors().size());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("unsafe Jira key")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("both inwardIssue and outwardIssue")));
    }

    @Test
    void rejectsSelfReferencesAndMissingIdentifiers() throws Exception {
        var result = JiraIssueLinks.parse(issue(" pay-10 ", """
                {"issuelinks":[
                  {"inwardIssue":{"id":"10010","key":"PAY-10"}},
                  {"outwardIssue":{"id":"20002"}},
                  {"outwardIssue":{"key":"BUG-2"}},
                  {"outwardIssue":{"id":"not-a-number","key":"BUG-3"}}
                ]}
                """));

        assertTrue(result.references().isEmpty());
        assertEquals(4, result.errors().size());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("parent itself")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("non-blank text key")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("non-blank text id")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("malformed Jira id")));
    }

    private static JiraClient.JiraIssue issue(String key, String fieldsJson) throws Exception {
        JsonNode fields = MAPPER.readTree(fieldsJson);
        return new JiraClient.JiraIssue("10010", key, fields);
    }
}
