import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import {
  apiCatalog,
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do001Schema,
  do009Schema,
  do010Schema,
  do012Schema,
  do013Schema,
  do015Schema,
} from '../../contracts';
import { resetDelayExecutor, setDelayExecutor } from '../delayPolicy';
import { createMockHandlers } from '../handlers';
import { createMockRuntime } from '../scenarios';

type Operation = {
  'x-api-id': string;
  'x-msw-path': string;
  parameters?: Array<{ name: string; in: 'path' | 'query'; example?: string | number }>;
  requestBody?: { content: { 'application/json': { example: unknown } } };
};

type OpenApiDocument = { paths: Record<string, Record<string, Operation>> };

const runtime = createMockRuntime();
const handlers = createMockHandlers(runtime);
const server = setupServer(...handlers);
const observedDelays: number[] = [];

function readOperations(): Array<{ method: string; operation: Operation }> {
  const openapi = JSON.parse(
    readFileSync(join(process.cwd(), 'docs', 'baseline', 'openapi.yaml'), 'utf8'),
  ) as OpenApiDocument;
  return Object.values(openapi.paths).flatMap((pathItem) =>
    Object.entries(pathItem)
      .filter(([method]) => ['get', 'post'].includes(method))
      .map(([method, operation]) => ({ method, operation })),
  );
}

function requestFor(method: string, operation: Operation): [string, RequestInit] {
  let path = operation['x-msw-path'];
  for (const parameter of operation.parameters ?? []) {
    if (parameter.in === 'path') {
      const example = operation['x-api-id'] === 'API-013' ? 'SCN-01' : parameter.example;
      path = path.replace(`:${parameter.name}`, String(example));
    }
  }

  const init: RequestInit = { method: method.toUpperCase() };
  const example = operation['x-api-id'] === 'API-015'
    ? { action: 'ACK', reason: '确认异常' }
    : operation['x-api-id'] === 'API-017'
      ? { action: 'REQUEST_RESET', reason: '申请复位' }
      : operation['x-api-id'] === 'API-019'
        ? { action: 'UPLOAD', reason: '上传缓存离线包' }
        : operation.requestBody?.content['application/json'].example;
  if (example !== undefined) {
    init.headers = { 'content-type': 'application/json' };
    init.body = JSON.stringify(example);
  }
  return [new URL(path, window.location.origin).toString(), init];
}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  runtime.reset('SCN-01');
  observedDelays.length = 0;
  setDelayExecutor(async (milliseconds) => {
    observedDelays.push(milliseconds);
  });
});
afterEach(() => {
  server.resetHandlers();
  resetDelayExecutor();
});
afterAll(() => server.close());

