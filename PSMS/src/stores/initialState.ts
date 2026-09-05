import { z } from 'zod';

import {
  do001Schema,
  do002Schema,
  do003Schema,
  do004Schema,
  do005Schema,
  do006Schema,
  do007Schema,
  do008Schema,
  do009Schema,
  do010Schema,
  do011Schema,
  do012Schema,
  do013Schema,
  do014Schema,
  do015Schema,
  roleCodeSchema,
} from '../contracts';
import { validateFixtureSnapshot, type DemoScenario, type FixtureSnapshot } from '../mocks/fixtures';
import { validateCommandAuditEntry } from '../governance/audit';
import {
  createConfigAuditSlice,
  createExceptionSlice,
  createInterlockSlice,
  createOfflineSlice,
  createPlanSlice,
  createRecommendationSlice,
  createReportSlice,
  createResourceSlice,
  createScenarioSlice,
  createSessionSlice,
  createSystemConfigSlice,
  createVehicleSlice,
  createWorkOrderSlice,
} from './slices';
import type { DemoRootState, DemoSessionSeed } from './types';

const sessionSeedSchema = z
  .object({
    actorId: z.string().min(1),
    roleCode: roleCodeSchema,
    dataScope: z.array(z.string().min(1)),
    shiftId: z.string().min(1),
    online: z.boolean(),
    scenarioId: z.custom<DemoScenario['id']>(
      (value) => typeof value === 'string' && /^SCN-\d{2}$/.test(value),
    ),
  })
  .strict();

function activeScenario(
  scenarios: DemoScenario[],
  scenarioId: DemoScenario['id'],
): DemoScenario {
  const scenario = scenarios.find(({ id }) => id === scenarioId);
  if (!scenario) throw new Error(`Unknown scenario: ${scenarioId}`);
  return scenario;
}

function validateScenarioSlice(state: DemoRootState['scenario']): DemoRootState['scenario'] {
  const scenarios = structuredClone(state.scenarios);
  const ids = scenarios.map(({ id }) => id);
  if (
    scenarios.length !== 7 ||
    new Set(ids).size !== 7 ||
    !ids.every((id, index) => id === `SCN-0${index + 1}`)
  ) {
    throw new Error('Scenario slice must contain SCN-01 through SCN-07 exactly once.');
  }

  const selected = activeScenario(scenarios, state.activeScenarioId);
  if (
    JSON.stringify(state.activeFault) !== JSON.stringify(selected.fault) ||
    state.resetPoint !== selected.resetPoint
  ) {
    throw new Error('Scenario projection does not match the active scenario.');
  }

  return createScenarioSlice({
    activeScenarioId: selected.id,
    scenarios,
    activeFault: structuredClone(selected.fault),
    resetPoint: selected.resetPoint,
  });
}

export function createInitialState(
  input: FixtureSnapshot,
  sessionInput: DemoSessionSeed,
): DemoRootState {
  const snapshot = validateFixtureSnapshot(input);
  const session = sessionSeedSchema.parse(structuredClone(sessionInput));
  const selected = activeScenario(snapshot.scenarios, session.scenarioId);

  return {
    session: createSessionSlice({ ...session, demoTime: selected.clock }),
    scenario: createScenarioSlice({
      activeScenarioId: selected.id,
      scenarios: structuredClone(snapshot.scenarios),
      activeFault: structuredClone(selected.fault),
      resetPoint: selected.resetPoint,
    }),
    plan: createPlanSlice({
      plans: do001Schema.array().parse(snapshot.objects['DO-001']),
      waybills: do002Schema.array().parse(snapshot.objects['DO-002']),
    }),
    recommendation: createRecommendationSlice({ drafts: {} }),
    workOrder: createWorkOrderSlice({
      workOrders: do005Schema.array().parse(snapshot.objects['DO-005']),
      nodes: do006Schema.array().parse(snapshot.objects['DO-006']),
    }),
    resource: createResourceSlice({
      tracks: do003Schema.array().parse(snapshot.objects['DO-003']),
      materials: do004Schema.array().parse(snapshot.objects['DO-004']),
      resources: do007Schema.array().parse(snapshot.objects['DO-007']),
    }),
    vehicle: createVehicleSlice({
      appointments: do008Schema.array().parse(snapshot.objects['DO-008']),
    }),
    exception: createExceptionSlice({
      exceptions: do009Schema.array().parse(snapshot.objects['DO-009']),
    }),
    interlock: createInterlockSlice({
      interlocks: do010Schema.array().parse(snapshot.objects['DO-010']),
    }),
    offline: createOfflineSlice({
      packets: do011Schema.array().parse(snapshot.objects['DO-011']),
    }),
    report: createReportSlice({ reports: do012Schema.array().parse(snapshot.objects['DO-012']) }),
    systemConfig: createSystemConfigSlice({
      configVersions: do015Schema.array().parse(structuredClone(snapshot.objects['DO-015'])),
    }),
    configAudit: createConfigAuditSlice({
      audit: do013Schema.array().parse(snapshot.objects['DO-013']),
      userRoles: do014Schema.array().parse(snapshot.objects['DO-014']),
      commandAudit: [],
    }),
  };
}

