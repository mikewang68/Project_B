import { z } from 'zod';
import {
  api006RequestSchema,
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do001Schema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type Plan,
} from '../../contracts';

export type Api006Request = z.infer<typeof api006RequestSchema>;

export type RecommendationGatewayData = Readonly<{
  apiId: 'API-005' | 'API-006';
  operationId: string;
  now: string;
  scenarioId: string;
  items: readonly Plan[];
}>;

export type RecommendationGatewaySuccess = Omit<ApiSuccessEnvelope, 'data'> &
  Readonly<{ data: RecommendationGatewayData }>;

export type RecommendationGatewayResult = RecommendationGatewaySuccess | ApiErrorEnvelope;

export type RecommendationGateway = {
  getRecommendation: (
    planId: string,
    inputVersion: number,
  ) => Promise<RecommendationGatewayResult>;
  confirmRecommendation: (
    planId: string,
    input: Api006Request,
  ) => Promise<RecommendationGatewayResult>;
};

function recommendationDataSchema(
  apiId: 'API-005' | 'API-006',
  operationId: string,
  planId: string,
) {
  return z
    .object({
      apiId: z.literal(apiId),
      operationId: z.literal(operationId),
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

async function request(
  fetcher: typeof fetch,
  path: string,
  planId: string,
  apiId: 'API-005' | 'API-006',
  operationId: string,
  init?: RequestInit,
): Promise<RecommendationGatewayResult> {
  const response = await fetcher(path, init);
  const json: unknown = await response.json();
  const success = apiSuccessEnvelopeSchema.safeParse(json);
  if (success.success) {
    const data = recommendationDataSchema(apiId, operationId, planId).parse(success.data.data);
    return { ...success.data, data };
  }
  return apiErrorEnvelopeSchema.parse(json);
}

export function createRecommendationGateway(fetcher: typeof fetch): RecommendationGateway {
  return {
    getRecommendation: async (planIdInput, inputVersionInput) => {
      const planId = z.string().min(1).parse(planIdInput);
      const inputVersion = z.number().int().positive().parse(inputVersionInput);
      const query = new URLSearchParams({ inputVersion: String(inputVersion) });
      return await request(
        fetcher,
        `/mock/plans/${encodeURIComponent(planId)}/recommendation?${query.toString()}`,
        planId,
        'API-005',
        'GET_mock_plans_id_recommendation',
      );
    },
    confirmRecommendation: async (planIdInput, input) => {
      const planId = z.string().min(1).parse(planIdInput);
      const parsedInput = api006RequestSchema.parse(input);
      return await request(
        fetcher,
        `/mock/plans/${encodeURIComponent(planId)}/recommendation/confirm`,
        planId,
        'API-006',
        'POST_mock_plans_id_recommendation_confirm',
        {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(parsedInput),
        },
      );
    },
  };
}
