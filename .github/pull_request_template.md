## Summary

<!-- What changes and why? -->

## Audit and privacy contract

- [ ] Actor identity and authoritative receive time remain server-derived.
- [ ] Persisted event types, resource types, and metadata remain allowlisted.
- [ ] No PHI, credentials, or raw exception messages were added to logs or tests.
- [ ] Append-only schema and idempotency behavior remain fail-closed.

## Validation

| Status | Command | Scope |
| --- | --- | --- |
| NOT RUN | `mvn --batch-mode --show-version --no-transfer-progress clean verify` | API and OMOD |

## Deployment

- Production deployment: NOT AUTHORIZED
- MariaDB migration/startup smoke: NOT RUN
- DEV/QLTY endpoint, RBAC, and replay smoke: NOT RUN
