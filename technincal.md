# Jira Sprint Auditor — Technical Walkthrough and Architecture

## 1. Purpose of this document

This document explains the current Jira Sprint Auditor implementation from startup to shutdown. It is intended for developers, support engineers, administrators, and maintainers who need to understand the application, configure it, troubleshoot it, or change its behavior safely.

The document covers:

- the application architecture and runtime workflow;
- every production class and its responsibility;
- configuration binding and startup validation;
- Jira authentication, search, pagination, comments, and retries;
- Day 3 and Day 10 policy evaluation;
- Excel report creation;
- email composition and SMTP delivery;
- errors and process exit codes;
- automated tests; and
- practical recipes for making common code changes.

The implementation described here is the code currently present in this repository. The user-facing setup and execution guide remains in [README.md](README.md), while the annotated policy template is [config/company-audit.example.yml](config/company-audit.example.yml).

## 2. Application summary

Jira Sprint Auditor is a Java 21 Spring Boot command-line application. It executes one configured JQL query against self-hosted Jira Server/Data Center, evaluates each returned story against either the Day 3 or Day 10 company policy, and writes an `.xlsx` audit report containing one row per Jira ticket.

The application is read-only with respect to Jira. It reads issues, fields, attachments, linked issues, and comments. It never edits a Jira field, uploads an attachment, transitions an issue, or posts a comment.

The checkpoint is explicitly supplied as `3` or `10`. The application does not calculate a checkpoint from a date, sprint start date, calendar, or time zone.

The main input and output relationship is:

```text
company-audit.yml + environment variables + run-day
                         |
                         v
                  validated policy
                         |
                         v
                  configured JQL
                         |
                         v
              Jira issues and comments
                         |
                         v
               individual CheckResult objects
                         |
             +-----------+------------+
             |                        |
             v                        v
       Excel .xlsx report       mail preview / SMTP mail
```

## 3. Technology stack

The build is defined in [pom.xml](pom.xml).

| Technology | Purpose |
|---|---|
| Java 21 | Language and runtime |
| Spring Boot 3.5.7 | Dependency injection, configuration binding, application lifecycle |
| Spring `RestClient` | Jira REST API calls |
| Jackson | Jira JSON representation and traversal |
| Apache POI 5.5.1 | Excel `.xlsx` workbook generation |
| Spring Mail / Jakarta Mail | MIME message construction and SMTP delivery |
| Java2D / ImageIO | Team-wise pie chart generation without an external chart service |
| JUnit 5 and Spring Boot Test | Unit and integration-style automated tests |
| Maven | Build, test, and executable-JAR packaging |

The Maven Spring Boot plugin repackages the application and its dependencies into an executable JAR. Surefire runs tests with `java.awt.headless=true`, which allows chart tests to work on Linux servers without a graphical desktop.

## 4. Source tree and responsibility map

```text
jira-sprint-auditor/
├── pom.xml
├── README.md
├── technincal.md
├── config/
│   └── company-audit.example.yml
└── src/
    ├── main/
    │   ├── java/com/example/jiraaudit/
    │   │   ├── JiraAuditApplication.java
    │   │   ├── AuditRunner.java
    │   │   ├── AuditDay.java
    │   │   ├── AuditProperties.java
    │   │   ├── AuditConfigurationValidator.java
    │   │   ├── JiraClient.java
    │   │   ├── JiraSearchClient.java
    │   │   ├── PolicyEvaluator.java
    │   │   ├── IssueDetails.java
    │   │   ├── CheckResult.java
    │   │   ├── ExcelReportWriter.java
    │   │   ├── AuditMailer.java
    │   │   ├── SmtpAuditMailer.java
    │   │   ├── SmtpMailTransport.java
    │   │   └── SmtpMailTestApplication.java
    │   └── resources/
    │       └── application.yml
    └── test/java/com/example/jiraaudit/
        ├── AuditConfigurationValidatorTest.java
        ├── AuditPropertiesBindingTest.java
        ├── AuditRunnerTest.java
        ├── ExcelReportWriterTest.java
        ├── JiraAuditApplicationTest.java
        ├── JiraSearchClientTest.java
        ├── PolicyEvaluatorTest.java
        ├── SmtpAuditMailerTest.java
        ├── SmtpMailTransportTest.java
        └── TestAuditProperties.java
```

The principal architectural boundaries are:

| Layer | Classes | Responsibility |
|---|---|---|
| Bootstrapping | `JiraAuditApplication`, `AuditDay` | Start and stop Spring, create shared infrastructure, enforce supported checkpoint numbers |
| Configuration | `AuditProperties`, `AuditConfigurationValidator` | Bind external policy into typed records and reject unsafe/incomplete policies |
| Orchestration | `AuditRunner` | Coordinate the complete audit without implementing individual compliance decisions |
| Jira integration | `JiraClient`, `JiraSearchClient` | Retrieve complete issue and comment data from Jira Data Center |
| Policy engine | `PolicyEvaluator` | Convert Jira data and configuration into detailed PASS/FAIL checks |
| Domain result | `IssueDetails`, `CheckResult` | Carry normalized issue metadata and one compliance decision |
| Reporting | `ExcelReportWriter` | Aggregate checks into one ticket row and write the workbook atomically |
| Mail | `AuditMailer`, `SmtpAuditMailer`, `SmtpMailTransport` | Compose the summary, print the preview, and optionally deliver the MIME email |
| SMTP diagnostic | `SmtpMailTestApplication` | Test SMTP connectivity independently of Jira and the audit flow |

These boundaries are important when modifying the application. Jira transport concerns should stay in the Jira client, audit decisions should stay in the evaluator, and formatting should stay in the report or mail layer.

## 5. End-to-end architecture workflow

### 5.1 Successful execution sequence

```text
Operator / scheduler
       |
       | java -jar ... --audit.run-day=3|10
       v
JiraAuditApplication
       |
       | Spring binds application.yml + company-audit.yml + environment
       v
AuditProperties
       |
       | credentials validated while RestClient bean is built
       v
AuditRunner.run()
       |
       | complete policy validated
       | requested Jira field set assembled
       v
JiraSearchClient.searchAll()
       |
       | POST /rest/api/2/search until declared total is loaded
       v
For each Jira issue
       |
       +--> PolicyEvaluator.requiresComments()
       |         |
       |         +-- no evidence can require comments --> use empty list
       |         |
       |         +-- comments may be needed --> load and verify every comment page
       |
       +--> PolicyEvaluator.evaluate()
                 |
                 +-- field checks
                 +-- artifact checks
                 +-- conditional comment check
                 +-- Day-10 scenario-count check
                 +-- Day-10 linked-Bug checks
                 |
                 v
            List<CheckResult>
                 |
                 +--> ExcelReportWriter.write()
                 |         |
                 |         +-- one row per issue
                 |         +-- temporary file then atomic move
                 |
                 +--> SmtpAuditMailer.sendIfEnabled()
                           |
                           +-- always compose and print preview
                           +-- send only when audit.mail.enabled=true
                 |
                 v
        AuditRunner chooses exit code 0 or 2
                 |
                 v
        JiraAuditApplication closes Spring and exits
```

