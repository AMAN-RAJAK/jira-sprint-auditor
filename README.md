# Jira Sprint Auditor

A Java 21 / Spring Boot command-line application for checking Jira sprint-story
compliance on Day 3 or Day 10. It runs one configured JQL query, audits every
returned ticket, and creates an Excel `.xlsx` report with one row per ticket.

The application is read-only. It does not update Jira.

## What it does

For each ticket returned by the configured JQL, the application:

1. records the Jira ID, key, summary, assignee, and workflow status;
2. checks the fields configured for the selected checkpoint;
3. checks the checkpoint's required attachment filename prefixes;
4. reads newest-first Jira comments only when a missing Day-10 artifact needs
   comment evidence; and
5. verifies that every directly linked Bug is resolved on Day 10; and
6. writes an overall `PASS` or `FAIL` and detailed remarks to a grid-style Excel
   report.

The checkpoint is supplied explicitly as `3` or `10`. There is no date,
sprint-calendar, or time-zone calculation.

## Audit rules

### Day 3

- Only the fields under `audit.checkpoints.3.fields` are checked.
- Each field passes when its Jira value matches any configured
  `accepted-values` entry.
- An attachment whose filename starts with the configured WBS prefix is
  mandatory.
- CWC, UT, ATDD, and ST artifacts are not checked on Day 3.

### Day 10

- Only the fields under `audit.checkpoints.10.fields` are checked.
- Each field passes when its Jira value matches any configured
  `accepted-values` entry.
- WBS is always mandatory and cannot be waived by a comment.
- CWC, ST, and ATDD are linked to configured Day-10 field rules, each with its
  own configurable Not Applicable values.
- ATDD Status decides the ATDD/UT evidence path. An applicable ATDD Status
  requires ATDD; a Not Applicable ATDD Status permits UT or a UT comment.
- Every directly linked issue whose type matches the configured Bug issue type
  must have one of the configured resolved statuses.

The Day-10 artifact decisions are:

| Artifact | Rule |
|---|---|
| WBS | Must be attached |
| CWC | If Coding Status is configured as Not Applicable, CWC must be absent; otherwise CWC attachment or the configured CWC comment is required |
| ST | If ST Status is configured as Not Applicable, ST must be absent; otherwise ST attachment or the configured ST comment is required |
| ATDD | If ATDD Status matches `not-applicable-values`, ATDD must be absent; otherwise ATDD must be attached and cannot be waived by a comment |
| UT | Has no mapped status field; when absent it requires the configured `UT Not Applicable` comment |
| Linked Bugs | Every directly linked Bug must have a configured resolved status; unresolved or incomplete linked-issue data fails the parent ticket |

The ATDD/UT combinations are:

- ATDD Status Not Applicable: ATDD must be absent. UT may be attached, or both
  ATDD and UT may be absent when a matching UT Not Applicable comment exists.
- ATDD Status applicable: ATDD must be attached, UT must be absent, and a
  matching UT Not Applicable comment is required.

ST remains an independent rule in both paths: attach it when applicable, or
omit it when its mapped field is Not Applicable. If an applicable CWC, ST, or
UT artifact is absent, its configured standard comment can provide the
missing-artifact evidence. A comment can never replace a required WBS or ATDD
attachment.

Field comparisons, attachment-prefix comparisons, and comment-phrase searches
are case-insensitive. Jira comments are requested in newest-first order, all
pages are loaded, and the newest matching comment is recorded in the evidence.

## Excel output

Each run creates a file like:

```text
reports/jira-audit-day10-20261025-063000-000-a1b2c3d4.xlsx
```

The `Audit Results` sheet contains one row per JQL ticket:

| Column | Content |
|---|---|
| Run Day | `3` or `10` |
| Jira ID | Jira's internal ID |
| Jira Key | Clickable issue key |
| Summary | Ticket summary |
| Assignee | Jira display name or `Unassigned` |
| Jira Status | Jira workflow status |
| Audit Result | `PASS` or `FAIL` |
| Remarks | Failure reasons with expected/actual values, or accepted Not Applicable evidence |

## Required configuration

Copy the template into the project root:

```bash
cp config/company-audit.example.yml company-audit.yml
```

Configure these company-specific values in `company-audit.yml`:

- `audit.jql`: the complete story scope for the audit;
- `audit.checkpoints.3.fields`: Day-3 Jira field IDs and possible values;
- `audit.checkpoints.10.fields`: Day-10 Jira field IDs and possible values;
- `audit.artifacts.day-3-wbs-prefix`: the Day-3 WBS filename prefix;
- Day-10 WBS, CWC, UT, ATDD, and ST filename prefixes;
- the Day-10 field-rule mapping for CWC, ATDD, and ST;
- each mapped artifact's configurable `not-applicable-values`;
- CWC, UT, and ST standard `comment-contains` phrases;
- the linked Bug issue type and resolved workflow statuses; and
- report output and failure behavior.

Day 3 and Day 10 field lists are independent. A field may be configured on one
checkpoint and omitted from the other. Configure `accepted-values` as one
quoted string and separate possible values with `$`. The values use OR logic:

