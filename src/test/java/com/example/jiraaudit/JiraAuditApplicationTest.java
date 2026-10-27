package com.example.jiraaudit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraAuditApplicationTest {
    @Test
    void sendsOnlyTheDataCenterPersonalAccessTokenAsBearerAuthentication() {
        AuditProperties settings = new AuditProperties(
                new AuditProperties.Jira("https://jira.company.example/jira", "secret-test-pat"),
                null, null, null, null, null, null, null, null);
        RestClient.Builder builder = JiraAuditApplication.configureJiraRestClient(
                RestClient.builder(), settings);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://jira.company.example/jira/rest/api/2/myself"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-test-pat"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        builder.build().get().uri("rest/api/2/myself").retrieve().toBodilessEntity();

        server.verify();
    }
}
