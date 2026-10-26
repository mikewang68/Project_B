# Trusted SSO adapter (IAM contract 1.1)

This adapter exchanges an IAM-issued one-time ticket for an ordinary WMS servlet
session. WMS holds only its audience-specific service credential. IAM performs
signature validation, active-session validation and atomic ticket consumption.
WMS uses an explicit stable IAM subject mapping and the local account's current
roles, permissions and company/warehouse/owner assignments. There is no username
or administrator fallback. Nonempty unsupported zone, org or domain scope is denied.

## Prerequisites and database

Use Java 17, Maven 3.9+, and the versions of Node/pnpm pinned by the frontend.
Run the existing WMS database migrations in their documented order, then execute
`db/sso/001-wms-sso-subject-map.sql` against the WMS database's application schema,
then apply `db/sso/002-gauss-empty-zone.sql`. The second migration also upgrades
existing mapping tables. Gauss A compatibility converts empty strings to NULL;
an absent zone claim is therefore nullable. Nonblank zones remain rejected until
the business data model can enforce them. Company scope must still be nonblank,
match the local account's active company, and include warehouse and owner access.

The optional `WmsSsoSubjectDatabaseTest` uses connection-local temporary tables.
Set `WMS_TEST_JDBC_URL`, `WMS_TEST_DB_USER`, and `WMS_TEST_DB_PASSWORD` for an owned
integration database to exercise the upgrade and mapping query on real Gauss.
It refuses databases outside the integration naming prefixes and rolls back its
temporary fixture. No shared accounts or mappings are updated by this test.
This additional script creates tables only; it does not seed users or mappings.
Do not run IAM's `010-sso-ticket.sql` or gateway `011-sso-bindings.sql` on WMS.
Those are IAM-side migrations. Provision explicit IAM and WMS mappings using the
actual IAM UUID, an existing active WMS user, and its actual company code.

## Configuration

Keep actual values in a private runtime configuration, outside source control.

| Key | Meaning |
| --- | --- |
| `wms.sso.enabled` | Explicit opt-in; defaults to false |
| `wms.sso.iam-base-url` | IAM context root, without trailing slash |
| `wms.sso.service-token` | Credential registered only for the `wms` audience |
| `wms.sso.audience` | Must be `wms` |
| `wms.sso.timeout-ms` | 100–30000 ms |
| `wms.sso.exchange-allowed-cidrs` | Actual allowed clients; empty denies all |
| `wms.sso.trusted-proxies` | Explicit TCP peers allowed to append XFF; empty ignores XFF |
| `server.forward-headers-strategy` | Keep `none` so the guard sees the real TCP peer |

The gateway must overwrite client-supplied forwarding headers or append the actual
client at its verified trust boundary. The guard walks trusted hops from right to
left, stops at the nearest untrusted hop, and never trusts an arbitrary left prefix.

## Interfaces and lifecycle

- `GET /api/v1/auth/sso/health`: nonsecret configuration readiness.
- `POST /api/v1/auth/sso/exchange`: JSON `ticket` or `X-SSO-Ticket`; never a URL parameter.
- `POST /api/v1/auth/sso/revoke`: JSON `iamSessionId`, with `X-SSO-Service-Token`.
- Existing `/api/v1/auth/me`, `/csrf`, `/password` and `/logout` retain native semantics.

The first-password-change flag remains the local account policy. Changing a
password requires its current local password. Read-only accounts cannot mutate
business data even if accidentally granted a write permission. Password maintenance
and logout remain available.

IAM must notify the configured WMS revoke endpoint on logout. Every protected
request checks SSO revocation before restoring its security context. Verify a
retained old cookie directly against WMS after logout; clearing a browser cookie
is insufficient. Session rotation preserves the binding and destruction removes it.
The registry is process-local and supports one WMS instance with in-memory servlet
sessions. Distributed sessions or multiple WMS instances require a shared revocation
store or reliable notification to every instance; this implementation makes no
cross-instance guarantee. Revocation tombstones currently live until process restart.

## Apply and build

Start from the exact baseline recorded in the external delivery manifest and a clean
worktree. For mixed Windows/Linux line endings use the same option for check/apply:

```sh
git status --short
git apply --check --ignore-space-change WMS_SSO_COMPLETE_FROM_MAIN.patch
git apply --ignore-space-change WMS_SSO_COMPLETE_FROM_MAIN.patch
git diff --check
cd WMS/WMS
mvn test package
python -m unittest discover -s simulator/tests
```

Review changed paths before committing. If the agreed baseline has advanced, use a
new worktree and resolve source changes with the module maintainer. Do not overwrite
uncommitted work or force-apply a conflicting patch.

Source acceptance does not deploy an existing instance or close a legacy-secret
incident. A maintenance release must independently remove old verification paths,
rotate compromised credentials, invalidate old sessions and verify the actual
running artifact and gateway route. Rollback must not restore compromised trust.
