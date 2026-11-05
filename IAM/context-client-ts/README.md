# IAM context client for Node.js

This server-only TypeScript/ESM package consumes the existing IAM 1.3 assertion with
the additive version 2 user context. It does not create another identity database,
accept browser contexts as trusted, cache permissions, or enable a consumer in IAM.
Keep its service credential in the backend's protected runtime configuration.

Build with TypeScript 6.0.3 and Node.js 20.19 or newer. The package has no runtime
dependencies. Compiled `.mjs` and `.d.mts` files can be placed in a private backend
dependency without introducing a frontend dependency.

```typescript
import { IamContextClient, requireAction, buildFilters, postgresFilter }
  from '@bproject/iam-context-client';

const iam = new IamContextClient({
  baseUrl: process.env.IAM_BASE_URL!,
  audience: 'psms',
  serviceToken: process.env.IAM_SERVICE_TOKEN!,
});
// Only the backend exchanges the short ticket. Store this binding in the session.
const initial = await iam.consume(ticket);
session.iamBinding = initial.binding;

// Repeat for EVERY protected request. Do not reuse initial.context as a cache.
const auth = await iam.current(session.iamBinding);
requireAction(auth.context, 'PSMS:orders', 'VIEW');
const f = buildFilters(auth.context.scopeMode, auth.context.activeScope, 'ORDER');
const q = postgresFilter({
  condition: f.my_info, template: f.my_info_template, parameters: f.scope_params,
}, 'ORDER');
const rows = await pool.query(
  'SELECT o.* FROM orders o WHERE (o.status = $1 OR o.status = $2)'
    + q.text.replace(/\$(\d+)/g, (_, n) => '$' + (Number(n) + 2)),
  ['READY', 'RUNNING', ...q.values],
);
```

The example assumes the resource's service-side classification is REGION_SYSTEM.
Each consumer must register its own resources and verify real column names and
business predicates before enabling context version 2. Use the same condition
for details, counts, statistics, exports, updates and deletes. Scope values on
inserts come from the current trusted authorized tuple.

For a shared resource, call `resourceFilter(binding, {resourceKey, menuKey,
action, profile})`. IAM rechecks the live session, menu action and explicit
consumer grants. Bind the returned `template` using `postgresFilter`; never
remove the subsystem clause locally to invent a shared authorization. A shared
filter may contain extra unused parameters for the system dimension; the adapter
binds only parameters referenced by the template.

`my_info` and `my_project_zone_info` retain the teacher's pure SQL contract.
Values use ASCII letters, digits, underscore and hyphen with fixed length limits.
Only STANDARD and ORDER column profiles are registered. The PostgreSQL adapter
accepts the corresponding fixed predicate grammar and also verifies literal and
parameter agreement. It is not a general SQL parser.

Fail closed on missing V2 context, inactive sessions, mismatched subjects,
audiences or filters. Return 401/403 for central denial and 503 for IAM outage;
do not fall back to a local permission snapshot. Ticket consumption is one time
and is not automatically retried. HTTP is allowed only on explicit loopback
development connections; use authenticated HTTPS otherwise.

`ACTIONS` lists the twelve initial dictionary seeds, not an exhaustive permission
allowlist. IAM may register additional codes matching `[A-Z][A-Z0-9_]{0,63}`.
The authenticated current context must explicitly grant the requested code for
that menu; a valid spelling or a dictionary entry alone grants no permission.
Invalid codes reject the context. Resource filters also require IAM's current
authorization, including for newly registered codes.

Read-only users may only have explicitly granted VIEW, DOWNLOAD, EXPORT and
AUDIT_VERIFY. Any additional action is denied to read-only users even if present
in the returned menu grants. VIEW alone does not grant EXPORT, and AUDIT_VERIFY
does not grant CHAIN_SUBMIT.