### 5.2 Why checks are represented individually

`PolicyEvaluator` does not directly produce an issue-level PASS or FAIL. It emits one `CheckResult` for every configured or structural check. For example, one issue can produce separate results for Coding Status, Revised SP, WBS, ATDD, UT, Scenario Count, the GWT comment, and each linked Bug.

That design provides three benefits:

1. the Excel remarks can explain every failure;
2. the mailer can count failed fields by responsible team; and
3. adding a new validation normally requires adding a new `CheckResult`, without redesigning the report contract.

An issue passes only when none of its `CheckResult` objects has outcome `FAIL`.

## 6. Configuration architecture

### 6.1 Configuration sources

[src/main/resources/application.yml](src/main/resources/application.yml) provides application defaults and imports an optional external policy file:

```yaml
spring:
  config:
    import: optional:file:./company-audit.yml
```

`./company-audit.yml` is resolved from the process working directory, not from the JAR location. In IntelliJ the working directory should be the project root. On a Linux VM, either start the JAR from the directory containing `company-audit.yml` or explicitly provide a different Spring configuration location.

Spring Boot combines configuration from YAML, environment variables, JVM system properties, and command-line options. A common pattern is:

- keep non-secret company rules in `company-audit.yml`;
- supply Jira and SMTP secrets through environment variables or a secret manager; and
- select the checkpoint using `AUDIT_RUN_DAY` or `--audit.run-day=3|10`.

The local `company-audit.yml`, generated reports, `.env`, IDE metadata, and Maven output are intentionally excluded from version control where applicable.

### 6.2 Typed configuration model

[AuditProperties.java](src/main/java/com/example/jiraaudit/AuditProperties.java) is annotated with:

```java
@ConfigurationProperties(prefix = "audit", ignoreUnknownFields = false)
```

Every property under `audit` is bound into an immutable Java record. `ignoreUnknownFields = false` is deliberate: misspelled or obsolete settings should stop startup instead of being silently ignored.

The top-level model is conceptually:

```text
AuditProperties
├── jira: Jira
├── jql: String
├── runDay: Integer
├── pageSize: Integer
├── checkpoints: Map<Integer, Checkpoint>
├── artifacts: Artifacts
├── report: Report
├── context: AuditContext
└── mail: Mail
```

The most important nested records are:

| Record | Important properties | Meaning |
|---|---|---|
| `Jira` | `baseUrl`, `personalAccessToken` | Jira Data Center endpoint and bearer PAT |
| `AuditContext` | `teamName`, `gaVersion`, `sprintPhase` | Human-readable report/email context |
| `Checkpoint` | `fields`, `linkedBugs`, `completionComment`, `atddScenarioCount` | Rules for one audit day |
| `FieldRule` | `label`, `jiraField`, `responsibleTeam`, `acceptedValues` | One configurable Jira field validation |
| `LinkedBugRule` | `issueType`, `resolvedStatuses` | Day-10 direct linked-issue policy |
| `CompletionCommentRule` | `fieldRule`, `completedValues`, `commentPrefix` | Conditional merged-feature comment rule |
| `ScenarioCountRule` | `label`, `jiraField`, `responsibleTeam` | Conditional ATDD Scenario Count field |
| `Artifacts` | `day3WbsPrefix`, `day10` | Day-specific artifact policy |
| `Day10Artifacts` | `wbsPrefix`, `cwc`, `ut`, `atdd`, `st` | Day-10 artifact definitions |
| `ArtifactRule` | `prefix`, `fieldRule`, `notApplicableValues`, `commentContains` | Attachment and Not Applicable evidence mapping |
| `Report` | `directory`, `failOnViolations`, `failOnEmpty` | Output and failure behavior |
| `Mail` | enable flag, envelope, subject, SMTP | Email behavior |
| `Smtp` | host, port, protocol, auth, credentials, STARTTLS | SMTP transport settings |

### 6.3 `$`-separated values

The following properties use one quoted string with `$` as the OR separator:

- field `accepted-values`;
- artifact `not-applicable-values`;
- linked-Bug `resolved-statuses`;
- completion-comment `completed-values`; and
- Day 3 and Day 10 WBS marker alternatives.

For example:

```yaml
accepted-values: 'Done - On Time$Not Applicable'
```

`AuditProperties.dollarSeparatedValues()` splits with `split("\\$", -1)`, trims each item, and preserves empty segments so that the validator can reject mistakes such as `Done$$Not Applicable`. Commas are ordinary content and remain inside a value:

```yaml
accepted-values: 'Not Applicable (POC, Backporting, NoCodeChange)$Suite Pass (90%)'
```

The `$` character is therefore reserved and cannot be part of a literal accepted value.

### 6.4 Non-empty field rule

`FieldRule.NON_EMPTY_VALUE` is the reserved marker `__NON_EMPTY__`.

```yaml
revised-sp:
  label: Revised SP
  jira-field: customfield_10105
  responsible-team: PMG
  accepted-values: '__NON_EMPTY__'
```

It means “accept any meaningful value.” It must be the only accepted value for that field. It passes text, numbers including zero, selected values, and arrays or objects containing meaningful content. It fails missing fields, Jira `null`, blank text, empty arrays, and empty objects.

### 6.5 SMTP property aliases

`application.yml` maps the company property names to the typed `audit.mail.smtp` structure. Supported inputs include:

| Typed purpose | Recognized external names |
|---|---|
| Host | `smtpHostip`, `SMTP_HOST_IP`, `mail.smtp.host`, `MAIL_SMTP_HOST` |
| Port | `smtpPort`, `SMTP_PORT`, `mail.smtp.port`, `MAIL_SMTP_PORT` |
| Protocol | `smtpProtocol`, `SMTP_PROTOCOL` |
| Authentication | `smptpAuthenticationRequired`, `smtpAuthenticationRequired`, `SMTP_AUTHENTICATION_REQUIRED` |
| Username | `smtpUsername`, `SMTP_USERNAME` |
| Password | `smtpPassword`, `SMTP_PASSWORD` |
| STARTTLS | `smtpStartTlsEnabled`, `SMTP_STARTTLS_ENABLED` |

