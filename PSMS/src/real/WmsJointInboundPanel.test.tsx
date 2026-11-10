import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { SessionContext } from '../auth';
import WmsJointInboundPanel from './WmsJointInboundPanel';
vi.mock('./client', () => ({ liveApi: vi.fn() }));
vi.mock('./business', () => ({ canRunBusinessAction: () => false, loadBusinessPage: async () => ({ items: [] }) }));
import { liveApi } from './client';
const row = { id: 'WO1', status: 'IN_PROGRESS', updated_at: '2026-11-04', record_version: 'v1', plan_id: 'P1' };
const snapshot = { enabled: true, pendingSequences: [3], task: { task_id: 'PSMS-IN-1', state: 'PARTIALLY_RECEIVED',
  warehouse_code: 'WH1', wms_order_code: 'WMS-001', last_sequence: 1, error: null, reconcile_error: null,
  remote_snapshot: { lastSequence: 3 }, payload: { companyCode: 'C1', ownerCode: 'O1', expectedArrivalAt: '2026-11-04T16:00:00+08:00',
    lines: [{ lineId: '1', goodCode: 'STEEL-01', plannedQty: 100, unit: 'BUNDLE' }] },
  progress: [{ lineId: '1', receivedQty: 60, remainingQty: 40 }] } };
afterEach(() => { cleanup(); vi.clearAllMocks(); });
describe('WMS progress from server events', () => {
  it('shows WMS order, actual receipt and sequence gaps without declaring completion from a snapshot', async () => {
    vi.mocked(liveApi).mockResolvedValue({ ok: true, data: snapshot });
    render(<WmsJointInboundPanel row={row} session={{} as SessionContext} />);
    expect(await screen.findByText('WMS-001')).toBeVisible();
    expect(screen.getByText('部分收货')).toBeVisible();
    expect(screen.getByText('60')).toBeVisible();
    expect(screen.getByText('40')).toBeVisible();
    expect(screen.getByText('回执缺号，已暂存序号 3')).toBeVisible();
    expect(screen.queryByText('已收齐')).not.toBeInTheDocument();
  });
  it('reports a disabled server integration without presenting a dispatch form', async () => {
    vi.mocked(liveApi).mockResolvedValue({ ok: true, data: { enabled: false, task: null, pendingSequences: [] } });
    render(<WmsJointInboundPanel row={row} session={{} as SessionContext} />);
    expect(await screen.findByText('当前环境尚未启用 WMS 联调')).toBeVisible();
    expect(screen.queryByRole('button', { name: '派发入库任务' })).not.toBeInTheDocument();
  });
});
