# SIHSALUS clinical audit OMOD

This module owns `POST /ws/rest/v1/sihsalus/audit` and the privileged review `GET` on the same path.

This repository is the source of truth for the module. The SIHSALUS distribution must consume a
released `sihsalusaudit-omod` version or, while a release is pending, build an immutable commit from
this repository. Module source code must not be vendored into the distribution repository.

## Build

The module targets Java 8 bytecode and is verified on Java 8 and Java 21:

```bash
mvn --batch-mode --show-version --no-transfer-progress clean verify
```

The deployable module is produced at `omod/target/sihsalusaudit-<version>.omod`.

## Deployment status

Building this OMOD does not make it production-ready. Promotion additionally requires a real
MariaDB migration/startup test, explicit role assignment for its two privileges, a retention policy,
and synthetic endpoint/RBAC/offline-replay smoke tests in a coordinated non-production environment.

- A request is an atomic JSON array of 1 to 50 events and at most 64 KiB.
- `id` is an idempotency UUID. Writes for the same actor are serialized with a database row lock, so simultaneous retries return the stored event instead of surfacing a uniqueness failure.
- Actor and receive time are always derived by the server. Client `userUuid` and `sessionId` are compatibility-only, ignored regardless of shape, and never persisted. A valid client `timestamp` is retained separately as non-authoritative `occurredAt`; malformed or database-incompatible client times are discarded rather than blocking an offline batch. `timestamp`/`receivedAt` in review remain the authoritative server receive time.
- Client occurrence time retains millisecond precision in the existing `datetime(3)` column. Hibernate maps an `Instant` field while the Java API continues to accept and return `Date`. This preserves the client claim through OpenMRS' global interceptor, which truncates `Date` fields to seconds. The field's custom Hibernate type binds a parameterized UTC string: Connector/J 8.0.30 interprets MariaDB's `5.5.5-10.11.7` compatibility handshake as a server without fractional-second support and truncates JDBC `Timestamp` parameters, even when `sendFractionalSeconds=true`. Identical retries remain idempotent; reusing the same actor/event ID with a timestamp differing by even 1 ms is a conflict. Server receive-time handling, the global interceptor and other JDBC mappings are unchanged.
- Event types, resource types, top-level fields, and metadata field names are explicit allowlists in `AuditPayloadParser`. Clinical event families require their patient/encounter target and receive a normalized resource type.
- `PRESCRIPTION_PRINT_REQUEST` requires both patient and encounter UUIDs and is normalized to the persisted `Encounter` that groups the medication orders. It records that the user requested the browser print flow; it does not claim that paper was physically printed and does not invent a `DocumentReference`.
- Client `PERMISSION_CHANGE` events are non-authoritative telemetry. Delayed ingestion intentionally does not recheck a current `Manage Roles` or `Edit Users` privilege because that would lose offline history without proving authorization at mutation time. Authoritative permission auditing still requires a server-side hook in the permission mutation transaction.
- Values for allowed metadata keys remain accepted within the 64 KiB request bound, but persistence is limited to an enumerated frontend `appName`, enumerated `outcome`, boolean `offline`, and UUID `locationUuid`. Free text, malformed values, and unknown machine values are discarded so they cannot poison an offline batch or become a durable PHI/secret channel. Review applies the same canonicalization to legacy rows and omits malformed metadata without changing the stored evidence.
- `Record Clinical Audit Events` permits ingestion. `View Clinical Audit Events` independently permits review.
- Stored rows are immutable through the Hibernate mapping and append/read service. MariaDB/MySQL update and delete triggers add database-level enforcement; missing triggers are recreated and an unexpected trigger body halts startup instead of silently accepting weaker protection. The migration identity needs permission to inspect and create triggers. An approved retention migration must explicitly replace those triggers before archival or deletion.
- Both `/audit` and `/audit/` are covered by the endpoint-only response sanitizer. Every success and error response carries `Cache-Control: no-store`. Responses stay buffered within a 512 KiB endpoint bound so a later error cannot expose a partial raw body. Unexpected failures retain diagnostic stack locations, but their potentially sensitive messages, causes, and suppressed exceptions are not copied into this endpoint's log entry.
- Unsupported methods on the audit root return sanitized `405` with `Allow`; POST requests without an `application/json` content type return sanitized `415`. Valid JSON parameters such as `charset=UTF-8` are accepted. Replay conflicts return sanitized `400` without passing an incomplete validation error to the generic REST handler.
- A bounded per-node limiter permits 20 requests per second per authenticated actor and returns `429` with `Retry-After`. An approved gateway or distributed limit remains required before multi-node production promotion.
- Liquibase uses one recoverable implicit-commit DDL operation per change set, validates MariaDB/MySQL's `PRIMARY` key name and column explicitly, rechecks exact append-only trigger actions on startup, and halts on a final schema validation mismatch.

The `sihsalusaudit.retentionDays` setting is intentionally empty and no automatic deletion runs. A clinical/legal owner must approve a retention duration and deletion/archive procedure before that lifecycle can be enabled.

The frontend still needs contract-side validation plus dead-letter/quarantine handling for any future rejected event shape. Until that work is delivered, a newly introduced incompatible field can still stall an atomic offline batch even though invalid client timestamps and long discarded error text no longer do.

The timestamp regression suite uses the production Hibernate mapping and DAO with OpenMRS' real
`DropMillisecondsHibernateInterceptor`. It runs locally with H2 and in CI with MariaDB 10.11.7 and
the deployed Connector/J 8.0.30, with both client and server prepared statements and a non-UTC JVM.
It covers first save, reload in a new session, identical replay, a 1 ms conflict, null client time,
and pre-existing second-precision rows. A direct JDBC control reproduces the driver's truncation
and confirms that a bound string retains the fraction. CI publication depends on these checks.
These isolated tests do not replace OpenMRS startup, endpoint/RBAC and browser offline acceptance.

The timestamp fix adds no column or data migration. Existing UTC `datetime(3)` rows remain readable,
including rows whose fractions were already lost; those fractions cannot be reconstructed and are
not rewritten. Rolling back the module preserves the schema and evidence, but restores the old
write-precision defect, so offline acceptance must remain blocked on the old version.

To run the MariaDB regression outside CI, provision a disposable local database named
`audit_timestamp_test`, with user `audit_test` restricted to that database. The suite creates and
drops its test tables. Supply its localhost port and test password through `AUDIT_TEST_DB_PORT`
and `AUDIT_TEST_DB_PASSWORD`, then run:

```bash
mvn --batch-mode --no-transfer-progress -pl api -am test \
  -Dtest=ClinicalAuditTimestampPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Daudit.test.mariadb=true -Daudit.test.serverPrepared=false
```

Repeat with `-Daudit.test.serverPrepared=true`. Never point this test at a clinical database.
