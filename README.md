# Jira Sprint Auditor

A read-only Java 21 / Spring Boot 3.5 command-line application for checking Jira Cloud sprint stories against company process rules. It runs a configured JQL query, evaluates fields according to the sprint day, verifies required attachment filename prefixes, and creates a real Excel `.xlsx` audit workbook.

## Current capabilities

- Runs `POST /rest/api/3/search/jql`, follows Jira's `nextPageToken` pagination, and supplies known linked numeric IDs through `reconcileIssues` for stronger read-after-write consistency at the final gate.
- Requests only the required Jira fields and never updates an issue.
- Applies day-based expectations. A rule configured for Day 3 stays active until the next configured day.
- Applies `when-resolved` expectations immediately when Jira's `resolution` is non-null.
- Checks that every configured artifact prefix, such as `WBS_`, `CWC_`, and `UT_`, has a matching Jira attachment filename. Matching is case-insensitive.
- Enforces a configurable final gate from Day 10 onward: every check must pass, required artifacts cannot be skipped, and natively linked Bugs and Tasks must be in a completed Jira status category.
- Supports Jira scalar, select, and multi-select field values. Configured alternatives are OR conditions and comparisons ignore surrounding whitespace and case.
- Reports Jira's internal ID, Jira key, URL, summary, issue type, assignee name/account ID, status, resolution, and every expected/actual compliance result. Linked-ticket ID/key, assignee, status, category, direction, and relationship are separately structured in Excel.
- Produces issue-level and check-level Excel sheets with filters, frozen headers, hyperlinks, and highlighted outcomes.
- Returns process exit code `2` when policy violations exist and `fail-on-violations` is enabled, which makes the app suitable for CI or scheduled controls.
- Fails by default when JQL returns no issues, preventing an incorrect sprint ID or permissions problem from appearing compliant.
- Rejects incomplete pagination and missing essential Jira metadata rather than producing a misleading partial audit.

This is a one-shot batch application. It does not expose an HTTP API or UI.

## Prerequisites

- Java 21
- Maven 3.9+
- A Jira Cloud account or service account with Browse Projects permission and visibility of the selected issues, their native issue links, linked Bugs and Tasks (including those in other projects), custom fields, users, and attachment metadata
- An Atlassian API token for that account

## Required company configuration

The most important setup is the JQL, explicit run day, real Jira custom-field IDs, day expectations, and artifact prefixes. Do not use the sample IDs unchanged.

### 1. Credentials

Set credentials as environment variables. Do not put the API token in YAML, `.env` files committed to source control, or command history.

```bash
export JIRA_BASE_URL='https://yourcompany.atlassian.net'
export JIRA_EMAIL='jira-auditor@yourcompany.com'
export JIRA_API_TOKEN='your-atlassian-api-token'
```

Authentication is Jira Cloud email plus API-token Basic Auth. Jira Data Center PAT and OAuth are not implemented.

### 2. JQL

Replace `audit.jql` with the query that defines the audited sprint scope. Prefer a stable numeric sprint ID so Day 1 and Day 7 audit the same scope.

```yaml
audit:
  jql: 'project = PAY AND sprint = 123 AND issuetype = Story ORDER BY key ASC'
```

Use Stories as the primary scope. The final gate fetches linked Bugs and Tasks separately, so including them in the primary JQL would audit them twice for different purposes. Do not add `resolution = Unresolved`: doing so removes completed stories and prevents end-of-sprint resolved-ticket checks.

### 3. Required run day

Supply `audit.run-day` explicitly for every execution. It is the only input used to select day-based policy rules; the application does not calculate a day from dates, calendars, or time zones.

Use one of the agreed checkpoints, for example:

```bash
--audit.run-day=1
--audit.run-day=3
--audit.run-day=7
--audit.run-day=10
```

The run fails when `audit.run-day` is missing or invalid. The workbook's generation/execution timestamp is audit evidence only and never changes which policy rules are evaluated.

Use the numeric day value (`1`, `3`, `7`, or `10`), not a calendar date. Remove any old `audit.as-of` or `audit.sprint.*` settings from deployment configuration; obsolete and misspelled `audit.*` properties are rejected so they cannot be silently ignored.

### 4. Field rules

Each entry under `audit.fields` has:

- a stable company rule key, such as `download`;
- a report label;
- the Jira REST field ID, such as `customfield_10101`;
- expected values effective from selected sprint days;
- optional values required as soon as the issue is resolved.

