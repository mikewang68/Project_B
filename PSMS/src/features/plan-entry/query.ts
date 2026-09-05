import { planStatuses } from '../../contracts';
import type { DemoScenario } from '../../mocks/fixtures';
import { planExceptionFilterValues, type PlanEntryQuery } from './types';

const DEFAULT_QUERY: PlanEntryQuery = {
  date: '2026-07-16',
  workArea: 'AREA-A',
  scenarioId: 'SCN-01',
  planBatchNo: '',
  trainNo: '',
  statuses: [],
  exceptionTypes: [],
  page: 1,
  pageSize: 20,
  sort: 'updatedAt:desc',
};

const validScenarioIds = new Set([
  'SCN-01',
  'SCN-02',
  'SCN-03',
  'SCN-04',
  'SCN-05',
  'SCN-06',
  'SCN-07',
]);
const validWorkAreas = new Set(['AREA-A', 'AREA-B', 'AREA-C']);
const validStatuses = new Set<string>(planStatuses);
const validExceptionTypes = new Set<string>(planExceptionFilterValues);
const validSorts = new Set<PlanEntryQuery['sort']>([
  'updatedAt:desc',
  'updatedAt:asc',
  'status:asc',
]);

function isValidDate(value: string | null): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [yearText, monthText, dayText] = value.split('-');
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysByMonth = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= daysByMonth[month - 1];
}

function normalizeList(values: readonly string[], allowed: ReadonlySet<string>): string[] {
  return [...new Set(values.map((value) => value.trim()).filter((value) => allowed.has(value)))].sort();
}

function nonEmpty(value: string | null): string | undefined {
  const normalized = value?.trim();
  return normalized ? normalized : undefined;
}

function positivePage(value: string | null): number {
  if (!value || !/^\d+$/.test(value)) return DEFAULT_QUERY.page;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : DEFAULT_QUERY.page;
}

function asSearchParams(input: string | URLSearchParams): URLSearchParams {
  if (input instanceof URLSearchParams) return new URLSearchParams(input);
  return new URLSearchParams(input.startsWith('?') ? input.slice(1) : input);
}

export function parsePlanEntryQuery(input: string | URLSearchParams): PlanEntryQuery {
  const params = asSearchParams(input);
  const dateValue = params.get('date');
  const workAreaValue = params.get('workArea');
  const scenarioValue = params.get('scenarioId');
  const sortValue = params.get('sort');
  const planId = nonEmpty(params.get('planId'));

  return {
    date: isValidDate(dateValue) ? dateValue : DEFAULT_QUERY.date,
    workArea: workAreaValue && validWorkAreas.has(workAreaValue) ? workAreaValue : DEFAULT_QUERY.workArea,
    scenarioId: validScenarioIds.has(scenarioValue ?? '')
      ? (scenarioValue as DemoScenario['id'])
      : DEFAULT_QUERY.scenarioId,
    planBatchNo: nonEmpty(params.get('planBatchNo')) ?? '',
    trainNo: nonEmpty(params.get('trainNo')) ?? '',
    statuses: normalizeList(params.getAll('status'), validStatuses),
    exceptionTypes: normalizeList(params.getAll('exceptionType'), validExceptionTypes),
    page: positivePage(params.get('page')),
    pageSize: 20,
    sort: validSorts.has(sortValue as PlanEntryQuery['sort'])
      ? (sortValue as PlanEntryQuery['sort'])
      : DEFAULT_QUERY.sort,
    ...(planId ? { planId } : {}),
    ...(params.get('from') === 'overview' ? { from: 'overview' as const } : {}),
  };
}

export function serializePlanEntryQuery(query: PlanEntryQuery): URLSearchParams {
  const params = new URLSearchParams();
  const date = isValidDate(query.date) ? query.date : DEFAULT_QUERY.date;
  const workArea = validWorkAreas.has(query.workArea) ? query.workArea : DEFAULT_QUERY.workArea;
  const scenarioId = validScenarioIds.has(query.scenarioId)
    ? query.scenarioId
    : DEFAULT_QUERY.scenarioId;
  const statuses = normalizeList(query.statuses, validStatuses);
  const normalizedExceptionTypes = normalizeList(query.exceptionTypes, validExceptionTypes);
  const page = Number.isSafeInteger(query.page) && query.page > 0 ? query.page : DEFAULT_QUERY.page;
  const sort = validSorts.has(query.sort) ? query.sort : DEFAULT_QUERY.sort;

  params.set('date', date);
  params.set('workArea', workArea);
  params.set('scenarioId', scenarioId);
  if (query.planBatchNo.trim()) params.set('planBatchNo', query.planBatchNo.trim());
  if (query.trainNo.trim()) params.set('trainNo', query.trainNo.trim());
  for (const status of statuses) params.append('status', status);
  for (const exceptionType of normalizedExceptionTypes) params.append('exceptionType', exceptionType);
  if (page !== DEFAULT_QUERY.page) {
    params.set('page', String(page));
    params.set('pageSize', '20');
  }
  if (sort !== DEFAULT_QUERY.sort) params.set('sort', sort);
  if (query.planId?.trim()) params.set('planId', query.planId.trim());
  if (query.from === 'overview') params.set('from', query.from);
  return params;
}
