import { generateStatuses, reportTypes } from '../../contracts';
import type { GenerateStatus, ReportQueryContext, ReportType } from './reportTypes';

const controlCharacters = /[\u0000-\u001f\u007f]/g;
const maxDisplayLength = 128;

function clean(value: string | null): string | undefined {
  if (value === null) return undefined;
  const sanitized = value.replace(controlCharacters, '').trim().slice(0, maxDisplayLength);
  return sanitized.length > 0 ? sanitized : undefined;
}

function reportType(value: string | undefined): ReportType | undefined {
  return value && reportTypes.some((candidate) => candidate === value)
    ? value as ReportType
    : undefined;
}

function generateStatus(value: string | undefined): GenerateStatus | undefined {
  return value && generateStatuses.some((candidate) => candidate === value)
    ? value as GenerateStatus
    : undefined;
}

export function parseReportQuery(input: string | URLSearchParams): ReportQueryContext {
  const params = typeof input === 'string'
    ? new URLSearchParams(input.startsWith('?') ? input.slice(1) : input)
    : input;
  const reportId = clean(params.get('reportId'));
  const parsedReportType = reportType(clean(params.get('reportType')));
  const period = clean(params.get('period'));
  const parsedGenerateStatus = generateStatus(clean(params.get('generateStatus')));
  const scenarioId = clean(params.get('scenarioId'));
  const from = clean(params.get('from'));

  return Object.freeze({
    ...(reportId ? { reportId } : {}),
    ...(parsedReportType ? { reportType: parsedReportType } : {}),
    ...(period ? { period } : {}),
    ...(parsedGenerateStatus ? { generateStatus: parsedGenerateStatus } : {}),
    ...(scenarioId ? { scenarioId } : {}),
    ...(from ? { from } : {}),
  });
}