describe('C02 MSW handlers', () => {
  it('恰好注册 25 个与 catalog 一致的方法和 :id 路径', () => {
    const signatures = handlers.map((handler) => {
      const info = handler.info as { method: string; path: string };
      return `${info.method.toLowerCase()} ${info.path}`;
    });

    expect(signatures).toEqual(apiCatalog.map(({ method, mswPath }) => `${method} ${mswPath}`));
  });

  it('25 个冻结操作都返回可解析成功信封，且测试不真实等待', async () => {
    const operations = readOperations();
    expect(operations).toHaveLength(25);

    for (const { method, operation } of operations) {
      const response = await fetch(...requestFor(method, operation));
      const payload = await response.json();

      expect(response.status, operation['x-api-id']).toBe(200);
      expect(apiSuccessEnvelopeSchema.safeParse(payload).success, operation['x-api-id']).toBe(true);
    }

    expect(observedDelays).toHaveLength(25);
    expect(observedDelays.every((milliseconds) => milliseconds === 300)).toBe(true);
  });

  it('非法请求返回 400 DEMO-SCENARIO-001 和 Zod issues', async () => {
    const response = await fetch(new URL('/mock/plans/PLAN-001/decompose', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ruleVersion: 1, mode: 'AUTO' }),
    });
    const payload = await response.json();

    expect(response.status).toBe(400);
    expect(payload).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(payload.details.issues).toBeInstanceOf(Array);
    expect(apiErrorEnvelopeSchema.safeParse(payload).success).toBe(true);
  });

  it('一次性权限覆盖返回 403 TOS-AUTH-001，下一请求恢复正常', async () => {
    runtime.forceNextFailure({ status: 403, errorCode: 'TOS-AUTH-001', message: 'forbidden' });

    const denied = await fetch(new URL('/mock/plans', window.location.origin));
    const deniedPayload = await denied.json();
    const allowed = await fetch(new URL('/mock/plans', window.location.origin));

    expect(denied.status).toBe(403);
    expect(deniedPayload).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
    expect(apiErrorEnvelopeSchema.safeParse(deniedPayload).success).toBe(true);
    expect(allowed.status).toBe(200);
  });

  it('API-008/API-009 return only the requested C06 WorkOrder transport identity', async () => {
    const workOrderId = 'C06-WO-PLAN-001-G001-02';
    const assign = await fetch(
      new URL(`/mock/work-orders/${workOrderId}/assign`, window.location.origin),
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ resourceId: 'RESOURCE-001', reason: '演示资源绑定' }),
      },
    );
    const dispatch = await fetch(
      new URL(`/mock/work-orders/${workOrderId}/dispatch`, window.location.origin),
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ target: 'AREA-A', simulateReceipt: true }),
      },
    );

    expect(await assign.json()).toMatchObject({
      ok: true,
      data: {
        apiId: 'API-008',
        operationId: 'POST_mock_work_orders_id_assign',
        scenarioId: 'SCN-01',
        items: [{ id: workOrderId }],
      },
    });
    expect(await dispatch.json()).toMatchObject({
      ok: true,
      data: {
        apiId: 'API-009',
        operationId: 'POST_mock_work_orders_id_dispatch',
        scenarioId: 'SCN-01',
        items: [{ id: workOrderId }],
      },
    });
  });

  it('API-014 lists strict DO-009 records and API-015 returns only the path exception', async () => {
    const list = await fetch(new URL('/mock/exceptions', window.location.origin));
    const listPayload = await list.json();
    const command = await fetch(new URL('/mock/exceptions/EX-001/command', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        action: 'ACK',
        reason: '确认设备离线异常',
        owner: 'TEAM-01',
        evidence: ['EVIDENCE-001'],
      }),
    });
    const commandPayload = await command.json();

    expect(list.status).toBe(200);
    expect(listPayload).toMatchObject({
      ok: true,
      data: {
        apiId: 'API-014',
        operationId: 'GET_mock_exceptions',
        scenarioId: 'SCN-01',
      },
    });
    expect(listPayload.data.items).toHaveLength(5);
    expect(listPayload.data.items.every((item: unknown) => do009Schema.safeParse(item).success)).toBe(true);
    expect(command.status).toBe(200);
    expect(commandPayload).toMatchObject({
      ok: true,
      data: {
        apiId: 'API-015',
        operationId: 'POST_mock_exceptions_id_command',
        scenarioId: 'SCN-01',
        items: [{ id: 'EX-001' }],
      },
    });
    expect(commandPayload.data.items).toHaveLength(1);
    expect(do009Schema.safeParse(commandPayload.data.items[0]).success).toBe(true);
  });

  it('API-015 rejects the legacy or broadened request shape without changing public schemas', async () => {
    const response = await fetch(new URL('/mock/exceptions/EX-001/command', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ command: 'ACK', reason: 'legacy', evidence: {} }),
    });

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
    });
  });

  it('offers deterministic C08 network and malformed-response controls through an internal header', async () => {
    await expect(fetch(new URL('/mock/exceptions', window.location.origin), {
      headers: { 'x-demo-c08-fault': 'network' },
    })).rejects.toThrow();

    const malformed = await fetch(new URL('/mock/exceptions/EX-001/command', window.location.origin), {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-demo-c08-fault': 'malformed',
      },
      body: JSON.stringify({ action: 'ACK', reason: '确认异常' }),
    });

    expect(malformed.status).toBe(200);
    expect(apiSuccessEnvelopeSchema.safeParse(await malformed.json()).success).toBe(false);
  });

  it('API-016 lists strict DO-010 records and API-017 returns only the path interlock', async () => {
    const list = await fetch(new URL('/mock/interlocks', window.location.origin));
    const listPayload = await list.json();
    const command = await fetch(new URL('/mock/interlocks/IL-001/command', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        action: 'REQUEST_RESET',
        reason: '现场条件已核验，申请复位',
        approvalUserId: 'USER-002',
        resetRequest: { requested: true },
      }),
    });
    const commandPayload = await command.json();

    expect(list.status).toBe(200);
    expect(listPayload).toMatchObject({
      ok: true,
      data: {
        apiId: 'API-016',
        operationId: 'GET_mock_interlocks',
        scenarioId: 'SCN-01',
      },
    });
    expect(listPayload.data.items).toHaveLength(4);
    expect(listPayload.data.items.every((item: unknown) => do010Schema.safeParse(item).success))
      .toBe(true);
    expect(command.status).toBe(200);
    expect(commandPayload).toMatchObject({
      ok: true,
      data: {
        apiId: 'API-017',
        operationId: 'POST_mock_interlocks_id_command',
        scenarioId: 'SCN-01',
        items: [{ id: 'IL-001' }],
      },
    });
    expect(commandPayload.data.items).toHaveLength(1);
    expect(do010Schema.safeParse(commandPayload.data.items[0]).success).toBe(true);
  });

  it('API-017 rejects legacy, broadened, and fabricated foreign-key request shapes', async () => {
    for (const body of [
      { command: 'REQUEST_RESET', receipt: {}, approval: {} },
      { action: 'FORCE_RESTORE', reason: '禁止新增动作' },
      { action: 'REQUEST_RESET', reason: '申请复位', exceptionId: 'EX-003' },
    ]) {
      const response = await fetch(new URL('/mock/interlocks/IL-001/command', window.location.origin), {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
      });

      expect(response.status).toBe(400);
      expect(await response.json()).toMatchObject({
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
      });
    }
  });

  it('offers deterministic C09 network and malformed-response controls through an internal header', async () => {
    await expect(fetch(new URL('/mock/interlocks', window.location.origin), {
      headers: { 'x-demo-c09-fault': 'network' },
    })).rejects.toThrow();

    const malformed = await fetch(new URL('/mock/interlocks/IL-001/command', window.location.origin), {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-demo-c09-fault': 'malformed',
      },
      body: JSON.stringify({ action: 'REQUEST_RESET', reason: '申请复位' }),
    });

    expect(malformed.status).toBe(200);
    expect(apiSuccessEnvelopeSchema.safeParse(await malformed.json()).success).toBe(false);
  });

  it('offers deterministic C10 network and malformed-response controls through an internal header', async () => {
    await expect(fetch(new URL('/mock/offline-packets', window.location.origin), {
      headers: { 'x-demo-c10-fault': 'network' },
    })).rejects.toThrow();

    const malformed = await fetch(
      new URL('/mock/offline-packets/OFF-004/command', window.location.origin),
      {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'x-demo-c10-fault': 'malformed',
        },
        body: JSON.stringify({ action: 'UPLOAD', reason: '上传缓存离线包' }),
      },
    );

    expect(malformed.status).toBe(200);
    expect(apiSuccessEnvelopeSchema.safeParse(await malformed.json()).success).toBe(false);
  });

  it('offers strict API-020 reports and deterministic C11 network/malformed controls', async () => {
    const list = await fetch(new URL('/mock/reports?type=DAILY&period=2026-07-17', window.location.origin));
    const payload = await list.json();
    expect(list.status).toBe(200);
    expect(payload).toMatchObject({
      ok: true,
      data: { apiId: 'API-020', operationId: 'GET_mock_reports', scenarioId: 'SCN-01' },
    });
    expect(payload.data.items).toHaveLength(3);
    expect(payload.data.items.every((item: unknown) => do012Schema.safeParse(item).success)).toBe(true);

    await expect(fetch(new URL('/mock/reports', window.location.origin), {
      headers: { 'x-demo-c11-fault': 'network' },
    })).rejects.toThrow();

    const malformed = await fetch(new URL('/mock/reports', window.location.origin), {
      headers: { 'x-demo-c11-fault': 'malformed' },
    });
    expect(malformed.status).toBe(200);
    expect(apiSuccessEnvelopeSchema.safeParse(await malformed.json()).success).toBe(false);
  });

  it('offers strict API-024 audit logs and deterministic C12 faults', async () => {
    const list = await fetch(new URL('/mock/audit-logs', window.location.origin));
    const payload = await list.json();
    expect(list.status).toBe(200);
    expect(payload).toMatchObject({
      ok: true,
      data: { apiId: 'API-024', operationId: 'GET_mock_audit_logs', scenarioId: 'SCN-01' },
    });
    expect(payload.data.items).toHaveLength(9);
    expect(payload.data.items.every((item: unknown) => do013Schema.safeParse(item).success)).toBe(true);

    await expect(fetch(new URL('/mock/audit-logs', window.location.origin), {
      headers: { 'x-demo-c12-fault': 'network' },
    })).rejects.toThrow();

    const malformed = await fetch(new URL('/mock/audit-logs', window.location.origin), {
      headers: { 'x-demo-c12-fault': 'malformed' },
    });
    expect(malformed.status).toBe(200);
    expect(apiSuccessEnvelopeSchema.safeParse(await malformed.json()).success).toBe(false);

    const business = await fetch(new URL('/mock/audit-logs', window.location.origin), {
      headers: { 'x-demo-c12-fault': 'business' },
    });
    const businessPayload = await business.json();
    expect(business.status).toBe(409);
    expect(businessPayload).toMatchObject({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: '当前场景不允许读取审计日志。',
    });
    expect(apiErrorEnvelopeSchema.safeParse(businessPayload).success).toBe(true);
  });

  it('API-022 lists DO-015 and API-023 returns only CFG-001', async () => {
    const list = await fetch(new URL('/mock/config', window.location.origin));
    const listPayload = await list.json();
    const command = await fetch(new URL('/mock/config/CFG-001/command', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        command: 'edit',
        expectedVersion: 1,
        changes: { displayName: 'B项目生产调度 Demo（草稿）' },
      }),
    });
    const commandPayload = await command.json();

    expect(listPayload.data.items).toHaveLength(1);
    expect(listPayload.data.items[0]).toMatchObject({ id: 'CFG-001', configVersion: 'CFG-1.0' });
    expect(do015Schema.safeParse(listPayload.data.items[0]).success).toBe(true);
    expect(commandPayload.data.items).toHaveLength(1);
    expect(commandPayload.data.items[0].id).toBe('CFG-001');
    expect(do015Schema.safeParse(commandPayload.data.items[0]).success).toBe(true);
  });

  it('API-023 rejects legacy bodies, broadened changes, and unknown config ids', async () => {
    for (const [id, body] of [
      ['CFG-001', { edit: 'edit', submit: 'submit', approve: 'approve', publish: 'publish', rollback: 'rollback' }],
      ['CFG-001', { command: 'edit', expectedVersion: 1, changes: { areaCode: 'AREA-A' } }],
      ['CFG-404', { command: 'edit', expectedVersion: 1, changes: { displayName: 'missing' } }],
    ] as const) {
      const response = await fetch(new URL(`/mock/config/${id}/command`, window.location.origin), {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
      });
      expect(response.status).toBe(400);
      expect(await response.json()).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    }
  });

  it('offers C13 network, malformed, and business faults only for API-022/023', async () => {
    const before = structuredClone(runtime.getSnapshot().objects['DO-015']);

    await expect(fetch(new URL('/mock/config', window.location.origin), {
      headers: { 'x-demo-c13-fault': 'network' },
    })).rejects.toThrow();

    const malformed = await fetch(new URL('/mock/config/CFG-001/command', window.location.origin), {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-demo-c13-fault': 'malformed',
      },
      body: JSON.stringify({
        command: 'edit', expectedVersion: 1,
        changes: { displayName: '不会写入 MockRuntime' }, reason: '故障测试',
      }),
    });
    expect(malformed.status).toBe(200);
    expect(apiSuccessEnvelopeSchema.safeParse(await malformed.json()).success).toBe(false);

    const business = await fetch(new URL('/mock/config', window.location.origin), {
      headers: { 'x-demo-c13-fault': 'business' },
    });
    const businessPayload = await business.json();
    expect(business.status).toBe(409);
    expect(businessPayload).toMatchObject({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: '当前场景不允许读取或修改系统配置。',
    });
    expect(apiErrorEnvelopeSchema.safeParse(businessPayload).success).toBe(true);

    const unrelated = await fetch(new URL('/mock/reports', window.location.origin), {
      headers: { 'x-demo-c13-fault': 'business' },
    });
    expect(unrelated.status).toBe(200);
    expect(await unrelated.json()).toMatchObject({ ok: true, data: { apiId: 'API-020' } });
    expect(runtime.getSnapshot().objects['DO-015']).toEqual(before);
  });

  it('uses the C10 feature-local API-019 body and rejects the frozen legacy transport shape', async () => {
    runtime.reset('SCN-01');
    const current = await fetch(
      new URL('/mock/offline-packets/OFF-004/command', window.location.origin),
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ action: 'UPLOAD', reason: '上传缓存离线包' }),
      },
    );
    const legacy = await fetch(
      new URL('/mock/offline-packets/OFF-004/command', window.location.origin),
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ upload: 'UPLOAD', merge: 'MERGE', reject: 'REJECT', retry: 'RETRY' }),
      },
    );

    expect(current.status).toBe(200);
    expect(await current.json()).toMatchObject({ ok: true });
    expect(legacy.status).toBe(400);
    expect(await legacy.json()).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
  });

  it('keeps existing work-order scenario faults active for API-008/API-009', async () => {
    runtime.reset('SCN-04');
    const offline = await fetch(new URL('/mock/work-orders/WO-005/assign', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ resourceId: 'RESOURCE-001' }),
    });

    runtime.reset('SCN-05');
    const interlock = await fetch(new URL('/mock/work-orders/WO-006/dispatch', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ target: 'AREA-A', simulateReceipt: true }),
    });

    expect(await offline.json()).toMatchObject({ ok: false, errorCode: 'TOS-WO-001' });
    expect(await interlock.json()).toMatchObject({ ok: false, errorCode: 'TOS-IL-001' });
  });

  it('SCN-02 返回缺字段错误，SCN-06 返回离线版本冲突', async () => {
    runtime.reset('SCN-02');
    const missing = await fetch(new URL('/mock/plans', window.location.origin));
    const missingPayload = await missing.json();

    runtime.reset('SCN-06');
    const list = await fetch(new URL('/mock/offline-packets', window.location.origin));
    const listPayload = await list.json();
    const conflict = await fetch(new URL('/mock/offline-packets/OFF-001/command', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ action: 'RETRY', reason: '识别离线版本冲突' }),
    });
    const conflictPayload = await conflict.json();

    expect(missing.status).toBe(400);
    expect(missingPayload).toMatchObject({ ok: false, errorCode: 'TOS-EXT-002' });
    expect(list.status).toBe(200);
    expect(listPayload).toMatchObject({
      ok: true,
      data: { apiId: 'API-018', scenarioId: 'SCN-06' },
    });
    expect(conflict.status).toBe(409);
    expect(conflictPayload).toMatchObject({ ok: false, errorCode: 'TOS-OFF-001' });
    expect(apiErrorEnvelopeSchema.safeParse(conflictPayload).success).toBe(true);
  });

  it('SCN-02 permits only API-004 requests that supply every declared missing field', async () => {
    runtime.reset('SCN-02');
    const incomplete = await fetch(new URL('/mock/plans/PLAN-002/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ reason: '字段仍不完整', supplements: {} }),
    });
    expect(await incomplete.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-002' });

    const complete = await fetch(new URL('/mock/plans/PLAN-002/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        reason: '补录并复核',
        supplements: {
          trackNo: 'T2',
          effectiveUntil: '2026-07-16T14:00:00+08:00',
          reviewerId: 'USER-002',
        },
      }),
    });
    expect(complete.status).toBe(200);
    expect(await complete.json()).toMatchObject({ ok: true });

    runtime.reset('SCN-03');
    const otherScenario = await fetch(new URL('/mock/plans/PLAN-003/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ reason: '不得绕过', supplements: { trackNo: 'T3' } }),
    });
    expect(await otherScenario.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });
  });

  it('SCN-02 complete API-004 only unlocks strict API-005 and API-006 for PLAN-002', async () => {
    runtime.reset('SCN-02');

    const beforeResolution = await fetch(
      new URL('/mock/plans/PLAN-002/recommendation?inputVersion=2', window.location.origin),
    );
    expect(await beforeResolution.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-002' });

    const complete = await fetch(new URL('/mock/plans/PLAN-002/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ supplements: { trackNo: 'T1' } }),
    });
    expect(complete.status).toBe(200);
    expect(runtime.getResolvedFaultObjects()).toEqual({ 'PLAN-002': ['trackNo'] });

    const recommendation = await fetch(
      new URL('/mock/plans/PLAN-002/recommendation?inputVersion=2', window.location.origin),
    );
    const recommendationPayload = await recommendation.json();
    expect(recommendation.status).toBe(200);
    expect(recommendationPayload).toMatchObject({
      ok: true,
      data: { apiId: 'API-005', items: [{ id: 'PLAN-002', trackNo: 'T2' }] },
    });
    expect(do001Schema.safeParse(recommendationPayload.data.items[0]).success).toBe(true);

    const confirmation = await fetch(
      new URL('/mock/plans/PLAN-002/recommendation/confirm', window.location.origin),
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          trackNo: 'T1',
          window: '2026-07-16T10:00:00+08:00/2026-07-16T12:00:00+08:00',
        }),
      },
    );
    const confirmationPayload = await confirmation.json();
    expect(confirmation.status).toBe(200);
    expect(confirmationPayload).toMatchObject({
      ok: true,
      data: { apiId: 'API-006', items: [{ id: 'PLAN-002', trackNo: 'T2' }] },
    });
    expect(do001Schema.safeParse(confirmationPayload.data.items[0]).success).toBe(true);
  });

  it('SCN-02 resolution rejects incomplete, invalid, failed, reset, and different-object continuations', async () => {
    runtime.reset('SCN-02');

    const incomplete = await fetch(new URL('/mock/plans/PLAN-002/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ supplements: {} }),
    });
    expect(await incomplete.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-002' });
    expect(runtime.getResolvedFaultObjects()).toEqual({});

    const invalid = await fetch(new URL('/mock/plans/PLAN-002/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ supplements: { trackNo: 'T1' }, unexpected: true }),
    });
    expect(invalid.status).toBe(400);
    expect(await invalid.json()).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(runtime.getResolvedFaultObjects()).toEqual({});

    runtime.forceNextFailure({ status: 403, errorCode: 'TOS-AUTH-001', message: 'forced denial' });
    const forced = await fetch(new URL('/mock/plans/PLAN-002/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ supplements: { trackNo: 'T1' } }),
    });
    expect(await forced.json()).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
    expect(runtime.getResolvedFaultObjects()).toEqual({});

    const wrongObject = await fetch(new URL('/mock/plans/PLAN-001/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ supplements: { trackNo: 'T1' } }),
    });
    expect(await wrongObject.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-002' });
    expect(runtime.getResolvedFaultObjects()).toEqual({});

    const complete = await fetch(new URL('/mock/plans/PLAN-002/confirm', window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ supplements: { trackNo: 'T1' } }),
    });
    expect(complete.status).toBe(200);

    const otherPlan = await fetch(
      new URL('/mock/plans/PLAN-001/recommendation?inputVersion=1', window.location.origin),
    );
    expect(await otherPlan.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-002' });

    runtime.reset('SCN-02');
    const afterReset = await fetch(
      new URL('/mock/plans/PLAN-002/recommendation?inputVersion=2', window.location.origin),
    );
    expect(await afterReset.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-002' });
  });

  it('非 SCN-02 场景继续返回各自原始错误，不继承 resolvedFaultObjects', async () => {
    runtime.reset('SCN-03');
    const timeout = await fetch(
      new URL('/mock/plans/PLAN-003/recommendation?inputVersion=1', window.location.origin),
    );
    expect(await timeout.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });

    runtime.reset('SCN-07');
    const conflict = await fetch(new URL('/mock/appointments', window.location.origin));
    expect(await conflict.json()).toMatchObject({ ok: false, errorCode: 'TOS-EXT-003' });
  });
});
