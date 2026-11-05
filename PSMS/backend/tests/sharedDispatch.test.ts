import { expect, it, vi } from 'vitest';
vi.mock('../src/db/openGaussClient.js', () => ({ connectClient: vi.fn() }));
vi.mock('../src/config/env.js', () => ({ env: { OPENGAUSS_SCHEMA: 'public' } }));
vi.mock('../src/lib/logger.js', () => ({ logger: { error: vi.fn() } }));
import { buildFilters } from '@bproject/iam-context-client';
import { sharedQuery, sharedResources } from '../src/services/shared-dispatch.service.js';

it('uses project-region sharing while fixing the provider owner and minimal projections', () => {
  const f = buildFilters('SCOPED', [{ projectId: 'PROJECT_B', regionId: 'SHEN_TIE', subsystemCode: 'WMS' }]);
  const q = sharedQuery('plans', { condition: f.my_project_zone_info,
    template: f.my_project_zone_info_template, parameters: f.scope_params }, 'LW-NORMAL');
  expect(q.where).toContain("(subsystem_code = 'PSMS')");
  expect(q.where).toContain('region_id = $2');
  expect(q.where).toContain('AND id=$3');
  expect(q.values).toEqual(['PROJECT_B', 'SHEN_TIE', 'LW-NORMAL']);
  expect(q.descriptor.columns).not.toMatch(/supplier|phone|supplements|contact/);
  expect(sharedResources['work-orders'].columns).not.toMatch(/instructions|operator|crew|feedback/);
});
it('keeps explicit deny and rejects SQL/id substitution', () => {
  const deny = { condition: ' AND 1=0 ', template: ' AND 1=0 ', parameters: {} };
  expect(sharedQuery('work-orders', deny).where).toContain('AND 1=0');
  expect(() => sharedQuery('plans', deny, "' OR 1=1")).toThrow();
  expect(() => sharedQuery('plans', { ...deny, condition: ' AND 1=1 ' })).toThrow();
});
