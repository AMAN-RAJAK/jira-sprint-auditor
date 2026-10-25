# Jira Sprint Auditor

A small Java 21 / Spring Boot command-line application that:

1. runs one configured JQL query;
2. checks configured Jira field values for the declared sprint day;
3. checks only the artifacts required on that day;
4. checks newest Jira comments when a missing artifact has a configured Not Applicable rule; and
5. writes one Excel `.xlsx` grid with one row per Jira ticket.

The application is read-only. It never changes a Jira ticket.

## Audit behavior

The sprint day is always supplied explicitly. No dates, sprint calendars, or time zones are used to select policy.

| Declared day | Checks performed |
|---|---|
| Day 1 | Configured Day-1 field values only |
| Day 3 | Configured Day-3 field values and `WBS_` attachment |
| Day 7 | Configured Day-7 field values only |
| Day 10 or later | Day-10 field values and every configured Day-10 artifact |

Each field must define expected values for Days 1, 3, 7, and 10. Multiple values on one day mean that any one of those values is acceptable.

### Missing Day-10 artifacts

A matching attachment filename prefix passes immediately. The following controls are mandatory:

| Artifact | Evidence required when attachment is missing |
|---|---|
| `WBS_` | No exception; the attachment is required |
| `CWC_` | Coding Status = `No Code Changes` **and** a comment containing `CWC Not Applicable` |
| `UT_` | A comment containing `UT Not Applicable` |
| `ATDD_` | A comment containing `ATDD Not Applicable` |
| `ST_` | ST Status = `Not Applicable` **and** a comment containing `ST Not Applicable` |

The application first identifies missing artifacts. It requests comments only when at least one missing artifact has an exception rule. Jira comments are loaded completely using the Jira Server/Data Center comments endpoint in newest-first order. Matching is case-insensitive and stops at the newest matching comment.

Because calendar logic is intentionally absent, comment matching does not determine whether a comment belongs to the current sprint. If that distinction is required, use a sprint-specific phrase or add a separate comment-age rule later.

## Excel output

Each run creates:

```text
reports/jira-audit-dayN-<UTC timestamp>-<run-id>.xlsx
```

The workbook has one sheet named `Audit Results` and one row per JQL ticket:

| Column | Meaning |
|---|---|
| Run Day | Explicit execution day |
| Jira ID | Jira's internal numeric ID |
| Jira Key | Clickable ticket key |
| Summary | Ticket summary |
| Assignee | Jira display name or `Unassigned` |
| Jira Status | Ticket workflow status |
| Audit Result | `PASS` or `FAIL` |
| Remarks | All failure reasons, expected/actual values, or accepted Not Applicable evidence |

## Prerequisites

- Java 21
- Maven 3.9+
- Jira Server/Data Center with Personal Access Token support (Jira 8.14+)
- A Personal Access Token created from the Jira user profile
- Permission for that Jira user to view every ticket, field, attachment name, and comment returned by the JQL
- HTTPS access from the machine running this application

The application sends only `Authorization: Bearer <personal-access-token>`.
No email, username, password, or Basic authentication is used. The audit sees
only issues and comments that the PAT owner is permitted to view.

If Jira is hosted below a context path, include it in the base URL, for example
`https://jira.company.example/jira`. If the internal Jira certificate uses a
private company CA, that CA must be trusted by the Java runtime; the application
does not disable TLS certificate validation.

## Configuration

Copy the sample policy:

```bash
cp config/company-audit.example.yml company-audit.yml
```

`company-audit.yml` is loaded automatically when the working directory is the
project root. It contains the JQL, field IDs, expected day values, and artifact
rules; the base URL and PAT still come from environment variables.

Edit `company-audit.yml` and configure:

- `audit.jql`: the complete list of tickets to audit;
- every `jira-field`: use the Jira REST field ID, such as `customfield_10101`;
- expected values for Days 1, 3, 7, and 10;
- any additional Day-10 artifact filename prefixes your company requires;
- report directory and failure behavior.

The named `WBS_`, `CWC_`, `UT_`, `ATDD_`, and `ST_` prefixes and the four
Not Applicable rules shown in the sample are mandatory. The CWC rule uses the
`coding-status` field-rule key, the ST rule uses `st-status`, and their required
Day-10 exception values must remain configured. Additional Day-10 artifact
prefixes are allowed, but they cannot be waived.

The sample file is [`config/company-audit.example.yml`](config/company-audit.example.yml).

Do not commit `company-audit.yml`, `.env`, or personal access tokens. The supplied `.gitignore` excludes the local company file and report directory.

### Find Jira custom-field IDs

```bash
curl --header "Authorization: Bearer $JIRA_PERSONAL_ACCESS_TOKEN" \
  --header 'Accept: application/json' \
  "$JIRA_BASE_URL/rest/api/2/field"
```

Use the returned field `id`, not its display label.

## Build

```bash
mvn clean test
mvn clean package
```

The runnable jar is created at:

```text
target/jira-sprint-auditor-1.0.0.jar
```

## Run

Set credentials outside YAML:

```bash
export JIRA_BASE_URL='https://jira.company.example/jira'
export JIRA_PERSONAL_ACCESS_TOKEN='your-profile-personal-access-token'
```

`JIRA_ACCESS_TOKEN` is also accepted as an alias for
`JIRA_PERSONAL_ACCESS_TOKEN`.

Then choose the checkpoint explicitly:

```bash
# Day 1
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --audit.run-day=1

# Day 3
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --audit.run-day=3

# Day 7
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --audit.run-day=7

# Day 10 final audit
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --audit.run-day=10
```

Days after 10 reuse the Day-10 policy, preserving the final-check behavior.

### Run from IntelliJ IDEA

Run `JiraAuditApplication.main()` with the project root as the working
directory. In the run configuration, set:

```text
JIRA_BASE_URL=https://jira.company.example/jira
JIRA_PERSONAL_ACCESS_TOKEN=<your PAT>
AUDIT_RUN_DAY=3
```

Alternatively, put `--audit.run-day=3` in **Program arguments**. Before running,
make sure `company-audit.yml` exists in the project root and contains your real
JQL, Jira custom-field IDs, and expected values.

## Results and exit codes

- Exit `0`: the run completed and all tickets passed, or `fail-on-violations` is disabled.
- Exit `2`: at least one ticket failed and `fail-on-violations` is enabled.
- Startup/runtime error: invalid configuration, authentication failure, invalid JQL, incomplete Jira pagination, unreadable required comments, or an empty result when `fail-on-empty` is enabled.

## Deliberately simple scope

- Only tickets returned by the configured JQL are audited.
- Linked tickets, subtasks, hierarchy, external files, and attachment contents are not followed.
- Artifact checks use attachment filename prefixes only.
- The app does not schedule itself, update Jira, send notifications, or store history.
- The generated workbook is the audit snapshot for that run.
