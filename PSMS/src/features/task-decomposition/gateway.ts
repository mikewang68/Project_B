import { z } from 'zod';
import {
  api007RequestSchema,
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do001Schema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type Plan,
} from '../../contracts';
import { TASK_DECOMPOSITION_RULE_VERSION, taskModes } from './constants';
import type { TaskMode } from './types';

export type TaskDecompositionGatewayData = Readonly<{
  apiId: 'API-007';
  operationId: 'POST_mock_plans_id_decompose';
  now: string;
  scenarioId: string;
  items: readonly Plan[];
}>;

export type TaskDecompositionGatewaySuccess = Omit<ApiSuccessEnvelope, 'data'> &
  Readonly<{ data: TaskDecompositionGatewayData }>;

export type TaskDecompositionGatewayResult = TaskDecompositionGatewaySuccess | ApiErrorEnvelope;

export type TaskDecompositionGateway = {
  decomposePlan: (
    planId: string,
    input: { ruleVersion: string; mode: TaskMode },
  ) => Promise<TaskDecompositionGatewayResult>;
};

const featureRequestSchema = z
  .object({
    ruleVersion: z.literal(TASK_DECOMPOSITION_RULE_VERSION),
    mode: z.enum(taskModes),
  })
  .strict();

function api007DataSchema(planId: string) {
  return z
    .object({
      apiId: z.literal('API-007'),
      operationId: z.literal('POST_mock_plans_id_decompose'),
      now: z.string(),
      scenarioId: z.string(),
      items: do001Schema.array().length(1),
    })
    .strict()
    .superRefine((data, context) => {
      if (data.items[0]?.id !== planId) {
        context.addIssue({
          code: 'custom',
          path: ['items', 0, 'id'],
          message: `Expected response Plan ${planId}.`,
        });
      }
    });
}

export function createTaskDecompositionGateway(
  fetcher: typeof fetch,
): TaskDecompositionGateway {
  return {
    decomposePlan: async (planIdInput, input) => {
      const planId = z.string().trim().min(1).parse(planIdInput);
      const frozenInput = structuredClone(input);
      api007RequestSchema.parse(frozenInput);
      const parsedInput = featureRequestSchema.parse(frozenInput);
      const response = await fetcher(`/mock/plans/${encodeURIComponent(planId)}/decompose`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(parsedInput),
      });
      const json: unknown = await response.json();
      const success = apiSuccessEnvelopeSchema.safeParse(json);
      if (success.success) {
        const data = api007DataSchema(planId).parse(success.data.data);
        return { ...success.data, data };
      }
      return apiErrorEnvelopeSchema.parse(json);
    },
  };
}
