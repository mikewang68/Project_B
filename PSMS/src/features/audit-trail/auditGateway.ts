import { z } from 'zod';

import {
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do013Schema,
  type ApiErrorEnvelope,
  type AuditLog,
} from '../../contracts';

const auditGatewayDataSchema = z
  .object({
    apiId: z.literal('API-024'),
    operationId: z.literal('GET_mock_audit_logs'),
    now: z.string().min(1),
    scenarioId: z.string().min(1),
    items: z.array(do013Schema),
  })
  .strict();

export type AuditGatewayFault = 'network' | 'malformed' | 'business';

export type AuditGatewayQuery = Readonly<{
  expectedScenarioId?: string;
  expectedAuditId?: string;
  fault?: AuditGatewayFault;
}>;

export type AuditGatewayData = Readonly<{
  apiId: 'API-024';
  operationId: 'GET_mock_audit_logs';
  now: string;
  scenarioId: string;
  items: readonly AuditLog[];
}>;

export type AuditGatewaySuccess = Readonly<{
  ok: true;
  data: AuditGatewayData;
  auditLogId: string;
  traceId: string;
}>;

export type AuditGatewayResult = AuditGatewaySuccess | ApiErrorEnvelope;

export type AuditGateway = Readonly<{
  listAuditLogs(query?: AuditGatewayQuery): Promise<AuditGatewayResult>;
}>;

async function parseAuditResponse(
  response: Response,
  query: AuditGatewayQuery,
): Promise<AuditGatewayResult> {
  const payload: unknown = await response.json();
  const successEnvelope = apiSuccessEnvelopeSchema.safeParse(payload);

  if (successEnvelope.success) {
    const data = auditGatewayDataSchema.parse(successEnvelope.data.data);
    if (query.expectedScenarioId && data.scenarioId !== query.expectedScenarioId) {
      throw new Error(
        `API-024 scenario mismatch: expected ${query.expectedScenarioId}, received ${data.scenarioId}.`,
      );
    }
    if (query.expectedAuditId && !data.items.some(({ id }) => id === query.expectedAuditId)) {
      throw new Error(`API-024 audit record ${query.expectedAuditId} is missing from the response.`);
    }
    return Object.freeze({
      ok: true as const,
      data: Object.freeze({ ...data, items: Object.freeze([...data.items]) }),
      auditLogId: successEnvelope.data.auditLogId,
      traceId: successEnvelope.data.traceId,
    });
  }

  return Object.freeze(apiErrorEnvelopeSchema.parse(payload));
}

export function createAuditGateway(fetcher: typeof fetch = fetch): AuditGateway {
  return Object.freeze({
    async listAuditLogs(query: AuditGatewayQuery = {}) {
      const headers: Record<string, string> = query.fault
        ? { 'x-demo-c12-fault': query.fault }
        : {};
      const response = await fetcher('/mock/audit-logs', { headers });
      return parseAuditResponse(response, query);
    },
  });
}
