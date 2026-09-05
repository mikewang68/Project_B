import { actionLevels, interlockStatuses, receiptStatuses } from '../../contracts';
import type {
  InterlockActionLevel,
  InterlockQueryContext,
  InterlockReceiptStatus,
  InterlockStatus,
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
  return new URLSearchParams(input.startsWith('?') ? input.slice(1) : input);
}

export function parseInterlockQueryContext(
  input: string | URLSearchParams,
): InterlockQueryContext {
  const query = parameters(input);
  const exceptionId = safeString(query.get('exceptionId'));
  const scenarioId = safeString(query.get('scenarioId'));
  const from = safeString(query.get('from'));
  const status = enumValue<InterlockStatus>(
    safeString(query.get('status')),
    interlockStatuses,
  );
  const actionLevel = enumValue<InterlockActionLevel>(
    safeString(query.get('actionLevel')),
    actionLevels,
  );
  const riskType = safeString(query.get('riskType'));
  const receiptStatus = enumValue<InterlockReceiptStatus>(
    safeString(query.get('receiptStatus')),
    receiptStatuses,
  );

  return Object.freeze({
    ...(exceptionId ? { exceptionId } : {}),
    ...(scenarioId ? { scenarioId } : {}),
    ...(from ? { from } : {}),
    ...(status ? { status } : {}),
    ...(actionLevel ? { actionLevel } : {}),
    ...(riskType ? { riskType } : {}),
    ...(receiptStatus ? { receiptStatus } : {}),
  });
}
