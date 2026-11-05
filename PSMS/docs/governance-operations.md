# Governance operations in SSO mode

The native UI uses `/api/governance` for interlocks, offline packets, reports,
configuration versions and audit records. Existing read contracts remain available
at their original paths. Legacy interlock, offline and configuration write commands
are rejected in SSO mode; callers must migrate to the versioned adapter.

Apply `backend/sql/028_governance_operations.sql` before starting this version,
first in an independent database copy. Grant the application role SELECT, INSERT
and UPDATE on `public.interlock_requests`. Existing table permissions remain
unchanged. New work-area columns deliberately leave historical ownership unknown;
only a platform account with global data scope can read those records until an
authorized ownership migration is supplied. Do not assign all old rows to a
default work area.

Every adapter request requires the current platform session. Operations additionally
require the local business role, current central action permission, area ownership
and `X-PSMS-CSRF`. Reads use `pps:api:<resource>:read`; ordinary writes use
`:write`; approvals and conflict resolution use `:approve`; configuration publication
and rollback use `:publish`. Interlock requests share the `interlocks` permissions.
Export requires an explicit `pps:export` grant and report-read permission, or a
current platform super administrator. Read-only status always vetoes writes.

- `GET /interlocks`, `/interlock-requests`, `/offline`, `/settings`, `/audit-logs`
  support bounded pagination, exact object lookup and authorized work-area filters.
- `POST /interlocks/{id}` registers `requestOverride` or `requestReset` with a reason.
  `POST /interlock-requests/{id}` accepts `approve` or `cancel` with the expected
  version. The requester cannot approve the request. Approval records a software
  decision; it does not clear the actual interlock or claim device acknowledgement.
- `POST /offline/{id}` accepts `sync` with explicit terminal, work area, version,
  payload and reason. Identical retries return `duplicate: true`; identity changes
  conflict. A stale version preserves the accepted payload and records the incoming
  payload for conflict handling. `resolve` requires the expected server version,
  reason and `ACCEPT_LOCAL`, `ACCEPT_SERVER`, `MANUAL_MERGE` or `DISCARD`.
  Manual merge additionally requires the complete merged payload.
- `POST /settings` creates a scoped draft. `POST /settings/{id}` accepts `edit`,
  `submit`, `approve`, `publish`, `rollback` or `reopen`, with an expected version
  and reason. Submission and approval must use different identities. These
  endpoints maintain configuration versions; they do not execute an algorithm,
  control equipment or purge audit history.
- `GET /reports` and `POST /reports/export` use the same Beijing calendar-day and
  area filters. The end date is inclusive with an exclusive next-day boundary.
  Equipment reports describe current scoped equipment, rather than equipment
  created during the reporting period. Exports require a purpose.

Business changes and their audit insertion share a transaction. Unknown ownership,
database errors, invalid versions and invalid state transitions return explicit
errors. UI failures do not become successful empty collections or zero metrics.

The migration is additive. Roll back application artifacts and proxy configuration
independently, preserve the new tables and records, and disable affected entry
points if an older artifact lacks the required scope enforcement. Never truncate
business tables to undo verification.
