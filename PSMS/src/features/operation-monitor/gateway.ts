import { z } from 'zod';

import {
  api013RequestSchema,
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do005Schema,
  do006Schema,
  do007Schema,
  type ApiErrorEnvelope,
  type Resource,
  type WorkNode,
  type WorkOrder,
} from '../../contracts';
import { createAppFetch } from '../../runtime/appBasePath';

const operationItemSchema = z.union([do005Schema, do006Schema, do007Schema]);
const operationDataSchema = z.object({
  apiId: z.literal('API-012'),
  operationId: z.literal('GET_mock_operations'),
  now: z.string(),
  scenarioId: z.string(),
  items: z.array(operationItemSchema),
}).strict();
const scenarioDataSchema = z.object({
  apiId: z.literal('API-013'),
  operationId: z.literal('POST_mock_scenarios_id_play'),
  now: z.string(),
  scenario: z.object({ id: z.string() }).passthrough(),
}).strict();

export type OperationItem = WorkOrder | WorkNode | Resource;
export type OperationListResult =
  | Readonly<{ ok: true; items: readonly OperationItem[]; scenarioId: string; now: string }>
  | ApiErrorEnvelope;
export type ScenarioPlayResult =
  | Readonly<{ ok: true; scenarioId: string; now: string }>
  | ApiErrorEnvelope;

export type OperationMonitorGateway = Readonly<{
  listOperations: (time?: string) => Promise<OperationListResult>;
  playScenario: (scenarioId: string, speed: number) => Promise<ScenarioPlayResult>;
}>;

async function parseResponse(response: Response) {
  const body: unknown = await response.json();
  const success = apiSuccessEnvelopeSchema.safeParse(body);
  if (success.success) return success.data;
  return apiErrorEnvelopeSchema.parse(body);
}

export function createOperationMonitorGateway(
  fetcher: typeof fetch = fetch,
): OperationMonitorGateway {
  const appFetcher = createAppFetch(fetcher);
  return {
    async listOperations(time) {
      const query = time ? `?time=${encodeURIComponent(time)}` : '';
      const response = await parseResponse(await appFetcher(`/mock/operations${query}`));
      if (!response.ok) return response;
      const data = operationDataSchema.parse(response.data);
      return { ok: true, items: data.items, scenarioId: data.scenarioId, now: data.now };
    },

    async playScenario(scenarioId, speed) {
      const body = api013RequestSchema.parse({ speed });
      const response = await parseResponse(await appFetcher(
        `/mock/scenarios/${encodeURIComponent(scenarioId)}/play`,
        {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(body),
        },
      ));
      if (!response.ok) return response;
      const data = scenarioDataSchema.parse(response.data);
      if (data.scenario.id !== scenarioId) throw new Error('场景播放回执与当前场景不一致。');
      return { ok: true, scenarioId: data.scenario.id, now: data.now };
    },
  };
}
