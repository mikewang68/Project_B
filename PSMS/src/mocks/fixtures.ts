import { z } from 'zod';
import fixtureSource from '../../docs/baseline/demo-fixtures.json';
import {
  domainObjectCounts,
  domainObjectIds,
  domainSchemas,
  jsonObjectSchema,
  publicErrorCodeSchema,
  type DomainObjectId,
  type PublicErrorCode,
} from '../contracts';

export type DomainRecord = Record<string, unknown> & { id: string };
export type FixtureObjects = Record<DomainObjectId, DomainRecord[]>;

export type FaultMutation = {
  objectId: string;
  omitFields?: string[];
  replaceValues?: Record<string, unknown>;
};

export type DemoScenario = {
  id: `SCN-${string}`;
  name: string;
  initialState: unknown;
  events: unknown[];
  expected: unknown;
  resetPoint: string;
  clock: string;
  seedRefs: string[];
  fault: {
    type:
      | 'NONE'
      | 'VALIDATION_MISSING_FIELD'
      | 'INTERFACE_TIMEOUT'
      | 'DEVICE_OFFLINE'
      | 'INTERLOCK_FORCE_STOP'
      | 'OFFLINE_VERSION_CONFLICT'
      | 'SOURCE_DATA_CONFLICT';
    errorCode: PublicErrorCode | null;
    delayMs: number;
    mutations: FaultMutation[];
  };
  reset: {
    restoreBaseObjects: boolean;
    restoreScenarioOverlay: boolean;
    resetTraceCounter: number;
    resetAuditCounter: number;
    clearFaultState: boolean;
  };
};

export type ErrorCodeDefinition = {
  code: PublicErrorCode;
  meaning: string;
  uiHandling: string;
  audit: boolean;
  httpStatus: 400 | 403 | 409;
};

export type FixtureSnapshot = {
  version: string;
  timezone: string;
  objects: FixtureObjects;
  roles: Array<Record<string, unknown>>;
  scenarios: DemoScenario[];
  acceptanceScenarios: Array<Record<string, unknown>>;
  errorCodes: ErrorCodeDefinition[];
};

const scenarioIds = ['SCN-01', 'SCN-02', 'SCN-03', 'SCN-04', 'SCN-05', 'SCN-06', 'SCN-07'] as const;
const faultTypeSchema = z.enum([
  'NONE',
  'VALIDATION_MISSING_FIELD',
  'INTERFACE_TIMEOUT',
  'DEVICE_OFFLINE',
  'INTERLOCK_FORCE_STOP',
  'OFFLINE_VERSION_CONFLICT',
  'SOURCE_DATA_CONFLICT',
]);

const faultMutationSchema = z
  .object({
    objectId: z.string(),
    omitFields: z.array(z.string()).optional(),
    replaceValues: jsonObjectSchema.optional(),
  })
  .strict();

const demoScenarioSchema = z
  .object({
    id: z.enum(scenarioIds),
    name: z.string(),
    initialState: z.unknown(),
    events: z.array(z.unknown()),
    expected: z.unknown(),
    resetPoint: z.string(),
    clock: z.string(),
    seedRefs: z.array(z.string()),
    fault: z
      .object({
        type: faultTypeSchema,
        errorCode: publicErrorCodeSchema.nullable(),
        delayMs: z.number().int().nonnegative(),
        mutations: z.array(faultMutationSchema),
      })
      .strict(),
    reset: z
      .object({
        restoreBaseObjects: z.boolean(),
        restoreScenarioOverlay: z.boolean(),
        resetTraceCounter: z.number().int().positive(),
        resetAuditCounter: z.number().int().positive(),
        clearFaultState: z.boolean(),
      })
      .strict(),
  })
  .strict();

const errorCodeDefinitionSchema = z
  .object({
    code: publicErrorCodeSchema,
    meaning: z.string(),
    uiHandling: z.string(),
    audit: z.boolean(),
    httpStatus: z.union([z.literal(400), z.literal(403), z.literal(409)]),
  })
  .strict();

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function validateFixtureSnapshot(input: unknown): FixtureSnapshot {
  if (!isRecord(input) || !isRecord(input.objects)) {
    throw new Error('Fixture must contain an objects map.');
  }

  const candidate = structuredClone(input) as FixtureSnapshot;
  const objectKeys = Object.keys(candidate.objects);
  if (JSON.stringify(objectKeys) !== JSON.stringify(domainObjectIds)) {
    throw new Error(`Fixture domain objects mismatch: ${objectKeys.join(', ')}`);
  }

  for (const objectId of domainObjectIds) {
    const records = candidate.objects[objectId];
    if (!Array.isArray(records) || records.length !== domainObjectCounts[objectId]) {
      throw new Error(`Fixture count mismatch for ${objectId}.`);
    }
    const schema = domainSchemas[objectId] as z.ZodTypeAny;
    for (const record of records) schema.parse(record);
  }

  if (!Array.isArray(candidate.roles) || candidate.roles.length !== 13) {
    throw new Error('Fixture must contain 13 roles.');
  }
  for (const role of candidate.roles) jsonObjectSchema.parse(role);

  if (!Array.isArray(candidate.scenarios) || candidate.scenarios.length !== scenarioIds.length) {
    throw new Error('Fixture must contain seven scenarios.');
  }
  const parsedScenarios = candidate.scenarios.map((scenario) => demoScenarioSchema.parse(scenario));
  if (JSON.stringify(parsedScenarios.map(({ id }) => id)) !== JSON.stringify(scenarioIds)) {
    throw new Error('Fixture scenario IDs are not SCN-01 through SCN-07.');
  }

  const allObjectIds = new Set(
    domainObjectIds.flatMap((objectId) => candidate.objects[objectId].map(({ id }) => id)),
  );
  for (const scenario of parsedScenarios) {
    for (const seedRef of scenario.seedRefs) {
      if (!allObjectIds.has(seedRef)) throw new Error(`Unknown scenario seedRef: ${seedRef}`);
    }
  }

  if (!Array.isArray(candidate.errorCodes) || candidate.errorCodes.length !== 9) {
    throw new Error('Fixture must contain nine public error codes.');
  }
  const parsedErrors = candidate.errorCodes.map((error) => errorCodeDefinitionSchema.parse(error));
  if (new Set(parsedErrors.map(({ code }) => code)).size !== 9) {
    throw new Error('Fixture public error codes must be unique.');
  }

  if (!Array.isArray(candidate.acceptanceScenarios) || candidate.acceptanceScenarios.length !== 14) {
    throw new Error('Fixture must contain 14 acceptance scenarios.');
  }

  return candidate;
}

export function createFixtureSnapshot(): FixtureSnapshot {
  return validateFixtureSnapshot(structuredClone(fixtureSource));
}
