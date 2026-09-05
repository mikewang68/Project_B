import { HttpResponse, http, type HttpHandler } from 'msw';
import { exceptionCommandRequestSchema } from '../features/exception-handling/gateway';
import { offlinePacketCommandRequestSchema } from '../features/offline-sync/offlinePacketGateway';
import { interlockCommandRequestSchema } from '../features/safety-interlock/gateway';
import {
  apiCatalog,
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  requestSchemas,
  type ApiContract,
  type ApiId,
  type DomainObjectId,
  type PublicErrorCode,
} from '../contracts';
import { waitForMockDelay } from './delayPolicy';
import { type DemoScenario, type FixtureSnapshot } from './fixtures';
import { mockRuntime, type MockRuntime, type RuntimeFailure } from './scenarios';
import { withAppBasePath } from '../runtime/appBasePath';

const objectGroupsByApi: Partial<Record<ApiId, readonly DomainObjectId[]>> = {
  'API-001': ['DO-001', 'DO-005', 'DO-007', 'DO-008', 'DO-009', 'DO-010', 'DO-012'],
  'API-002': ['DO-001'],
  'API-004': ['DO-001'],
  'API-005': ['DO-001', 'DO-003'],
  'API-006': ['DO-001', 'DO-003'],
  'API-007': ['DO-001', 'DO-005', 'DO-006'],
  'API-008': ['DO-005', 'DO-007'],
  'API-009': ['DO-005', 'DO-007'],
  'API-010': ['DO-008'],
  'API-011': ['DO-008'],
  'API-012': ['DO-005', 'DO-006', 'DO-007'],
  'API-014': ['DO-009'],
  'API-015': ['DO-009'],
  'API-016': ['DO-010'],
  'API-017': ['DO-010'],
  'API-018': ['DO-011'],
  'API-019': ['DO-011'],
  'API-020': ['DO-012'],
  'API-021': ['DO-012'],
  'API-022': ['DO-015'],
  'API-023': ['DO-015'],
  'API-024': ['DO-013'],
};

type ValidatedRequest = {
  body?: Record<string, unknown>;
  pathId?: string;
};

function pathValue(params: Record<string, string | readonly string[] | undefined>, name: string) {
  const value = params[name];
  return Array.isArray(value) ? value[0] : value;
}

function validationFailure(runtime: MockRuntime, issues: unknown[]): Response {
  return failureResponse(runtime, {
    status: 400,
    errorCode: 'DEMO-SCENARIO-001',
    message: '请求结构、参数或场景不合法。',
    details: { issues },
  });
}

async function validateRequest(
  contract: ApiContract,
  request: Request,
  params: Record<string, string | readonly string[] | undefined>,
  runtime: MockRuntime,
): Promise<ValidatedRequest | Response> {
  const url = new URL(request.url);
  let pathId: string | undefined;
  const issues: Array<Record<string, unknown>> = [];

  for (const parameter of contract.parameters) {
    if (parameter.in === 'path') {
      const value = pathValue(params, parameter.name);
      if (parameter.required && (!value || value.length === 0)) {
        issues.push({ path: ['path', parameter.name], message: 'Required path parameter is missing.' });
      }
      if (parameter.name === 'id') pathId = value;
      continue;
    }

    const values = url.searchParams.getAll(parameter.name);
    if (parameter.required && values.length === 0) {
      issues.push({ path: ['query', parameter.name], message: 'Required query parameter is missing.' });
    }
    if (parameter.type === 'integer' && values.some((value) => !Number.isInteger(Number(value)))) {
      issues.push({ path: ['query', parameter.name], message: 'Expected an integer query parameter.' });
    }
  }

  let body: Record<string, unknown> | undefined;
  if (contract.requestSchema) {
    let rawBody: unknown;
    try {
      rawBody = await request.json();
    } catch {
      return validationFailure(runtime, [
        { path: ['body'], message: 'Expected a valid JSON request body.' },
      ]);
    }
    const requestSchema = contract.apiId === 'API-015'
      ? exceptionCommandRequestSchema
      : contract.apiId === 'API-017'
        ? interlockCommandRequestSchema
        : contract.apiId === 'API-019'
          ? offlinePacketCommandRequestSchema
          : requestSchemas[contract.requestSchema];
    const parsed = requestSchema.safeParse(rawBody);
    if (!parsed.success) {
      return validationFailure(
        runtime,
        parsed.error.issues.map(({ code, path, message }) => ({ code, path, message })),
      );
    }
    body = parsed.data as Record<string, unknown>;
  }

  if (issues.length > 0) return validationFailure(runtime, issues);
  return { body, pathId };
}

