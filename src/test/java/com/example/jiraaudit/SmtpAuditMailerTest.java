package com.example.jiraaudit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpAuditMailerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-27T12:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDirectory;

    @Test
    void disabledMailDoesNotComposeOrCallTheTransport() {
        AtomicInteger calls = new AtomicInteger();
        SmtpAuditMailer mailer = new SmtpAuditMailer(
                TestAuditProperties.settings(3), CLOCK,
                (settings, email) -> calls.incrementAndGet());

        mailer.sendIfEnabled(List.of(), 0, 3, tempDirectory.resolve("unused.xlsx"));

        assertEquals(0, calls.get());
    }

    @Test
    void enabledMailContainsSummaryTeamCountsChartAndReportAttachment() throws Exception {
        Path report = tempDirectory.resolve("jira-audit-day10.xlsx");
        Files.writeString(report, "test workbook");
        AuditProperties properties = propertiesWithMail(true);
        AtomicReference<AuditProperties.Mail> capturedSettings = new AtomicReference<>();
        AtomicReference<AuditEmail> capturedEmail = new AtomicReference<>();
        AuditMailTransport transport = (settings, email) -> {
            capturedSettings.set(settings);
            capturedEmail.set(email);
        };
        SmtpAuditMailer mailer = new SmtpAuditMailer(properties, CLOCK, transport);
        IssueDetails first = new IssueDetails("10001", "PAY-101", "First story",
                "Asha Rao", "In Progress");
        IssueDetails second = new IssueDetails("10002", "PAY-102", "Second story",
                "Dev Two", "Done");
        IssueDetails passed = new IssueDetails("10003", "PAY-103", "Passing story",
                "Dev Three", "Done");
        List<CheckResult> checks = List.of(
                field(first, "Coding Status", "DEV", CheckResult.Outcome.FAIL),
                field(first, "Download", "dev", CheckResult.Outcome.FAIL),
                new CheckResult(first, "ARTIFACT", "WBS", "WBS", "attachment",
                        "WBS attachment", "<missing>", CheckResult.Outcome.FAIL,
                        "Required WBS artifact is not attached"),
                field(second, "ATDD Status", "PQM", CheckResult.Outcome.FAIL),
                field(passed, "Coding Status", "DEV", CheckResult.Outcome.PASS));

        mailer.sendIfEnabled(checks, 3, 10, report);

        assertSame(properties.mail(), capturedSettings.get());
        AuditEmail email = capturedEmail.get();
        assertEquals("Payments - 9.5-GA Audit Report for Sprint-5", email.subject());
        assertEquals(report, email.attachment());
        assertTrue(email.htmlText().contains("Payments &amp; Cards"));
        assertTrue(email.htmlText().contains("PAY-101"));
        assertTrue(email.htmlText().contains("PAY-102"));
        assertTrue(email.plainText().contains("Tickets: 3 total, 1 passed, 2 failed"));
        assertTrue(email.plainText().contains("Configured field checks: 4 total, 3 pending"));
        assertTrue(email.plainText().contains("- DEV: 1 tickets, 2 fields"));
        assertTrue(email.plainText().contains("- PQM: 1 tickets, 1 fields"));
        assertTrue(email.plainText().contains("Other compliance failures: 1 checks across 1 tickets"));
        assertTrue(email.chartPng().length > 100);
        assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47},
                new byte[]{email.chartPng()[0], email.chartPng()[1],
                        email.chartPng()[2], email.chartPng()[3]});
        assertEquals("auditTeamChart", email.chartContentId());
    }

    @Test
    void configuredFailOnErrorControlsSmtpFailureBehavior() throws Exception {
        Path report = tempDirectory.resolve("jira-audit-day3.xlsx");
        Files.writeString(report, "test workbook");
        AuditMailTransport failingTransport = (settings, email) -> {
            throw new IllegalStateException("SMTP unavailable");
        };

        SmtpAuditMailer nonBlocking = new SmtpAuditMailer(
                propertiesWithMail(false), CLOCK, failingTransport);
        assertDoesNotThrow(() -> nonBlocking.sendIfEnabled(List.of(), 0, 3, report));

        SmtpAuditMailer blocking = new SmtpAuditMailer(
                propertiesWithMail(true), CLOCK, failingTransport);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> blocking.sendIfEnabled(List.of(), 0, 3, report));
        assertTrue(error.getMessage().contains("Excel report was created"));
        assertEquals("SMTP unavailable", error.getCause().getMessage());
    }

    private static CheckResult field(IssueDetails issue, String name, String team,
                                     CheckResult.Outcome outcome) {
        return new CheckResult(issue, "FIELD", name.toLowerCase().replace(' ', '-'), name,
                "customfield_1", "Done", outcome == CheckResult.Outcome.PASS ? "Done" : "Pending",
                outcome, outcome == CheckResult.Outcome.PASS ?
                "One configured value matched" : "Field value is pending", team);
    }

    private static AuditProperties propertiesWithMail(boolean failOnError) {
        AuditProperties base = TestAuditProperties.settings(10);
        AuditProperties.AuditContext context = new AuditProperties.AuditContext(
                "Payments & Cards", "9.5-GA", "Sprint-5");
        AuditProperties.Smtp smtp = new AuditProperties.Smtp(
                "mail.company.example", 2525, "smtp", true,
                "audit-user", "test-password", true);
        AuditProperties.Mail mail = new AuditProperties.Mail(
                true, failOnError, "audit@company.example",
                List.of("delivery@company.example"), List.of(),
                "Payments - 9.5-GA Audit Report for Sprint-5", smtp);
        return new AuditProperties(base.jira(), base.jql(), base.runDay(), base.pageSize(),
                base.checkpoints(), base.artifacts(), base.report(), context, mail);
    }
}
