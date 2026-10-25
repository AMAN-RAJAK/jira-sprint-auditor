package com.example.jiraaudit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
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
                "JIRA_BASE_URL=https://test.atlassian.net",
                "JIRA_EMAIL=audit@example.com",
                "JIRA_API_TOKEN=test-token",
                "AUDIT_RUN_DAY=3"
        );

        sampleRunner.run(context -> {
            assertNull(context.getStartupFailure());
            AuditProperties properties = context.getBean(AuditProperties.class);
            assertEquals(3, properties.runDay());
            assertEquals(List.of("WBS_"), properties.artifacts().requiredByDay().get(3));
            assertEquals("CWC Not Applicable",
                    properties.artifacts().missingExceptions().get("CWC_").commentContains());
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