The misspelled company name `smptpAuthenticationRequired` is intentionally supported for compatibility.

## 7. Startup and configuration validation

### 7.1 `JiraAuditApplication`

[JiraAuditApplication.java](src/main/java/com/example/jiraaudit/JiraAuditApplication.java) is the executable entry point.

Its responsibilities are:

1. start the Spring application context;
2. register `AuditProperties` for typed binding;
3. provide a UTC `Clock` bean used by reports and email;
4. construct the authenticated Jira `RestClient`;
5. retrieve the final exit code from `AuditRunner`; and
6. close Spring and call `System.exit`.

The application is configured as a non-web process, so it does not open an HTTP port or continue running after the audit.

The Jira `RestClient`:

- validates credentials before it is created;
- removes trailing slashes from the base URL and adds exactly one slash;
- sends `Authorization: Bearer <PAT>` on every Jira request;
- uses a 10-second connection timeout; and
- uses a 30-second read timeout.

Only Jira Server/Data Center bearer PAT authentication is used. Jira Cloud email/API-token Basic authentication is not implemented.

### 7.2 `AuditConfigurationValidator`

[AuditConfigurationValidator.java](src/main/java/com/example/jiraaudit/AuditConfigurationValidator.java) fails early when a policy is missing, inconsistent, or ambiguous. It has two entry points:

- `validateCredentials()` runs while the Jira `RestClient` bean is created;
- `validatePolicy()` runs at the start of `AuditRunner.run()`.

Credential validation requires a non-null `audit.jira`, an HTTPS URL with a host, and a nonblank personal access token.

Full policy validation verifies, among other things:

- JQL is present;
- `run-day` is exactly `3` or `10`;
- page size, when specified, is between 1 and 100;
- checkpoint keys are exactly `3` and `10`;
- report directory, GA version, and sprint phase are configured;
- every field has a key, Jira field ID, responsible team, and accepted values;
- Jira field IDs are not duplicated within a checkpoint;
- `$` lists contain no empty or duplicate alternatives where uniqueness is required;
- `__NON_EMPTY__` is not combined with another accepted value;
- Day 3 does not configure linked Bugs;
- Day 10 has a valid linked-Bug rule;
- Scenario Count is Day-10-only and does not duplicate a regular field ID;
- completion-comment points to a field rule in the same checkpoint;
- all mandatory artifact configurations exist;
- artifact field-rule names refer to real Day-10 rules;
- UT does not define its own field mapping because ATDD Status controls UT;
- WBS alternatives and artifact prefixes do not collide;
- mail addresses, subject, and SMTP values are valid when mail is enabled; and
- authenticated SMTP has a username and password.

Validation should be extended whenever a new configuration relationship is introduced. A new property that can be internally inconsistent should not rely on a later `NullPointerException` or an accidental runtime default.

### 7.3 `AuditDay`

[AuditDay.java](src/main/java/com/example/jiraaudit/AuditDay.java) centralizes supported checkpoint numbers. `CONFIGURED_DAYS` is `List.of(3, 10)`, and `policyDay()` returns the explicitly supplied day.

There is no date mapping. Adding a third checkpoint requires coordinated changes described later in this document; changing this list alone is insufficient.

## 8. Audit orchestration

### 8.1 `AuditRunner`

[AuditRunner.java](src/main/java/com/example/jiraaudit/AuditRunner.java) implements Spring Boot's `ApplicationRunner`, so Spring invokes it after the application context is ready.

Its `run()` method performs these steps in order:

1. Validate the complete policy.
2. Read `run-day` and default `page-size` to 100.
3. Start the requested Jira field set with `summary`, `assignee`, `status`, and `attachment`.
4. Add every `jira-field` configured for the selected checkpoint.
5. On Day 10, add `issuelinks` and the Scenario Count field when configured.
6. Print the declared day, policy day, and JQL.
7. Call `JiraClient.searchAll()` to load the entire JQL result.
8. Fail on an empty scope unless `audit.report.fail-on-empty=false`.
9. For each issue, ask `PolicyEvaluator.requiresComments()` whether comments could affect its result.
10. Load complete newest-first comments only when needed.
11. Call `PolicyEvaluator.evaluate()` and append all returned checks.
12. Write the Excel workbook.
13. Calculate issue-level PASS/FAIL and print failed checks.
14. Compose the mail preview and optionally send the message.
15. Set exit code 2 when at least one issue failed and `fail-on-violations` is not false.

`AuditRunner` deliberately depends on the `JiraClient` and `AuditMailer` interfaces. Tests can substitute small fake implementations without making Jira or SMTP calls.

### 8.2 Requested-field optimization

The application does not request all Jira fields. It requests the common metadata plus only the selected day's configured fields. Day 10 additionally requests linked issues and Scenario Count. This reduces response size and prevents the audit from depending on unrelated Jira data.

The corresponding maintenance rule is important: if a new evaluator check needs another Jira field, that field must also be added to `AuditRunner`'s requested field set. Adding evaluation logic without requesting its field will cause `<not returned>` or malformed-data failures.

## 9. Jira integration

### 9.1 `JiraClient` abstraction

[JiraClient.java](src/main/java/com/example/jiraaudit/JiraClient.java) defines the transport contract:

```java
List<JiraIssue> searchAll(String jql, Set<String> fields, int pageSize);
List<JiraComment> getCommentsNewestFirst(String issueKey, int pageSize);
```

`JiraIssue` carries Jira's ID, issue key, and the returned `fields` JSON object. `JiraComment` carries comment ID, body JSON, and parsed creation time.

Keeping raw JSON at this boundary avoids creating a large DTO hierarchy for custom fields whose shapes differ between Jira installations.

### 9.2 Issue search

[JiraSearchClient.java](src/main/java/com/example/jiraaudit/JiraSearchClient.java) implements `JiraClient` using Jira REST API v2.

For each issue-search page it sends:

```http
POST <base-url>/rest/api/2/search
Content-Type: application/json
Accept: application/json
Authorization: Bearer <PAT>
```

The JSON request contains:

```json
{
  "jql": "the configured query",
  "fields": ["summary", "assignee", "status", "attachment", "..."],
  "maxResults": 100,
  "startAt": 0
}
```

The client continues requesting offset pages until the number loaded equals Jira's declared `total`.

### 9.3 Fail-closed pagination

Partial issue or comment data could incorrectly produce a passing compliance report. Therefore the client rejects incomplete pagination instead of returning the pages it happened to receive.

