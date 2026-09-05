import { describe, expect, it } from 'vitest';

import { api004RequestSchema, type Plan } from '../../contracts';
import {
  authorize,
  type PolicyContext,
  type RoleCode,
} from '../../auth';
import { createAuditLedger, createCommandAuditAppender } from '../../governance/audit';
import { createMockRuntime } from '../../mocks/scenarios';
import { createDemoStore, type DemoSessionSeed } from '../../stores';
import {
  createCommandExecutor,
  createCommandPermissionEvaluator,
  transitionState,
  type DemoCommand,
} from '..';

const session: DemoSessionSeed = {
  actorId: 'USER-001',
  roleCode: 'DISPATCHER',
  dataScope: ['AREA-A'],
  shiftId: 'SHIFT-001',
  online: true,
  scenarioId: 'SCN-01',
};

function createCommand(commandId: string, roleCode: RoleCode): DemoCommand {
  return {
    commandId,
    action: 'confirm',
    entityType: 'DO-001',
    entityId: 'PLAN-001',
    expectedVersion: 1,
    payload: { reason: 'reviewed' },
    actor: {
      actorId: roleCode === 'AUDITOR' ? 'AUDITOR-001' : 'USER-001',
      roleCode,
      dataScope: ['AREA-A'],
      online: true,
    },
    traceId: `TRACE-${commandId}`,
    clientTime: '2026-07-19T08:00:00+08:00',
  };
}

describe('C03 real authorization and audit command integration', () => {
  it('commits an allowed command once, audits denial once, and keeps replay idempotent', async () => {
    const runtime = createMockRuntime();
    const store = createDemoStore(runtime.getSnapshot(), session);
    const ledger = createAuditLedger([], (entries) => {
      store.replaceDomainState((candidate) => {
        candidate.configAudit.commandAudit = entries;
      });
    });
    let mockCalls = 0;
    let auditSequence = 1;

    const contextFor = (input: DemoCommand): PolicyContext => ({
      session: {
        actorId: input.actor.actorId,
        roleCode: input.actor.roleCode as RoleCode,
        dataScope: input.actor.dataScope,
        online: input.actor.online,
        demoTime: runtime.now(),
      },
      pageId: 'UI-002',
      permission: 'plan:confirm',
      objectScope: { type: 'AREA', value: 'AREA-A' },
      expectedVersion: input.expectedVersion,
      actualVersion: store.getState().plan.plans.find(({ id }) => id === input.entityId)?.version,
    });

    const executor = createCommandExecutor({
      authorize: createCommandPermissionEvaluator(contextFor, authorize),
      validate: (input) => {
        api004RequestSchema.parse(input.payload);
      },
      invokeMock: async (input) => {
        mockCalls += 1;
        const plan = store.getState().plan.plans.find(({ id }) => id === input.entityId);
        if (!plan) throw new Error('plan missing');
        return {
          ok: true,
          data: structuredClone(plan),
          auditLogId: 'MOCK-AUDIT-001',
          traceId: input.traceId,
        };
      },
      transition: transitionState,
      commit: (input) => {
        store.replaceDomainState((candidate) => {
          const index = candidate.plan.plans.findIndex(({ id }) => id === input.entityId);
          if (index < 0) throw new Error('plan missing');
          const current: Plan = candidate.plan.plans[index];
          candidate.plan.plans[index] = {
            ...current,
            status: 'CONFIRMED',
            version: current.version + 1,
          };
        });
      },
      appendAudit: createCommandAuditAppender(ledger),
      nextAuditId: () => `AUD-C03-${String(auditSequence++).padStart(3, '0')}`,
      now: () => runtime.now(),
    });

    const allowed = createCommand('CMD-ALLOW-001', 'DISPATCHER');
    const allowedResult = await executor.execute(allowed);

    expect(allowedResult.ok).toBe(true);
    expect(store.getState().plan.plans[0]).toMatchObject({ status: 'CONFIRMED', version: 2 });
    expect(mockCalls).toBe(1);
    expect(ledger.list()).toHaveLength(1);
    expect(store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(ledger.list()[0].metadata.result).toBe('SUCCESS');

    expect(await executor.execute({ ...allowed, payload: { reason: 'replay' } })).toBe(allowedResult);
    expect(store.getState().plan.plans[0]).toMatchObject({ status: 'CONFIRMED', version: 2 });
    expect(mockCalls).toBe(1);
    expect(ledger.list()).toHaveLength(1);

    const planBeforeDenial = structuredClone(store.getState().plan);
    const denied = createCommand('CMD-DENY-001', 'AUDITOR');
    denied.expectedVersion = 2;
    const deniedResult = await executor.execute(denied);

    expect(deniedResult).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
    expect(store.getState().plan).toEqual(planBeforeDenial);
    expect(mockCalls).toBe(1);
    expect(ledger.list()).toHaveLength(2);
    expect(store.getState().configAudit.commandAudit).toHaveLength(2);
    expect(ledger.list()[1].metadata).toMatchObject({
      result: 'DENIED',
      errorCode: 'TOS-AUTH-001',
    });

    expect(await executor.execute(denied)).toBe(deniedResult);
    expect(store.getState().plan).toEqual(planBeforeDenial);
    expect(mockCalls).toBe(1);
    expect(ledger.list()).toHaveLength(2);
  });
});
