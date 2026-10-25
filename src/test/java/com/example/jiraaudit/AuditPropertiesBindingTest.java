package com.example.jiraaudit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPropertiesBindingTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void bindsAnExplicitRunDay() {
        contextRunner.withPropertyValues("audit.run-day=3").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(3, context.getBean(AuditProperties.class).runDay());
        });
    }

    @Test
    void defaultApplicationMapsIntellijEnvironmentVariablesIntoJiraSettings() {
        ApplicationContextRunner defaultConfigRunner = contextRunner.withInitializer(context -> {
            try {
                var sources = new YamlPropertySourceLoader().load("application",
                        new ClassPathResource("application.yml"));
                sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }).withPropertyValues(
                "JIRA_BASE_URL=https://jira.company.example/jira",
                "JIRA_ACCESS_TOKEN=intellij-pat"
        );

        defaultConfigRunner.run(context -> {
            assertNull(context.getStartupFailure());
            AuditProperties properties = context.getBean(AuditProperties.class);
            assertNotNull(properties.jira());
            assertEquals("https://jira.company.example/jira", properties.jira().baseUrl());
            assertEquals("intellij-pat", properties.jira().personalAccessToken());
            assertDoesNotThrow(() -> AuditConfigurationValidator.validateCredentials(properties));
        });
    }

    @Test
    void sampleCompanyConfigurationBindsAndValidates() {
        ApplicationContextRunner sampleRunner = contextRunner.withInitializer(context -> {
            try {
                var sources = new YamlPropertySourceLoader().load("company-audit",
                        new FileSystemResource("config/company-audit.example.yml"));
                sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }).withPropertyValues(
                "JIRA_BASE_URL=https://jira.company.example",
                "JIRA_PERSONAL_ACCESS_TOKEN=test-pat",
                "AUDIT_RUN_DAY=3"
        );

        sampleRunner.run(context -> {
            assertNull(context.getStartupFailure());
            AuditProperties properties = context.getBean(AuditProperties.class);
            assertEquals(3, properties.runDay());
            assertEquals("test-pat", properties.jira().personalAccessToken());
            assertEquals(List.of("Started", "In Progress", "Done - On Time", "Not Applicable"),
                    properties.checkpoints().get(3).fields().get("download").acceptedValueList());
            assertTrue(properties.checkpoints().get(3).fields().get("revised-sp")
                    .requiresNonEmptyValue());
            assertEquals(List.of("Resolved", "Closed", "Done"),
                    properties.checkpoints().get(10).linkedBugs().resolvedStatusList());
            assertEquals(List.of("WBS", "WORK_BREAKDOWN"),
                    properties.artifacts().day3WbsPrefixList());
            assertEquals(List.of("WBS", "WORK_BREAKDOWN"),
                    properties.artifacts().day10().wbsPrefixList());
            assertEquals(List.of("Not Applicable"),
                    properties.artifacts().day10().atdd().notApplicableValueList());
            assertEquals("CWC Not Applicable",
                    properties.artifacts().day10().cwc().commentContains());
            assertDoesNotThrow(() -> AuditConfigurationValidator.validatePolicy(properties));
        });
    }

    @Test
    void rejectsObsoleteDateBasedConfiguration() {
        contextRunner.withPropertyValues(
                "audit.run-day=3",
                "audit.as-of=2026-10-23",
                "audit.sprint.start-date=2026-10-20",
                "audit.sprint.zone=Asia/Kolkata"
        ).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            assertTrue(messages.toString().contains("left unbound"), messages::toString);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuditProperties.class)
    static class BindingConfiguration { }
}