export function validateDemoRootState(input: DemoRootState): DemoRootState {
  const session = sessionSeedSchema.parse({
    actorId: input.session.actorId,
    roleCode: input.session.roleCode,
    dataScope: input.session.dataScope,
    shiftId: input.session.shiftId,
    online: input.session.online,
    scenarioId: input.session.scenarioId,
  });
  const scenario = validateScenarioSlice(input.scenario);
  const selected = activeScenario(scenario.scenarios, scenario.activeScenarioId);
  if (input.session.demoTime !== selected.clock || session.scenarioId !== scenario.activeScenarioId) {
    throw new Error('Session clock or scenario does not match the active scenario.');
  }

  return {
    session: createSessionSlice({ ...session, demoTime: selected.clock }),
    scenario,
    plan: createPlanSlice({
      plans: do001Schema.array().parse(structuredClone(input.plan.plans)),
      waybills: do002Schema.array().parse(structuredClone(input.plan.waybills)),
    }),
    recommendation: createRecommendationSlice({
      drafts: z.record(z.string(), z.unknown()).parse(structuredClone(input.recommendation.drafts)),
    }),
    workOrder: createWorkOrderSlice({
      workOrders: do005Schema.array().parse(structuredClone(input.workOrder.workOrders)),
      nodes: do006Schema.array().parse(structuredClone(input.workOrder.nodes)),
    }),
    resource: createResourceSlice({
      tracks: do003Schema.array().parse(structuredClone(input.resource.tracks)),
      materials: do004Schema.array().parse(structuredClone(input.resource.materials)),
      resources: do007Schema.array().parse(structuredClone(input.resource.resources)),
    }),
    vehicle: createVehicleSlice({
      appointments: do008Schema.array().parse(structuredClone(input.vehicle.appointments)),
    }),
    exception: createExceptionSlice({
      exceptions: do009Schema.array().parse(structuredClone(input.exception.exceptions)),
    }),
    interlock: createInterlockSlice({
      interlocks: do010Schema.array().parse(structuredClone(input.interlock.interlocks)),
    }),
    offline: createOfflineSlice({
      packets: do011Schema.array().parse(structuredClone(input.offline.packets)),
    }),
    report: createReportSlice({
      reports: do012Schema.array().parse(structuredClone(input.report.reports)),
    }),
    systemConfig: createSystemConfigSlice({
      configVersions: do015Schema.array().parse(
        structuredClone(input.systemConfig.configVersions),
      ),
    }),
    configAudit: createConfigAuditSlice({
      audit: do013Schema.array().parse(structuredClone(input.configAudit.audit)),
      userRoles: do014Schema.array().parse(structuredClone(input.configAudit.userRoles)),
      commandAudit: input.configAudit.commandAudit.map(validateCommandAuditEntry),
    }),
  };
}
