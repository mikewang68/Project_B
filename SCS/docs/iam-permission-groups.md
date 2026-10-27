# SCS menu and operation permissions

Apply the IAM catalog migrations `034-scs-operation-permissions.sql`,
`037-scs-missing-operation-permissions.sql`, and
`038-scs-system-menu-group.sql` before assigning the corresponding permissions.
These scripts register codes only. They do not create accounts, grant roles, or
publish business features. The separate navigation-address migration numbered
037 has a different filename and purpose; these are explicitly applied SQL
scripts rather than Flyway versions.

The legacy `safety` system code maps to the canonical application `SCS`.
`safety:sys:view` controls the system-management group inside SCS; it grants no
access to the separate SYS application. A visible group also requires at least
one authorized child page. Register all three group codes:

| Group | Code |
| --- | --- |
| Work safety | `safety:work:view` |
| Event management | `safety:event:view` |
| System management | `safety:sys:view` |

Role grants and the subject's local binding permissions must both contain each
required code. A group code grants no business write operation. An alert-handling
role can receive `safety:event:view`, `safety:module:alert:view`, and
`safety:module:alert:handle` without receiving model, rule, or device-control
permissions. Additional read pages are assigned explicitly.

The frontend checks operation permissions on fence editing and publication,
rule submission, approval and execution, alert handling, and risk simulation.
The backend remains authoritative and rejects unauthorized requests with valid
CSRF tokens as well. Read-only status takes precedence over operation codes.

Export codes are operation permissions, not navigation leaves. Registering an
export code does not implement an export endpoint; publish a capability only
after its route, authorization and business behavior have been verified.