Issue search checks that:

- an `issues` array exists;
- `startAt` and `total` are valid integers;
- the response offset equals the requested offset;
- the declared total remains stable across pages;
- Jira does not return an empty page before the total is reached;
- every issue has an ID, key, and fields object;
- issue IDs and keys are not duplicated across pages; and
- the loaded number never exceeds the declared total.

Any violation throws an exception containing “refusing a partial audit.” No report is presented as a complete audit from a truncated result.

### 9.4 Comment retrieval

Comments are requested with:

```http
GET <base-url>/rest/api/2/issue/{issueKey}/comment
    ?startAt=0&maxResults=100&orderBy=-created
```

The client loads every page and verifies:

- the comments array and pagination metadata;
- stable total and matching offsets;
- nonblank unique comment IDs;
- body and creation timestamp presence;
- no early empty page or total overrun; and
- global newest-first order across page boundaries.

Jira timestamps are accepted in standard ISO Instant form and Jira's common offset form such as `+0530`.

The evaluator scans the verified newest-first list. It may inspect more than the first comment; the first matching comment is the newest matching evidence.

### 9.5 Retry behavior

Both search and comment HTTP operations attempt a maximum of three times for:

- HTTP 429 rate limiting; and
- HTTP 5xx server errors.

The delay increases linearly: 500 ms after the first failure and 1,000 ms after the second. Other HTTP 4xx errors fail immediately. Interrupted retry sleep restores the thread's interrupt flag and stops the audit.

The current retry implementation responds to HTTP response exceptions. Low-level connection failures are surfaced immediately by `RestClient`; adding retries for those failures should be done carefully to avoid hiding certificate, DNS, or permanent network problems.

## 10. Domain result model

### 10.1 `IssueDetails`

[IssueDetails.java](src/main/java/com/example/jiraaudit/IssueDetails.java) is an immutable record containing:

- internal Jira ID;
- Jira key;
- summary;
- assignee display name; and
- workflow status.

The normalized metadata is repeated in each `CheckResult` for convenient downstream processing.

### 10.2 `CheckResult`

[CheckResult.java](src/main/java/com/example/jiraaudit/CheckResult.java) represents one audit decision:

| Property | Meaning |
|---|---|
| `issue` | Normalized issue metadata |
| `checkType` | Category such as `FIELD`, `ARTIFACT`, `COMMENT`, or linked-issue check |
| `ruleKey` | Stable configuration/code identifier |
| `checkName` | Human-readable report label |
| `source` | Jira field, attachment, comment, or linked-issue source |
| `expected` | Expected values/evidence |
| `actual` | Actual observed values/evidence |
| `outcome` | `PASS` or `FAIL` |
| `reason` | Human-readable explanation |
| `responsibleTeam` | Owning team for field failures, otherwise normally blank |

The responsible team is reporting metadata. It never changes the outcome.

When a new check is added, its `ruleKey` should be stable, its expected and actual values should be useful for troubleshooting, and its reason should be understandable without reading the code.

## 11. Policy evaluation engine

### 11.1 Evaluation entry point

[PolicyEvaluator.java](src/main/java/com/example/jiraaudit/PolicyEvaluator.java) contains the compliance rules.

`evaluate(issue, properties, runDay, comments)` performs the following work:

1. verify essential Jira metadata;
2. build `IssueDetails`;
3. evaluate the selected checkpoint's configured fields;
4. evaluate Day 3 or Day 10 artifacts;
5. evaluate the conditional completion comment;
6. on Day 10, evaluate Scenario Count; and
7. on Day 10, evaluate directly linked Bugs.

The evaluator is stateless. All behavior comes from the supplied issue JSON, policy, day, and comments, which makes it easy to test.

### 11.2 Essential metadata validation

Before compliance rules run, the evaluator requires Jira to have returned:

- a fields object;
- `summary` as nonblank text;
- `status` as an object with a nonblank name;
- `assignee`, which may be null for an unassigned issue or an object with `displayName`; and
- `attachment` as a returned field.

Missing or structurally invalid essential data stops the run. This is different from a normal policy failure: the application refuses to claim compliance when it cannot identify or describe the issue reliably.

### 11.3 Jira value flattening

Jira custom fields can be text, numbers, selections, users, arrays, or objects. `flattenValues()` converts these shapes into comparable strings:

- arrays are recursively flattened;
- object properties are preferred in this order: `value`, `name`, `displayName`, `id`;
- an unrecognized object falls back to its JSON text;
- scalar values use their text representation; and
- null produces no values.

Accepted-value comparisons trim text and ignore case. They are equality comparisons, not substring matches.

### 11.4 Regular configured fields

For every field under the selected checkpoint:

1. look up the configured Jira field ID in the response;
2. flatten its value;
3. if `__NON_EMPTY__` is configured, test meaningful content recursively;
4. otherwise pass when any actual value equals any accepted value, ignoring case;
5. create a `FIELD` `CheckResult`; and
6. copy the rule's `responsible-team` into the result.

The logic is OR logic. A field with `Done - On Time$Not Applicable` passes when either value matches. It does not require both values.

`label` is optional in the current Java validator. When it is blank, the configuration key is used as the display label. Supplying a clear label is strongly recommended because it appears in report remarks and team summaries.

A field omitted from Jira's response is shown as `<not returned>` and fails. A returned but empty field is shown as `<empty>` or `<blank>` where applicable.

### 11.5 Day 3 rules

Day 3 is intentionally small:

| Check | Rule |
|---|---|
| Configured fields | Each Day-3 field must match one accepted value or its non-empty rule |
| WBS | At least one attachment filename must contain any configured Day-3 WBS marker |
| CWC, UT, ATDD, ST | Not evaluated |
| Linked Bugs | Not evaluated and prohibited in Day-3 configuration |
| Scenario Count | Not evaluated |

WBS matching is case-insensitive `contains`, not strict prefix matching. A configured marker `WBS` accepts `WBS_file.xlsx`, `WBS-file.xlsx`, and `IAD_WBS_some_name.xlsx`.

### 11.6 Day 10 rules overview

| Check | Current rule |
|---|---|
| Configured fields | Each Day-10 field must match one accepted value or non-empty rule |
| WBS | Mandatory attachment; cannot be waived by a comment |
| CWC | Controlled by Coding Status or another configured field rule |
| ST | Controlled by ST Status or another configured field rule |
| ATDD | Controlled by ATDD Status; applicable requires attachment |
| UT | Controlled indirectly by ATDD Status |
| Scenario Count | Required only when ATDD Status is applicable |
| Completion comment | Required only when mapped field has a configured completed value |
| Linked Bugs | Every directly linked matching issue type must have a resolved status |