```yaml
audit:
  fields:
    download:
      label: Download
      jira-field: customfield_10101
      days:
        1: [Started]
        3: [In Progress]
        7: [Done]
        10: [Done]
      when-resolved: [Done]
    atdd:
      label: ATDD_STATUS
      jira-field: customfield_10102
      days:
        1: [Started]
        7: [Done]
        10: [Done]
      when-resolved: [Done]
```

Day 1 and the configured final-gate day are mandatory for every field. This prevents an early value such as `Started` from accidentally carrying into the final audit. On Day 3, the example `download` rule expects `In Progress`; on Day 7 and Day 10 it expects `Done`. If a ticket resolves before the final gate, `when-resolved: [Done]` takes precedence. At the final gate, the explicit final-day value is pinned for Day 10 and every later run. Configuration entries after the final-gate day are rejected because they could otherwise weaken the gate.

To make unfinished stories fail at the sprint deadline, also configure Jira's native `status` field using your workflow's exact status names:

```yaml
audit:
  fields:
    jira-status:
      label: Jira Status
      jira-field: status
      days:
        1: [To Do, In Progress, Done]
        10: [Done]
      when-resolved: [Done]
```

Jira display labels are not API field keys. Discover the correct custom-field IDs from Jira Cloud:

```bash
curl --user "$JIRA_EMAIL:$JIRA_API_TOKEN" \
  --header 'Accept: application/json' \
  "$JIRA_BASE_URL/rest/api/3/field"
```

### 5. Required artifacts

```yaml
audit:
  artifacts:
    only-for-resolved: true
    prefixes: [WBS_, CWC_, UT_]
```

Before the final gate, each resolved ticket must have at least one attached filename for every prefix. For example, `UT_payment-service.xlsx` satisfies `UT_`. With `only-for-resolved: true`, unresolved tickets receive `SKIP` for artifact checks before the final-gate day. From the final-gate day onward, artifact checks run for every selected story, including unresolved stories, and missing artifacts fail.

This validates attachment names and presence only. It does not validate document contents, freshness, uploader, Confluence pages, remote links, or files in another repository.

### 6. Final compliance gate

```yaml
audit:
  final-gate:
    from-day: 10
    linked-issue-types: [Bug, Task]
    completed-status-category-keys: [completed, done]
```

On Day 10 and every later day, the final gate requires every configured field and artifact check to be `PASS`; a check that would otherwise be `SKIP` becomes a failure. It also follows both inward and outward native Jira issue links from each selected story. Every linked issue whose type matches `Bug` or `Task` must have a status-category key listed in `completed-status-category-keys`. This uses Jira's stable status category rather than company-specific status names such as `Closed`, `Resolved`, or `Production Done`.

If a story has no visible linked Bug or Task, its linked-issue check passes. Duplicate links to the same issue are checked once. A reported link with missing or unreadable identity, type, summary, assignee, status/category, or resolution data fails closed instead of being silently ignored.

Only Jira's native issue links (`issuelinks`) are in scope. Parent/subtask relationships, Epic or hierarchy relationships, remote links, Confluence links, and URLs in text are not included unless Jira also exposes them as native issue links. Jira may hide links or linked issues because of Browse Projects or issue-security permissions; grant the auditor account access to every in-scope linked project so the result is complete. The gate checks the selected story's linked Bugs and Tasks, but does not by itself require the story to be closed—add a native `status` field rule as shown above when that is company policy.

Jira documents that enhanced JQL search can briefly lag very recent changes. The auditor sends each known linked Jira ID in `reconcileIssues` to request stronger consistency. For especially time-sensitive compliance runs, allow transitions to settle and rerun before archiving the workbook if Jira was changed during the audit window.

### 7. Report behavior

```yaml
audit:
  report:
    directory: ./reports
    fail-on-violations: true
    fail-on-empty: true
```

The report directory must be writable. `fail-on-empty: true` makes an empty Jira scope a run error; set it to `false` only when zero selected tickets is a legitimate result. Because reports contain employee names and ticket data, store and retain them according to company access and privacy policy.

## Recommended external configuration

For local and production use, keep the company policy outside the packaged jar. Copy [`config/company-audit.example.yml`](config/company-audit.example.yml), replace its sample values, and run:

