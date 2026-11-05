import { describe, expect, it, vi, beforeEach } from 'vitest';
vi.mock('../src/lib/logger.js', () => ({ logger: { error: vi.fn() } }));
const f = vi.hoisted(() => ({ plan: vi.fn(), device: vi.fn(), replace: vi.fn(), audit: vi.fn() }));
vi.mock('../src/db/tables.js', () => ({
  Plan: { findById: () => ({ lean: f.plan }) },
  Equipment: { findOne: (q: unknown) => ({ lean: () => f.device(q) }) },
  Task: { replaceMany: f.replace }, WorkOrder: {}
}));
vi.mock('../src/middleware/audit.js', () => ({ writeBusinessAudit: f.audit }));
import { createTasksForPlan } from '../src/services/workOrder.service.js';

describe('plan tasks preserve inherited work area', () => {
  beforeEach(() => { vi.clearAllMocks(); f.plan.mockResolvedValue({
    _id: 'P1', planBatchNo: 'B1', workArea: 'AREA-A', status: 'CONFIRMED'
  }); f.replace.mockImplementation(async (_: unknown, docs: unknown[]) => docs); });
  it('rejects absent or other-area equipment before replacing any task', async () => {
    f.device.mockResolvedValue(null);
    await expect(createTasksForPlan('P1', [{ equipmentId: 'OTHER-AREA' }])).rejects.toThrow('授权作业区');
    expect(f.device).toHaveBeenCalledWith({ equipmentId: 'OTHER-AREA', workArea: 'AREA-A' });
    expect(f.replace).not.toHaveBeenCalled(); expect(f.audit).not.toHaveBeenCalled();
  });
  it('uses server plan area and persists the inherited tasks only after validation', async () => {
    f.device.mockResolvedValue({ equipmentId: 'E1', workArea: 'AREA-A' });
    const rows = await createTasksForPlan('P1', [{ equipmentId: 'E1', name: 'controlled acceptance' }]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ planId: 'P1', workArea: 'AREA-A', status: 'PENDING', equipmentId: 'E1' });
    expect(f.replace).toHaveBeenCalledWith({ planId: 'P1' }, rows);
  });
});
