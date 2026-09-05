import { exceptionLevels, exceptionStatuses, exceptionTypes } from '../../contracts';
import type {
  ExceptionLevel,
  ExceptionQueryContext,
  ExceptionStatus,
  ExceptionType,
} from './types';

const DISPLAY_VALUE_LIMIT = 128;

function safeString(value: string | null): string | undefined {
  if (value === null) return undefined;
  const safe = value.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, DISPLAY_VALUE_LIMIT);
  return safe === '' ? undefined : safe;
}

function enumValue<T extends string>(
  value: string | undefined,
  values: readonly T[],
): T | undefined {
  return value !== undefined && values.includes(value as T) ? value as T : undefined;
}

function parameters(input: string | URLSearchParams): URLSearchParams {
  if (input instanceof URLSearchParams) return new URLSearchParams(input);
  const query = input.startsWith('?') ? input.slice(1) : input;
  return new URLSearchParams(query);
}

export function parseExceptionQueryContext(
  input: string | URLSearchParams,
): ExceptionQueryContext {
  const query = parameters(input);
  const workOrderId = safeString(query.get('workOrderId'));
  const planId = safeString(query.get('planId'));
  const scenarioId = safeString(query.get('scenarioId'));
  const from = safeString(query.get('from'));
  const status = enumValue<ExceptionStatus>(safeString(query.get('status')), exceptionStatuses);
  const level = enumValue<ExceptionLevel>(safeString(query.get('level')), exceptionLevels);
  const type = enumValue<ExceptionType>(safeString(query.get('type')), exceptionTypes);
  const owner = safeString(query.get('owner'));

  return Object.freeze({
    ...(workOrderId ? { workOrderId } : {}),
    ...(planId ? { planId } : {}),
    ...(scenarioId ? { scenarioId } : {}),
    ...(from ? { from } : {}),
    ...(status ? { status } : {}),
    ...(level ? { level } : {}),
    ...(type ? { type } : {}),
    ...(owner ? { owner } : {}),
  });
}