```bash
cp config/company-audit.example.yml company-audit.yml

java -jar target/jira-sprint-auditor-1.0.0.jar \
  --spring.config.additional-location=file:./company-audit.yml \
  --audit.run-day=1
```

`--audit.run-day` is required on every execution unless it is supplied through the `AUDIT_RUN_DAY` environment variable referenced by the sample YAML.

The bundled [`application.yml`](src/main/resources/application.yml) contains only harmless Spring startup settings. Company rules are intentionally excluded so Spring cannot merge forgotten sample rule entries into an external policy. The external company file must therefore be complete.

## Build and run

```bash
mvn clean test
mvn clean package

# Day 1
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --spring.config.additional-location=file:./company-audit.yml \
  --audit.run-day=1

# Day 3
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --spring.config.additional-location=file:./company-audit.yml \
  --audit.run-day=3

# Day 7
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --spring.config.additional-location=file:./company-audit.yml \
  --audit.run-day=7

# Final compliance gate (also active for Day 11 and later)
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --spring.config.additional-location=file:./company-audit.yml \
  --audit.run-day=10
```

The value of `audit.run-day` is the sole policy-day selector. Any Spring property can also be overridden on the command line, including JQL:

```bash
java -jar target/jira-sprint-auditor-1.0.0.jar \
  --spring.config.additional-location=file:./company-audit.yml \
  --audit.run-day=10 \
  '--audit.jql=project = PAY AND sprint = 123 AND issuetype = Story ORDER BY key ASC'
```

## Excel output

Each run writes `reports/jira-audit-dayN-<timestamp>-<run-id>.xlsx`. It is written to a temporary file and moved into place only after the workbook is complete. The correct Excel extension is `.xlsx`. The timestamp records when the evidence was generated; it does not determine the selected policy day.

The workbook contains:

- **Run Summary**: the explicitly selected run day, generation/execution timestamp, JQL, issue totals, compliant/non-compliant issue counts, and PASS/FAIL/SKIP totals. The timestamp is retained only as audit evidence.
- **Issues**: one row per Jira item with Jira ID/key/link, summary, issue type, assignee, status, resolution, check totals, overall compliance, and a violation summary.
- **Linked Issues**: one row per verified or inaccessible linked target with parent ticket identity plus the linked Jira ID/key/link, summary, issue type, assignee, status/category, resolution, link direction/type, outcome, and reason.
- **Check Details**: one row per configured field or artifact prefix, plus `LINKED_ISSUE` rows for final-gate link checks and a `FINAL_GATE` roll-up row. Each row includes the rule key, Jira field/source, expected value, actual value, outcome, and reason.

An issue is `NON-COMPLIANT` when at least one of its checks fails. Before the final gate, `SKIP` is not a failure; on and after the configured final-gate day, every check must be `PASS`. The `FINAL_GATE` row is a roll-up for readability and is excluded from PASS/FAIL/check totals so an underlying violation is not counted twice.

## Operational use

A practical company rollout is:

1. Create a least-privilege Jira service account and keep its token in the CI secret store.
2. Review JQL, custom-field mappings, workflow resolution behavior, and attachment naming with Jira/process owners.
3. Version the non-secret policy YAML in the company's configuration repository.
4. Run or schedule the jar with an explicit `--audit.run-day` for each required checkpoint, such as Day 1, Day 3, Day 7, and the Day 10 final gate.
5. Archive each generated workbook in an access-controlled location. Exit code `2` can fail a CI compliance job.
6. Periodically review false positives, renamed Jira fields, workflow changes, and new process artifacts.

## Important limitations and next improvements

- Reports are current-state snapshots. Defensible historical Day 1 reconstruction requires Jira changelog processing or saving an immutable workbook on each scheduled day.
- The operator or scheduler must supply the correct sprint day; the application intentionally performs no calendar or date-based day calculation.
- A multi-select field passes when any actual value matches any allowed value; strict exact-set matching is not implemented.
- One global policy applies to all issues selected by the JQL. Rules conditional on project, team, component, or issue type are not implemented.
- Primary-ticket `when-resolved` rules and pre-final artifact gating use Jira's non-null `resolution`; linked Bug/Task closure uses the configured status-category keys. Jira workflows should maintain both consistently.
- Artifact checking covers Jira attachment filenames only, not content or external systems.
- The application does not schedule itself, send notifications, persist trend history, or provide a dashboard.

Those are the main areas to add after validating the core rules and workbook with a real company sprint.
