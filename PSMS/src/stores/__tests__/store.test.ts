import { describe, expect, it } from 'vitest';

import { createMockRuntime } from '../../mocks/scenarios';
import {
  createDemoStore,
  selectPlanWorkOrders,
  type DemoSessionSeed,
} from '..';

const sliceKeys = [
  'session',
  'scenario',
  'plan',
  'recommendation',
  'workOrder',
  'resource',
  'vehicle',
  'exception',
  'interlock',
  'offline',
  'report',
  'systemConfig',
  'configAudit',
] as const;

const baseSession: DemoSessionSeed = {
  actorId: 'USER-001',
  roleCode: 'DISPATCHER',
  dataScope: ['AREA-A'],
  shiftId: 'SHIFT-001',
  online: true,
  scenarioId: 'SCN-01',
};

describe('C03 atomic domain store', () => {
  it('composes exactly 13 named slices and maps DO-001 through DO-015 once', () => {
    const runtime = createMockRuntime();
    const store = createDemoStore(runtime.getSnapshot(), baseSession);
    const state = store.getState();

    expect(Object.keys(state)).toEqual(sliceKeys);
    expect({
      plans: state.plan.plans.length,
      waybills: state.plan.waybills.length,
      recommendationDrafts: Object.keys(state.recommendation.drafts).length,
      workOrders: state.workOrder.workOrders.length,
      workNodes: state.workOrder.nodes.length,
      tracks: state.resource.tracks.length,
      materials: state.resource.materials.length,
      resources: state.resource.resources.length,
      appointments: state.vehicle.appointments.length,
      exceptions: state.exception.exceptions.length,
      interlocks: state.interlock.interlocks.length,
      offlinePackets: state.offline.packets.length,
      reports: state.report.reports.length,
      configVersions: state.systemConfig.configVersions.length,
      audit: state.configAudit.audit.length,
      userRoles: state.configAudit.userRoles.length,
    }).toEqual({
      plans: 3,
      waybills: 8,
      recommendationDrafts: 0,
      workOrders: 12,
      workNodes: 12,
      tracks: 4,
      materials: 8,
      resources: 10,
      appointments: 6,
      exceptions: 5,
      interlocks: 4,
      offlinePackets: 4,
      reports: 3,
      configVersions: 1,
      audit: 9,
      userRoles: 13,
    });
    expect(state.scenario.scenarios).toHaveLength(7);
    expect(state.scenario.activeScenarioId).toBe('SCN-01');
    expect(state.session.demoTime).toBe(runtime.getActiveScenario().clock);
  });

  it('owns one deeply frozen DO-015 record outside configAudit', () => {
    const runtime = createMockRuntime();
    const store = createDemoStore(runtime.getSnapshot(), baseSession);
    const config = store.getState().systemConfig.configVersions[0];

    expect(config).toEqual(runtime.getSnapshot().objects['DO-015'][0]);
    expect(config.id).toBe('CFG-001');
    expect(store.getState().configAudit).not.toHaveProperty('configVersions');
    expect(() => { config.displayName = 'caller mutation'; }).toThrow();
  });

  it('atomically validates and resets DO-015 without touching upstream slices', () => {
    const runtime = createMockRuntime();
    const snapshot = runtime.getSnapshot();
    const store = createDemoStore(snapshot, baseSession);
    const upstream = structuredClone({
      plan: store.getState().plan,
      workOrder: store.getState().workOrder,
      configAudit: store.getState().configAudit,
    });

    store.replaceDomainState((candidate) => {
      candidate.systemConfig.configVersions[0] = {
        ...candidate.systemConfig.configVersions[0], displayName: '草稿名称', version: 2,
      };
    });
    expect(store.getState().systemConfig.configVersions[0].displayName).toBe('草稿名称');
    expect(store.getState().plan).toEqual(upstream.plan);
    expect(store.getState().workOrder).toEqual(upstream.workOrder);
    expect(store.getState().configAudit).toEqual(upstream.configAudit);

    store.resetFromSnapshot(snapshot, baseSession);
    expect(store.getState().systemConfig.configVersions).toEqual(snapshot.objects['DO-015']);
  });

  it('deep-copies fixture input and never holds the Mock runtime mutable state', () => {
    const runtime = createMockRuntime();
    const snapshot = runtime.getSnapshot();
    const originalTrackNo = snapshot.objects['DO-001'][0].trackNo;
    const planId = snapshot.objects['DO-001'][0].id;
    const store = createDemoStore(snapshot, baseSession);

    snapshot.objects['DO-001'][0].trackNo = 'CALLER-MUTATION';
    runtime.updateObject(planId, { trackNo: 'RUNTIME-MUTATION' });

    expect(store.getState().plan.plans[0].trackNo).toBe(originalTrackNo);
    expect(() => {
      store.getState().plan.plans[0].trackNo = 'STORE-MUTATION';
    }).toThrow();
    expect(store.getState().plan.plans[0].trackNo).toBe(originalTrackNo);
  });

  it('commits a validated cross-slice candidate with exactly one root notification', () => {
    const runtime = createMockRuntime();
    const store = createDemoStore(runtime.getSnapshot(), baseSession);
    let notifications = 0;
    store.subscribe(() => {
      notifications += 1;
    });

    store.replaceDomainState((candidate) => {
      candidate.plan.plans[0] = {
        ...candidate.plan.plans[0],
        status: 'CONFIRMED',
        version: candidate.plan.plans[0].version + 1,
      };
      candidate.workOrder.workOrders[0] = {
        ...candidate.workOrder.workOrders[0],
        status: 'READY',
        version: candidate.workOrder.workOrders[0].version + 1,
      };
    });

    expect(notifications).toBe(1);
    expect(store.getState().plan.plans[0].status).toBe('CONFIRMED');
    expect(store.getState().workOrder.workOrders[0].status).toBe('READY');
  });

  it('leaves state and notifications unchanged when candidate construction throws', () => {
    const runtime = createMockRuntime();
    const store = createDemoStore(runtime.getSnapshot(), baseSession);
    const before = store.getState();
    let notifications = 0;
    store.subscribe(() => {
      notifications += 1;
    });

    expect(() => {
      store.replaceDomainState((candidate) => {
        candidate.plan.plans[0] = { ...candidate.plan.plans[0], status: 'CONFIRMED' };
        throw new Error('candidate failed');
      });
    }).toThrow('candidate failed');

    expect(store.getState()).toBe(before);
    expect(notifications).toBe(0);
  });

  it('rejects an invalid complete candidate without a partial commit', () => {
    const runtime = createMockRuntime();
    const store = createDemoStore(runtime.getSnapshot(), baseSession);
    const before = store.getState();
    let notifications = 0;
    store.subscribe(() => {
      notifications += 1;
    });

    expect(() => {
      store.replaceDomainState((candidate) => {
        candidate.plan.plans[0] = {
          ...candidate.plan.plans[0],
          status: 'NOT_A_PLAN_STATUS' as never,
        };
      });
    }).toThrow();

    expect(store.getState()).toBe(before);
    expect(notifications).toBe(0);
  });

  it.each(['SCN-01', 'SCN-02', 'SCN-03', 'SCN-04', 'SCN-05', 'SCN-06', 'SCN-07'] as const)(
    'atomically resets all 13 slices for %s',
    (scenarioId) => {
      const runtime = createMockRuntime();
      runtime.reset(scenarioId);
      const session = { ...baseSession, scenarioId };
      const snapshot = runtime.getSnapshot();
      const store = createDemoStore(snapshot, session);

      store.replaceDomainState((candidate) => {
        candidate.plan.plans = [];
        candidate.recommendation.drafts = { temporary: { score: 1 } };
        candidate.configAudit.audit = [];
      });

      let notifications = 0;
      store.subscribe(() => {
        notifications += 1;
      });
      store.resetFromSnapshot(snapshot, session);

      expect(notifications).toBe(1);
      expect(store.getState()).toEqual(createDemoStore(snapshot, session).getState());
      expect(store.getState().scenario.activeScenarioId).toBe(scenarioId);
    },
  );

  it('keeps a cross-slice selector referentially stable for an unchanged root state', () => {
    const runtime = createMockRuntime();
    const store = createDemoStore(runtime.getSnapshot(), baseSession);
    const initialState = store.getState();
    const first = selectPlanWorkOrders(initialState);

    expect(first).toBe(selectPlanWorkOrders(initialState));
    expect(first).toHaveLength(12);

    store.replaceDomainState((candidate) => {
      candidate.recommendation.drafts = { 'PLAN-001': { score: 100 } };
    });

    const nextState = store.getState();
    expect(nextState).not.toBe(initialState);
    expect(selectPlanWorkOrders(nextState)).not.toBe(first);
    expect(selectPlanWorkOrders(nextState)).toBe(selectPlanWorkOrders(nextState));
  });
});
