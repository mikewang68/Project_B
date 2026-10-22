import { beforeEach, describe, expect, it, vi } from 'vitest';

const db = vi.hoisted(() => ({ query: vi.fn(), release: vi.fn() }));
vi.mock('../src/db/openGaussClient.js', () => ({ getPool: () => db, connectClient: async () => db }));
vi.mock('../src/lib/logger.js', () => ({ logger: { warn: vi.fn(), info: vi.fn() } }));
import { Table, makeHydrate } from '../src/db/table.js';
import { ALL_TABLES, SOFT_DELETE_STATEMENTS } from '../src/db/schema.js';
import { resetAllData, purgeAuditLogs } from '../src/db/maintenance.js';

const table = new Table({ table: 'tasks', columns: { _id: 'id', planId: 'plan_id', name: 'name' } });
beforeEach(() => {
  db.query.mockReset(); db.release.mockClear();
  db.query.mockResolvedValue({ rows: [], rowCount: 0 });
});
describe('PSMS logical deletion', () => {
  it('normal list, count, ID lookup and updates exclude invalid rows; history can be queried explicitly', async () => {
    await table.find({ planId: 'PLAN-1' });
    expect(db.query).toHaveBeenLastCalledWith(expect.stringContaining('WHERE valid = 1 AND plan_id = $1'), ['PLAN-1']);
    await table.countDocuments();
    expect(db.query).toHaveBeenLastCalledWith(expect.stringContaining('WHERE valid = 1'), []);
    await table.findById('old');
    expect(db.query).toHaveBeenLastCalledWith(expect.stringContaining('valid = 1 AND id = $1'), ['old', 1]);
    await table.find({ valid: 0 });
    expect(db.query).toHaveBeenLastCalledWith(expect.stringContaining('WHERE valid = $1'), [0]);
    await table.findByIdAndUpdate('old', { name: 'new' });
    expect(db.query).toHaveBeenLastCalledWith(expect.stringContaining('WHERE valid = 1 AND id = $2'), ['new', 'old']);
  });
  it('deleteMany marks invalid and never sends a physical DELETE or TRUNCATE', async () => {
    db.query.mockImplementation(async (sql: string) => ({ rows: sql.startsWith('UPDATE tasks') ? [{ id: 'task-old' }] : [], rowCount: sql.startsWith('UPDATE tasks') ? 1 : 0 }));
    expect(await table.deleteMany({ planId: 'PLAN-1' })).toBe(1);
    expect(db.query).toHaveBeenCalledWith('UPDATE tasks SET valid = 0 WHERE valid = 1 AND plan_id = $1 RETURNING *', ['PLAN-1']);
    expect(db.query.mock.calls.some(([sql]) => /^(DELETE|TRUNCATE)\b/.test(sql))).toBe(false);
  });
  it('a failed replacement rolls back the invalidation instead of losing the previous generation', async () => {
    db.query.mockImplementation(async (sql: string) => {
      if (sql.startsWith('INSERT INTO tasks')) throw new Error('insert failed');
      return { rows: [], rowCount: 0 };
    });
    await expect(table.replaceMany({ planId: 'PLAN-1' }, [{ _id: 'new', planId: 'PLAN-1', name: 'new' }])).rejects.toThrow('insert failed');
    expect(db.query).toHaveBeenCalledWith('ROLLBACK');
    expect(db.query).not.toHaveBeenCalledWith('COMMIT');
    expect(db.release).toHaveBeenCalledOnce();
  });
  it('reset and expired audit cleanup preserve stored records', async () => {
    const tables = await resetAllData();
    expect(tables).not.toContain('users'); expect(tables).not.toContain('audit_logs');
    await purgeAuditLogs(365);
    expect(db.query.mock.calls.some(([sql]) => /^(DELETE|TRUNCATE)\b/.test(sql))).toBe(false);
    expect(db.query).toHaveBeenCalledWith(expect.stringContaining('UPDATE audit_logs SET valid = 0'), [365]);
  });
  it('all business and child tables receive valid and child reads exclude historical versions', async () => {
    for (const name of ALL_TABLES) expect(SOFT_DELETE_STATEMENTS.some((sql) => sql.startsWith(`ALTER TABLE ${name} ADD COLUMN valid`))).toBe(true);
    await makeHydrate({ key: 'crew', table: 'task_crew', foreignKey: 'task_id', columns: { name: 'crew' }, keyColumns: ['task_id', 'crew'], orderBy: 'crew' })([{ _id: 'TASK-1' }]);
    expect(db.query).toHaveBeenLastCalledWith(expect.stringContaining('WHERE valid = 1 AND task_id'), [['TASK-1']]);
  });
});