### 11.7 Attachment-name extraction and matching

Jira's `attachment` field must be an array. The evaluator reads each element's `filename`.

- WBS uses case-insensitive `contains` against every configured marker.
- CWC, UT, ATDD, and ST use case-insensitive `startsWith` against their configured prefix.

Artifact contents are never downloaded or inspected. Only Jira attachment filenames are evaluated.

A malformed or missing attachment array produces artifact failures rather than silently acting as “no attachments.”

### 11.8 WBS

Day-10 WBS is always mandatory. It passes when at least one filename contains one of the configured WBS alternatives. A Jira comment cannot waive WBS.

### 11.9 CWC and ST state table

CWC and ST use the same `configurableArtifact()` behavior. Each artifact maps to a configured Day-10 field rule and a configurable list of Not Applicable values.

| Mapped field state | Attachment state | Comment state | Result |
|---|---|---|---|
| Not Applicable | absent | any | PASS — artifact correctly omitted |
| Not Applicable | present | any | FAIL — artifact should not be attached |
| Applicable | present | any | PASS |
| Applicable | absent | matching configured phrase exists | PASS with comment evidence |
| Applicable | absent | no matching phrase | FAIL |

Comment matching is case-insensitive and searches the full verified newest-first history. The newest matching comment ID/text is recorded as evidence.

### 11.10 ATDD and UT state table

ATDD Status is the controlling field for both ATDD and UT.

| ATDD Status | ATDD artifact | UT artifact/comment | Result |
|---|---|---|---|
| Applicable | present | optional | ATDD and UT checks pass |
| Applicable | absent | any | FAIL — ATDD attachment is mandatory |
| Not Applicable | present or absent | UT present | PASS |
| Not Applicable | present or absent | UT absent but UT Not Applicable comment exists | PASS |
| Not Applicable | present or absent | UT absent and no comment | FAIL |

Important consequences:

- a comment cannot replace a required ATDD attachment;
- UT is optional when ATDD Status is applicable;
- UT evidence is required when ATDD Status is Not Applicable;
- ATDD is optional in the Not Applicable path; and
- both ATDD and UT are allowed to be attached together.

UT intentionally has no separate `field-rule` or `not-applicable-values`. The configuration validator rejects those properties because ATDD Status owns the decision.

### 11.11 Scenario Count

The optional `atdd-scenario-count` rule is a special Day-10 field check. It uses the ATDD artifact rule's mapped status field and `not-applicable-values`.

| ATDD Status | Scenario Count | Result |
|---|---|---|
| Not Applicable | missing, null, blank, empty, or populated | PASS |
| Applicable | meaningful value, including numeric `0` | PASS |
| Applicable | missing, null, blank, empty array, or empty object | FAIL |

Scenario Count creates a `FIELD` result and therefore contributes to the Responsible Team column and team-wise email statistics.

### 11.12 Conditional GWT completion comment

The completion-comment rule is generic even though the current example uses GWT Status.

```yaml
completion-comment:
  field-rule: gwt-status
  completed-values: 'Completed$Done'
  comment-prefix: 'Merged feature file link:'
```

When the mapped field does not match a completed value, no comment check is added. When it does match, at least one comment must contain the configured prefix followed by a non-empty value.

The evaluator supports both plain Jira comment bodies and Atlassian Document Format. For ADF it recursively collects `text` nodes under `content`. It normalizes whitespace before matching.

Examples:

```text
Merged feature file link: https://git.company/features/PAY-102   -> PASS
Merged feature file link: feature/PAY-102                        -> PASS
Merged feature file link:                                        -> FAIL
```

The application validates the comment only; it does not add the comment to Jira.

### 11.13 Linked-Bug validation

On Day 10 the evaluator inspects `issuelinks`. For each inward or outward linked issue:

1. verify the linked issue has a key and fields object;
2. read its issue type;
3. ignore it when its type does not match the configured type, normally `Bug`;
4. de-duplicate the matching linked issue key;
5. read its status; and
6. pass only when the status matches one configured resolved status, ignoring case.

An unresolved matching Bug creates a failure on the parent story containing the Bug key and current status. Missing type or status data fails closed because the application cannot prove that the link is compliant.

When there are no matching Bugs and no malformed links, the evaluator emits a passing “No linked Bugs” check.

### 11.14 Comment-fetch optimization

`requiresComments()` avoids one Jira comments request per issue when comments cannot affect that issue's result.

It requests comments when:

- the completion-comment trigger field has a completed value;
- an applicable CWC attachment is absent;
- an applicable ST attachment is absent; or
- ATDD Status is Not Applicable and UT is absent.

It does not request comments merely because ATDD is missing while applicable, because an ATDD comment cannot waive that failure. It also avoids comments for Day 3 unless a configured completion-comment rule is triggered.

When adding a comment-dependent rule, update both the evaluation method and `requiresComments()`. Forgetting the optimization predicate would cause the evaluator to receive an empty comment list and incorrectly fail evidence that actually exists in Jira.

## 12. Excel report generation

### 12.1 `ExcelReportWriter`

[ExcelReportWriter.java](src/main/java/com/example/jiraaudit/ExcelReportWriter.java) uses Apache POI to create one sheet named `Audit Results`.

The filename format is:

```text
AUDIT_<GA_VERSION>_<SPRINT_PHASE>_DAY<DAY>_<UTC_TIMESTAMP>_<RANDOM>.xlsx
```

For example:

```text
AUDIT_9.5-GA_Sprint-5_DAY10_20261025-063000-000_a1b2c3d4.xlsx
```

GA version and sprint phase are sanitized to letters, numbers, dot, underscore, and hyphen, with a maximum of 60 characters per segment. A random suffix prevents collisions when concurrent or rapid runs share a timestamp.

### 12.2 Atomic report writing

The writer:

1. creates the output directory when necessary;
2. creates a temporary file in that same directory;
3. writes and closes the entire workbook;
4. atomically moves the completed file to the final name when the filesystem supports it;
5. falls back to a normal move when atomic move is unavailable; and
6. deletes any leftover temporary file in `finally`.

This prevents consumers from observing a partially written file under the final report name.

### 12.3 One-row-per-issue aggregation

`PolicyEvaluator` produces many checks per issue, but the writer groups them by internal Jira ID and creates one row per issue.

