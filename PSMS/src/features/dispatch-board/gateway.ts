import { z } from 'zod';
import {
  api008RequestSchema,
  api009RequestSchema,
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
} from '../../contracts';

export type Api008Request = z.infer<typeof api008RequestSchema>;
export type Api009Request = z.infer<typeof api009RequestSchema>;

export type DispatchTransportIdentity = Readonly<{ id: string }>;

export type DispatchBoardGatewayData = Readonly<{
  apiId: 'API-008' | 'API-009';
  operationId:
    | 'POST_mock_work_orders_id_assign'
    | 'POST_mock_work_orders_id_dispatch';
  now: string;
  scenarioId: string;
  items: readonly DispatchTransportIdentity[];
}>;

export type DispatchBoardGatewaySuccess = Omit<ApiSuccessEnvelope, 'data'> &
  Readonly<{ data: DispatchBoardGatewayData }>;

export type DispatchBoardGatewayResult = DispatchBoardGatewaySuccess | ApiErrorEnvelope;

export type DispatchBoardGateway = Readonly<{
  assignWorkOrder: (
    workOrderId: string,
    input: Api008Request,
  ) => Promise<DispatchBoardGatewayResult>;
  dispatchWorkOrder: (
    workOrderId: string,
    input: Api009Request,
  ) => Promise<DispatchBoardGatewayResult>;
}>;

function gatewayDataSchema(
  apiId: 'API-008' | 'API-009',
  operationId:
    | 'POST_mock_work_orders_id_assign'
    | 'POST_mock_work_orders_id_dispatch',
  workOrderId: string,
) {
  return z
    .object({
      apiId: z.literal(apiId),
      operationId: z.literal(operationId),
      now: z.string(),
      scenarioId: z.string(),
      items: z.array(z.object({ id: z.string() }).strict()).length(1),
    })
    .strict()
    .superRefine((data, context) => {
      if (data.items[0]?.id !== workOrderId) {
        context.addIssue({
          code: 'custom',
          path: ['items', 0, 'id'],
          message: `Expected response WorkOrder ${workOrderId}.`,
        });
      }
    });
}

async function post(
  fetcher: typeof fetch,
  workOrderIdInput: string,
  suffix: 'assign' | 'dispatch',
  apiId: 'API-008' | 'API-009',
  operationId:
    | 'POST_mock_work_orders_id_assign'
    | 'POST_mock_work_orders_id_dispatch',
  requestSchema: typeof api008RequestSchema | typeof api009RequestSchema,
  input: unknown,
): Promise<DispatchBoardGatewayResult> {
  const workOrderId = z.string().trim().min(1).parse(workOrderIdInput);
  const parsedInput = requestSchema.parse(structuredClone(input));
  const response = await fetcher(
    `/mock/work-orders/${encodeURIComponent(workOrderId)}/${suffix}`,
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(parsedInput),
    },
  );
  const json: unknown = await response.json();
  const success = apiSuccessEnvelopeSchema.safeParse(json);
  if (success.success) {
    const data = gatewayDataSchema(apiId, operationId, workOrderId).parse(success.data.data);
    return { ...success.data, data };
  }
  return apiErrorEnvelopeSchema.parse(json);
}

export function createDispatchBoardGateway(fetcher: typeof fetch): DispatchBoardGateway {
  return {
    assignWorkOrder: async (workOrderId, input) => await post(
      fetcher,
      workOrderId,
      'assign',
      'API-008',
      'POST_mock_work_orders_id_assign',
      api008RequestSchema,
      input,
    ),
    dispatchWorkOrder: async (workOrderId, input) => await post(
      fetcher,
      workOrderId,
      'dispatch',
      'API-009',
      'POST_mock_work_orders_id_dispatch',
      api009RequestSchema,
      input,
    ),
  };
}