function failureResponse(runtime: MockRuntime, failure: RuntimeFailure): Response {
  const payload = apiErrorEnvelopeSchema.parse({
    ok: false,
    errorCode: failure.errorCode,
    message: failure.message,
    ...(failure.details ? { details: failure.details } : {}),
    ...runtime.nextEnvelopeIds(),
  });
  return HttpResponse.json(payload, { status: failure.status });
}

function scenarioAffectsRequest(
  contract: ApiContract,
  scenario: DemoScenario,
  validated: ValidatedRequest,
): boolean {
  if (scenario.fault.type === 'NONE') return false;
  if (scenario.fault.type === 'OFFLINE_VERSION_CONFLICT') {
    return contract.apiId === 'API-019'
      && validated.pathId !== undefined
      && scenario.seedRefs.includes(validated.pathId);
  }
  if (contract.apiId === 'API-001' || contract.apiId === 'API-003' || contract.apiId === 'API-013') {
    return true;
  }
  if (validated.pathId && scenario.seedRefs.includes(validated.pathId)) return true;

  const prefixesByApi: Partial<Record<ApiId, readonly string[]>> = {
    'API-002': ['PLAN-'],
    'API-004': ['PLAN-'],
    'API-005': ['PLAN-'],
    'API-006': ['PLAN-'],
    'API-007': ['PLAN-'],
    'API-008': ['WO-'],
    'API-009': ['WO-'],
    'API-010': ['APT-'],
    'API-011': ['APT-'],
    'API-012': ['WO-'],
    'API-016': ['IL-'],
    'API-017': ['IL-'],
    'API-018': ['OFF-'],
    'API-019': ['OFF-'],
  };
  const prefixes = prefixesByApi[contract.apiId] ?? [];
  return scenario.seedRefs.some((seedRef) => prefixes.some((prefix) => seedRef.startsWith(prefix)));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function mutationFieldsFor(scenario: DemoScenario, pathId?: string): string[] {
  if (!pathId) return [];
  return [
    ...new Set(
      scenario.fault.mutations
        .filter(({ objectId }) => objectId === pathId)
        .flatMap(({ omitFields = [] }) => omitFields),
    ),
  ].sort();
}

function hasCompleteScenarioSupplements(
  contract: ApiContract,
  scenario: DemoScenario,
  validated: ValidatedRequest,
): boolean {
  if (scenario.id !== 'SCN-02' || contract.apiId !== 'API-004' || !validated.pathId) return false;
  const supplements = validated.body?.supplements;
  if (!isRecord(supplements)) return false;

  const missingFields = mutationFieldsFor(scenario, validated.pathId);
  return missingFields.length > 0 && missingFields.every((field) => {
    const value = supplements[field];
    return value !== undefined && value !== null && value !== '';
  });
}

function hasResolvedScenarioFault(
  runtime: MockRuntime,
  contract: ApiContract,
  scenario: DemoScenario,
  validated: ValidatedRequest,
): boolean {
  if (
    scenario.id !== 'SCN-02' ||
    (contract.apiId !== 'API-005' && contract.apiId !== 'API-006') ||
    !validated.pathId
  ) {
    return false;
  }
  return runtime.hasResolvedFaultFields(
    validated.pathId,
    mutationFieldsFor(scenario, validated.pathId),
  );
}

function scenarioFailure(
  runtime: MockRuntime,
  contract: ApiContract,
  validated: ValidatedRequest,
): RuntimeFailure | undefined {
  const scenario = runtime.getActiveScenario();
  if (hasCompleteScenarioSupplements(contract, scenario, validated)) return undefined;
  if (hasResolvedScenarioFault(runtime, contract, scenario, validated)) return undefined;
  if (!scenario.fault.errorCode || !scenarioAffectsRequest(contract, scenario, validated)) return undefined;
  const definition = runtime.getErrorDefinition(scenario.fault.errorCode as PublicErrorCode);
  return {
    status: definition.httpStatus,
    errorCode: definition.code,
    message: definition.meaning,
    details: {
      scenarioId: scenario.id,
      faultType: scenario.fault.type,
      seedRefs: scenario.seedRefs,
    },
  };
}

function selectScenarioIfRequested(
  runtime: MockRuntime,
  contract: ApiContract,
  validated: ValidatedRequest,
): Response | undefined {
  const requestedScenario =
    contract.apiId === 'API-013'
      ? validated.pathId
      : contract.apiId === 'API-003' || contract.apiId === 'API-025'
        ? validated.body?.scenarioId
        : undefined;
  if (typeof requestedScenario !== 'string') return undefined;

  try {
    runtime.reset(requestedScenario);
  } catch (error) {
    return validationFailure(runtime, [
      { path: ['scenarioId'], message: error instanceof Error ? error.message : 'Unknown scenario.' },
    ]);
  }
  return undefined;
}

function recordsForApi(snapshot: FixtureSnapshot, apiId: ApiId, pathId?: string) {
  if ((apiId === 'API-008' || apiId === 'API-009') && pathId) {
    const hasFixtureOrder = snapshot.objects['DO-005'].some(({ id }) => id === pathId);
    return hasFixtureOrder || pathId.startsWith('C06-WO-') ? [{ id: pathId }] : [];
  }
  const groups = objectGroupsByApi[apiId] ?? [];
  const records = groups.flatMap((group) => snapshot.objects[group]);
  return pathId ? records.filter(({ id }) => id === pathId) : records;
}

function successData(
  runtime: MockRuntime,
  contract: ApiContract,
  validated: ValidatedRequest,
): Record<string, unknown> {
  const scenario = runtime.getActiveScenario();
  if (contract.apiId === 'API-003' || contract.apiId === 'API-013' || contract.apiId === 'API-025') {
    return {
      apiId: contract.apiId,
      operationId: contract.operationId,
      now: runtime.now(),
      scenario,
    };
  }

  const restoreResolvedObjectId = hasResolvedScenarioFault(runtime, contract, scenario, validated)
    ? validated.pathId
    : undefined;
  const snapshot = runtime.getProjectedSnapshot({ restoreResolvedObjectId });
  return {
    apiId: contract.apiId,
    operationId: contract.operationId,
    now: runtime.now(),
    scenarioId: scenario.id,
    items: recordsForApi(snapshot, contract.apiId, validated.pathId),
  };
}

function successResponse(runtime: MockRuntime, contract: ApiContract, validated: ValidatedRequest): Response {
  const payload = apiSuccessEnvelopeSchema.parse({
    ok: true,
    data: successData(runtime, contract, validated),
    ...runtime.nextEnvelopeIds(),
  });
  return HttpResponse.json(payload, { status: 200 });
}

export function createMockHandlers(runtime: MockRuntime): HttpHandler[] {
  return apiCatalog.map((contract) => {
    const resolver = async ({
      request,
      params,
    }: {
      request: Request;
      params: Record<string, string | readonly string[] | undefined>;
    }) => {
      if (
        (contract.apiId === 'API-014' || contract.apiId === 'API-015')
        && request.headers.get('x-demo-c08-fault') === 'network'
      ) {
        return HttpResponse.error();
      }
      if (
        (contract.apiId === 'API-016' || contract.apiId === 'API-017')
        && request.headers.get('x-demo-c09-fault') === 'network'
      ) {
        return HttpResponse.error();
      }
      if (
        (contract.apiId === 'API-018' || contract.apiId === 'API-019')
        && request.headers.get('x-demo-c10-fault') === 'network'
      ) {
        return HttpResponse.error();
      }
      if (
        contract.apiId === 'API-020'
        && request.headers.get('x-demo-c11-fault') === 'network'
      ) {
        return HttpResponse.error();
      }
      if (
        contract.apiId === 'API-024'
        && request.headers.get('x-demo-c12-fault') === 'network'
      ) {
        return HttpResponse.error();
      }
      if (
        (contract.apiId === 'API-022' || contract.apiId === 'API-023')
        && request.headers.get('x-demo-c13-fault') === 'network'
      ) {
        return HttpResponse.error();
      }

      const validated = await validateRequest(contract, request, params, runtime);
      if (validated instanceof Response) return validated;

      if (
        (contract.apiId === 'API-014' || contract.apiId === 'API-015')
        && request.headers.get('x-demo-c08-fault') === 'malformed'
      ) {
        return HttpResponse.json({ ok: true, data: { malformed: true } }, { status: 200 });
      }
      if (
        (contract.apiId === 'API-016' || contract.apiId === 'API-017')
        && request.headers.get('x-demo-c09-fault') === 'malformed'
      ) {
        return HttpResponse.json({ ok: true, data: { malformed: true } }, { status: 200 });
      }
      if (
        (contract.apiId === 'API-018' || contract.apiId === 'API-019')
        && request.headers.get('x-demo-c10-fault') === 'malformed'
      ) {
        return HttpResponse.json({ ok: true, data: { malformed: true } }, { status: 200 });
      }
      if (
        contract.apiId === 'API-020'
        && request.headers.get('x-demo-c11-fault') === 'malformed'
      ) {
        return HttpResponse.json({ ok: true, data: { malformed: true } }, { status: 200 });
      }
      if (
        contract.apiId === 'API-024'
        && request.headers.get('x-demo-c12-fault') === 'malformed'
      ) {
        return HttpResponse.json({ ok: true, data: { malformed: true } }, { status: 200 });
      }
      if (
        contract.apiId === 'API-024'
        && request.headers.get('x-demo-c12-fault') === 'business'
      ) {
        return failureResponse(runtime, {
          status: 409,
          errorCode: 'DEMO-SCENARIO-001',
          message: '当前场景不允许读取审计日志。',
          details: { scenarioId: runtime.getActiveScenario().id },
        });
      }
      if (
        (contract.apiId === 'API-022' || contract.apiId === 'API-023')
        && request.headers.get('x-demo-c13-fault') === 'malformed'
      ) {
        return HttpResponse.json({ ok: true, data: { malformed: true } }, { status: 200 });
      }
      if (
        (contract.apiId === 'API-022' || contract.apiId === 'API-023')
        && request.headers.get('x-demo-c13-fault') === 'business'
      ) {
        return failureResponse(runtime, {
          status: 409,
          errorCode: 'DEMO-SCENARIO-001',
          message: '当前场景不允许读取或修改系统配置。',
          details: { scenarioId: runtime.getActiveScenario().id },
        });
      }

      const selectionFailure = selectScenarioIfRequested(runtime, contract, validated);
      if (selectionFailure) return selectionFailure;

      await waitForMockDelay(runtime.getActiveScenario().fault.delayMs);

      const forcedFailure = runtime.consumeForcedFailure();
      if (forcedFailure) return failureResponse(runtime, forcedFailure);

      if (contract.apiId !== 'API-025') {
        const fault = scenarioFailure(runtime, contract, validated);
        if (fault) return failureResponse(runtime, fault);
      }

      if (
        contract.apiId === 'API-023'
        && validated.pathId
        && recordsForApi(runtime.getProjectedSnapshot(), contract.apiId, validated.pathId).length === 0
      ) {
        return validationFailure(runtime, [
          { path: ['path', 'id'], message: 'ConfigVersion does not exist.' },
        ]);
      }

      const scenario = runtime.getActiveScenario();
      if (
        contract.apiId === 'API-004' &&
        validated.pathId &&
        hasCompleteScenarioSupplements(contract, scenario, validated)
      ) {
        runtime.resolveFaultFields(
          validated.pathId,
          mutationFieldsFor(scenario, validated.pathId),
        );
      }

      return successResponse(runtime, contract, validated);
    };

    return contract.method === 'get'
      ? http.get(withAppBasePath(contract.mswPath), resolver)
      : http.post(withAppBasePath(contract.mswPath), resolver);
  });
}

export const handlers = createMockHandlers(mockRuntime);
