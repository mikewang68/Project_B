import { z } from 'zod';

import {
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do009Schema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type DispatchException,
} from '../../contracts';

export const exceptionCommandActions = [
  'ACK',
  'ASSIGN',
  'HANDLE',
  'REVIEW',
  'CLOSE',
  'REOPEN',
] as const;

export const exceptionCommandRequestSchema = z
  .object({
    action: z.enum(exceptionCommandActions),
    reason: z.string().trim().min(1),
    owner: z.string().trim().min(1).optional(),
    evidence: z.array(z.string().trim().min(1)).optional(),
  })
  .strict();

export type ExceptionCommandRequest = z.infer<typeof exceptionCommandRequestSchema>;

export type ExceptionHandlingGatewayData = Readonly<{
  apiId: 'API-014' | 'API-015';
  operationId: 'GET_mock_exceptions' | 'POST_mock_exceptions_id_command';
  now: string;
  scenarioId: string;
  items: readonly DispatchException[];
}>;

export type ExceptionHandlingGatewaySuccess = Omit<ApiSuccessEnvelope, 'data'> &
  Readonly<{ data: ExceptionHandlingGatewayData }>;

export type ExceptionHandlingGatewayResult = ExceptionHandlingGatewaySuccess | ApiErrorEnvelope;

export type ExceptionHandlingGateway = Readonly<{
  listExceptions: () => Promise<ExceptionHandlingGatewayResult>;
  commandException: (
    exceptionId: string,
    input: ExceptionCommandRequest,
  ) => Promise<ExceptionHandlingGatewayResult>;
}>;

function gatewayDataSchema(
  apiId: 'API-014' | 'API-015',
  operationId: 'GET_mock_exceptions' | 'POST_mock_exceptions_id_command',
  exceptionId?: string,
) {
  return z
    .object({
      apiId: z.literal(apiId),
      operationId: z.literal(operationId),
      now: z.string().min(1),
      scenarioId: z.string().min(1),
      items: apiId === 'API-015' ? do009Schema.array().length(1) : do009Schema.array(),
    })
    .strict()
    .superRefine((data, context) => {
      if (exceptionId !== undefined && data.items[0]?.id !== exceptionId) {
        context.addIssue({
          code: 'custom',
          path: ['items', 0, 'id'],
          message: `Expected response DispatchException ${exceptionId}.`,
        });
      }
    });
}

async function parseResponse(
  response: Response,
  apiId: 'API-014' | 'API-015',
  operationId: 'GET_mock_exceptions' | 'POST_mock_exceptions_id_command',
  exceptionId?: string,
): Promise<ExceptionHandlingGatewayResult> {
  const json: unknown = await response.json();
  const success = apiSuccessEnvelopeSchema.safeParse(json);
  if (success.success) {
    const data = gatewayDataSchema(apiId, operationId, exceptionId).parse(success.data.data);
    return { ...success.data, data };
  }
  return apiErrorEnvelopeSchema.parse(json);
}

export function createExceptionHandlingGateway(fetcher: typeof fetch): ExceptionHandlingGateway {
  return {
    listExceptions: async () => await parseResponse(
      await fetcher('/mock/exceptions'),
      'API-014',
      'GET_mock_exceptions',
    ),
    commandException: async (exceptionIdInput, input) => {
      const exceptionId = z.string().trim().min(1).parse(exceptionIdInput);
      const request = exceptionCommandRequestSchema.parse(structuredClone(input));
      return await parseResponse(
        await fetcher(`/mock/exceptions/${encodeURIComponent(exceptionId)}/command`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(request),
        }),
        'API-015',
        'POST_mock_exceptions_id_command',
        exceptionId,
      );
    },
  };
}
