import { describe, expect, it } from 'vitest';
import { mayReadBusinessRoute } from '../src/middleware/sso-policy.js';

const limited = (permissions: string[]) => ({ permissions, superAdmin: false, readOnly: true });
describe('current SSO business read grants', () => {
  it('does not treat system entry or readonly mode as a business read grant', () => {
    expect(mayReadBusinessRoute(limited([]), '/api/plans')).toBe(false);
    expect(mayReadBusinessRoute(limited(['pps:entry:view']), '/api/plans/PLAN-1')).toBe(false);
  });
  it.each(['pps:api:plans:read', 'plan:view', 'pps:plan:list:view'])('accepts the explicit plan grant %s', permission => {
    expect(mayReadBusinessRoute(limited([permission]), '/api/plans')).toBe(true);
    expect(mayReadBusinessRoute(limited([permission]), '/api/plans/PLAN-1')).toBe(true);
    expect(mayReadBusinessRoute(limited([permission]), '/api/reports')).toBe(false);
  });
  it('a remaining overview grant cannot preserve a revoked plan read', () => {
    expect(mayReadBusinessRoute(limited(['overview:view']), '/api/overview')).toBe(true);
    expect(mayReadBusinessRoute(limited(['overview:view']), '/api/plans')).toBe(false);
  });
  it('does not extend plan viewing into nested task or unknown child APIs', () => {
    expect(mayReadBusinessRoute(limited(['plan:view']), '/api/plans/PLAN-1/tasks')).toBe(false);
    expect(mayReadBusinessRoute(limited(['pps:api:plans:read']), '/api/plans/PLAN-1/unknown')).toBe(false);
  });
  it('requires a separately registered grant for other business resources', () => {
    expect(mayReadBusinessRoute(limited(['pps:api:equipment:read']), '/api/equipment/EQ-1')).toBe(true);
    expect(mayReadBusinessRoute(limited(['pps:api:equipment:read']), '/api/reports')).toBe(false);
    expect(mayReadBusinessRoute(limited(['plan:view']), '/api/plans-other')).toBe(false);
  });
  it('preserves current-user session maintenance without granting business reads', () => {
    expect(mayReadBusinessRoute(limited([]), '/api/auth/me')).toBe(true);
    expect(mayReadBusinessRoute(limited([]), '/api/auth/users')).toBe(false);
  });
  it('allows full administration while readonly veto still requires an actual read grant', () => {
    expect(mayReadBusinessRoute({ permissions: [], superAdmin: true, readOnly: false }, '/api/reports')).toBe(true);
    expect(mayReadBusinessRoute({ permissions: [], superAdmin: true, readOnly: true }, '/api/reports')).toBe(false);
  });
});
