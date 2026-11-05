# Central scope contract V2

The existing SSO exchange and introspection assertions carry `userContext` from
IAM. The backend validates its subject, audience `TRUST`, authorization version,
scope tuples, menu actions and SQL filters on every request. The native
`localSubject` and organization mapping remain a separate authorization boundary.

Regional event, evidence, trace and task queries use ownership in immutable root
records. Historical records retain `LEGACY / UNASSIGNED`; organization codes do
not imply a project or a region. New event canonical documents and durable upload
receipts include their trusted tuple before persistence. Existing canonical
documents and retention history are never rewritten.

| HTTP operation | Menu | Required action |
|---|---|---|
| List/detail events | `TRUST:events` | `VIEW` |
| Read event processing history | `TRUST:events` | `VIEW`, native organization and immutable event ownership |
| Submit event | `TRUST:events` | `CHAIN_SUBMIT` |
| Append correction | `TRUST:events` | `EDIT` and `CHAIN_SUBMIT` |
| Upload evidence | `TRUST:events` | `UPLOAD` |
| Download evidence | `TRUST:events` | `DOWNLOAD` |
| Verify stored event | `TRUST:events` | `AUDIT_VERIFY` |
| Export evidence package | `TRUST:events` | `EXPORT` |
| Trace | `TRUST:trace` | `VIEW` |
| List/retry tasks | `TRUST:tasks` | `VIEW` / `CHAIN_SUBMIT` |

An explicitly authorized readonly user may verify, download and export. Verify
only queries existing ledger and file records and records the verification audit;
it never submits a ledger transaction. CSRF applies to these POST operations.
Other resources require their own scope adapters and reject V2 sessions until
adapted. Machine ingress retains its existing service identity contract and is
not granted a browser session's scope.

`GET /api/v1/me` returns `contextContractVersion=2`, `menuOperations`,
`centralAdminLevel` and `authorizationVersion` for validated V2 sessions only.
Its effective authority list distinguishes `trust:trace:read` from
`trust:event:read`; a trace-only grant cannot open the event ledger or details.
Clients refresh V2 access every 15 seconds and discard cached views when
authorization changes. Temporary transport failures do not create new grants;
each business request still revalidates its server session.

The existing `POST /audit/page-actions` and
`POST /audit/page-actions/{uuid}/result` endpoints remain available for the
authenticated actor's own client observation receipts, including readonly
navigation. Existing receipt ownership, idempotency and CSRF checks apply.
They confer no permission to read global audit data, configure delivery, or
execute the recorded business action. Every actual command remains separately
authorized.

Deploy the audited database increment before the application. This document
defines behavior; deployment and actual acceptance are recorded separately in
private operational evidence.
