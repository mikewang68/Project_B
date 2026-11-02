import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import express from 'express';
import type { Server } from 'node:http';

const fake = vi.hoisted(() => ({
  count: vi.fn(), insert: vi.fn(), scope: ['AREA-A'], localEnabled: true,
  env: { AI_BASE_URL: 'https://model.example/v1', AI_MODEL: 'test-model', AI_API_KEY: 'test-credential' },
}));
vi.mock('../src/config/env.js', () => ({ env: fake.env }));
vi.mock('../src/db/tables.js', () => ({ Plan: { countDocuments: fake.count, insertMany: fake.insert }, Equipment: {},
  User: { findById: () => ({ lean: async () => fake.localEnabled
    ? { _id: 'local-dispatcher', username: 'dispatcher', actorId: 'TEST', roleCode: 'dispatcher', dataScope: fake.scope }
    : null }) },
}));
vi.mock('../src/services/plan.service.js', () => ({}));
vi.mock('../src/services/workOrder.service.js', () => ({}));
vi.mock('../src/middleware/audit.js', () => ({ auditOperation: () => (_req: unknown, _res: unknown, next: () => void) => next() }));
vi.mock('../src/middleware/auth.js', () => ({
  optionalAuth: (_req: unknown, _res: unknown, next: () => void) => next(),
  authenticate: (req: any, res: any, next: () => void) => {
    if (req.headers.authorization !== 'Bearer test-session') { res.status(401).json({ message: '未登录' }); return; }
    req.user = { userId: 'local-dispatcher', actorId: 'TEST', roleCode: 'dispatcher', dataScope: fake.scope }; next();
  },
}));
import { plansRouter } from '../src/routes/plans.routes.js';
import { aiRouter } from '../src/routes/ai.routes.js';

const nativeFetch = globalThis.fetch;
let server: Server;
let base: string;
beforeEach(async () => {
  fake.scope = ['AREA-A']; fake.localEnabled = true; fake.count.mockReset().mockResolvedValue(0);
  fake.insert.mockReset().mockImplementation(async (docs) => docs);
  const app = express(); app.use(express.json()); app.use('/api/plans', plansRouter); app.use('/api/ai', aiRouter);
  server = await new Promise<Server>((resolve) => { const instance = app.listen(0, '127.0.0.1', () => resolve(instance)); });
  const address = server.address() as { port: number }; base = `http://127.0.0.1:${address.port}`;
});
afterEach(async () => { vi.unstubAllGlobals(); await new Promise<void>((resolve) => server.close(() => resolve())); });
const plan = { planBatchNo: 'CSV-BATCH', trainNo: 'T99999', trackNo: 'T1', cargoType: 'FLY_ASH', arrivalTime: '2026-10-12T08:00:00+08:00', departureTime: '2026-10-12T10:00:00+08:00' };
const post = (path: string, body: unknown, authenticated = true) => nativeFetch(base + path, {
  method: 'POST', headers: { 'content-type': 'application/json', ...(authenticated ? { authorization: 'Bearer test-session' } : {}) }, body: JSON.stringify(body),
});
describe('database import and AI API', () => {
  it('imports complete rows as pending confirmation with new IDs', async () => {
    const response = await post('/api/plans/import', { plans: [plan] });
    expect(response.status).toBe(201);
    expect(fake.insert).toHaveBeenCalledWith([expect.objectContaining({ _id: expect.stringMatching(/^PLAN-CSV-/), status: 'PENDING_CONFIRM', workArea: 'AREA-A', supplements: { sourceSystem: 'MANUAL_CSV', departureTime: plan.departureTime } })]);
  });
  it('rejects invalid rows, in-file duplicates and existing batches without writing', async () => {
    expect((await post('/api/plans/import', { plans: [{ ...plan, departureTime: plan.arrivalTime }] })).status).toBe(400);
    expect((await post('/api/plans/import', { plans: [plan, plan] })).status).toBe(400);
    fake.count.mockResolvedValue(1);
    expect((await post('/api/plans/import', { plans: [plan] })).status).toBe(409);
    expect(fake.insert).not.toHaveBeenCalled();
  });
  it('requires login and enforces data scope for database imports and AI', async () => {
    expect((await post('/api/plans/import', { plans: [plan] }, false)).status).toBe(401);
    fake.scope = ['AREA-B'];
    expect((await post('/api/plans/import', { plans: [plan] })).status).toBe(403);
    expect((await post('/api/ai/assist', {})).status).toBe(403);
    expect(fake.insert).not.toHaveBeenCalled();
  });
  it('rejects a previously authenticated import when the local account no longer exists', async () => {
    fake.localEnabled = false;
    expect((await post('/api/plans/import', { plans: [plan] })).status).toBe(403);
    expect(fake.insert).not.toHaveBeenCalled();
  });
  it('sends a bounded business snapshot to the configured model and labels its source', async () => {
    const provider = vi.fn(async () => new Response(JSON.stringify({ choices: [{ message: { content: '准备建议：先确认股道。' } }] }), { status: 200 }));
    vi.stubGlobal('fetch', provider);
    const response = await post('/api/ai/assist', { question: '有哪些准备风险？', context: {
      plan: { planBatchNo: plan.planBatchNo, trainNo: plan.trainNo, cargoType: plan.cargoType, status: 'PENDING_CONFIRM', trackNo: plan.trackNo, arrivalDepartureTime: `${plan.arrivalTime}/${plan.departureTime}` },
      workOrders: [], resources: [], missingFields: [], conflicts: 0,
    } });
    expect(await response.json()).toEqual({ answer: '准备建议：先确认股道。', model: 'test-model', source: 'model' });
    expect(provider).toHaveBeenCalledWith('https://model.example/v1/chat/completions', expect.objectContaining({ method: 'POST' }));
    const init = provider.mock.calls[0] as unknown as [string, RequestInit];
    const body = JSON.parse(init[1].body as string);
    expect(JSON.parse(body.messages[1].content).context.dataSource).toBe('MOCK');
    expect(body.enable_thinking).toBeUndefined();
  });
  it('does not pass malformed business data to the model', async () => {
    const provider = vi.fn(); vi.stubGlobal('fetch', provider);
    expect((await post('/api/ai/assist', { question: '风险', context: { rawDatabase: 'invalid' } })).status).toBe(400);
    expect(provider).not.toHaveBeenCalled();
  });
});
