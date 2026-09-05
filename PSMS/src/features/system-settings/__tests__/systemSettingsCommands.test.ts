import { describe, expect, it, vi } from 'vitest';

import {
  do015Schema,
  type Api023SuccessEnvelope,
  type ApiErrorEnvelope,
} from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore } from '../../../stores';
import {
  createSystemSettingsCommandService,
  createSystemSettingsWorkflowStore,
  type SystemSettingsGateway,
} from '../index';

function success(): Api023SuccessEnvelope {
  return {
    ok: true,
    data: {
      apiId: 'API-023',
      operationId: 'POST_mock_config_id_command',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: [do015Schema.parse(createFixtureSnapshot().objects['DO-015'][0])],
    },
    auditLogId: 'AUD-TRANSPORT-023',
    traceId: 'TRACE-TRANSPORT-023',
  };
}

function failure(errorCode: ApiErrorEnvelope['errorCode'] = 'DEMO-SCENARIO-001'): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Gateway failure ${errorCode}`,
    auditLogId: 'AUD-TRANSPORT-FAIL',
    traceId: 'TRACE-TRANSPORT-FAIL',
  };
}

function createContext(options: Readonly<{
  roleCode?: 'SYS_ADMIN' | 'INTERFACE_OPS' | 'SAFETY' | 'DISPATCHER';
  online?: boolean;
  editConfig?: SystemSettingsGateway['editConfig'];
}> = {}) {
  const snapshot = createFixtureSnapshot();
  const store = createDemoStore(snapshot, {
    actorId: 'USER-C13',
    roleCode: options.roleCode ?? 'SYS_ADMIN',
    dataScope: ['GLOBAL'],
    online: options.online ?? true,
    shiftId: 'SHIFT-001',
    scenarioId: 'SCN-01',
  });
  const workflow = createSystemSettingsWorkflowStore();
  const editConfig = vi.fn<SystemSettingsGateway['editConfig']>(
    options.editConfig ?? (async () => success()),
  );
  const gateway: SystemSettingsGateway = {
    listConfig: vi.fn(async () => success() as never),
    editConfig,
  };
  const commands = createSystemSettingsCommandService({ store, workflow, gateway });
  return { snapshot, store, workflow, gateway, editConfig, commands };
}

function input(overrides: Record<string, unknown> = {}) {
  return {
    commandId: 'CMD-C13-CLIENT-001',
    configId: 'CFG-001',
    expectedVersion: 1,
    changes: { displayName: '新的 Demo 名称', recommendationEnabled: false },
    reason: '  调整 Demo 配置  ',
    ...overrides,
  } as never;
}

function configState(context: ReturnType<typeof createContext>) {
  return structuredClone(context.store.getState().systemConfig.configVersions);
}

function upstreamState(context: ReturnType<typeof createContext>) {
  const state = context.store.getState();
  return structuredClone({
    plan: state.plan,
    recommendation: state.recommendation,
    workOrder: state.workOrder,
    resource: state.resource,
    vehicle: state.vehicle,
    exception: state.exception,
    interlock: state.interlock,
    offline: state.offline,
    report: state.report,
    userRoles: state.configAudit.userRoles,
    audit: state.configAudit.audit,
  });
}

describe('C13 system settings command service', () => {
  it('calls API-023 first, then atomically commits DO-015 plus one real audit', async () => {
    const context = createContext();
    const before = do015Schema.parse(context.store.getState().systemConfig.configVersions[0]);
    const upstream = upstreamState(context);
    let notifications = 0;
    context.store.subscribe(() => { notifications += 1; });

    const result = await context.commands.save(input());

    expect(context.editConfig).toHaveBeenCalledWith({
      configId: 'CFG-001',
      expectedScenarioId: 'SCN-01',
      expectedNow: '2026-07-16T09:00:00+08:00',
      expectedVersion: 1,
      changes: { displayName: '新的 Demo 名称', recommendationEnabled: false },
      reason: '调整 Demo 配置',
    });
    expect(result).toEqual({
      ok: true,
      commandId: 'CMD-C13-CLIENT-001',
      traceId: 'TRACE-TRANSPORT-023',
      auditLogId: 'AUD-C13-001',
    });
    expect(context.store.getState().systemConfig.configVersions[0]).toEqual({
      ...before,
      displayName: '新的 Demo 名称',
      recommendationEnabled: false,
      version: 2,
      updatedAt: '2026-07-16T09:00:00+08:00',
      updatedBy: 'USER-C13',
    });
    expect(context.store.getState().configAudit.commandAudit).toEqual([
      expect.objectContaining({
        record: {
          id: 'AUD-C13-001',
          actorId: 'USER-C13',
          operatorTerminal: 'WEB-DEMO',
          action: 'SS-05',
          objectType: 'DO-015',
          objectId: 'CFG-001',
          before,
          after: context.store.getState().systemConfig.configVersions[0],
          reason: '调整 Demo 配置',
          traceId: 'TRACE-TRANSPORT-023',
          occurredAt: '2026-07-16T09:00:00+08:00',
        },
        metadata: expect.objectContaining({ result: 'SUCCESS', errorCode: null }),
      }),
    ]);
    expect(context.workflow.getState().lastFeedback).toEqual({
      ok: true,
      commandId: 'CMD-C13-CLIENT-001',
      traceId: 'TRACE-TRANSPORT-023',
      transportAuditLogId: 'AUD-TRANSPORT-023',
      domainAuditLogId: 'AUD-C13-001',
      message: '系统配置已保存',
      idempotent: false,
      version: 2,
    });
    expect(upstreamState(context)).toEqual(upstream);
    expect(notifications).toBe(1);
  });

  it('denies page/action and offline sessions before API without changing config', async () => {
    for (const context of [
      createContext({ roleCode: 'DISPATCHER' }),
      createContext({ roleCode: 'SYS_ADMIN', online: false }),
    ]) {
      const before = configState(context);
      const result = await context.commands.save(input());
      expect(result).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
      expect(context.editConfig).not.toHaveBeenCalled();
      expect(configState(context)).toEqual(before);
      expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
      expect(context.store.getState().configAudit.commandAudit[0]).toMatchObject({
        record: { action: 'SS-05', before: before[0], after: before[0] },
        metadata: { result: 'DENIED', errorCode: 'TOS-AUTH-001' },
      });
    }
  });

  it.each([
    ['blank command id', { commandId: '   ' }],
    ['blank target', { configId: '   ' }],
    ['unknown target', { configId: 'CFG-404' }],
    ['blank reason', { reason: '   ' }],
    ['empty changes', { changes: {} }],
    ['unknown changes', { changes: { areaCode: 'AREA-A' } }],
    ['invalid changes', { changes: { auditRetentionDays: 0 } }],
    ['version mismatch', { expectedVersion: 99 }],
  ])('rejects %s before API and leaves config unchanged', async (_label, overrides) => {
    const context = createContext();
    const before = configState(context);
    const result = await context.commands.save(input(overrides));

    expect(result).toMatchObject({ ok: false });
    expect(context.editConfig).not.toHaveBeenCalled();
    expect(configState(context)).toEqual(before);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('rejects a non-DRAFT lifecycle before API', async () => {
    const context = createContext();
    context.store.replaceDomainState((candidate) => {
      candidate.systemConfig.configVersions[0].status = 'PUBLISHED';
    });
    const before = configState(context);

    const result = await context.commands.save(input());
    expect(result).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(context.editConfig).not.toHaveBeenCalled();
    expect(configState(context)).toEqual(before);
  });

  it('keeps config unchanged for business and network Gateway failures', async () => {
    for (const editConfig of [
      async () => failure(),
      async () => { throw new TypeError('config network down'); },
    ]) {
      const context = createContext({ editConfig });
      const before = configState(context);
      const result = await context.commands.save(input());
      expect(result).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
      expect(configState(context)).toEqual(before);
      expect(context.workflow.getState().draft).toBeUndefined();
      expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
      expect(context.store.getState().configAudit.commandAudit[0]).toMatchObject({
        record: { before: before[0], after: before[0] },
        metadata: { result: 'FAILED' },
      });
    }
  });

  it('detects response-time Store drift and never overwrites the external change', async () => {
    let context: ReturnType<typeof createContext>;
    context = createContext({
      editConfig: async () => {
        context.store.replaceDomainState((candidate) => {
          candidate.systemConfig.configVersions[0].version = 2;
          candidate.systemConfig.configVersions[0].displayName = '外部并发修改';
        });
        return success();
      },
    });

    const result = await context.commands.save(input());
    expect(result).toMatchObject({ ok: false, errorCode: 'DEMO-VERSION-001' });
    expect(context.store.getState().systemConfig.configVersions[0]).toMatchObject({
      version: 2,
      displayName: '外部并发修改',
    });
    expect(context.store.getState().systemConfig.configVersions[0].recommendationEnabled).toBe(true);
  });

  it('replays a resolved command without a second API, commit, or audit side effect', async () => {
    const context = createContext();
    const first = await context.commands.save(input());
    const second = await context.commands.save(input());

    expect(second).toBe(first);
    expect(context.editConfig).toHaveBeenCalledOnce();
    expect(context.store.getState().systemConfig.configVersions[0].version).toBe(2);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(context.workflow.getState().lastFeedback).toMatchObject({
      commandId: 'CMD-C13-CLIENT-001', idempotent: true,
    });
  });

  it('clears replay state and deterministic ids on resetCommandState', async () => {
    const context = createContext();
    await context.commands.save(input({ commandId: undefined }));
    context.commands.resetCommandState();
    context.store.replaceDomainState((candidate) => {
      candidate.systemConfig.configVersions = structuredClone(context.snapshot.objects['DO-015']) as never;
      candidate.configAudit.commandAudit = [];
    });

    const result = await context.commands.save(input({ commandId: undefined }));
    expect(result).toMatchObject({
      ok: true, commandId: 'CMD-C13-001', auditLogId: 'AUD-C13-001',
    });
    expect(context.editConfig).toHaveBeenCalledTimes(2);
  });
});