| Column | Source/logic |
|---|---|
| Run Day | Execution checkpoint |
| Jira ID | Internal Jira ID |
| Jira Key | Clickable link to `<base-url>/browse/<key>` |
| Summary | Jira summary |
| Assignee | Display name or `Unassigned` |
| Jira Status | Workflow status |
| Audit Result | PASS when no grouped check failed; otherwise FAIL |
| Responsible Team | Failed `FIELD` checks grouped by team |
| Remarks | Detailed failures or accepted evidence summary |

Responsible Team includes only failed checks whose `checkType` is `FIELD`. Artifact, comment, metadata, and linked-Bug failures normally show `N/A` unless the same issue also has failed field checks. Scenario Count is a field check and is included.

Failure remarks concatenate each failed check's label, reason, expected evidence, and actual evidence. Pass remarks normally say `All applicable checks passed`; if an absent artifact was accepted through Not Applicable evidence, that evidence is summarized.

The sheet freezes the header row, enables an auto-filter, applies fixed readable column widths, wraps long fields, colors PASS green and FAIL rose, and truncates any cell that exceeds Excel's 32,767-character limit.

## 13. Email architecture

### 13.1 Interfaces and data contract

[AuditMailer.java](src/main/java/com/example/jiraaudit/AuditMailer.java) defines:

- `AuditMailer`, used by `AuditRunner`;
- `AuditEmail`, the fully composed subject/body/chart/attachment value; and
- `AuditMailTransport`, the delivery boundary.

Separating composition from transport lets tests inspect the email without connecting to SMTP.

### 13.2 `SmtpAuditMailer`

[SmtpAuditMailer.java](src/main/java/com/example/jiraaudit/SmtpAuditMailer.java) transforms check results into a human-readable mail.

It calculates:

- total, passed, and failed tickets;
- total configured field checks and failed field checks;
- non-field failure count and affected ticket count;
- distinct pending tickets per responsible team;
- pending field count per responsible team; and
- up to 50 failed-ticket detail rows.

It produces both plain text and escaped HTML. The HTML includes an inline PNG pie chart whose slices represent failed fields by responsible team. The chart is generated locally with Java2D, so no issue data is sent to a chart website.

The full Excel workbook is attached.

### 13.3 Preview and enable flag

`audit.mail.enabled` controls SMTP delivery:

```yaml
audit:
  mail:
    enabled: false
```

The mailer still composes and prints the resolved subject, full plain-text body, delivery state, and attachment path when delivery is disabled. This makes a production-like message available in console logs before SMTP is enabled.

When enabled, `AuditConfigurationValidator` additionally requires valid sender/recipient addresses, subject, host, port, protocol, and authentication credentials where applicable.

If enabled delivery fails:

- `fail-on-error: true` throws an exception after the Excel report is preserved;
- `fail-on-error: false` prints a warning and retains the normal audit outcome.

### 13.4 `SmtpMailTransport`

[SmtpMailTransport.java](src/main/java/com/example/jiraaudit/SmtpMailTransport.java) creates a `JavaMailSenderImpl` for each send.

It configures:

- `smtp` or `smtps` protocol;
- host and port;
- optional username/password authentication;
- optional STARTTLS;
- 10-second connection timeout;
- 30-second read and write timeouts; and
- implicit SSL for `smtps`.

The MIME message is `multipart/mixed-related` and contains:

1. plain-text alternative;
2. HTML alternative;
3. inline `image/png` chart referenced by content ID; and
4. `.xlsx` attachment with the correct OpenXML spreadsheet media type.

### 13.5 Standalone SMTP diagnostic

[SmtpMailTestApplication.java](src/main/java/com/example/jiraaudit/SmtpMailTestApplication.java) has a separate `main()` method for testing SMTP without starting the audit or contacting Jira.

It reads the supported company properties from JVM system properties or environment variables, builds the same transport configuration, and sends a small text message. It prints connection parameters without printing the password. On failure it prints the root error and exits 1.

Use this program when separating an SMTP connectivity/certificate/authentication problem from audit logic.

## 14. Error model and exit behavior

There are two different kinds of negative result:

1. **Compliance failure:** Jira data is complete and valid, but one or more checks fail. A report is written and the process normally exits 2.
2. **Technical failure:** configuration, authentication, Jira response completeness, file writing, email preparation, or required SMTP delivery fails. The run terminates with an exception and a nonzero process status, normally 1 from the Java process/Spring startup path.

Normal audit exit behavior is:

| Condition | Result |
|---|---|
| All issues pass | Exit 0 |
| Some issue fails and `fail-on-violations` is true or omitted | Report written, exit 2 |
| Some issue fails and `fail-on-violations=false` | Report written, exit 0 |
| JQL returns no issues and `fail-on-empty` is true or omitted | Technical failure; no misleading empty audit |
| JQL returns no issues and `fail-on-empty=false` | Empty workbook/summary allowed |
| Invalid policy or credentials | Startup/run failure |
| Incomplete Jira pagination or malformed mandatory data | Technical failure; partial audit refused |
| Email enabled, send fails, `fail-on-error=true` | Report preserved, technical failure |
| Email enabled, send fails, `fail-on-error=false` | Warning; keep audit's exit result |

Schedulers should distinguish exit 2, meaning the audit executed and found violations, from a technical execution failure.

## 15. Security and operational characteristics

### 15.1 Jira permissions

The PAT owner must be able to view:

- every issue in the JQL scope;
- the configured custom fields;
- attachment filenames;
- comments when evidence is needed; and
- linked issue type/status data.

Insufficient field or linked-issue visibility may cause a fail-closed technical or compliance result rather than silently passing.

### 15.2 Secret handling

Do not put a Jira PAT or SMTP password into committed YAML. Prefer environment variables, protected systemd environment files, a VM secret store, or another company-approved secret mechanism.

The Jira PAT is placed only in the Authorization header. It is not written into the Excel report or email content by application code. Nevertheless, JVM command-line system properties may be visible to local process-list users, so environment or secret-file injection is usually safer than `-DsmtpPassword=...` on shared systems.

### 15.3 TLS

Jira base URL must use HTTPS. The Java 21 runtime must trust the company certificate authority. SMTP TLS behavior depends on `smtpProtocol` and `smtpStartTlsEnabled`.

### 15.4 Data contained in outputs

The report and mail may contain Jira keys, summaries, assignees, statuses, policy failures, and comment-derived evidence. Store reports and logs according to company data-retention and access policies.

## 16. Automated test suite

This repository does not currently contain a Maven wrapper. Run all tests with an installed Maven 3.9+:

```bash
mvn test
```

The test classes cover these areas:

