package com.example.jiraaudit;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraSearchClientTest {
    private static final String BASE_URL = "https://jira.company.example/jira/";

    @Test
    void followsDataCenterOffsetPaginationAndPreservesContextPath() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "rest/api/2/search"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.jql").value("project = PAY"))
                .andExpect(jsonPath("$.fields[0]").value("status"))
                .andExpect(jsonPath("$.startAt").value(0))
                .andExpect(jsonPath("$.maxResults").value(100))
                .andRespond(withSuccess("""
                    {"startAt":0,"maxResults":1,"total":2,"issues":[
                       {"id":"10001","key":"PAY-1","fields":{"status":{"name":"Done"}}}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "rest/api/2/search"))
                .andExpect(jsonPath("$.startAt").value(1))
                .andRespond(withSuccess("""
                    {"startAt":1,"maxResults":1,"total":2,"issues":[
                       {"id":"10002","key":"PAY-2","fields":{"status":{"name":"To Do"}}}
                    ]}
                    """, MediaType.APPLICATION_JSON));

        var issues = new JiraSearchClient(builder.build()).searchAll(
                "project = PAY", Set.of("status"), 100);

        assertEquals(2, issues.size());
        assertEquals("10001", issues.get(0).id());
        assertEquals("PAY-1", issues.get(0).key());
        assertEquals("PAY-2", issues.get(1).key());
        server.verify();
    }

    @Test
    void rejectsTruncatedPaginationInsteadOfReportingPartialSuccess() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "rest/api/2/search"))
                .andRespond(withSuccess("""
                    {"startAt":0,"maxResults":1,"total":2,"issues":[
                      {"id":"10001","key":"PAY-1","fields":{}}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "rest/api/2/search"))
                .andExpect(jsonPath("$.startAt").value(1))
                .andRespond(withSuccess("""
                    {"startAt":1,"maxResults":1,"total":2,"issues":[]}
                    """, MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new JiraSearchClient(builder.build()).searchAll(
                        "project = PAY", Set.of("status"), 100));

        assertTrue(error.getMessage().contains("refusing a partial audit"));
        server.verify();
    }

    @Test
    void rejectsDuplicateIssuesAcrossPages() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "rest/api/2/search"))
                .andRespond(withSuccess("""
                    {"startAt":0,"maxResults":1,"total":2,"issues":[
                       {"id":"10001","key":"PAY-1","fields":{}}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "rest/api/2/search"))
                .andRespond(withSuccess("""
                    {"startAt":1,"maxResults":1,"total":2,"issues":[
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
    void loadsEveryDataCenterCommentPageInNewestFirstOrder() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "rest/api/2/issue/PAY-1/comment"
                        + "?startAt=0&maxResults=2&orderBy=-created"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                    {"startAt":0,"maxResults":2,"total":3,"comments":[
                      {"id":"103","created":"2026-10-25T13:00:00.000+0530",
                       "body":"CWC Not Applicable"},
                      {"id":"102","created":"2026-10-25T06:00:00Z","body":"Other"}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "rest/api/2/issue/PAY-1/comment"
                        + "?startAt=2&maxResults=2&orderBy=-created"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                    {"startAt":2,"maxResults":2,"total":3,"comments":[
                      {"id":"101","created":"2026-10-24T05:00:00.000+0000","body":"Old"}
                    ]}
                    """, MediaType.APPLICATION_JSON));

        var comments = new JiraSearchClient(builder.build())
                .getCommentsNewestFirst(" PAY-1 ", 2);

        assertEquals(3, comments.size());
        assertEquals("103", comments.get(0).id());
        assertEquals(Instant.parse("2026-10-25T07:30:00Z"), comments.get(0).created());
        assertEquals("CWC Not Applicable", comments.get(0).body().asText());
        assertEquals("101", comments.get(2).id());
        server.verify();
    }

    @Test
    void acceptsAnIssueWithNoCommentsAsACompleteResult() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "rest/api/2/issue/PAY-2/comment"
                        + "?startAt=0&maxResults=100&orderBy=-created"))
                .andRespond(withSuccess("""
                    {"startAt":0,"maxResults":100,"total":0,"comments":[]}
                    """, MediaType.APPLICATION_JSON));

        assertTrue(new JiraSearchClient(builder.build())
                .getCommentsNewestFirst("PAY-2", 100).isEmpty());
        server.verify();
    }

    @Test
    void rejectsTruncatedCommentPaginationInsteadOfUsingPartialHistory() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "rest/api/2/issue/PAY-3/comment"
                        + "?startAt=0&maxResults=1&orderBy=-created"))
                .andRespond(withSuccess("""
                    {"startAt":0,"maxResults":1,"total":2,"comments":[
                      {"id":"2","created":"2026-10-25T06:00:00Z","body":"Latest"}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "rest/api/2/issue/PAY-3/comment"
                        + "?startAt=1&maxResults=1&orderBy=-created"))
                .andRespond(withSuccess("""
                    {"startAt":1,"maxResults":1,"total":2,"comments":[]}
                    """, MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new JiraSearchClient(builder.build())
                        .getCommentsNewestFirst("PAY-3", 1));

        assertTrue(error.getMessage().contains("refusing a partial audit"));
        server.verify();
    }

    @Test
    void rejectsCommentsThatAreNotNewestFirst() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "rest/api/2/issue/PAY-4/comment"
                        + "?startAt=0&maxResults=10&orderBy=-created"))
                .andRespond(withSuccess("""
                    {"startAt":0,"maxResults":10,"total":2,"comments":[
                      {"id":"1","created":"2026-10-24T06:00:00Z","body":"Older"},
                      {"id":"2","created":"2026-10-25T06:00:00Z","body":"Newer"}
                    ]}
                    """, MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new JiraSearchClient(builder.build())
                        .getCommentsNewestFirst("PAY-4", 10));

        assertTrue(error.getMessage().contains("newest-first"));
        server.verify();
    }
}
