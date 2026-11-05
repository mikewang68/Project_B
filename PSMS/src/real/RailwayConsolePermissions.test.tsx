import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import RailwayConsole from './RailwayConsole';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); sessionStorage.clear(); });
describe('legacy read-only railway console', () => {
  it('retains reads while preventing generation, upload and import actions', async () => {
    const fetcher = vi.fn(async () => Response.json({ ok: true, data: {
      tickets: [{ id: 'SIM-RAIL-20261103', business_date: '2026-11-03', payload: { trainNo: 'SIM-12345', cargoType: 'COAL', weight: 1000 } }],
      runs: [], events: [], schedule: { PSMS_RAILWAY_AUTO_IMPORT: 'false', PSMS_RAILWAY_HOUR: 12, PSMS_RAILWAY_MINUTE: 30 },
    } }));
    vi.stubGlobal('fetch', fetcher);
    render(<RailwayConsole readOnly />);
    expect(await screen.findByText('SIM-RAIL-20261103')).toBeVisible();
    for (const name of ['生成并下载今日工单', '导入工单并启动流程', '导入并运行']) {
      const button = screen.getByRole('button', { name });
      expect(button).toBeDisabled();fireEvent.click(button);
    }
    expect(fetcher.mock.calls).toHaveLength(1);
  });
});