| Test class | Main coverage |
|---|---|
| `AuditConfigurationValidatorTest` | Days, exact checkpoint set, fields, non-empty marker, artifacts, prefix uniqueness, linked Bugs, completion comments, Scenario Count, mail validation |
| `AuditPropertiesBindingTest` | YAML/property binding, explicit run day, Jira environment mapping, dotted SMTP aliases, sample configuration, rejection of obsolete date settings |
| `AuditRunnerTest` | Day-specific requested fields, comment-fetch decisions, missing day, exit code 2, empty-JQL behavior, workbook orchestration |
| `ExcelReportWriterTest` | One-row grid, PASS/FAIL, remarks, responsible teams, workbook shape |
| `JiraAuditApplicationTest` | Bearer PAT authentication and no unintended authentication mechanism |
| `JiraSearchClientTest` | Context-path URLs, issue pagination, duplicate/truncated pages, comment pagination, newest-first order |
| `PolicyEvaluatorTest` | Day 3/10 fields, WBS alternatives, ATDD/UT paths, CWC/ST comments, linked Bugs, `$` splitting, non-empty fields, GWT comments, Scenario Count, malformed metadata |
| `SmtpAuditMailerTest` | Disabled preview, enabled summary/chart/attachment, team counts, failure policy |
| `SmtpMailTransportTest` | Company SMTP settings and multipart MIME construction |
| `TestAuditProperties` | Reusable valid policy fixture for other tests |

### 16.1 Testing rule changes

For a policy behavior change, tests should normally be added at three levels:

1. validator tests for accepted and rejected configuration;
2. evaluator tests for PASS and FAIL decision paths; and
3. runner/report/mail tests if requested fields or externally visible output changes.

Tests should include the normal path, the inverse path, missing/malformed Jira data, and any Not Applicable or comment-waiver branch.

## 17. How to make common changes

### 17.1 Add or change a normal audited field without Java changes

Most field changes are configuration-only.

1. Find the Jira custom-field ID, for example `customfield_12345`.
2. Add the rule under `audit.checkpoints.3.fields` and/or `.10.fields`.
3. Set a human-readable label.
4. Set the responsible team.
5. Configure accepted values using `$`, or use only `__NON_EMPTY__`.
6. Run configuration-binding/validator tests and a controlled Jira audit.

Example:

```yaml
design-status:
  label: Design Status
  jira-field: customfield_12345
  responsible-team: ARCHITECTURE
  accepted-values: 'Approved$Not Applicable'
```

`AuditRunner` automatically requests configured regular field IDs, and `PolicyEvaluator` automatically evaluates them.

### 17.2 Change accepted or Not Applicable values

Edit the quoted `$`-separated string in `company-audit.yml`. Java code is not required when only company wording changes.

```yaml
accepted-values: 'Done - On Time$Done - Delayed$Not Applicable'
not-applicable-values: 'Not Applicable$No Testing Required'
```

Remember that comparisons are exact after trimming and case normalization. `Done` does not match `Done - On Time`.

### 17.3 Change team ownership

Edit `responsible-team` on the field rule or Scenario Count rule. This changes the Excel Responsible Team column, mail counts, and pie chart grouping. It does not change PASS/FAIL.

### 17.4 Add a new generic conditional field check

If the rule cannot be represented by normal accepted values:

1. Add a typed rule record/property in `AuditProperties`.
2. Add semantic validation in `AuditConfigurationValidator`.
3. Add the necessary Jira field to `AuditRunner.requestedFields`.
4. Implement a focused helper in `PolicyEvaluator` that emits a `CheckResult`.
5. Decide whether the check is a `FIELD` check and should carry a responsible team.
6. Add PASS, FAIL, empty, and malformed-data tests in `PolicyEvaluatorTest`.
7. Add runner tests proving the new Jira field is requested.
8. Update the example YAML, README, and this document.

Scenario Count is the best existing example of this pattern.

### 17.5 Add a new comment-dependent validation

1. Add typed configuration and validator rules.
2. Implement the actual check in `PolicyEvaluator.evaluate()`.
3. Update `PolicyEvaluator.requiresComments()` with the same trigger conditions.
4. Reuse `commentText()` for plain/ADF bodies and scan the newest-first list.
5. Decide whether a prefix needs a non-empty suffix or only phrase containment.
6. Add tests for comments on later pages and newest matching evidence where relevant.

The crucial step is updating `requiresComments()`; otherwise production evaluation may never retrieve the evidence.

### 17.6 Add a new artifact type

A new artifact is a cross-cutting change:

1. Add it to `Day10Artifacts` or another appropriate typed configuration record.
2. Add required fields and relationship validation.
3. Decide filename semantics: `contains`, `startsWith`, or a new explicit matcher.
4. Add evaluation in `addArtifactChecks()`.
5. If comments can waive it, update `requiresComments()`.
6. Decide and document its Not Applicable state table.
7. Ensure its prefix cannot ambiguously collide with existing prefixes.
8. Add configuration, evaluator, runner, report, and mail tests as needed.
9. Update the annotated sample configuration and documentation.

Do not reuse the generic CWC/ST method unless the new artifact truly has the same state table.

### 17.7 Change ATDD/UT logic

Review all of these together:

- `atddArtifact()`;
- `utArtifact()`;
- `addAtddScenarioCountCheck()`;
- `requiresComments()`;
- ATDD/UT validation in `AuditConfigurationValidator`;
- ATDD/UT and Scenario Count tests in `PolicyEvaluatorTest`; and
- the sample YAML comments and decision tables.

These rules share one controlling field. A change in the meaning of ATDD applicability can affect three separate checks and comment retrieval.

### 17.8 Add a new checkpoint day

Adding Day 7, for example, is not a one-line configuration change. At minimum:

1. Add `7` to `AuditDay.CONFIGURED_DAYS`.
2. Update exact checkpoint validation.
3. Decide which nested checkpoint rules are legal/required on Day 7.
4. Update `AuditRunner` requested-field logic.
5. Implement Day-7 artifact and conditional policy behavior in `PolicyEvaluator`.
6. Update comment-fetch conditions.
7. Ensure report filename and labels handle the new day.
8. Add binding, validator, runner, evaluator, and end-to-end tests.
9. Update sample configuration and operating documentation.

Do not map a day from a calendar unless the product requirement explicitly reintroduces date-based behavior.

### 17.9 Change Excel columns or detail level

Modify `ExcelReportWriter.writeGrid()`:

- update the `headers` array;
- populate the corresponding cell for every row;
- update the `widths` array to the same column count;
- update `ExcelReportWriterTest`; and
- consider whether the value belongs at issue level or check level.

The current workbook is deliberately one row per issue. Producing one row per check would require changing the grouping loop and user-facing report contract.

