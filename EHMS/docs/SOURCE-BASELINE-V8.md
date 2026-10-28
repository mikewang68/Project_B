# EHMS V8 source baseline

This is the maintained EHMS source baseline from the V8 integration handoff dated
2026-10-27, synchronized on 2026-10-28. Subsequent EHMS changes should build on
this baseline rather than the previous standalone-login implementation.

## Provenance

- Handoff base: `75cfcff16e16d7470e42c458a7a0583df36d5451`.
- Handoff target snapshot: `0c3c26a565ec329be28c1840397c634dfd6d79ef`.
- EHMS patch SHA-256: `aeaf3df0377e6ffff8f2eff3c685504f8ddb0e24eb179798fec7add1df02b01e`.
- The 38 patch-target files were checked against the patch's Git blob identifiers
  before adding the test-fixture correction below. The complete target snapshot
  includes other systems; this synchronization imports EHMS only and preserves
  subsequent main-branch changes in other modules.

## Included integration behavior

- Native EHMS sessions exchange IAM tickets and revalidate current authorization.
  Local bindings, central permissions, local permissions, project scope and
  read-only restrictions are enforced together.
- Business pages use `/ehm/?page=<pageId>` with navigation-history support.
- Aggregate storage uses a project-scoped key. Time-series reads and writes also
  carry the project identifier.
- Existing data with unknown ownership remains `UNASSIGNED`; no project is
  inferred from a username or an arbitrary browser header.
- Project changes reload the page to discard previous project caches.

The visible edition may still be `1.8.1-server`. Use source commits and artifact
hashes, not that edition string alone, to identify the integration baseline.

## Verification

The backend was packaged with JDK 17 and Maven. Its 153 Java tests passed with
zero failures or errors. The 48 frontend tests passed. Four offline model
configuration tests passed in an isolated Linux temporary directory.

The existing AI provider test fixture was updated to supply the authenticated
`EhmAuth` object required by the new frontend. Three negative/readiness cases
cover anonymous access, restricted IAM read-only access and waiting for session
initialization. This correction changes tests only, not AI permissions or
application behavior. The server-only configuration script's POSIX permission
tests must run on Linux; a Windows run is not an equivalent permission test.

These checks use isolated or mocked dependencies. They do not establish live
openGauss migration success, IAM end-to-end acceptance, real model inference,
Fabric confirmation, or acceptance of every business feature.

## Deployment boundary

Review `sql/027_project_scope.sql` against the actual schema and take a backup
before upgrading an older database. The `ehm` schema in migration files is a
template. Do not apply it blindly to a different schema or overwrite historical
project ownership. New project-scoped code and incompatible old writers must not
run against the same table without a coordinated migration.

Private service identities, IAM bindings, account lists, database credentials,
environment files and coordinator responsibility sheets are not included in
this public source synchronization. Do not deploy this source by copying over
another maintainer's gateway, IAM, SYS or runtime configuration.

This is a GitHub source update. It does not itself restart services or deploy a
new server artifact. Preserve the centralized deployment paths and verify the
actual deployed version separately.
