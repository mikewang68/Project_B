import { describe, expect, it, vi } from 'vitest';
import { ZodError } from 'zod';
import { do001Schema, type ApiErrorEnvelope } from '../../../contracts';
import { transitionState } from '../../../commands';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import {
  calculateReceptionRecommendation,
  recommendationDraftSchema,
} from '../../recommendation';
import {
  createTaskDecompositionCommandService,
  taskCommandPayloadSchema,
} from '../commands';
import { TASK_DECOMPOSITION_RULE_VERSION } from '../constants';
import type {
  TaskDecompositionGateway,
  TaskDecompositionGatewaySuccess,
} from '../gateway';
import { isC06WorkNode, isC06WorkOrder } from '../ownership';
import { createTaskDecompositionWorkflowStore } from '../workflow';

function gatewaySuccess(): TaskDecompositionGatewaySuccess {
  const rawPlan = createFixtureSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-001');
  if (!rawPlan) throw new Error('PLAN-001 missing.');
  return {
    ok: true,
    data: {
      apiId: 'API-007',
      operationId: 'POST_mock_plans_id_decompose',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: [do001Schema.parse(rawPlan)],
    },
    auditLogId: 'MOCK-AUD-007',
    traceId: 'MOCK-TRACE-007',
  };
}

function gatewayFailure(errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Strict API-007 failure ${errorCode}`,
    auditLogId: 'MOCK-AUD-FAIL',
    traceId: 'MOCK-TRACE-FAIL',
  };
}

function setPrerequisites(store: ReturnType<typeof createDemoStore>): void {
  store.replaceDomainState((candidate) => {
    const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
    if (!plan) throw new Error('PLAN-001 missing.');
    plan.status = 'CONFIRMED';
    plan.version = 2;
    const calculation = calculateReceptionRecommendation({
      plan,
      tracks: candidate.resource.tracks,
      generatedAt: candidate.session.demoTime,
    });
    const selected = calculation.candidates[0];
    if (!selected) throw new Error('Recommendation candidate missing.');
    candidate.recommendation.drafts[plan.id] = recommendationDraftSchema.parse({
      planId: plan.id,
      draftVersion: 2,
      status: 'CONFIRMED',
      inputPlanVersion: plan.version,
      ruleVersion: 'C05-DEMO-RULE-1.0',
      generatedAt: candidate.session.demoTime,
      candidates: calculation.candidates,
      excluded: calculation.excluded,
      selectedCandidateId: selected.candidateId,
      confirmation: {
        actorId: candidate.session.actorId,
        roleCode: candidate.session.roleCode,
        confirmedAt: candidate.session.demoTime,
        commandId: 'CMD-C05-001',
        traceId: 'TRACE-C05-001',
      },
    });
  });
}

function createContext(options: Readonly<{
  roleCode?: DemoSessionSeed['roleCode'];
  dataScope?: string[];
  scenarioId?: DemoSessionSeed['scenarioId'];
  commandFormatter?: (sequence: number) => string;
  gateway?: TaskDecompositionGateway['decomposePlan'];
  withRecommendation?: boolean;
}> = {}) {
  const scenarioId = options.scenarioId ?? 'SCN-01';
  const store = createDemoStore(createFixtureSnapshot(), {
    actorId: 'USER-001',
    roleCode: options.roleCode ?? 'DISPATCHER',
    dataScope: options.dataScope ?? ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId,
  });
  setPrerequisites(store);
  if (options.withRecommendation === false) {
    store.replaceDomainState((candidate) => {
      delete candidate.recommendation.drafts['PLAN-001'];
    });
  }
  const workflow = createTaskDecompositionWorkflowStore();
  const decomposePlan = vi.fn<TaskDecompositionGateway['decomposePlan']>(
    options.gateway ?? (async () => gatewaySuccess()),
  );
  const gateway = { decomposePlan } satisfies TaskDecompositionGateway;
  const commands = createTaskDecompositionCommandService({
    store,
    gateway,
    workflow,
    ...(options.commandFormatter
      ? { idFormatters: { command: options.commandFormatter } }
      : {}),
  });
  return { store, workflow, commands, decomposePlan };
}

function owned(context: ReturnType<typeof createContext>) {
  const state = context.store.getState();
  const workOrders = state.workOrder.workOrders.filter((item) => isC06WorkOrder(item, 'PLAN-001'));
  const numbers = new Set(workOrders.map(({ workOrderNo }) => workOrderNo));
  const nodes = state.workOrder.nodes.filter(
    (item) => isC06WorkNode(item) && numbers.has(item.workOrderNo),
  );
  return { workOrders, nodes };
}

describe('C06 TaskCommandPayload', () => {
  it('accepts the exact SM-007 payload and rejects extra command context', () => {
    const payload = {
      current: 'ACCEPTED',
      businessAction: 'TD-01',
      mode: 'AUTO',
      planVersion: 2,
      recommendationDraftVersion: 2,
      resourceVersions: { 'RESOURCE-001': 1 },
      workOrderVersions: {},
      nodeVersions: {},
      generationVersion: 1,
      selectedNodeIds: [],
    };

    expect(taskCommandPayloadSchema.parse(payload)).toEqual(payload);
    expect(() => taskCommandPayloadSchema.parse({ ...payload, actor: 'USER-001' })).toThrow(ZodError);
  });
});

describe('TaskDecompositionCommandService TD-01/TD-04', () => {
  it('generates four strict C06 DRAFT/WAITING pairs atomically and appends one TD-01 audit', async () => {
    const context = createContext();
    const stateBefore = structuredClone(context.store.getState());

    const result = await context.commands.generateTasks('PLAN-001');

    expect(result).toEqual({
      ok: true,
      commandId: 'CMD-C06-001',
      traceId: 'TRACE-C06-001',
      auditLogId: 'AUD-C06-001',
    });
    expect(context.decomposePlan).toHaveBeenCalledOnce();
    expect(context.decomposePlan).toHaveBeenCalledWith('PLAN-001', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'AUTO',
    });
    expect(owned(context).workOrders).toHaveLength(4);
    expect(owned(context).nodes).toHaveLength(4);
    expect(owned(context).workOrders.every(({ status }) => status === 'DRAFT')).toBe(true);
    expect(owned(context).nodes.every(({ status }) => status === 'WAITING')).toBe(true);
    const stateAfter = context.store.getState();
    expect(stateAfter.plan).toEqual(stateBefore.plan);
    expect(stateAfter.recommendation).toEqual(stateBefore.recommendation);
    expect(stateAfter.resource).toEqual(stateBefore.resource);
    expect(stateAfter.workOrder.workOrders.filter(({ id }) => !id.startsWith('C06-WO-'))).toEqual(
      stateBefore.workOrder.workOrders,
    );
    expect(stateAfter.workOrder.nodes.filter(({ id }) => !id.startsWith('C06-NODE-'))).toEqual(
      stateBefore.workOrder.nodes,
    );
    expect(stateAfter.configAudit.commandAudit).toHaveLength(1);
    expect(stateAfter.configAudit.commandAudit[0]).toMatchObject({
      record: {
        id: 'AUD-C06-001',
        action: 'TD-01',
        objectId: 'PLAN-001',
        occurredAt: stateAfter.session.demoTime,
      },
      metadata: { result: 'SUCCESS' },
    });
  });

  it('rejects missing prerequisites, unauthorized role, and AREA-B before API with one audit each', async () => {
    const missing = createContext({ withRecommendation: false });
    const unauthorized = createContext({ roleCode: 'BUSINESS' });
    const outOfScope = createContext({ dataScope: ['AREA-B'] });

    const missingResult = await missing.commands.generateTasks('PLAN-001');
    const unauthorizedResult = await unauthorized.commands.generateTasks('PLAN-001');
    const outOfScopeResult = await outOfScope.commands.generateTasks('PLAN-001');

    expect(missingResult).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(unauthorizedResult).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
    expect(outOfScopeResult).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
    for (const context of [missing, unauthorized, outOfScope]) {
      expect(context.decomposePlan).not.toHaveBeenCalled();
      expect(owned(context).workOrders).toEqual([]);
      expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
    }
  });

  it('rejects a second AUTO before API and does not append duplicate C06 records', async () => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');

    const second = await context.commands.generateTasks('PLAN-001');

    expect(second).toMatchObject({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: expect.stringMatching(/重新生成|TD-04/),
    });
    expect(context.decomposePlan).toHaveBeenCalledOnce();
    expect(owned(context).workOrders).toHaveLength(4);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(2);
  });

  it('regenerates only current-plan C06 DRAFT pairs as G002 and preserves history/audits', async () => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    context.store.replaceDomainState((candidate) => {
      const edited = candidate.workOrder.workOrders.find(
        ({ id }) => id === 'C06-WO-PLAN-001-G001-02',
      );
      if (!edited) throw new Error('Generated task missing.');
      edited.title = '人工编辑后的卸料准备';
      edited.version += 1;
    });
    const beforeLegacyOrders = structuredClone(
      context.store.getState().workOrder.workOrders.filter(({ id }) => !id.startsWith('C06-WO-')),
    );
    const beforeLegacyNodes = structuredClone(
      context.store.getState().workOrder.nodes.filter(({ id }) => !id.startsWith('C06-NODE-')),
    );

    const result = await context.commands.regenerateTasks('PLAN-001');

    expect(result).toMatchObject({ ok: true, commandId: 'CMD-C06-002', traceId: 'TRACE-C06-002' });
    expect(context.decomposePlan).toHaveBeenLastCalledWith('PLAN-001', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'REGENERATE',
    });
    expect(owned(context).workOrders.map(({ id, title }) => [id, title])).toEqual([
      ['C06-WO-PLAN-001-G002-01', '识别与路由确认'],
      ['C06-WO-PLAN-001-G002-02', '卸料准备'],
      ['C06-WO-PLAN-001-G002-03', '输送转运'],
      ['C06-WO-PLAN-001-G002-04', '筒仓入库'],
    ]);
    expect(context.store.getState().workOrder.workOrders.filter(({ id }) => !id.startsWith('C06-WO-'))).toEqual(
      beforeLegacyOrders,
    );
    expect(context.store.getState().workOrder.nodes.filter(({ id }) => !id.startsWith('C06-NODE-'))).toEqual(
      beforeLegacyNodes,
    );
    expect(context.store.getState().configAudit.commandAudit.map(({ record }) => record.action)).toEqual([
      'TD-01', 'TD-04',
    ]);
  });

  it.each([
    ['gateway failure', async (context: ReturnType<typeof createContext>) => {
      context.decomposePlan.mockResolvedValueOnce(gatewayFailure('TOS-EXT-001'));
    }],
    ['malformed gateway rejection', async (context: ReturnType<typeof createContext>) => {
      context.decomposePlan.mockRejectedValueOnce(new ZodError([]));
    }],
  ] as const)('%s leaves the original draft intact', async (_label, arrange) => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    const before = structuredClone(context.store.getState().workOrder);
    await arrange(context);

    const result = await context.commands.regenerateTasks('PLAN-001');

    expect(result.ok).toBe(false);
    expect(context.store.getState().workOrder).toEqual(before);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(2);
  });

  it('returns DEMO-VERSION-001 after API when a participating Resource version drifts', async () => {
    let resolveGateway: ((value: TaskDecompositionGatewaySuccess) => void) | undefined;
    const context = createContext({
      gateway: () => new Promise((resolve) => {
        resolveGateway = resolve;
      }),
    });

    const pending = context.commands.generateTasks('PLAN-001');
    await vi.waitFor(() => expect(context.decomposePlan).toHaveBeenCalledOnce());
    context.store.replaceDomainState((candidate) => {
      const resource = candidate.resource.resources.find(({ id }) => id === 'RESOURCE-001');
      if (!resource) throw new Error('RESOURCE-001 missing.');
      resource.version += 1;
    });
    if (!resolveGateway) throw new Error('Gateway resolver missing.');
    resolveGateway(gatewaySuccess());

    await expect(pending).resolves.toMatchObject({ ok: false, errorCode: 'DEMO-VERSION-001' });
    expect(owned(context).workOrders).toEqual([]);
  });

  it('blocks SCN-05 force-stop before API with TOS-IL-001 and no task write', async () => {
    const context = createContext({ scenarioId: 'SCN-05' });

    const result = await context.commands.generateTasks('PLAN-001');

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-IL-001' });
    expect(context.decomposePlan).not.toHaveBeenCalled();
    expect(owned(context).workOrders).toEqual([]);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('replays the first frozen result for the same commandId without another API, commit, workflow, or audit', async () => {
    const context = createContext({ commandFormatter: () => 'CMD-C06-REPLAY' });
    const workflowListener = vi.fn();
    context.workflow.subscribe(workflowListener);

    const first = await context.commands.generateTasks('PLAN-001');
    const second = await context.commands.generateTasks('PLAN-001');

    expect(second).toBe(first);
    expect(context.decomposePlan).toHaveBeenCalledOnce();
    expect(owned(context).workOrders).toHaveLength(4);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(workflowListener).toHaveBeenCalledOnce();
  });

  it('runs TD-02 through task:edit, sends only SPLIT mode, rewires the tree, and records reason', async () => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    context.workflow.selectNodes(['C06-NODE-PLAN-001-G001-02']);
    context.workflow.openEditor('SPLIT', 'C06-NODE-PLAN-001-G001-02');
    context.workflow.setReason('按演示卸车波次拆分');

    const result = await context.commands.splitTask({
      planId: 'PLAN-001',
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      reason: '按演示卸车波次拆分',
    });

    expect(result).toMatchObject({ ok: true, commandId: 'CMD-C06-002' });
    expect(context.decomposePlan).toHaveBeenLastCalledWith('PLAN-001', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'SPLIT',
    });
    expect(owned(context).workOrders.map(({ id }) => id)).toContain(
      'C06-WO-PLAN-001-G001-02-S001-A',
    );
    expect(owned(context).workOrders.map(({ id }) => id)).toContain(
      'C06-WO-PLAN-001-G001-02-S001-B',
    );
    expect(context.workflow.getState()).toEqual({
      selectedNodeIds: [],
      editDrawerOpen: false,
      rulePanelOpen: false,
      reason: '',
    });
    expect(context.store.getState().configAudit.commandAudit.at(-1)).toMatchObject({
      record: { action: 'TD-02', reason: '按演示卸车波次拆分' },
      metadata: { result: 'SUCCESS' },
    });
  });

  it('runs TD-03 only for a valid direct pair and records merge provenance', async () => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    await context.commands.splitTask({
      planId: 'PLAN-001',
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      reason: '按演示卸车波次拆分',
    });
    const nodeIds = [
      'C06-NODE-PLAN-001-G001-02-S001-A',
      'C06-NODE-PLAN-001-G001-02-S001-B',
    ] as const;
    context.workflow.selectNodes(nodeIds);
    context.workflow.openEditor('MERGE');
    context.workflow.setReason('恢复连续卸车阶段');

    const result = await context.commands.mergeTasks({
      planId: 'PLAN-001',
      nodeIds,
      reason: '恢复连续卸车阶段',
    });

    expect(result).toMatchObject({ ok: true, commandId: 'CMD-C06-003' });
    expect(context.decomposePlan).toHaveBeenLastCalledWith('PLAN-001', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'MERGE',
    });
    expect(owned(context).workOrders.map(({ id }) => id)).toContain(
      'C06-WO-PLAN-001-G001-02-M001',
    );
    expect(context.store.getState().configAudit.commandAudit.at(-1)?.record).toMatchObject({
      action: 'TD-03',
      reason: '恢复连续卸车阶段',
    });
  });

  it('rejects empty reason or an invalid merge before API and preserves workflow/store state', async () => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    context.workflow.selectNodes(['C06-NODE-PLAN-001-G001-02']);
    context.workflow.openEditor('SPLIT', 'C06-NODE-PLAN-001-G001-02');
    context.workflow.setReason('保留的表单原因');
    const before = structuredClone(context.store.getState().workOrder);
    const callsBefore = context.decomposePlan.mock.calls.length;

    const noReason = await context.commands.splitTask({
      planId: 'PLAN-001',
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      reason: '   ',
    });
    const invalidMerge = await context.commands.mergeTasks({
      planId: 'PLAN-001',
      nodeIds: [
        'C06-NODE-PLAN-001-G001-02',
        'C06-NODE-PLAN-001-G001-04',
      ],
      reason: '不允许的非相邻合并',
    });

    expect(noReason).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(invalidMerge).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(context.decomposePlan).toHaveBeenCalledTimes(callsBefore);
    expect(context.store.getState().workOrder).toEqual(before);
    expect(context.workflow.getState()).toMatchObject({
      selectedNodeIds: ['C06-NODE-PLAN-001-G001-02'],
      editDrawerOpen: true,
      reason: '保留的表单原因',
    });
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(3);
  });

  it('preserves split editor state and graph when API-007 fails', async () => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    context.workflow.selectNodes(['C06-NODE-PLAN-001-G001-02']);
    context.workflow.openEditor('SPLIT', 'C06-NODE-PLAN-001-G001-02');
    context.workflow.setReason('按演示卸车波次拆分');
    context.decomposePlan.mockResolvedValueOnce(gatewayFailure('TOS-EXT-001'));
    const before = structuredClone(context.store.getState().workOrder);

    const result = await context.commands.splitTask({
      planId: 'PLAN-001',
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      reason: '按演示卸车波次拆分',
    });

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });
    expect(context.store.getState().workOrder).toEqual(before);
    expect(context.workflow.getState()).toMatchObject({
      selectedNodeIds: ['C06-NODE-PLAN-001-G001-02'],
      editDrawerOpen: true,
      reason: '按演示卸车波次拆分',
      lastCommandError: { errorCode: 'TOS-EXT-001' },
    });
  });

  it('exposes the complete five-action service contract', () => {
    const context = createContext();
    expect(Object.keys(context.commands).sort()).toEqual([
      'confirmTasks',
      'generateTasks',
      'mergeTasks',
      'regenerateTasks',
      'resetCommandState',
      'splitTask',
    ]);
  });
});

describe('TaskDecompositionCommandService TD-05', () => {
  it('confirms one complete draft atomically without assigning resource instances', async () => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    const before = structuredClone(context.store.getState());
    const beforeGeneration = owned(context);

    const result = await context.commands.confirmTasks('PLAN-001');

    expect(result).toMatchObject({ ok: true, commandId: 'CMD-C06-002', traceId: 'TRACE-C06-002' });
    expect(context.decomposePlan).toHaveBeenLastCalledWith('PLAN-001', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'CONFIRM',
    });
    const after = context.store.getState();
    const plan = after.plan.plans.find(({ id }) => id === 'PLAN-001');
    expect(plan).toMatchObject({
      status: 'DECOMPOSED',
      version: before.plan.plans.find(({ id }) => id === 'PLAN-001')!.version + 1,
      updatedAt: after.session.demoTime,
    });
    const afterGeneration = owned(context);
    expect(afterGeneration.workOrders.every(({ status }) => status === 'READY')).toBe(true);
    expect(afterGeneration.workOrders.every(({ resourceId, teamId }) =>
      resourceId === '' && teamId === '',
    )).toBe(true);
    expect(afterGeneration.nodes.every(({ status }) => status === 'WAITING')).toBe(true);
    expect(afterGeneration.workOrders.map(({ id, version, createdAt, updatedAt }) => ({
      id, version, createdAt, updatedAt,
    }))).toEqual(beforeGeneration.workOrders.map(({ id, version, createdAt }) => ({
      id,
      version: version + 1,
      createdAt,
      updatedAt: after.session.demoTime,
    })));
    expect(afterGeneration.nodes.map(({ id, version, updatedAt }) => ({ id, version, updatedAt })))
      .toEqual(beforeGeneration.nodes.map(({ id, version }) => ({
        id,
        version: version + 1,
        updatedAt: after.session.demoTime,
      })));
    expect(after.resource).toEqual(before.resource);
    expect(after.recommendation).toEqual(before.recommendation);
    expect(after.plan.waybills).toEqual(before.plan.waybills);
    expect(after.plan.plans.filter(({ id }) => id !== 'PLAN-001'))
      .toEqual(before.plan.plans.filter(({ id }) => id !== 'PLAN-001'));
    expect(after.workOrder.workOrders.filter(({ id }) => !id.startsWith('C06-WO-')))
      .toEqual(before.workOrder.workOrders.filter(({ id }) => !id.startsWith('C06-WO-')));
    expect(after.workOrder.nodes.filter(({ id }) => !id.startsWith('C06-NODE-')))
      .toEqual(before.workOrder.nodes.filter(({ id }) => !id.startsWith('C06-NODE-')));
    expect(after.configAudit.commandAudit.map(({ record }) => record.action)).toEqual([
      'TD-01', 'TD-05',
    ]);
  });

  it.each([
    ['non-confirmed Plan', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.plan.plans.find(({ id }) => id === 'PLAN-001')!.status = 'PENDING_CONFIRM';
      });
    }],
    ['Plan missingFields', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.plan.plans.find(({ id }) => id === 'PLAN-001')!.missingFields = ['trackNo'];
      });
    }],
    ['missing recommendation', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        delete candidate.recommendation.drafts['PLAN-001'];
      });
    }],
    ['non-confirmed recommendation', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        const draft = recommendationDraftSchema.parse(candidate.recommendation.drafts['PLAN-001']);
        candidate.recommendation.drafts['PLAN-001'] = { ...draft, status: 'DRAFT', confirmation: undefined };
      });
    }],
    ['zero draft nodes', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.workOrders = candidate.workOrder.workOrders.filter(
          ({ id }) => !id.startsWith('C06-WO-'),
        );
        candidate.workOrder.nodes = candidate.workOrder.nodes.filter(
          ({ id }) => !id.startsWith('C06-NODE-'),
        );
      });
    }],
    ['READY WorkOrder', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.workOrders.find(({ id }) => id === 'C06-WO-PLAN-001-G001-02')!.status = 'READY';
      });
    }],
    ['READY WorkNode', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.nodes.find(({ id }) => id === 'C06-NODE-PLAN-001-G001-02')!.status = 'READY';
      });
    }],
    ['orphan parent', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.workOrders.find(({ id }) => id === 'C06-WO-PLAN-001-G001-02')!.parentId = 'C06-WO-MISSING';
      });
    }],
    ['cycle', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.workOrders.find(({ id }) => id === 'C06-WO-PLAN-001-G001-01')!.parentId =
          'C06-WO-PLAN-001-G001-04';
      });
    }],
    ['duplicate WorkOrder ID', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.workOrders.find(({ id }) => id === 'C06-WO-PLAN-001-G001-02')!.id =
          'C06-WO-PLAN-001-G001-01';
      });
    }],
    ['noncontiguous sequence', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.nodes.find(({ id }) => id === 'C06-NODE-PLAN-001-G001-04')!.sequence = 8;
      });
    }],
    ['mismatched pair', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.nodes.find(({ id }) => id === 'C06-NODE-PLAN-001-G001-04')!.workOrderNo =
          'C06-WO-PLAN-001-G001-03';
      });
    }],
    ['missing required resource type', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.resource.resources = candidate.resource.resources.filter(
          ({ resourceType }) => resourceType !== 'TEAM',
        );
      });
    }],
  ] as const)('rejects %s before API and preserves the candidate graph', async (_label, arrange) => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    arrange(context);
    const before = structuredClone(context.store.getState());
    const callsBefore = context.decomposePlan.mock.calls.length;

    const result = await context.commands.confirmTasks('PLAN-001');

    expect(result).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(context.decomposePlan).toHaveBeenCalledTimes(callsBefore);
    expect(context.store.getState().plan).toEqual(before.plan);
    expect(context.store.getState().workOrder).toEqual(before.workOrder);
  });

  it('blocks TD-05 force-stop before API and keeps the DRAFT generation intact', async () => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    context.store.replaceDomainState((candidate) => {
      const scenario = candidate.scenario.scenarios.find(({ id }) => id === 'SCN-05')!;
      candidate.session.scenarioId = scenario.id;
      candidate.session.demoTime = scenario.clock;
      candidate.scenario.activeScenarioId = scenario.id;
      candidate.scenario.activeFault = structuredClone(scenario.fault);
      candidate.scenario.resetPoint = scenario.resetPoint;
    });
    const callsBefore = context.decomposePlan.mock.calls.length;

    const result = await context.commands.confirmTasks('PLAN-001');

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-IL-001' });
    expect(context.decomposePlan).toHaveBeenCalledTimes(callsBefore);
    expect(owned(context).workOrders.every(({ status }) => status === 'DRAFT')).toBe(true);
  });

  it.each([
    ['Plan', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.plan.plans.find(({ id }) => id === 'PLAN-001')!.version += 1;
      });
    }],
    ['RecommendationDraft', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        const draft = recommendationDraftSchema.parse(candidate.recommendation.drafts['PLAN-001']);
        candidate.recommendation.drafts['PLAN-001'] = { ...draft, draftVersion: draft.draftVersion + 1 };
      });
    }],
    ['Resource', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.resource.resources[0]!.version += 1;
      });
    }],
    ['WorkOrder', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.workOrders.find(({ id }) => id === 'C06-WO-PLAN-001-G001-01')!.version += 1;
      });
    }],
    ['WorkNode', (context: ReturnType<typeof createContext>) => {
      context.store.replaceDomainState((candidate) => {
        candidate.workOrder.nodes.find(({ id }) => id === 'C06-NODE-PLAN-001-G001-01')!.version += 1;
      });
    }],
  ] as const)('maps post-API %s drift to DEMO-VERSION-001 without a partial confirmation', async (_label, mutate) => {
    let apiCall = 0;
    let resolveConfirm: ((value: TaskDecompositionGatewaySuccess) => void) | undefined;
    const context = createContext({
      gateway: () => {
        apiCall += 1;
        if (apiCall === 1) return Promise.resolve(gatewaySuccess());
        return new Promise((resolve) => {
          resolveConfirm = resolve;
        });
      },
    });
    await context.commands.generateTasks('PLAN-001');
    const pending = context.commands.confirmTasks('PLAN-001');
    await vi.waitFor(() => expect(context.decomposePlan).toHaveBeenCalledTimes(2));
    mutate(context);
    if (!resolveConfirm) throw new Error('Confirm resolver missing.');
    resolveConfirm(gatewaySuccess());

    await expect(pending).resolves.toMatchObject({ ok: false, errorCode: 'DEMO-VERSION-001' });
    expect(context.store.getState().plan.plans.find(({ id }) => id === 'PLAN-001')!.status)
      .toBe('CONFIRMED');
    expect(owned(context).workOrders.every(({ status }) => status === 'DRAFT')).toBe(true);
  });

  it.each([
    ['malformed API-007', async (context: ReturnType<typeof createContext>) => {
      context.decomposePlan.mockRejectedValueOnce(new ZodError([]));
    }],
    ['transport rejection', async (context: ReturnType<typeof createContext>) => {
      context.decomposePlan.mockRejectedValueOnce(new Error('API-007 disconnected'));
    }],
  ] as const)('%s preserves DRAFT records and workflow input', async (_label, arrange) => {
    const context = createContext();
    await context.commands.generateTasks('PLAN-001');
    context.workflow.selectNodes(['C06-NODE-PLAN-001-G001-02']);
    context.workflow.openEditor('SPLIT', 'C06-NODE-PLAN-001-G001-02');
    context.workflow.setReason('保留确认前编辑输入');
    await arrange(context);

    const result = await context.commands.confirmTasks('PLAN-001');

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });
    expect(owned(context).workOrders.every(({ status }) => status === 'DRAFT')).toBe(true);
    expect(context.workflow.getState()).toMatchObject({
      selectedNodeIds: ['C06-NODE-PLAN-001-G001-02'],
      editDrawerOpen: true,
      reason: '保留确认前编辑输入',
    });
  });

  it('uses the frozen DO-001 and DO-005 transitions without extending the catalog', () => {
    expect(transitionState({
      machineId: 'DO-001', current: 'CONFIRMED', command: 'decompose',
    })).toEqual({
      ok: true, previous: 'CONFIRMED', next: 'DECOMPOSED', command: 'decompose',
    });
    expect(transitionState({
      machineId: 'DO-005', current: 'DRAFT', command: 'assign',
    })).toEqual({
      ok: true, previous: 'DRAFT', next: 'READY', command: 'assign',
    });
  });
});
