import { z } from 'zod';

import {
  api003RequestSchema,
  api004RequestSchema,
  api025RequestSchema,
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
} from '../../contracts';

export type Api003Request = z.infer<typeof api003RequestSchema>;
export type Api004Request = z.infer<typeof api004RequestSchema>;
export type Api025Request = z.infer<typeof api025RequestSchema>;

export type PlanEntryGateway = {
  getOverview: () => Promise<ApiSuccessEnvelope | ApiErrorEnvelope>;
  getPlans: () => Promise<ApiSuccessEnvelope | ApiErrorEnvelope>;
  syncPlans: (input: Api003Request) => Promise<ApiSuccessEnvelope | ApiErrorEnvelope>;
  confirmPlan: (
    planId: string,
    input: Api004Request,
  ) => Promise<ApiSuccessEnvelope | ApiErrorEnvelope>;
  resetDemo: (input: Api025Request) => Promise<ApiSuccessEnvelope | ApiErrorEnvelope>;
};

async function request(
  fetcher: typeof fetch,
  path: string,
  init?: RequestInit,
): Promise<ApiSuccessEnvelope | ApiErrorEnvelope> {
  const response = await fetcher(path, init);
  const json: unknown = await response.json();
  const success = apiSuccessEnvelopeSchema.safeParse(json);
  if (success.success) return success.data;
  return apiErrorEnvelopeSchema.parse(json);
}

function post(input: Api003Request | Api004Request | Api025Request): RequestInit {
  return {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(input),
  };
}

export function createPlanEntryGateway(fetcher: typeof fetch): PlanEntryGateway {
  return {
    getOverview: () => request(fetcher, '/mock/overview'),
    getPlans: () => request(fetcher, '/mock/plans'),
    syncPlans: (input) => request(fetcher, '/mock/plans/sync', post(input)),
    confirmPlan: (planId, input) =>
      request(fetcher, `/mock/plans/${encodeURIComponent(planId)}/confirm`, post(input)),
    resetDemo: (input) => request(fetcher, '/mock/demo/reset', post(input)),
  };
}