```yaml
accepted-values: 'Done - On Time$Not Done$Not Applicable'
```

Do not use commas as accepted-value separators. Commas inside a Jira value are
preserved, so this configuration defines exactly two possible values:

```yaml
accepted-values: 'Not Applicable (POC, Backporting, NoCodeChange)$Suite Pass (90%)'
```

Artifact `not-applicable-values` uses the same quoted `$` format:

```yaml
not-applicable-values: 'Not Applicable$No Testing Required$N/A'
```

This also preserves commas inside a single Not Applicable value.

Configure the Day-10 linked Bug check under the Day-10 checkpoint. Resolved
statuses use the same `$` separator and are matched case-insensitively:

```yaml
linked-bugs:
  issue-type: Bug
  resolved-statuses: 'Resolved$Closed$Done'
```

Only direct Jira issue links are checked. Non-Bug links are ignored. Each
unresolved linked Bug is included in the parent ticket's Excel remarks with its
issue key and current status. Missing link type/status data fails closed rather
than silently skipping the link.

The `$` character is reserved as the separator and cannot be part of an
accepted Jira value. Leading and trailing spaces around each separated value
are ignored. Empty values such as `Done$$Not Applicable` are rejected during
startup validation.

When a field does not require a specific value but must contain something, use
the reserved `__NON_EMPTY__` rule by itself. For example:

```yaml
revised-sp:
  label: Revised SP
  jira-field: customfield_10105
  accepted-values: '__NON_EMPTY__'
```

This rule passes for text, numbers, selections, and non-empty arrays or objects.
It fails when the Jira field is missing, `null`, blank text, an empty array, or
an empty object. `__NON_EMPTY__` cannot be combined with other accepted values,
because it already accepts every meaningful value.

The application requires exactly the Day `3` and Day `10` checkpoint sections.
Only the selected day's Jira fields are requested and checked.

See the complete annotated template at
[`config/company-audit.example.yml`](config/company-audit.example.yml).

### Jira credentials

This project targets self-hosted Jira Server/Data Center and uses a Personal
Access Token generated from the Jira user profile. It sends:

```text
Authorization: Bearer <personal-access-token>
```

No Jira Cloud email/API-token authentication and no Basic authentication are
used. Set credentials outside the company policy file:

```bash
export JIRA_BASE_URL='https://jira.company.example/jira'
export JIRA_PERSONAL_ACCESS_TOKEN='your-profile-personal-access-token'
```

`JIRA_ACCESS_TOKEN` is supported as an alias. Include Jira's context path in
the base URL when applicable. The URL must use HTTPS, and the Java runtime must
trust the company's TLS certificate authority.

The PAT owner needs permission to view all JQL tickets, their linked issues,
configured fields, attachment names, and comments. Keep `company-audit.yml`,
`.env`, generated reports, and tokens out of version control; the supplied
`.gitignore` excludes the local company file and report directory.

### Finding custom-field IDs

Use the returned field `id` such as `customfield_10101`, not the display label:

```bash
curl --header "Authorization: Bearer $JIRA_PERSONAL_ACCESS_TOKEN" \
  --header 'Accept: application/json' \
  "$JIRA_BASE_URL/rest/api/2/field"
```

## Build and run

Requirements: Java 21, Maven 3.9+, HTTPS access to Jira, and Jira Server/Data
Center with Personal Access Token support.

```bash
mvn clean test
mvn clean package
```

Run one checkpoint explicitly:

```bash
java -jar target/jira-sprint-auditor-1.0.0.jar --audit.run-day=3
java -jar target/jira-sprint-auditor-1.0.0.jar --audit.run-day=10
```

Any other day is rejected.

### IntelliJ IDEA

Run `JiraAuditApplication.main()` with the repository root as the working
directory. Add these environment variables to the run configuration:

```text
JIRA_BASE_URL=https://jira.company.example/jira
JIRA_PERSONAL_ACCESS_TOKEN=<your PAT>
AUDIT_RUN_DAY=3
```

Use `AUDIT_RUN_DAY=10` for the final check. You may instead pass
`--audit.run-day=3` or `--audit.run-day=10` in Program arguments. Ensure the
edited `company-audit.yml` exists in the repository root.

## Results and exit behavior

- Exit `0`: the run completed and every ticket passed, or
  `fail-on-violations` is disabled.
- Exit `2`: at least one ticket failed while `fail-on-violations` is enabled.
- The run stops with an error for invalid configuration, authentication/JQL
  errors, incomplete Jira pagination, malformed Jira data, or an empty result
  when `fail-on-empty` is enabled.

## Deliberately simple scope

- Tickets returned by the JQL are audited, and their direct linked issues are
  inspected only for the Day-10 linked Bug check.
- Artifact contents are not inspected; checks use attachment filename prefixes.
- The application does not schedule itself, modify Jira, send notifications,
  or keep audit history.
- The generated `.xlsx` workbook is the audit snapshot for that run.