### 17.10 Change report naming

Modify the final path construction and, if needed, `filePart()` in `ExcelReportWriter.write()`. Preserve sanitization, uniqueness, the `.xlsx` extension, temporary-file writing, and final move behavior.

### 17.11 Change email subject or content

- The subject normally comes from `audit.mail.subject`.
- The fallback subject is in `SmtpAuditMailer.mailSubject()`.
- Plain text is built in `plainBody()`.
- HTML is built in `htmlBody()` and related table helpers.
- Team calculations are in `teamStats()`.
- Chart drawing is in `pieChart()`.
- MIME assembly is in `SmtpMailTransport.createMessage()`.

Always escape new dynamic HTML values with the existing `escape()` helper. Keep plain and HTML statistics aligned, and update `SmtpAuditMailerTest`.

### 17.12 Add an SMTP alias

For the main application, extend the placeholder chain in `src/main/resources/application.yml`. For consistent standalone testing, add the same name to `SmtpMailTestApplication.value(...)`. Add or update `AuditPropertiesBindingTest`.

### 17.13 Change Jira endpoints or API version

Make endpoint and response-shape changes in `JiraSearchClient`. Preserve:

- the bearer Authorization header configured centrally;
- base URL context paths;
- requested-field minimization;
- complete pagination checks;
- retry classification; and
- newest-first comment guarantees.

Update `JiraSearchClientTest` with mock HTTP responses before using the change against company Jira.

## 18. Troubleshooting by execution stage

### 18.1 Error: `audit.jira is required`

Spring did not bind the expected Jira configuration. Confirm:

- `JIRA_BASE_URL` and `JIRA_PERSONAL_ACCESS_TOKEN` are present in the actual process environment;
- IntelliJ environment variables are on the `JiraAuditApplication` run configuration, not only in a terminal;
- the working directory and active configuration are correct; and
- no misspelled `audit` property exists, because unknown fields are rejected.

### 18.2 Policy-file values appear to be ignored

Confirm `company-audit.yml` is in the process working directory. The import is `./company-audit.yml`. Print or inspect the working directory used by IntelliJ, systemd, cron, or the shell.

### 18.3 A field reports `<not returned>`

Check:

- the configured ID is the Jira field ID, not its display name;
- the PAT user can see the field;
- the new special-purpose field was added to `AuditRunner`'s requested set; and
- the field is available for the issue type/project.

### 18.4 Accepted values containing commas match incorrectly

Use one quoted string and `$` separators. Commas are literal content:

```yaml
accepted-values: 'Not Applicable (POC, Backporting, NoCodeChange)$Suite Pass (90%)'
```

### 18.5 WBS filename does not pass

WBS uses case-insensitive containment. Verify the configured marker has no accidental characters or empty `$` alternatives. Other artifacts use prefix matching, so a CWC filename must begin with its CWC prefix.

### 18.6 A comment exists but is not accepted

Confirm:

- the configured phrase/prefix matches after whitespace normalization;
- a completion prefix has non-empty text after it;
- the relevant attachment is actually absent and the field state allows a comment waiver;
- the rule is not WBS or applicable ATDD, which comments cannot waive;
- the PAT can read every comments page; and
- Jira returned the history newest-first.

### 18.7 Report exists but process exits 2

This is normal compliance behavior. Read the FAIL rows and Remarks. Set `fail-on-violations=false` only when the calling process should not treat violations as a failing process status.

### 18.8 Jira works in a browser but the application fails TLS

The Java runtime may not trust the company CA even when the operating-system browser does. Import the approved CA chain into the JVM trust configuration or use the company's standard Java truststore deployment. Do not disable HTTPS validation in code.

### 18.9 SMTP fails after the report is written

Use `SmtpMailTestApplication` to isolate host, port, protocol, authentication, STARTTLS, DNS, firewall, and certificate problems. If audit delivery must be best-effort, configure `fail-on-error=false`; the report is already preserved before SMTP runs.

## 19. Known constraints and deliberate design choices

- Only Day 3 and Day 10 are supported.
- JQL is the full audit scope; the application does not discover a sprint or GA version automatically.
- Team, GA version, and sprint phase are explicit metadata and are not parsed from JQL.
- Jira is read-only.
- Only directly linked issues are checked; links are not traversed recursively.
- Artifact checks use filenames only, not file contents.
- WBS uses marker containment; other artifacts use strict prefix matching.
- Comments can waive only the rules explicitly designed to accept comment evidence.
- The report is one row per ticket, not one row per individual check.
- No database or audit-history store exists; generated workbooks are the snapshots.
- No internal scheduler exists; cron, systemd timer, CI, or another scheduler must launch the JAR.
- Jira calls are sequential. This is simpler and avoids unexpected load, but very large scopes can take longer, especially when many tickets require comments.
- Email ticket detail is capped at 50 failed tickets; the attachment remains complete.
- The mail preview is generated for both enabled and disabled delivery.

## 20. Safe-change checklist

Before merging a functional change:

1. Identify the correct architectural layer.
2. Add typed configuration rather than parsing arbitrary YAML inside business logic.
3. Add startup validation for new required values and cross-references.
4. Ensure every required Jira field is requested by `AuditRunner`.
5. Update `requiresComments()` for any comment-dependent rule.
6. Emit a precise `CheckResult` with stable key, expected value, actual value, and reason.
7. Preserve fail-closed behavior for missing or partial Jira data.
8. Preserve read-only Jira behavior unless the product scope is explicitly changed.
9. Add PASS, FAIL, Not Applicable, empty, and malformed-data tests.
10. Update the annotated example policy.
11. Update README user guidance and this architecture document.
12. Run the complete test suite.
13. Build the executable JAR.
14. Perform a controlled Day-3 and/or Day-10 run against non-production or a known Jira scope.
15. Inspect the Excel output and console mail preview before enabling SMTP.

## 21. Build and verification commands

Typical local verification is:

```bash
mvn clean test
mvn clean package
```

Run a checkpoint with the external policy in the current directory:

```bash
export JIRA_BASE_URL='https://jira.company.example/jira'
export JIRA_PERSONAL_ACCESS_TOKEN='replace-with-secret'
java -jar target/jira-sprint-auditor-1.0.0.jar --audit.run-day=3
java -jar target/jira-sprint-auditor-1.0.0.jar --audit.run-day=10
```

The complete editable policy example is [config/company-audit.example.yml](config/company-audit.example.yml). Start there for company-specific field IDs, values, teams, artifact prefixes, comments, linked-Bug statuses, report settings, and optional email settings.

