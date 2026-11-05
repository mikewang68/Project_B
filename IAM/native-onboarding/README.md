# Native identity onboarding

`com.bdemo.iam.provisioning.NativeOnboardingWorker` is an optional standalone reconciliation process. It does not run inside the IAM web application and exposes no HTTP endpoint.

The process polls committed IAM people and enabled subject bindings. Fixed L3/L4 identities use explicitly configured project/region/system policies. An optional L2 path provisions only tuples with effective project reading-role actions; an L2 management identity alone does not create a native business identity. Both paths have explicit creation-time windows. Active project, region, system grants and the V2 application contract are required. Global administrators and unowned existing aliases are excluded. An unknown scope remains unavailable.

Each new person gets an independent native identity. Password markers cannot authenticate locally. Native roles are finite consumer-side limits; current IAM authorization and the existing resource filters remain mandatory on every business request. The reconciler creates no inventory, orders, device records, energy measurements or evidence events.

## Configuration

Supply a service-owned, mode `0600` JSON file with:

- `createdAfter`: a timestamp obtained from the actual IAM database; historical accounts are outside the onboarding window.
- `stateDirectory`: an absolute service-owned private directory.
- `databases`: `IAM`, `WMS`, `PSMS`, `EMCS`, `TRUST` connections. Each has `url`, `username`, `password`, `oid`, and `compatibility`. Use restricted application accounts; never a database owner or superuser. JDBC URL schemas must match the consumer's actual tables.
- `scopes`: explicit `projectId`, `regionId`, `subsystemCode` policies. WMS also requires `companyCode`, `warehouseCode`, `ownerId`, `roleCode`; PSMS requires concrete `workAreas`; TRUST requires `orgCode`; SCS/EHMS require finite native `permissions`; EMCS requires `roleKey` and finite method/path/permission `routes`.
- Optional `projectReaderCreatedAfter` and `projectReaderScopes` enable new L2 identities. These policies use the same native mapping fields, but supply explicitly approved reading roles, read permissions and GET routes. They do not reuse the fixed-scope write-role policy implicitly. A project administrator remains bound to its own project; different projects require separate approved identities. Multiple regional policies may share one native identity only when company/organization/role/registry anchors are compatible.
- SCS/EHMS/EMCS/PSMS policies require an absolute `registry` pointing to a pre-created private registry. Copy all current mappings before switching consumers to it. Consumers must read atomic registry updates without a restart.

Keep passwords, actual connection addresses, account IDs, native organization assignments and populated policies outside source control. Configuration is server-controlled; none of these fields is accepted from a browser request.

## Operation

Start the class with its dependency classpath and the private configuration filename. `--once` performs one reconciliation cycle; otherwise it polls every two seconds. A filesystem lock prevents two workers using the same state directory.

Run under a dedicated unprivileged service identity. It needs network access to the configured databases, read access to its configuration, and write access only to its own journals and consumer registries. Consumers need read access to their corresponding registry. Preserve file groups and permissions on atomic replacement. Disable shell access and use systemd filesystem restrictions.

The IAM database role requires SELECT on the published navigation/action dictionaries, project/region upper grants and role membership/action metadata used by `ScopeContextService.nativeScopes`. Its `iam_user` access must remain column-scoped: `id, username, display_name, user_code, admin_level, read_only, scope_state, project_id, region_id, subsystem_code, authz_version, status, deleted, org_codes, inherited_from_user, created_at`. Do not grant whole-user-table SELECT or password-hash access. In `sso_subject_binding`, retain the existing explicitly reviewed read/claim-write permissions and add only `UPDATE(native_scope_json)` for readiness. A new nullable column does not inherit a role's older column-level UPDATE grant. Verify the actual role's column permissions before deployment; do not respond to a permission failure with `GRANT ALL` or owner credentials.

Provisioning is convergent rather than a distributed transaction. A durable private intent precedes a native insert; a native transaction commits before its file mapping and central claims are aligned. Partial failures retry with bounded backoff. A conflicting native username or pre-existing mapping is never adopted, and a policy change does not silently move a provisioned person. `status.json` reports completion, pending failures and scopes outside policy; per-person journals contain stable failure codes and no credentials.

Existing central aliases are immutable to this process. A short deterministic alias is used for a new EMCS identity because of native field lengths. Company/organization claims are set only on a newly managed default binding after its corresponding native profile exists. Unsupported legacy domain/zone claims are excluded.

Stopping the worker stops new provisioning. Do not delete profiles or rewind sequences on rollback. Existing people continue through the consumers' normal live IAM checks. Central account disable, scope withdrawal and role withdrawal still deny access without relying on this worker's polling loop. Retain identity and audit history.

## Native readiness boundary

Migration `003-native-scope-readiness.sql` adds nullable `iam.sso_subject_binding.native_scope_json`. For the six business consumers, scoped V2 identities require an explicit version-1 READY/PARTIAL tuple snapshot. Every SSO assertion checks the current central-session selection against both live IAM authorization and these native-ready tuples. NULL rejects scoped business SSO; it is not an unlimited legacy exemption. IAM/SYS management and L1 global identities retain their separate authorization contract.

Before deploying this guard, record individually verified pre-existing bindings and their exact native tuples through a controlled migration. Preserve IDs, aliases, password hashes, roles and all other old columns; never infer an unknown native assignment. A completed owned L3/L4 journal also stamps its already-provisioned exact tuple. New L2 journals preserve known ready mappings while listing added missing tuples in `project-<module>-<subject>.json`. Selecting only a still-authorized ready tuple remains possible. A failed audience does not disable unrelated audiences. Additional native resources never grant central authorization, and a removed central grant is denied on the next request even if its old native profile remains.

Extending an owned L2 profile reconciles only explicit compatible mappings. A different native company/organization boundary, conflicting alias, pre-existing unowned profile or missing policy blocks with a stable reason. Unchanged plans are idempotent and do not rewrite native records on every poll. This worker does not generate warehouses, organizations or region facts.

For the L2 reconciliation path, a policy/anchor conflict or native write failure clears only that audience's readiness; known mappings may remain usable only for the explicit missing-additional-tuple PARTIAL case. Fixed L3/L4 policies keep the older immutable-policy contract: changing a completed journal's policy hash is rejected and counted as a pending failure, without adopting the new mapping or automatically clearing the previously verified tuple. An operator must resolve that conflict explicitly. This is not automatic deprovisioning of native records; live central scope and action revocation still apply to both paths.

## Verification

Run worker policy/atomic-publication tests, consumer registry refresh and revocation tests on Linux. Then create new people using actual L3 management APIs, wait for their private completion records, and exercise normal SSO and business reads. Verify original identities unchanged and reject foreign scopes and old aliases. A build, an empty worker queue, or manually prepared profiles do not establish end-to-end onboarding success.
