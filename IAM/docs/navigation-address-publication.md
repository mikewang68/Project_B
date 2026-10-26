# Versioned navigation addresses

Apply `backend/db/isolated/037-navigation-address-publication.sql` with a migration account before deploying this feature. The IAM application account needs SELECT/INSERT/UPDATE on `iam.iam_navigation_address_revision` and its existing navigation permissions; it does not need DDL privileges. Back up navigation rows and record the applied migration.

Only a platform administrator without a read-only restriction can use `/system/addresses` or `/navigation/addresses`. Dedicated IAM administrators cannot change platform destinations. New hostnames must match `iam.navigation.address.allowed-host-suffixes`; list the deployment's actual DNS zones in private configuration. IP fallback is limited to the application's registered origin IP and requires a trusted HTTPS endpoint.

The workflow is draft → readiness validation → publication. Validation checks the application's signed readiness proof and a successful HTML response at the configured module path, with redirects disabled and bounded response sizes/timeouts. A failed validation keeps the active configuration unchanged and records the reason. Each publication checks the base version, updates the database address and its version in one transaction, and writes an operation log. Rollback validates a historical destination and publishes it as a new version; it does not erase history. The navigation response also includes each system's `addressVersion`; authenticated feature checks remain mandatory after publication.

Each public probe has an eight-second request timeout and at most three attempts for transport failures or server errors. Certificate failures, invalid proofs, and client errors fail immediately. Administrative clients should allow up to sixty seconds for a validation/publication request; retries never change the active version by themselves.

Public addresses and proxy transport addresses are distinct. HTTPS domain URLs use the configured public port. With no domain, the registered IP, origin protocol, port and module path form the fallback. A browser SSO entry cannot silently downgrade to HTTP. Changing a proxy's physical destination, DNS or a Cloudflare tunnel remains an explicit deployment operation; prepare that route before publishing the browser address.

## Gateway configuration

Deploy the new bridge to every application before enabling database address lookup. The bridge exposes `GET /sso/readiness?challenge=<nonce>` and returns an audience-bound HMAC proof. IAM checks DNS, TLS and this proof without sending a service credential to a proposed public address.

Set `SSO_DATABASE_ADDRESSES=true` on the central gateway. Each application's private edge configuration adds `addressSourceUrl`, pointing to IAM's `/api/v1/iam/auth/sso/navigation-address` endpoint over verified HTTPS or explicit loopback. The edge uses its existing audience-specific credential. Central registry files continue to hold credentials; their legacy `origin` values stop determining new transactions when database lookup is enabled.

The service endpoint accepts `{ "audience": "application-code", "version": null }` with `X-SSO-Service-Token` and returns the active `appCode`, `version`, `baseUrl`, `origin` and `modulePath`. An explicitly requested previous version is available for 180 seconds after replacement. New browser transactions capture the active version; central authorization resolves the same version. In-flight transactions therefore retain their original registered callback and feature destination.

Keep previous DNS/proxy aliases available for at least this transaction window. Validate hostname forwarding at the trusted reverse proxy: the edge redirects a stale entry hostname to the current registered origin before setting its host-only transaction cookie. Do not derive a callback from browser parameters or client-supplied forwarding headers.

## Verification

Check successful publication, failed readiness, stale-version conflict, rollback, new logins after publication, and a login started before publication. Verify both the intended deep link and native business session. Also verify dedicated IAM administrators, global read-only users and unsigned callers cannot manage or obtain service-protected configuration. Retain runtime and browser evidence privately.
