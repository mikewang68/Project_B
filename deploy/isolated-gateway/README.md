# Isolated gateway + reproducible navigation data

This directory holds the two pieces that were running in the isolated demo but
were **not** in the repository, which made the deployment impossible to rebuild
from source:

1. `e21_bff.py` — the isolated gateway (single-file Python HTTP server) that
   fronts IAM, SYS, WMS, SCS, EHMS, PSMS, EMCS and TRUST, provides the portal
   session, and enforces the Gate3 authorization checks.
2. `sql/` — the navigation schema, the Batch-1 seed, and a full 90-row snapshot
   of the menu state actually served in the demo.

---

## 1. Gateway

### What it does

- Serves the static portal and IAM/SYS SPAs.
- Owns the portal session (`POST /api/v1/portal/session/login|logout`,
  `GET /api/v1/portal/session/me`) and proxies IAM login.
- Exchanges an IAM identity for a short-lived WMS ticket
  (`POST {WMS}/api/v1/auth/sso/exchange`) and keeps the resulting WMS session
  server-side.
- Gate3 authorization: anonymous → 401, unauthorized app → 403, readonly actor
  writing → 403, unknown API → 404 (fail-closed).
- Same-origin passthrough for SCS, EMCS and TRUST, including rewriting the
  absolute `/api/v1` root inside proxied JS to the system prefix.

### Security properties that must not regress

| Check | Expected |
|---|---|
| Anonymous `GET /wms/` `/safety/` `/ehm/` | 401 |
| Anonymous `GET /api/v1/inventory/balances` | 401 |
| `scoped_user` `GET /api/v1/inventory/balances` | 403 |
| `auditor` `POST` / `PUT` / `DELETE` | 403 |
| Unknown prefix `GET /api/v1/secret/probe` | 404 |
| Portal logout then `GET /api/v1/portal/session/me` | 401 (never 501) |

### Configuration

All environment-specific values are externalised. There are **no** lab IP
addresses, absolute home paths, or default secrets in the code.

| Variable | Required | Default | Notes |
|---|---|---|---|
| `SSO_JWT_SECRET` | **yes** | — | ≥32 chars, no shipped default |
| `BASE_DIR` | **yes** | — | contains `artifacts/frontend/{portal,iam,sys}` and `logs/` |
| `BFF_PORT` | no | 18288 | |
| `BFF_BIND` | no | 127.0.0.1 | |
| `IAM_PORT` / `SYS_PORT` | no | 18086 / 18082 | |
| `WMS_BASE_URL` | no | `http://127.0.0.1:18084` | |
| `EHMS_BASE_URL` | no | `http://127.0.0.1:18083` | |
| `SCS_API_BASE_URL` | no | `http://127.0.0.1:18080` | SCS business API |
| `SCS_FE_BASE_URL` | no | `http://127.0.0.1:18088` | SCS frontend/proxy |
| `TRUST_BASE` | no | `http://127.0.0.1:28185` | |
| `EMCS_BASE` | no | `http://127.0.0.1:18095` | |
| `EHMS_WEB_DIR` | no | `$BASE_DIR/artifacts/frontend/ehm` | |
| `SCS_WEB_DIR` | no | `$BASE_DIR/artifacts/frontend/safety` | |
| `LOG_DIR` | no | `$BASE_DIR/logs` | |
| `CSRF_EXTRA_ORIGINS` | no | empty | comma separated `host:port` |
| `E12_SECRETS_FILE` | no | `~/.secrets/bff_secrets.env` | read at startup; its values override the environment |
| `LEGACY_BRIDGE_UNSAFE_FOR_RELEASE` | no | `false` | never enable outside a throwaway environment |

Copy `.env.example` to a 0600 file outside the repository and point
`E12_SECRETS_FILE` at it, or export the variables directly.

### Run

```bash
export E12_SECRETS_FILE=/secure/path/bff_secrets.env
python3 deploy/isolated-gateway/e21_bff.py
```

