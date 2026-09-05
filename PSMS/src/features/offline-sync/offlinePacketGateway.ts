import { z } from 'zod';

import {
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do011Schema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type OfflinePacket,
} from '../../contracts';

export const offlinePacketCommandActions = [
  'UPLOAD',
  'VALIDATE',
  'MERGE',
  'REJECT',
  'RETRY',
] as const;

export const offlinePacketCommandRequestSchema = z
  .object({
    action: z.enum(offlinePacketCommandActions),
    reason: z.string().trim().min(1),
    validation: z.record(z.string(), z.unknown()).optional(),
  })
  .strict();

export type OfflinePacketCommandRequest = z.infer<typeof offlinePacketCommandRequestSchema>;

export type OfflinePacketGatewayData = Readonly<{
  apiId: 'API-018' | 'API-019';
  operationId: 'GET_mock_offline_packets' | 'POST_mock_offline_packets_id_command';
  now: string;
  scenarioId: string;
  items: readonly OfflinePacket[];
}>;

export type OfflinePacketGatewaySuccess = Omit<ApiSuccessEnvelope, 'data'> &
  Readonly<{ data: OfflinePacketGatewayData }>;

export type OfflinePacketGatewayResult = OfflinePacketGatewaySuccess | ApiErrorEnvelope;

export type OfflinePacketGateway = Readonly<{
  listPackets: () => Promise<OfflinePacketGatewayResult>;
  commandPacket: (
    packetId: string,
    input: OfflinePacketCommandRequest,
  ) => Promise<OfflinePacketGatewayResult>;
}>;

function gatewayDataSchema(
  apiId: 'API-018' | 'API-019',
  operationId: 'GET_mock_offline_packets' | 'POST_mock_offline_packets_id_command',
  packetId?: string,
) {
  return z
    .object({
      apiId: z.literal(apiId),
      operationId: z.literal(operationId),
      now: z.string().min(1),
      scenarioId: z.string().min(1),
      items: apiId === 'API-019' ? do011Schema.array().length(1) : do011Schema.array(),
    })
    .strict()
    .superRefine((data, context) => {
      if (packetId !== undefined && data.items[0]?.id !== packetId) {
        context.addIssue({
          code: 'custom',
          path: ['items', 0, 'id'],
          message: `Expected response OfflinePacket ${packetId}.`,
        });
      }
    });
}

async function parseResponse(
  response: Response,
  apiId: 'API-018' | 'API-019',
  operationId: 'GET_mock_offline_packets' | 'POST_mock_offline_packets_id_command',
  packetId?: string,
): Promise<OfflinePacketGatewayResult> {
  const json: unknown = await response.json();
  const success = apiSuccessEnvelopeSchema.safeParse(json);
  if (success.success) {
    const data = gatewayDataSchema(apiId, operationId, packetId).parse(success.data.data);
    return { ...success.data, data };
  }
  return apiErrorEnvelopeSchema.parse(json);
}

export function createOfflinePacketGateway(fetcher: typeof fetch): OfflinePacketGateway {
  return {
    listPackets: async () => await parseResponse(
      await fetcher('/mock/offline-packets'),
      'API-018',
      'GET_mock_offline_packets',
    ),
    commandPacket: async (packetIdInput, input) => {
      const packetId = z.string().trim().min(1).parse(packetIdInput);
      const request = offlinePacketCommandRequestSchema.parse(structuredClone(input));
      return await parseResponse(
        await fetcher(`/mock/offline-packets/${encodeURIComponent(packetId)}/command`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(request),
        }),
        'API-019',
        'POST_mock_offline_packets_id_command',
        packetId,
      );
    },
  };
}
