package com.example.jiraaudit;

import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

@Component
public class SmtpMailTransport implements AuditMailTransport {
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override
    public void send(AuditProperties.Mail settings, AuditEmail email) throws Exception {
        JavaMailSenderImpl sender = configuredSender(settings.smtp());
        MimeMessage message = createMessage(sender, settings, email);
        sender.send(message);
    }

    JavaMailSenderImpl configuredSender(AuditProperties.Smtp smtp) {
        String protocol = smtp.protocol().trim().toLowerCase(Locale.ROOT);
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.hostIp().trim());
        sender.setPort(smtp.port());
        sender.setProtocol(protocol);
        if (Boolean.TRUE.equals(smtp.authenticationRequired())) {
            sender.setUsername(smtp.username());
            sender.setPassword(smtp.password());
        }

        Properties javaMail = sender.getJavaMailProperties();
        javaMail.setProperty("mail.transport.protocol", protocol);
        javaMail.setProperty("mail.smtp.host", smtp.hostIp().trim());
        javaMail.setProperty("mail.smtp.port", String.valueOf(smtp.port()));
        javaMail.setProperty("mail.smtp.auth",
                String.valueOf(Boolean.TRUE.equals(smtp.authenticationRequired())));
        javaMail.setProperty("mail." + protocol + ".auth",
                String.valueOf(Boolean.TRUE.equals(smtp.authenticationRequired())));
        javaMail.setProperty("mail." + protocol + ".starttls.enable",
                String.valueOf(Boolean.TRUE.equals(smtp.startTlsEnabled())));
        javaMail.setProperty("mail." + protocol + ".connectiontimeout", "10000");
        javaMail.setProperty("mail." + protocol + ".timeout", "30000");
        javaMail.setProperty("mail." + protocol + ".writetimeout", "30000");
        if ("smtps".equals(protocol)) {
            javaMail.setProperty("mail.smtps.ssl.enable", "true");
        }
        return sender;
    }

    MimeMessage createMessage(JavaMailSenderImpl sender, AuditProperties.Mail settings,
                              AuditEmail email) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message,
                MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                StandardCharsets.UTF_8.name());
        helper.setFrom(settings.from());
        helper.setTo(settings.to().toArray(String[]::new));
        List<String> cc = settings.cc();
        if (cc != null && !cc.isEmpty()) helper.setCc(cc.toArray(String[]::new));
        helper.setSubject(email.subject());
        helper.setText(email.plainText(), email.htmlText());
        helper.addInline(email.chartContentId(), new ByteArrayResource(email.chartPng()), "image/png");
        helper.addAttachment(email.attachment().getFileName().toString(),
                new FileSystemResource(email.attachment()), XLSX_CONTENT_TYPE);
        return message;
    }
}
