import { z } from 'zod';

import {
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do010Schema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type Interlock,
} from '../../contracts';

export const interlockCommandActions = [
  'TRIGGER',
  'RECEIPT',
  'REQUEST_RESET',
  'APPROVE',
  'RESTORE',
  'REQUEST_OVERRIDE',
] as const;

export const interlockCommandRequestSchema = z
  .object({
    action: z.enum(interlockCommandActions),
    reason: z.string().trim().min(1),
    approvalUserId: z.string().trim().min(1).optional(),
    resetRequest: z.record(z.string(), z.unknown()).optional(),
  })
  .strict();

export type InterlockCommandRequest = z.infer<typeof interlockCommandRequestSchema>;

export type SafetyInterlockGatewayData = Readonly<{
  apiId: 'API-016' | 'API-017';
  operationId: 'GET_mock_interlocks' | 'POST_mock_interlocks_id_command';
  now: string;
  scenarioId: string;
  items: readonly Interlock[];
}>;

export type SafetyInterlockGatewaySuccess = Omit<ApiSuccessEnvelope, 'data'> &
  Readonly<{ data: SafetyInterlockGatewayData }>;

export type SafetyInterlockGatewayResult = SafetyInterlockGatewaySuccess | ApiErrorEnvelope;

export type SafetyInterlockGateway = Readonly<{
  listInterlocks: () => Promise<SafetyInterlockGatewayResult>;
  commandInterlock: (
    interlockId: string,
    input: InterlockCommandRequest,
  ) => Promise<SafetyInterlockGatewayResult>;
}>;

function gatewayDataSchema(
  apiId: 'API-016' | 'API-017',
  operationId: 'GET_mock_interlocks' | 'POST_mock_interlocks_id_command',
  interlockId?: string,
) {
  return z
    .object({
      apiId: z.literal(apiId),
      operationId: z.literal(operationId),
      now: z.string().min(1),
      scenarioId: z.string().min(1),
      items: apiId === 'API-017' ? do010Schema.array().length(1) : do010Schema.array(),
    })
    .strict()
    .superRefine((data, context) => {
      if (interlockId !== undefined && data.items[0]?.id !== interlockId) {
        context.addIssue({
          code: 'custom',
          path: ['items', 0, 'id'],
          message: `Expected response Interlock ${interlockId}.`,
        });
      }
    });
}

async function parseResponse(
  response: Response,
  apiId: 'API-016' | 'API-017',
  operationId: 'GET_mock_interlocks' | 'POST_mock_interlocks_id_command',
  interlockId?: string,
): Promise<SafetyInterlockGatewayResult> {
  const json: unknown = await response.json();
  const success = apiSuccessEnvelopeSchema.safeParse(json);
  if (success.success) {
    const data = gatewayDataSchema(apiId, operationId, interlockId).parse(success.data.data);
    return { ...success.data, data };
  }
  return apiErrorEnvelopeSchema.parse(json);
}

export function createSafetyInterlockGateway(fetcher: typeof fetch): SafetyInterlockGateway {
  return {
    listInterlocks: async () => await parseResponse(
      await fetcher('/mock/interlocks'),
      'API-016',
      'GET_mock_interlocks',
    ),
    commandInterlock: async (interlockIdInput, input) => {
      const interlockId = z.string().trim().min(1).parse(interlockIdInput);
      const request = interlockCommandRequestSchema.parse(structuredClone(input));
      return await parseResponse(
        await fetcher(`/mock/interlocks/${encodeURIComponent(interlockId)}/command`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(request),
        }),
        'API-017',
        'POST_mock_interlocks_id_command',
        interlockId,
      );
    },
  };
}
