import { configVersionChangesSchema, do015Schema, type ConfigVersion } from '../../contracts';
import { businessLabel } from '../../presentation/businessCopy';
import {
  editableConfigKeys,
  type ConfigVersionChanges,
  type EditableConfigKey,
  type EditableConfigValues,
  type SystemSettingsChangePreview,
  type SystemSettingsDraft,
  type SystemSettingsValidation,
} from './systemSettingsTypes';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function editableValues(config: ConfigVersion): EditableConfigValues {
  return {
    displayName: config.displayName,
    defaultScenarioId: config.defaultScenarioId,
    ruleVersion: config.ruleVersion,
    dispatchStrategy: config.dispatchStrategy,
    recommendationEnabled: config.recommendationEnabled,
    offlineSyncEnabled: config.offlineSyncEnabled,
    reportPeriod: config.reportPeriod,
    auditRetentionDays: config.auditRetentionDays,
  };
}

export function createSystemSettingsDraft(config: ConfigVersion): SystemSettingsDraft {
  const strict = do015Schema.parse(config);
  return deepFreeze({ values: editableValues(strict), baseVersion: strict.version, reason: '' });
}

export function updateSystemSettingsDraft<K extends EditableConfigKey>(
  draft: SystemSettingsDraft,
  key: K,
  value: EditableConfigValues[K],
): SystemSettingsDraft {
  if (!editableConfigKeys.some((candidate) => candidate === key)) {
    throw new Error(`Unsupported editable config field: ${String(key)}`);
  }
  return deepFreeze({
    ...draft,
    values: { ...draft.values, [key]: value },
  });
}

export function setSystemSettingsDraftReason(
  draft: SystemSettingsDraft,
  reason: string,
): SystemSettingsDraft {
  return deepFreeze({ ...draft, reason });
}

export function deriveConfigChanges(
  config: ConfigVersion,
  draft: SystemSettingsDraft,
): ConfigVersionChanges {
  return deepFreeze(Object.fromEntries(
    editableConfigKeys
      .filter((key) => !Object.is(config[key], draft.values[key]))
      .map((key) => [key, draft.values[key]]),
  ) as ConfigVersionChanges);
}

const fieldLabels: Record<EditableConfigKey, string> = {
  displayName: '配置名称',
  defaultScenarioId: '默认场景',
  ruleVersion: '规则版本',
  dispatchStrategy: '调度策略',
  recommendationEnabled: '启用智能推荐',
  offlineSyncEnabled: '启用离线同步',
  reportPeriod: '默认报表周期',
  auditRetentionDays: '审计保留天数',
};

const enumLabels: Record<string, string> = {
  BALANCED: '均衡调度',
  PRIORITY_FIRST: '优先级优先',
  RESOURCE_FIRST: '资源优先',
  SHIFT: '班报',
  DAILY: '日报',
  MONTHLY: '月报',
};

function displayValue(value: unknown): string {
  if (typeof value === 'boolean') return value ? '已启用' : '已停用';
  if (typeof value === 'number') return String(value);
  if (typeof value === 'string') return enumLabels[value] ?? businessLabel(value);
  return String(value);
}

export function deriveSystemSettingsChangePreview(
  config: ConfigVersion,
  draft: SystemSettingsDraft,
): readonly SystemSettingsChangePreview[] {
  const changes = deriveConfigChanges(config, draft);
  return deepFreeze(editableConfigKeys
    .filter((field) => Object.hasOwn(changes, field))
    .map((field) => ({
      field,
      label: fieldLabels[field],
      before: displayValue(config[field]),
      after: displayValue(draft.values[field]),
    })));
}

export function validateSystemSettingsDraft(
  config: ConfigVersion,
  draft: SystemSettingsDraft,
): SystemSettingsValidation {
  const changes = deriveConfigChanges(config, draft);
  const fieldErrors: Partial<Record<EditableConfigKey, string>> = {};
  const candidate = do015Schema.safeParse({ ...config, ...draft.values });
  if (!candidate.success) {
    const friendlyErrors: Partial<Record<EditableConfigKey, string>> = {
      displayName: '配置名称不能为空，且最多 64 个字符。',
      defaultScenarioId: '默认场景必须是 SCN-01 至 SCN-07。',
      ruleVersion: '规则版本不能为空，且最多 32 个字符。',
      dispatchStrategy: '请选择受支持的调度策略。',
      recommendationEnabled: '智能推荐开关必须是布尔值。',
      offlineSyncEnabled: '离线同步开关必须是布尔值。',
      reportPeriod: '请选择受支持的报表周期。',
      auditRetentionDays: '审计保留天数必须是 1 至 3650 的整数。',
    };
    for (const issue of candidate.error.issues) {
      const field = issue.path[0];
      if (typeof field === 'string' && editableConfigKeys.includes(field as EditableConfigKey)) {
        fieldErrors[field as EditableConfigKey] ??=
          friendlyErrors[field as EditableConfigKey] ?? issue.message;
      }
    }
  }

  if (Object.keys(changes).length > 0) {
    const parsedChanges = configVersionChangesSchema.safeParse(changes);
    if (!parsedChanges.success) {
      for (const issue of parsedChanges.error.issues) {
        const field = issue.path[0];
        if (typeof field === 'string' && editableConfigKeys.includes(field as EditableConfigKey)) {
          fieldErrors[field as EditableConfigKey] ??= issue.message;
        }
      }
    }
  }

  const reason = draft.reason.trim();
  const formErrors: string[] = [];
  if (Object.keys(changes).length === 0) formErrors.push('至少修改一个配置字段。');
  if (!reason) formErrors.push('请填写变更说明。');

  return deepFreeze({
    valid: Object.keys(fieldErrors).length === 0 && formErrors.length === 0,
    fieldErrors,
    formErrors,
    changes,
    reason,
  });
}
