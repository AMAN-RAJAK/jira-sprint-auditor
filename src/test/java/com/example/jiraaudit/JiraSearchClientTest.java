package com.example.jiraaudit;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class JiraSearchClientTest {
    @Test
    void followsNextPageTokenAndExplicitlyRequestsFields() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://test.atlassian.net");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://test.atlassian.net/rest/api/3/search/jql"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.jql").value("project = PAY"))
                .andExpect(jsonPath("$.fields[0]").value("status"))
                .andRespond(withSuccess("""
                    {"isLast":false,"nextPageToken":"token-A","issues":[
                       {"id":"10001","key":"PAY-1","fields":{"status":{"name":"Done"}}}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://test.atlassian.net/rest/api/3/search/jql"))
                .andExpect(jsonPath("$.nextPageToken").value("token-A"))
                .andRespond(withSuccess("""
                    {"isLast":true,"issues":[
                       {"id":"10002","key":"PAY-2","fields":{"status":{"name":"To Do"}}}
                    ]}
                    """, MediaType.APPLICATION_JSON));

        var issues = new JiraSearchClient(builder.build()).searchAll("project = PAY", Set.of("status"), 100);
        assertEquals(2, issues.size());
        assertEquals("10001", issues.get(0).id());
        assertEquals("PAY-1", issues.get(0).key());
        assertEquals("PAY-2", issues.get(1).key());
        server.verify();
    }

    @Test
    void rejectsTruncatedPaginationInsteadOfReportingPartialSuccess() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://test.atlassian.net");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://test.atlassian.net/rest/api/3/search/jql"))
                .andRespond(withSuccess("""
                    {"isLast":false,"issues":[{"id":"10001","key":"PAY-1","fields":{}}]}
                    """, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class,
                () -> new JiraSearchClient(builder.build()).searchAll("project = PAY", Set.of("status"), 100));
        server.verify();
    }

    @Test
    void rejectsDuplicateIssuesAcrossPages() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://test.atlassian.net");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://test.atlassian.net/rest/api/3/search/jql"))
                .andRespond(withSuccess("""
                    {"isLast":false,"nextPageToken":"token-A","issues":[
                       {"id":"10001","key":"PAY-1","fields":{}}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://test.atlassian.net/rest/api/3/search/jql"))
                .andRespond(withSuccess("""
                    {"isLast":true,"issues":[
                       {"id":"10001","key":"PAY-1","fields":{}}
                    ]}
                    """, MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new JiraSearchClient(builder.build()).searchAll(
                        "project = PAY", Set.of("status"), 100));

        assertTrue(error.getMessage().contains("duplicate issue"));
        server.verify();
    }

    @Test
    void sendsKnownIssueIdsForStrongerReadAfterWriteConsistency() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://test.atlassian.net");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://test.atlassian.net/rest/api/3/search/jql"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.reconcileIssues[0]").value(20007))
                .andRespond(withSuccess("""
                    {"isLast":true,"issues":[
                       {"id":"20007","key":"BUG-7","fields":{"status":{"name":"Done"}}}
                    ]}
                    """, MediaType.APPLICATION_JSON));

        var issues = new JiraSearchClient(builder.build()).searchAll(
                "key in (BUG-7)", Set.of("status"), 100, Set.of(20007L));

        assertEquals(1, issues.size());
        server.verify();
    }
}
