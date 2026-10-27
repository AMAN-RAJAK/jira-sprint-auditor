package com.example.jiraaudit;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SmtpAuditMailer implements AuditMailer {
    private static final String CHART_CONTENT_ID = "auditTeamChart";
    private static final int MAX_TICKET_DETAILS = 50;
    private static final Color[] CHART_COLORS = {
            new Color(54, 116, 217), new Color(237, 125, 49),
            new Color(112, 173, 71), new Color(165, 80, 177),
            new Color(255, 192, 0), new Color(91, 155, 213),
            new Color(192, 80, 77), new Color(128, 128, 128)
    };

    private final AuditProperties properties;
    private final Clock clock;
    private final AuditMailTransport transport;

    public SmtpAuditMailer(AuditProperties properties, Clock clock,
                           AuditMailTransport transport) {
        this.properties = properties;
        this.clock = clock;
        this.transport = transport;
    }

    @Override
    public void sendIfEnabled(List<CheckResult> checks, int totalIssues,
                              int runDay, Path report) {
        AuditProperties.Mail mail = properties.mail();
        try {
            AuditEmail email = compose(checks, totalIssues, runDay, report);
            printToConsole(email, mail != null && mail.isEnabled());
            if (mail == null || !mail.isEnabled()) return;

            transport.send(mail, email);
            System.out.println("Audit email sent to: " + String.join(", ", mail.to()));
        } catch (Exception error) {
            if (mail == null || !mail.isEnabled() || mail.shouldFailOnError()) {
                throw new IllegalStateException(
                        "Excel report was created, but the audit email content could not be " +
                                "prepared or sent", error);
            }
            System.err.println("WARNING: Audit email could not be sent: " + error.getMessage());
        }
    }

    AuditEmail compose(List<CheckResult> checks, int totalIssues, int runDay,
                       Path report) throws IOException {
        Map<String, List<CheckResult>> failuresByIssue = checks.stream()
                .filter(check -> check.outcome() == CheckResult.Outcome.FAIL)
                .collect(Collectors.groupingBy(CheckResult::issueKey,
                        LinkedHashMap::new, Collectors.toList()));
        List<CheckResult> fieldChecks = checks.stream()
                .filter(check -> "FIELD".equals(check.checkType()))
                .toList();
        List<CheckResult> failedFieldChecks = fieldChecks.stream()
                .filter(check -> check.outcome() == CheckResult.Outcome.FAIL)
                .toList();
        Map<String, TeamStats> teamStats = teamStats(failedFieldChecks);
        long nonFieldFailures = checks.stream()
                .filter(check -> check.outcome() == CheckResult.Outcome.FAIL)
                .filter(check -> !"FIELD".equals(check.checkType()))
                .count();
        long nonFieldFailureTickets = failuresByIssue.values().stream()
                .filter(issueChecks -> issueChecks.stream()
                        .anyMatch(check -> !"FIELD".equals(check.checkType())))
                .count();
        int failedIssues = failuresByIssue.size();
        int passedIssues = Math.max(0, totalIssues - failedIssues);
        String overall = failedIssues == 0 ? "PASS" : "FAIL";
        String generatedAt = Instant.now(clock).toString();

        String html = htmlBody(failuresByIssue, teamStats, totalIssues,
                passedIssues, fieldChecks.size(), failedFieldChecks.size(),
                nonFieldFailures, nonFieldFailureTickets, overall, runDay, generatedAt);
        String plain = plainBody(failuresByIssue, teamStats, totalIssues, passedIssues,
                fieldChecks.size(), failedFieldChecks.size(), nonFieldFailures,
                nonFieldFailureTickets, overall, runDay, generatedAt);
        byte[] chart = pieChart(teamStats);
        return new AuditEmail(mailSubject(runDay), plain, html, chart,
                CHART_CONTENT_ID, report);
    }

    private void printToConsole(AuditEmail email, boolean deliveryEnabled) {
        System.out.println();
        System.out.println("================ AUDIT EMAIL PREVIEW ================");
        System.out.println("Delivery: " + (deliveryEnabled ? "ENABLED" : "DISABLED"));
        System.out.println("Subject: " + email.subject());
        System.out.println("-------------------- MAIL BODY ----------------------");
        System.out.print(email.plainText());
        if (!email.plainText().endsWith("\n")) System.out.println();
        System.out.println("Attachment: " + email.attachment().toAbsolutePath());
        System.out.println("============== END AUDIT EMAIL PREVIEW ==============");
        System.out.println();
    }

    private Map<String, TeamStats> teamStats(List<CheckResult> failedFields) {
        Map<String, MutableTeamStats> grouped = new LinkedHashMap<>();
        for (CheckResult check : failedFields) {
            String team = normalizedTeam(check.responsibleTeam());
            String key = team.toLowerCase(Locale.ROOT);
            MutableTeamStats stats = grouped.computeIfAbsent(key,
                    ignored -> new MutableTeamStats(team));
            stats.ticketKeys.add(check.issueKey());
            stats.failedFields++;
        }
        Map<String, TeamStats> completed = new LinkedHashMap<>();
        grouped.forEach((key, value) -> completed.put(key,
                new TeamStats(value.team, value.ticketKeys.size(), value.failedFields)));
        return completed;
    }

    private String htmlBody(Map<String, List<CheckResult>> failuresByIssue,
                            Map<String, TeamStats> teamStats,
                            int totalIssues, int passedIssues,
                            int fieldChecks, int failedFields,
                            long nonFieldFailures, long nonFieldFailureTickets,
                            String overall, int runDay, String generatedAt) {
        AuditProperties.AuditContext context = mailContext();
        String resultColor = "PASS".equals(overall) ? "#1b7f3a" : "#b42318";
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><body style=\"font-family:Arial,sans-serif;color:#202124\">")
                .append("<h2 style=\"margin-bottom:8px\">Jira Sprint Audit Summary</h2>")
                .append("<table style=\"border-collapse:collapse;margin-bottom:18px\">")
                .append(contextRow("Team", context.teamName()))
                .append(contextRow("GA Version", context.gaVersion()))
                .append(contextRow("Sprint Phase", context.sprintPhase()))
                .append(contextRow("Audit Day", String.valueOf(runDay)))
                .append(contextRow("Generated (UTC)", generatedAt))
                .append("</table>")
                .append("<div style=\"font-size:20px;font-weight:bold;color:")
                .append(resultColor).append("\">Overall result: ").append(overall).append("</div>")
                .append("<p><b>Tickets:</b> ").append(totalIssues).append(" total, ")
                .append(passedIssues).append(" passed, ")
                .append(failuresByIssue.size()).append(" failed<br>")
                .append("<b>Configured field checks:</b> ").append(fieldChecks)
                .append(" total, ").append(failedFields).append(" pending<br>")
                .append("<b>Other compliance failures:</b> ").append(nonFieldFailures)
                .append(" checks across ").append(nonFieldFailureTickets).append(" tickets</p>")
                .append("<h3>Pending field updates by team</h3>")
                .append(teamTable(teamStats))
                .append("<p><img src=\"cid:").append(CHART_CONTENT_ID)
                .append("\" alt=\"Pending field updates by team\" style=\"max-width:760px;width:100%;height:auto\"></p>")
                .append("<h3>Failed ticket details</h3>")
                .append(failedTicketTable(failuresByIssue))
                .append("<p style=\"color:#5f6368\">The complete Excel report is attached. ")
                .append("Responsible-team information is reporting metadata and does not change audit results.</p>")
                .append("</body></html>");
        return html.toString();
    }

    private String teamTable(Map<String, TeamStats> teamStats) {
        if (teamStats.isEmpty()) {
            return "<p style=\"color:#1b7f3a\"><b>No pending field updates.</b></p>";
        }
        StringBuilder table = new StringBuilder(tableStart())
                .append(headerRow("Responsible Team", "Pending Tickets", "Pending Fields"));
        teamStats.values().forEach(stats -> table.append("<tr>")
                .append(cell(stats.team()))
                .append(cell(String.valueOf(stats.pendingTickets())))
                .append(cell(String.valueOf(stats.pendingFields())))
                .append("</tr>"));
        return table.append("</table>").toString();
    }

    private String failedTicketTable(Map<String, List<CheckResult>> failuresByIssue) {
        if (failuresByIssue.isEmpty()) {
            return "<p style=\"color:#1b7f3a\"><b>All tickets passed.</b></p>";
        }
        StringBuilder table = new StringBuilder(tableStart())
                .append(headerRow("Jira Ticket", "Assignee", "Failed Fields", "Other Failures"));
        int rendered = 0;
        for (List<CheckResult> failures : failuresByIssue.values()) {
            if (rendered++ == MAX_TICKET_DETAILS) break;
            IssueDetails issue = failures.get(0).issue();
            String jiraUrl = properties.jira().baseUrl().replaceAll("/+$", "") +
                    "/browse/" + issue.issueKey();
            String failedFields = failures.stream()
                    .filter(check -> "FIELD".equals(check.checkType()))
                    .map(check -> check.checkName() + " (" +
                            normalizedTeam(check.responsibleTeam()) + ")")
                    .distinct().collect(Collectors.joining(", "));
            String otherFailures = failures.stream()
                    .filter(check -> !"FIELD".equals(check.checkType()))
                    .map(CheckResult::checkName).distinct().collect(Collectors.joining(", "));
            table.append("<tr><td style=\"border:1px solid #dadce0;padding:7px\"><a href=\"")
                    .append(escape(jiraUrl)).append("\">").append(escape(issue.issueKey()))
                    .append("</a></td>")
                    .append(cell(issue.assigneeName()))
                    .append(cell(emptyAsDash(failedFields)))
                    .append(cell(emptyAsDash(otherFailures)))
                    .append("</tr>");
        }
        table.append("</table>");
        if (failuresByIssue.size() > MAX_TICKET_DETAILS) {
            table.append("<p>Showing the first ").append(MAX_TICKET_DETAILS)
                    .append(" failed tickets. See the attached report for all details.</p>");
        }
        return table.toString();
    }

    private String plainBody(Map<String, List<CheckResult>> failuresByIssue,
                             Map<String, TeamStats> teamStats,
                             int totalIssues, int passedIssues,
                             int fieldChecks, int failedFields,
                             long nonFieldFailures, long nonFieldFailureTickets,
                             String overall, int runDay, String generatedAt) {
        AuditProperties.AuditContext context = mailContext();
        StringBuilder text = new StringBuilder()
                .append("Jira Sprint Audit Summary\n")
                .append("Team: ").append(context.teamName()).append('\n')
                .append("GA Version: ").append(context.gaVersion()).append('\n')
                .append("Sprint Phase: ").append(context.sprintPhase()).append('\n')
                .append("Audit Day: ").append(runDay).append('\n')
                .append("Generated (UTC): ").append(generatedAt).append("\n\n")
                .append("Overall result: ").append(overall).append('\n')
                .append("Tickets: ").append(totalIssues).append(" total, ")
                .append(passedIssues).append(" passed, ").append(failuresByIssue.size())
                .append(" failed\n")
                .append("Configured field checks: ").append(fieldChecks).append(" total, ")
                .append(failedFields).append(" pending\n")
                .append("Other compliance failures: ").append(nonFieldFailures)
                .append(" checks across ").append(nonFieldFailureTickets).append(" tickets\n\n")
                .append("Pending field updates by team:\n");
        if (teamStats.isEmpty()) {
            text.append("- None\n");
        } else {
            teamStats.values().forEach(stats -> text.append("- ").append(stats.team())
                    .append(": ").append(stats.pendingTickets()).append(" tickets, ")
                    .append(stats.pendingFields()).append(" fields\n"));
        }
        text.append("\nFailed ticket details:\n");
        if (failuresByIssue.isEmpty()) {
            text.append("- All tickets passed.\n");
        } else {
            int rendered = 0;
            for (List<CheckResult> failures : failuresByIssue.values()) {
                if (rendered++ == MAX_TICKET_DETAILS) break;
                IssueDetails issue = failures.get(0).issue();
                text.append("- ").append(issue.issueKey()).append(" | Assignee: ")
                        .append(emptyAsDash(issue.assigneeName())).append('\n');
                failures.forEach(check -> text.append("  * ").append(check.checkName())
                        .append(" [").append("FIELD".equals(check.checkType())
                                ? normalizedTeam(check.responsibleTeam()) : check.checkType())
                        .append("]: ").append(check.reason()).append('\n'));
            }
            if (failuresByIssue.size() > MAX_TICKET_DETAILS) {
                text.append("- Showing the first ").append(MAX_TICKET_DETAILS)
                        .append(" failed tickets; see the Excel report for all details.\n");
            }
        }
        return text.append("\nThe complete Excel report is attached.\n").toString();
    }

    private AuditProperties.AuditContext mailContext() {
        AuditProperties.AuditContext context = properties.context();
        if (context == null) {
            return new AuditProperties.AuditContext(
                    "Not configured", "Not configured", "Not configured");
        }
        return new AuditProperties.AuditContext(
                displayValue(context.teamName()), displayValue(context.gaVersion()),
                displayValue(context.sprintPhase()));
    }

    private String mailSubject(int runDay) {
        AuditProperties.Mail mail = properties.mail();
        if (mail != null && mail.subject() != null && !mail.subject().isBlank()) {
            return mail.subject().trim();
        }
        return "Jira Sprint Audit Report - Day " + runDay;
    }

    private static String displayValue(String value) {
        return value == null || value.isBlank() ? "Not configured" : value.trim();
    }

    private byte[] pieChart(Map<String, TeamStats> teamStats) throws IOException {
        int width = 760;
        int height = Math.max(420, 130 + teamStats.size() * 34);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(32, 33, 36));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            graphics.drawString("Pending Fields by Responsible Team", 30, 35);

            if (teamStats.isEmpty()) {
                graphics.setColor(new Color(27, 127, 58));
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
                graphics.drawString("No pending field updates", 225, 220);
            } else {
                int total = teamStats.values().stream().mapToInt(TeamStats::pendingFields).sum();
                int startAngle = 90;
                int index = 0;
                int usedAngle = 0;
                for (TeamStats stats : teamStats.values()) {
                    int angle = index == teamStats.size() - 1
                            ? 360 - usedAngle
                            : (int) Math.floor(360.0 * stats.pendingFields() / total);
                    graphics.setColor(CHART_COLORS[index % CHART_COLORS.length]);
                    graphics.fillArc(45, 70, 300, 300, startAngle, -angle);
                    startAngle -= angle;
                    usedAngle += angle;
                    index++;
                }
                graphics.setColor(Color.WHITE);
                graphics.setStroke(new BasicStroke(2));
                graphics.drawOval(45, 70, 300, 300);

                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
                index = 0;
                int legendY = 90;
                for (TeamStats stats : teamStats.values()) {
                    graphics.setColor(CHART_COLORS[index % CHART_COLORS.length]);
                    graphics.fillRect(390, legendY - 13, 18, 18);
                    graphics.setColor(new Color(32, 33, 36));
                    double percentage = 100.0 * stats.pendingFields() / total;
                    graphics.drawString(truncate(stats.team(), 28) + " - " +
                            stats.pendingFields() + " (" + String.format(Locale.ROOT, "%.1f", percentage) +
                            "%)", 420, legendY + 1);
                    legendY += 32;
                    index++;
                }
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("No PNG image writer is available");
            }
            return output.toByteArray();
        }
    }

    private static String contextRow(String label, String value) {
        return "<tr><td style=\"padding:3px 14px 3px 0;color:#5f6368\">" +
                escape(label) + "</td><td style=\"padding:3px 0;font-weight:bold\">" +
                escape(value) + "</td></tr>";
    }

    private static String tableStart() {
        return "<table style=\"border-collapse:collapse;margin-bottom:18px\">";
    }

    private static String headerRow(String... labels) {
        StringBuilder row = new StringBuilder("<tr>");
        for (String label : labels) {
            row.append("<th style=\"background:#1f4e78;color:white;border:1px solid #dadce0;")
                    .append("padding:7px;text-align:left\">").append(escape(label)).append("</th>");
        }
        return row.append("</tr>").toString();
    }

    private static String cell(String value) {
        return "<td style=\"border:1px solid #dadce0;padding:7px\">" +
                escape(value) + "</td>";
    }

    private static String emptyAsDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String normalizedTeam(String value) {
        return value == null || value.isBlank() ? "Unassigned" : value.trim();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    record TeamStats(String team, int pendingTickets, int pendingFields) { }

    private static final class MutableTeamStats {
        private final String team;
        private final Set<String> ticketKeys = new LinkedHashSet<>();
        private int failedFields;

        private MutableTeamStats(String team) {
            this.team = team;
        }
    }
}
