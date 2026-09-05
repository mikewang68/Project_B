import { z } from 'zod';

import {
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  do012Schema,
  reportTypeSchema,
  type ApiErrorEnvelope,
  type Report,
} from '../../contracts';
import type { ReportType } from './reportTypes';

const reportGatewayDataSchema = z
  .object({
    apiId: z.literal('API-020'),
    operationId: z.literal('GET_mock_reports'),
    now: z.string(),
    scenarioId: z.string(),
    items: z.array(do012Schema),
  })
  .strict();

export type ReportGatewayQuery = Readonly<{
  reportType?: ReportType;
  period?: string;
  dimensions?: readonly string[];
  expectedScenarioId?: string;
  expectedReportId?: string;
  fault?: 'network' | 'malformed';
}>;

export type ReportGatewayData = Readonly<{
  apiId: 'API-020';
  operationId: 'GET_mock_reports';
  now: string;
  scenarioId: string;
  items: readonly Report[];
}>;

export type ReportGatewaySuccess = Readonly<{
  ok: true;
  data: ReportGatewayData;
  auditLogId: string;
  traceId: string;
}>;

export type ReportGatewayResult = ReportGatewaySuccess | ApiErrorEnvelope;

export type ReportGateway = Readonly<{
  listReports(query?: ReportGatewayQuery): Promise<ReportGatewayResult>;
}>;

function buildReportUrl(query: ReportGatewayQuery): string {
  const parameters = new URLSearchParams();
  if (query.reportType) parameters.set('type', reportTypeSchema.parse(query.reportType));
  if (query.period) parameters.set('period', query.period);
  query.dimensions?.forEach((dimension) => parameters.append('dimensions', dimension));
  const search = parameters.toString();
  return search ? `/mock/reports?${search}` : '/mock/reports';
}

async function parseReportResponse(
  response: Response,
  query: ReportGatewayQuery,
): Promise<ReportGatewayResult> {
  const payload: unknown = await response.json();
  const successEnvelope = apiSuccessEnvelopeSchema.safeParse(payload);

  if (successEnvelope.success) {
    const data = reportGatewayDataSchema.parse(successEnvelope.data.data);
    if (query.expectedScenarioId && data.scenarioId !== query.expectedScenarioId) {
      throw new Error(
        `API-020 scenario mismatch: expected ${query.expectedScenarioId}, received ${data.scenarioId}.`,
      );
    }
    if (query.expectedReportId && !data.items.some(({ id }) => id === query.expectedReportId)) {
      throw new Error(`API-020 report ${query.expectedReportId} is missing from the response.`);
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

export function createReportGateway(fetcher: typeof fetch = fetch): ReportGateway {
  return Object.freeze({
    async listReports(query: ReportGatewayQuery = {}) {
      const headers: Record<string, string> = query.fault
        ? { 'x-demo-c11-fault': query.fault }
        : {};
      const response = await fetcher(buildReportUrl(query), { headers });
      return parseReportResponse(response, query);
    },
  });
}
