import type { ConfigVersion, PublicErrorCode } from '../../contracts';

export const systemSettingsGroupIds = [
  'overview',
  'dispatch',
  'integration',
  'governance',
  'access',
] as const;

export type SystemSettingsGroupId = (typeof systemSettingsGroupIds)[number];

export const editableConfigKeys = [
  'displayName',
  'defaultScenarioId',
  'ruleVersion',
  'dispatchStrategy',
  'recommendationEnabled',
  'offlineSyncEnabled',
  'reportPeriod',
  'auditRetentionDays',
] as const;

export type EditableConfigKey = (typeof editableConfigKeys)[number];
export type EditableConfigValues = Pick<ConfigVersion, EditableConfigKey>;
export type ConfigVersionChanges = Partial<EditableConfigValues>;

export type SystemSettingsReadOnlyKey =
  | 'id'
  | 'configVersion'
  | 'status'
  | 'version'
  | 'createdAt'
  | 'updatedAt'
  | 'updatedBy'
  | 'api022'
  | 'api023'
  | 'externalSystems'
  | 'roleCode'
  | 'dataScope'
  | 'shiftId'
  | 'online'
  | 'timezone'
  | 'demoTime'
  | 'activeScenarioId';

export type SystemSettingsGroup = Readonly<{
  id: SystemSettingsGroupId;
  label: string;
  description: string;
  editableKeys: readonly EditableConfigKey[];
  readOnlyKeys: readonly SystemSettingsReadOnlyKey[];
}>;

export type SystemSettingsQuery = Readonly<{
  group: SystemSettingsGroupId;
  configId?: string;
  scenarioId?: string;
  from?: string;
}>;

export type SystemSettingsRuntimeContext = Readonly<{
  timezone: string;
}>;

export type SystemSettingsContextProjection = Readonly<{
  roleCode: string;
  dataScope: readonly string[];
  shiftId: string;
  online: boolean;
  timezone: string;
  demoTime: string;
  activeScenarioId: string;
}>;

export type SystemSettingsProjection = Readonly<{
  config?: Readonly<ConfigVersion>;
  selectedGroup: SystemSettingsGroupId;
  groups: readonly SystemSettingsGroup[];
  context: SystemSettingsContextProjection;
  emptyReason?: 'STORE' | 'NOT_FOUND';
}>;

export type SystemSettingsDraft = Readonly<{
  values: EditableConfigValues;
  baseVersion: number;
  reason: string;
}>;

export type SystemSettingsValidation = Readonly<{
  valid: boolean;
  fieldErrors: Readonly<Partial<Record<EditableConfigKey, string>>>;
  formErrors: readonly string[];
  changes: ConfigVersionChanges;
  reason: string;
}>;

export type SystemSettingsChangePreview = Readonly<{
  field: EditableConfigKey;
  label: string;
  before: string;
  after: string;
}>;

export type SystemSettingsCommandFeedback = Readonly<{
  ok: boolean;
  commandId: string;
  traceId: string;
  transportAuditLogId: string;
  domainAuditLogId?: string;
  message: string;
  errorCode?: PublicErrorCode;
  idempotent: boolean;
  version?: number;
}>;
