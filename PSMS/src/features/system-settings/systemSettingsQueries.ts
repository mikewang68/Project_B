import { systemSettingsGroupIds, type SystemSettingsGroupId, type SystemSettingsQuery } from './systemSettingsTypes';

const controlCharacters = /[\u0000-\u001f\u007f]/g;
const scenarioIds = ['SCN-01', 'SCN-02', 'SCN-03', 'SCN-04', 'SCN-05', 'SCN-06', 'SCN-07'];

function clean(value: string | null): string | undefined {
  if (value === null) return undefined;
  const sanitized = value.replace(controlCharacters, '').trim().slice(0, 128);
  return sanitized.length > 0 ? sanitized : undefined;
}

function group(value: string | undefined): SystemSettingsGroupId {
  return value && systemSettingsGroupIds.some((candidate) => candidate === value)
    ? value as SystemSettingsGroupId
    : 'overview';
}

export function parseSystemSettingsQuery(input: string | URLSearchParams): SystemSettingsQuery {
  const params = typeof input === 'string'
    ? new URLSearchParams(input.startsWith('?') ? input.slice(1) : input)
    : input;
  const configId = clean(params.get('configId'));
  const scenarioIdInput = clean(params.get('scenarioId'));
  const scenarioId = scenarioIdInput && scenarioIds.includes(scenarioIdInput)
    ? scenarioIdInput
    : undefined;
  const from = clean(params.get('from'));

  return Object.freeze({
    group: group(clean(params.get('group'))),
    ...(configId ? { configId } : {}),
    ...(scenarioId ? { scenarioId } : {}),
    ...(from ? { from } : {}),
  });
}
