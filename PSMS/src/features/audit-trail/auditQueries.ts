import {
  auditResultCategories,
  auditSourceModules,
  type AuditQueryContext,
  type AuditResultCategory,
  type AuditSourceModule,
} from './auditTypes';

const textKeys = [
  'auditId',
  'action',
  'objectType',
  'actorId',
  'objectId',
  'traceId',
  'period',
  'scenarioId',
  'from',
] as const;

function clean(value: string | null): string | undefined {
  const sanitized = value?.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 128);
  return sanitized || undefined;
}

function isModule(value: string | undefined): value is AuditSourceModule {
  return value !== undefined && auditSourceModules.includes(value as AuditSourceModule);
}

function isResult(value: string | undefined): value is AuditResultCategory {
  return value !== undefined && auditResultCategories.includes(value as AuditResultCategory);
}

export function parseAuditQuery(input: string | URLSearchParams): AuditQueryContext {
  const parameters = typeof input === 'string'
    ? new URLSearchParams(input.startsWith('?') ? input.slice(1) : input)
    : input;
  const context: Record<string, string> = {};

  for (const key of textKeys) {
    const value = clean(parameters.get(key));
    if (value) context[key] = value;
  }
  const module = clean(parameters.get('module'));
  if (isModule(module)) context.module = module;
  const result = clean(parameters.get('result'));
  if (isResult(result)) context.result = result;

  return Object.freeze(context) as AuditQueryContext;
}
