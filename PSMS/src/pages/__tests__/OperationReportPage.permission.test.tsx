import { cleanup, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { renderReportingPage, waitForReportingPage } from './reportingTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-011 reporting permissions and safe scope', () => {
  it.each(['BUSINESS', 'REGULATOR', 'DISPATCHER', 'AUDITOR'] as const)(
    '%s can view the dashboard and generate reports',
    async (roleCode) => {
      renderReportingPage({ roleCode });
      await waitForReportingPage();
      expect(screen.getByRole('button', { name: '生成或刷新快照' })).toBeEnabled();
    },
  );

  it('renders route forbidden before API-020 for a role without report:view', async () => {
    const forbidden = renderReportingPage({ roleCode: 'SHIFT_LEADER' });
    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：report:view')).toBeVisible();
    expect(forbidden.fetcher).not.toHaveBeenCalled();
  });

  it('uses safe not-found and skips API-020 outside AREA-A data scope', async () => {
    const scoped = renderReportingPage({ dataScope: ['AREA-B'] });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(scoped.fetcher).not.toHaveBeenCalled();
    expect(scoped.runtime.store.getState().configAudit.commandAudit).toEqual([]);
  });
});