No third-party Python packages are required (standard library only, Python 3.9+).

### Operational notes

- Sessions are **in-memory**. Restarting the gateway invalidates every portal
  session; users must log in again.
- Keep-alive correctness depends on consuming the request body before any early
  response. Every reject path calls `drain_body()`; do not add a new early
  `return` without it, or the next request on the same connection will see a
  stray method line (HTTP 501).
- The gateway never validates SCS or EHMS business tokens. Those systems remain
  responsible for their own authentication.

---

## 2. Navigation SQL

| File | Purpose |
|---|---|
| `006-navigation-schema.sql` | 6 tables, foreign keys, CHECK constraints, hierarchy trigger |
| `007-navigation-seed-batch1.sql` | 8 managed apps + DTS legacy row + **7 formal pages** |
| `021-navigation-full-snapshot.sql` | **Full 90-row menu state** (8 APP / 15 DIRECTORY / 67 PAGE) |

### Why a snapshot is needed

`007` alone produces 7 pages. The demo serves 48 visible pages across 8 systems
because later batches (E17/E18/E20/E21/SCS/EMCS) were applied incrementally with
`UPDATE` statements against the then-current state. Those scripts cannot rebuild
from an empty database, so the resulting state is captured directly as
`021-navigation-full-snapshot.sql`.

### Apply order

```
006 → 007 → 021
```

Two ordering rules matter:

1. **The database must be created with `DBCOMPATIBILITY 'PG'`.**
   openGauss A-compatibility mode stores `''` as `NULL`, which violates the
   `NOT NULL DEFAULT ''` columns used throughout the schema.

   ```sql
   CREATE DATABASE iam_demo WITH DBCOMPATIBILITY 'PG' TEMPLATE template0;
   ```

2. **`021` emits rows in hierarchy order** (APP → DIRECTORY level 2 → level 3 →
   PAGE level 2 → level 3). The `006` trigger rejects a child whose parent does
   not exist yet and enforces `normalized_level = parent_level + 1`.

### Idempotency

Every statement in `021` is guarded:

```sql
INSERT INTO iam_navigation_menu (<cols>)
SELECT <values>
 WHERE NOT EXISTS (SELECT 1 FROM iam_navigation_menu WHERE stable_menu_key = '<key>');
```

`ON CONFLICT` is not used because openGauss does not support it.
`INSERT ... VALUES ... WHERE` is a syntax error in openGauss, hence the
`SELECT` form.

### Expected result (asserted at the end of `021`)

| Metric | Value |
|---|---|
| total rows | 90 |
| `nav_eligible = 1` | 7 |
| `menu_kind = 'APP'` | 8 |
| `menu_kind = 'DIRECTORY'` | 15 |
| `menu_kind = 'PAGE' AND visible = 1` | 48 |
| `BATCH_EMCS_HIDE_20261020 AND visible = 0` | 16 |

### Verified rebuild

A fresh database was created with `DBCOMPATIBILITY 'PG'` and `006 → 007 → 021`
applied in order. Result:

- All three scripts committed without error.
- Counts matched the table above.
- A row-level diff of `stable_menu_key | menu_kind | parent | level | name |
  entry_path | visible | nav_eligible | sort_order` against the live database
  returned **0 differences over 90 rows**.

### Known gap: user accounts

`002-schema.sql` / `004-identity.sql` / `005-permissions.sql` create the identity
tables but the three demo accounts (`admin`, `auditor`, `scoped_user`) and their
role bindings are **not** in the repository seed. A rebuilt database therefore
starts with an empty `iam_user` table and IAM login fails until accounts are
provisioned.

This is deliberate: password hashes must not enter the repository. Provision demo
accounts through the IAM admin API or an Operator-approved seed.

### Rollback

`021` inserts only; there is no destructive DDL. To discard a rebuild, drop the
database. Do not run these scripts against a shared business database.
