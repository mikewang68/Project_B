import { beforeEach, describe, expect, it, vi } from 'vitest';

const db = vi.hoisted(() => ({ query: vi.fn() }));
vi.mock('../src/db/openGaussClient.js', () => ({ getPool: () => db }));
vi.mock('../src/lib/logger.js', () => ({ logger: { warn: vi.fn(), info: vi.fn() } }));
vi.mock('../src/db/telemetry.js', () => ({ TelemetryPoint: {} }));
vi.mock('../src/middleware/audit.js', () => ({ writeBusinessAudit: vi.fn() }));
import { listPlans } from '../src/services/plan.service.js';
import { Table } from '../src/db/table.js';

beforeEach(() => {
  db.query.mockReset();
  db.query.mockImplementation(async (sql: string) => ({ rows: sql.startsWith('SELECT COUNT') ? [{ n: '0' }] : [] }));
});

describe('plan ledger search', () => {
  it('accepts case-insensitive batch and train filters in both the scoped list and count', async () => {
    const result = await listPlans({ planBatchNo: 'batch-7', trainNo: 'Train-3', workAreas: ['AREA-B'], statuses: ['CONFIRMED'] });
    expect(result.items).toEqual([]);
    expect(result.total).toBe(0);
    expect(db.query).toHaveBeenCalledTimes(2);
    for (const [sql, params] of db.query.mock.calls) {
      expect(sql).toContain('valid = 1');
      expect(sql).toContain('work_area = ANY(');
      expect(sql).toContain('status = ANY(');
      expect(sql).toMatch(/plan_batch_no ILIKE \$\d+/);
      expect(sql).toMatch(/train_no ILIKE \$\d+/);
      expect(params).toEqual(expect.arrayContaining([['AREA-B'], ['CONFIRMED'], '%batch-7%', '%Train-3%']));
      expect(sql).not.toContain('batch-7');
    }
  });

  it('keeps case-sensitive searches and parameter binding available', async () => {
    const table = new Table({ table: 'plans', columns: { _id: 'id', batch: 'plan_batch_no' } });
    await table.find({ batch: { $regex: "A' OR 1=1 --" } });
    expect(db.query).toHaveBeenCalledWith(expect.stringContaining('plan_batch_no LIKE $1'), ["%A' OR 1=1 --%"]);
  });

  it('continues rejecting unsupported operators and unattached options', async () => {
    const table = new Table({ table: 'plans', columns: { _id: 'id', batch: 'plan_batch_no' } });
    await expect(table.find({ batch: { $regex: 'x', $options: 'i', $unknown: 'value' } })).rejects.toThrow('$unknown');
    await expect(table.find({ batch: { $options: 'i' } })).rejects.toThrow('$options');
    expect(db.query).not.toHaveBeenCalled();
  });
});
