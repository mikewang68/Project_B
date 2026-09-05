import { cleanup, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { renderAuditTrailPage, waitForAuditTrailPage } from './auditTrailTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-013 audit trail permissions', () => {
  it('allows the frozen AUDITOR role to read API-024 and keeps the page read-only', async () => {
    const allowed = renderAuditTrailPage({ roleCode: 'AUDITOR' });
    await waitForAuditTrailPage();
    expect(allowed.fetcher).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('审计台账')).toBeVisible();
    expect(screen.getByRole('button', { name: '重置到 SCN-01' })).toBeDisabled();
    expect(screen.getByText('审计人员无场景重置权限；本页保持只读。')).toBeVisible();
  });

  it('renders route forbidden before API-024 for a role outside UI-013', async () => {
    const forbidden = renderAuditTrailPage({ roleCode: 'SHIFT_LEADER' });
    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：audit:view')).toBeVisible();
    expect(forbidden.fetcher).not.toHaveBeenCalled();
  });
});
