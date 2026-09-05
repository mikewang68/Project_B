import {
  api022SuccessEnvelopeSchema,
  api023RequestSchema,
  api023SuccessEnvelopeSchema,
  apiErrorEnvelopeSchema,
  type Api022SuccessEnvelope,
  type Api023SuccessEnvelope,
  type ApiErrorEnvelope,
} from '../../contracts';
import type { ConfigVersionChanges } from './systemSettingsTypes';

export type SystemSettingsFault = 'network' | 'malformed' | 'business';

export type ListConfigQuery = Readonly<{
  expectedScenarioId: string;
  expectedNow: string;
  expectedConfigId: string;
  fault?: SystemSettingsFault;
}>;

export type EditConfigInput = Readonly<{
  configId: string;
  expectedScenarioId: string;
  expectedNow: string;
  expectedVersion: number;
  changes: ConfigVersionChanges;
  reason: string;
  fault?: SystemSettingsFault;
}>;

export type SystemSettingsGatewayResult =
  | Api022SuccessEnvelope
  | Api023SuccessEnvelope
  | ApiErrorEnvelope;

export type SystemSettingsGateway = Readonly<{
  listConfig(query: ListConfigQuery): Promise<Api022SuccessEnvelope | ApiErrorEnvelope>;
  editConfig(input: EditConfigInput): Promise<Api023SuccessEnvelope | ApiErrorEnvelope>;
}>;

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function headers(fault?: SystemSettingsFault): Record<string, string> {
  return fault ? { 'x-demo-c13-fault': fault } : {};
}

function assertContext(
  data: { scenarioId: string; now: string },
  expectedScenarioId: string,
  expectedNow: string,
  apiId: string,
): void {
  if (data.scenarioId !== expectedScenarioId) {
    throw new Error(
      `${apiId} scenario mismatch: expected ${expectedScenarioId}, received ${data.scenarioId}.`,
    );
  }
  if (data.now !== expectedNow) {
    throw new Error(`${apiId} demo time mismatch: expected ${expectedNow}, received ${data.now}.`);
  }
}

async function payload(response: Response): Promise<unknown> {
  return response.json();
}

function publicError(input: unknown): ApiErrorEnvelope {
  return deepFreeze(apiErrorEnvelopeSchema.parse(input));
}

async function parseListResponse(
  response: Response,
  query: ListConfigQuery,
): Promise<Api022SuccessEnvelope | ApiErrorEnvelope> {
  const input = await payload(response);
  const success = api022SuccessEnvelopeSchema.safeParse(input);
  if (!success.success) return publicError(input);

  assertContext(success.data.data, query.expectedScenarioId, query.expectedNow, 'API-022');
  const matches = success.data.data.items.filter(({ id }) => id === query.expectedConfigId);
  if (matches.length !== 1) {
    throw new Error(
      `API-022 config ${query.expectedConfigId} must appear exactly once; received ${matches.length}.`,
    );
  }
  return deepFreeze(success.data);
}

async function parseEditResponse(
  response: Response,
  input: EditConfigInput,
): Promise<Api023SuccessEnvelope | ApiErrorEnvelope> {
  const raw = await payload(response);
  const success = api023SuccessEnvelopeSchema.safeParse(raw);
  if (!success.success) return publicError(raw);

  assertContext(success.data.data, input.expectedScenarioId, input.expectedNow, 'API-023');
  if (success.data.data.items[0]?.id !== input.configId) {
    throw new Error(
      `API-023 target mismatch: expected ${input.configId}, received ${success.data.data.items[0]?.id ?? 'none'}.`,
    );
  }
  return deepFreeze(success.data);
}

export function createSystemSettingsGateway(
  fetcher: typeof fetch = fetch,
): SystemSettingsGateway {
  return Object.freeze({
    async listConfig(query: ListConfigQuery) {
      const response = await fetcher('/mock/config', { headers: headers(query.fault) });
      return parseListResponse(response, query);
    },
    async editConfig(input: EditConfigInput) {
      const body = api023RequestSchema.parse({
        command: 'edit',
        expectedVersion: input.expectedVersion,
        changes: input.changes,
        reason: input.reason,
      });
      const requestHeaders = {
        'content-type': 'application/json',
        ...headers(input.fault),
      };
      const response = await fetcher(
        `/mock/config/${encodeURIComponent(input.configId)}/command`,
        { method: 'POST', headers: requestHeaders, body: JSON.stringify(body) },
      );
      return parseEditResponse(response, input);
    },
  });
}
