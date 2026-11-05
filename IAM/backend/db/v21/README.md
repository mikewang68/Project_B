# V2.1 scope migration

These are additive, offline openGauss 6.0 PG-mode migrations. They retain the existing identity, role and navigation tables. They do not configure project ownership or enable new grants for historical users. Run the IAM and SYS scripts against their respective databases, never against a connection inferred from a directory name.

## IAM execution order

1. Apply `001-region-scope.sql` through the reviewed `ScopeMigration` workflow below, unless its application has already been verified in the existing ledger.
2. Before deploying the session-selection implementation, apply `002-session-scope.sql` once through a separate controlled migration. It adds nullable `iam.iam_session.scope_selection_json TEXT` without a default. Existing NULL values preserve the default selection of each session; the migration does not select, revoke or replace sessions.
3. Verify the new column and retained data/permissions before starting the new IAM program. The program requires this column even when an account still uses the earlier scope contract.
4. Before enabling native-readiness SSO enforcement, separately apply `003-native-scope-readiness.sql` and record an explicit inventory-backed snapshot for each previously verified scoped business binding. This adds nullable `iam.sso_subject_binding.native_scope_json TEXT` without a default. Unknown tuples remain NULL and are denied by the scoped-business guard; do not mass-fill defaults or alter existing aliases. Verify old-column digests, constraints, indexes and owner/ACL under a short NOWAIT table lock, and retain a restricted schema/data backup. See `../../../native-onboarding/README.md` for the per-binding readiness contract.

`ScopeMigration.java` currently records the fixed version `v21-region-001`; it is the runner for 001, not a generic numbered migration runner. Do **not** pass 002 or 003 to it or replace the checksum/fingerprint of the existing 001 ledger entry. After either increment, the whole-schema fingerprint has intentionally changed, so replaying the 001 runner is not a valid health check and can report schema drift. Verify the original 001 ledger plus the separate increment execution records instead. A program rollback retains both additive columns and verified data; removing the readiness guard also removes its extra enforcement and requires an explicit access-control decision.

The 002 execution record must contain the exact database fingerprint (name, OID, owner, PG compatibility and UTF8 encoding), SQL checksum, a protected pre-change schema backup, and before/after checks. Use a dedicated DDL connection and a short transaction with `LOCK TABLE iam.iam_session IN ACCESS EXCLUSIVE MODE NOWAIT`; a busy lock or fingerprint mismatch stops the attempt. Under that lock, compare the existing columns, row count and original-column digest, owner/ACL, constraints and indexes before committing. The new column must be nullable TEXT with no default. If it already exists, inspect its definition and prior execution record rather than replaying raw ALTER or clearing existing selections. Do not log credentials or session values.

Fresh installations initialized by `db/isolated/004-identity.sql` already include the column: inspect and record this state instead of executing 002 again. For an application rollback, retain the additive column and data; any destructive removal requires an independently reviewed migration. Restoring an older program also restores its older scope-selection behavior, so application rollback needs a separate access-control decision.

## Region migration 001

`ScopeMigration.java` is a standalone Java 17 runner using the existing openGauss JDBC driver. It is not invoked at application startup. Its SQL scanner preserves quoted identifiers, doubled quotes, escape strings, nested comments and dollar-quoted function bodies. A database ledger records the version, script checksum and schema fingerprints in the same transaction as the migration.

Before a first application:

1. Obtain a verified backup, inspect the actual database and compatibility mode, and rehearse on an isolated structural/data fixture.
2. Export connection values through a protected process environment: `SCOPE_JDBC_URL`, `SCOPE_DB_USER`, `SCOPE_DB_PASSWORD`, `SCOPE_EXPECT_DATABASE`. Do not place credentials in command arguments or source files.
3. Compile with `javac -cp "$JDBC_JAR" ScopeMigration.java` and run `java -cp ".:$JDBC_JAR" ScopeMigration iam inspect 001-region-scope.sql`.
4. Independently review the fingerprint and set `SCOPE_EXPECT_FINGERPRINT` to the approved value. The fingerprint is environment evidence and belongs in the private operations record.
5. Run `rehearse` to apply and roll back, checking that the original fingerprint returns. Then use `apply` in the approved deployment window. For SYS, use module `sys` with its own script in `SYS/backend/db/v21/`.

The runner checks the exact database name, openGauss 6.0 version, PG mode, string semantics, pre-migration schema fingerprint and normalized username collisions. It takes a bounded table lock. Drift, an untracked partial migration, or a changed already-applied script causes failure. A repeated successful invocation skips the script; it does not replay seed grants or reactivate revoked permissions. Do not execute the SQL independently and later claim that the ledger covers it.

The migration account needs DDL privileges. Runtime accounts need only schema USAGE and the applicable table DML privileges, granted by the owner after migration. Default privileges apply to the owner that will create future tables; they do not retroactively grant existing objects or authorize an application to run DDL. Never run applications as the cluster administrator.

Failure before commit rolls back. A lost connection during commit is an unknown outcome: reconnect, inspect the ledger and fingerprint, and resolve it before retrying. A successful `rehearse` is not a backup-restoration test or permission to drop new production data. After new business/identity data exists, application rollback retains the additive schema; any destructive downgrade requires a separate reviewed data migration.

The fixed twelve action seeds do not grant operations to anyone. Existing users receive `user_code=id` and remain `PENDING`; project, region and subsystem remain unset until verified mappings and application capability checks are available. This migration alone does not implement management pages, SSO contract version 2 or business data filtering.

## Activation and conditional database tests

`ScopeActivation` requires the private activation manifest's `deploymentTier` and `approvedDeploymentTier` to match exactly (`ISOLATED` or `FORMAL`), as well as the reviewed database fingerprint. Approve the tier from actual deployment evidence; database name prefixes do not determine environment type. Existing private manifests must add `approvedDeploymentTier` before using this version. This does not create users or infer historical ownership.

`ScopeDatabaseTest` is a conditional integration test. Supply `IAM_SCOPE_TEST_URL`, `IAM_SCOPE_TEST_USER`, `IAM_SCOPE_TEST_PASSWORD` and the exact approved `IAM_SCOPE_TEST_DATABASE`. Missing or mismatched expected database names fail before fixture SQL. Use only the reviewed disposable test database: fixtures alter test records inside rollback transactions, so this suite is not a production health check. Keep the connection values private; do not run it against a live business database.
