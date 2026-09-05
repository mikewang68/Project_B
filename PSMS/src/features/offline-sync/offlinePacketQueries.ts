import { mergeStatuses } from '../../contracts';
import type { OfflineMergeStatus, OfflinePacketQueryContext } from './offlinePacketTypes';

const DISPLAY_VALUE_LIMIT = 128;

function safeString(value: string | null): string | undefined {
  if (value === null) return undefined;
  const safe = value.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, DISPLAY_VALUE_LIMIT);
  return safe === '' ? undefined : safe;
}

function parameters(input: string | URLSearchParams): URLSearchParams {
  if (input instanceof URLSearchParams) return new URLSearchParams(input);
  return new URLSearchParams(input.startsWith('?') ? input.slice(1) : input);
}

function mergeStatus(value: string | undefined): OfflineMergeStatus | undefined {
  return value !== undefined && mergeStatuses.includes(value as OfflineMergeStatus)
    ? value as OfflineMergeStatus
    : undefined;
}

export function parseOfflinePacketQuery(
  input: string | URLSearchParams,
): OfflinePacketQueryContext {
  const query = parameters(input);
  const packetId = safeString(query.get('packetId'));
  const terminalId = safeString(query.get('terminalId'));
  const workOrderNo = safeString(query.get('workOrderNo'));
  const selectedMergeStatus = mergeStatus(safeString(query.get('mergeStatus')));
  const scenarioId = safeString(query.get('scenarioId'));
  const from = safeString(query.get('from'));

  return Object.freeze({
    ...(packetId ? { packetId } : {}),
    ...(terminalId ? { terminalId } : {}),
    ...(workOrderNo ? { workOrderNo } : {}),
    ...(selectedMergeStatus ? { mergeStatus: selectedMergeStatus } : {}),
    ...(scenarioId ? { scenarioId } : {}),
    ...(from ? { from } : {}),
  });
}
