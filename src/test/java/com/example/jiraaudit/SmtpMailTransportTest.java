package com.example.jiraaudit;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpMailTransportTest {
    @TempDir
    Path tempDirectory;

    @Test
    void configuresCompanySmtpPropertiesAndBuildsMultipartMessage() throws Exception {
        AuditProperties.Smtp smtp = new AuditProperties.Smtp(
                "10.20.30.40", 2525, "smtp", true,
                "audit-user", "secret", true);
        AuditProperties.Mail settings = new AuditProperties.Mail(
                true, true, "audit@company.example",
                List.of("dev@company.example"), List.of("pqm@company.example"),
                "Payments - 9.5-GA Audit Report for Sprint-5", smtp);
        Path report = tempDirectory.resolve("jira-audit.xlsx");
        Files.writeString(report, "workbook");
        AuditEmail email = new AuditEmail(settings.subject(), "Plain summary",
                "<html><body>HTML summary<img src=\"cid:auditTeamChart\"></body></html>",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47},
                "auditTeamChart", report);
        SmtpMailTransport transport = new SmtpMailTransport();

        JavaMailSenderImpl sender = transport.configuredSender(smtp);
        MimeMessage message = transport.createMessage(sender, settings, email);
        message.saveChanges();

        assertEquals("10.20.30.40", sender.getHost());
        assertEquals(2525, sender.getPort());
        assertEquals("smtp", sender.getProtocol());
        assertEquals("audit-user", sender.getUsername());
        assertEquals("secret", sender.getPassword());
        assertEquals("10.20.30.40",
                sender.getJavaMailProperties().getProperty("mail.smtp.host"));
        assertEquals("2525", sender.getJavaMailProperties().getProperty("mail.smtp.port"));
        assertEquals("true", sender.getJavaMailProperties().getProperty("mail.smtp.auth"));
        assertEquals("true",
                sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
        assertEquals(settings.subject(), message.getSubject());
        assertEquals(2, message.getAllRecipients().length);

        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        message.writeTo(serialized);
        String rawMessage = serialized.toString(StandardCharsets.UTF_8);
        assertTrue(rawMessage.contains("jira-audit.xlsx"));
        assertTrue(rawMessage.contains("auditTeamChart"));
        assertTrue(rawMessage.contains("application/vnd.openxmlformats-officedocument"));
        assertTrue(rawMessage.contains("text/html"));
    }
}
