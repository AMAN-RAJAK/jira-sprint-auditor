package com.example.jiraaudit;

import java.nio.file.Path;
import java.util.List;

public interface AuditMailer {
    void sendIfEnabled(List<CheckResult> checks, int totalIssues, int runDay, Path report);
}

record AuditEmail(String subject, String plainText, String htmlText,
                  byte[] chartPng, String chartContentId, Path attachment) { }

interface AuditMailTransport {
    void send(AuditProperties.Mail settings, AuditEmail email) throws Exception;
}
