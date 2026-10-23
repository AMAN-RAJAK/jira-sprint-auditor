package com.example.jiraaudit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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
