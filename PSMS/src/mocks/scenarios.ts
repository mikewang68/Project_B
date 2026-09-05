import { publicErrorCodeSchema, type PublicErrorCode } from '../contracts';
import { EnvelopeIdSequence } from './clock';
import {
  createFixtureSnapshot,
  validateFixtureSnapshot,
  type DemoScenario,
  type DomainRecord,
  type FixtureSnapshot,
} from './fixtures';

export type FailureStatus = 400 | 403 | 409;
export type RuntimeFailure = {
  status: FailureStatus;
  errorCode: PublicErrorCode;
  message: string;
  details?: Record<string, unknown>;
};

type RuntimeState = {
  fixtures: FixtureSnapshot;
  activeScenarioId: string;
  clock: string;
  ids: EnvelopeIdSequence;
  forcedFailure?: RuntimeFailure;
  resolvedFaultObjects: Record<string, string[]>;
};

function clone<T>(value: T): T {
  return structuredClone(value);
}

function findObject(snapshot: FixtureSnapshot, objectId: string): DomainRecord | undefined {
  for (const records of Object.values(snapshot.objects)) {
    const found = records.find(({ id }) => id === objectId);
    if (found) return found;
  }
  return undefined;
}

function createState(scenarioId: string): RuntimeState {
  const fixtures = createFixtureSnapshot();
  const scenario = fixtures.scenarios.find(({ id }) => id === scenarioId);
  if (!scenario) throw new Error(`Unknown scenario: ${scenarioId}`);

  return {
    fixtures,
    activeScenarioId: scenario.id,
    clock: scenario.clock,
    ids: new EnvelopeIdSequence(scenario.reset.resetTraceCounter, scenario.reset.resetAuditCounter),
    resolvedFaultObjects: {},
  };
}

export class MockRuntime {
  private state: RuntimeState;

  constructor() {
    this.state = createState('SCN-01');
  }

  reset(scenarioId = 'SCN-01'): void {
    const candidate = createState(scenarioId);
    this.state = candidate;
  }

  getSnapshot(): FixtureSnapshot {
    return clone(this.state.fixtures);
  }

  getProjectedSnapshot(options: { restoreResolvedObjectId?: string } = {}): FixtureSnapshot {
    const projected = this.getSnapshot();
    const scenario = this.getActiveScenario();

    for (const mutation of scenario.fault.mutations) {
      const object = findObject(projected, mutation.objectId);
      if (!object) throw new Error(`Scenario mutation target not found: ${mutation.objectId}`);
      for (const field of mutation.omitFields ?? []) {
        const restored =
          options.restoreResolvedObjectId === mutation.objectId &&
          this.hasResolvedFaultFields(mutation.objectId, [field]);
        if (!restored) delete object[field];
      }
      if (mutation.replaceValues) Object.assign(object, clone(mutation.replaceValues));
    }

    return projected;
  }

  resolveFaultFields(objectId: string, fields: readonly string[]): void {
    const existing = this.state.resolvedFaultObjects[objectId] ?? [];
    this.state.resolvedFaultObjects[objectId] = [...new Set([...existing, ...fields])].sort();
  }

  hasResolvedFaultFields(objectId: string, fields: readonly string[]): boolean {
    const resolved = new Set(this.state.resolvedFaultObjects[objectId] ?? []);
    return fields.length > 0 && fields.every((field) => resolved.has(field));
  }

  getResolvedFaultObjects(): Readonly<Record<string, readonly string[]>> {
    return clone(this.state.resolvedFaultObjects);
  }

  getScenarioCatalog(): DemoScenario[] {
    return clone(this.state.fixtures.scenarios);
  }

  getActiveScenario(): DemoScenario {
    const scenario = this.state.fixtures.scenarios.find(({ id }) => id === this.state.activeScenarioId);
    if (!scenario) throw new Error(`Active scenario missing: ${this.state.activeScenarioId}`);
    return clone(scenario);
  }

  getObject(objectId: string): DomainRecord | undefined {
    const object = findObject(this.state.fixtures, objectId);
    return object ? clone(object) : undefined;
  }

  updateObject(objectId: string, patch: Record<string, unknown>): void {
    const candidate = this.getSnapshot();
    const object = findObject(candidate, objectId);
    if (!object) throw new Error(`Object not found: ${objectId}`);
    Object.assign(object, clone(patch));

    const validated = validateFixtureSnapshot(candidate);
    this.state.fixtures = validated;
  }

  now(): string {
    return this.state.clock;
  }

  nextEnvelopeIds(): { traceId: string; auditLogId: string } {
    return this.state.ids.next();
  }

  forceNextFailure(failure: RuntimeFailure): void {
    publicErrorCodeSchema.parse(failure.errorCode);
    this.state.forcedFailure = clone(failure);
  }

  consumeForcedFailure(): RuntimeFailure | undefined {
    const failure = this.state.forcedFailure;
    this.state.forcedFailure = undefined;
    return failure ? clone(failure) : undefined;
  }

  getErrorDefinition(errorCode: PublicErrorCode) {
    const definition = this.state.fixtures.errorCodes.find(({ code }) => code === errorCode);
    if (!definition) throw new Error(`Error definition not found: ${errorCode}`);
    return clone(definition);
  }
}

export function createMockRuntime(): MockRuntime {
  return new MockRuntime();
}

export const mockRuntime = createMockRuntime();
