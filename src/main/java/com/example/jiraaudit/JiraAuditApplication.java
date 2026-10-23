package com.example.jiraaudit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties(AuditProperties.class)
public class JiraAuditApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(JiraAuditApplication.class);
        int exitCode;
        try (ConfigurableApplicationContext context = app.run(args)) {
            exitCode = SpringApplication.exit(context,
                    () -> context.getBean(AuditRunner.class).exitCode());
        }
        System.exit(exitCode);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient jiraRestClient(AuditProperties settings) {
        AuditConfigurationValidator.validateCredentials(settings);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(30_000);
        return RestClient.builder()
                .baseUrl(settings.jira().baseUrl().replaceAll("/+$", ""))
                .defaultHeaders(headers -> headers.setBasicAuth(
                        settings.jira().email(), settings.jira().apiToken()))
                .requestFactory(requestFactory)
                .build();
    }
}
