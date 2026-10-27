package com.example.jiraaudit;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

/**
 * Standalone SMTP smoke test. Run this main method directly from IntelliJ;
 * it does not start the Jira audit.
 */
public final class SmtpMailTestApplication {
    private SmtpMailTestApplication() { }

    public static void main(String[] args) {
        try {
            sendTestMail();
        } catch (Exception error) {
            System.err.println("SMTP TEST FAILED: " + rootMessage(error));
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void sendTestMail() throws Exception {
        String host = required("SMTP host", value(
                "smtpHostip", "SMTP_HOST_IP", "mail.smtp.host", "MAIL_SMTP_HOST"));
        int port = integerValue("SMTP port", value(
                "smtpPort", "SMTP_PORT", "mail.smtp.port", "MAIL_SMTP_PORT"), 25);
        String protocol = defaultValue(value("smtpProtocol", "SMTP_PROTOCOL"), "smtp")
                .toLowerCase(Locale.ROOT);
        if (!protocol.equals("smtp") && !protocol.equals("smtps")) {
            throw new IllegalArgumentException("smtpProtocol must be smtp or smtps");
        }

        boolean authenticationRequired = booleanValue(
                "smptpAuthenticationRequired",
                value("smptpAuthenticationRequired", "smtpAuthenticationRequired",
                        "SMTP_AUTHENTICATION_REQUIRED"), true);
        boolean startTlsEnabled = booleanValue(
                "smtpStartTlsEnabled",
                value("smtpStartTlsEnabled", "SMTP_STARTTLS_ENABLED"), true);
        String username = value("smtpUsername", "SMTP_USERNAME");
        String password = value("smtpPassword", "SMTP_PASSWORD");
        if (authenticationRequired) {
            required("smtpUsername", username);
            required("smtpPassword", password);
        }

        String from = defaultValue(value("smtpFrom", "AUDIT_MAIL_FROM"), username);
        from = required("smtpFrom or AUDIT_MAIL_FROM", from);
        String to = required("smtpTo or AUDIT_MAIL_TO",
                value("smtpTo", "AUDIT_MAIL_TO"));
        String subject = defaultValue(value("smtpTestSubject", "SMTP_TEST_SUBJECT"),
                "Jira Sprint Auditor - SMTP Test");

        AuditProperties.Smtp smtp = new AuditProperties.Smtp(
                host, port, protocol, authenticationRequired,
                username, password, startTlsEnabled);
        JavaMailSenderImpl sender = new SmtpMailTransport().configuredSender(smtp);
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(
                message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(new InternetAddress(from, true));
        helper.setTo(InternetAddress.parse(to, true));
        helper.setSubject(subject);
        helper.setText("SMTP configuration test succeeded at " + Instant.now() +
                ".\n\nThis message was sent by SmtpMailTestApplication.", false);

        System.out.printf("Connecting to %s://%s:%d (authentication=%s, STARTTLS=%s)%n",
                protocol, host, port, authenticationRequired, startTlsEnabled);
        sender.send(message);
        System.out.println("SMTP TEST PASSED: test email sent to " + to);
    }

    private static String value(String... names) {
        for (String name : names) {
            String systemValue = System.getProperty(name);
            if (systemValue != null && !systemValue.isBlank()) return systemValue.trim();
            String environmentValue = System.getenv(name);
            if (environmentValue != null && !environmentValue.isBlank()) {
                return environmentValue.trim();
            }
        }
        return null;
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int integerValue(String name, String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 65_535) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a number from 1 to 65535");
        }
    }

    private static boolean booleanValue(String name, String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
