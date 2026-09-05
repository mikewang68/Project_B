import { do015Schema } from '../../contracts';
import type { DemoRootState } from '../../stores';
import type {
  SystemSettingsGroup,
  SystemSettingsProjection,
  SystemSettingsQuery,
  SystemSettingsRuntimeContext,
} from './systemSettingsTypes';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

const groups: readonly SystemSettingsGroup[] = deepFreeze([
  {
    id: 'overview',
    label: '基础与版本',
    description: '配置身份、默认场景和只读版本元数据。',
    editableKeys: ['displayName', 'defaultScenarioId'],
    readOnlyKeys: [
      'id', 'configVersion', 'status', 'version', 'createdAt', 'updatedAt', 'updatedBy',
    ],
  },
  {
    id: 'dispatch',
    label: '调度规则',
    description: '演示规则版本、调度策略和推荐开关。',
    editableKeys: ['ruleVersion', 'dispatchStrategy', 'recommendationEnabled'],
    readOnlyKeys: [],
  },
  {
    id: 'integration',
    label: '接口与离线',
    description: '离线同步开关和 API 调用观察。',
    editableKeys: ['offlineSyncEnabled'],
    readOnlyKeys: ['api022', 'api023', 'externalSystems'],
  },
  {
    id: 'governance',
    label: '报表与审计',
    description: '默认报表周期和演示审计保留天数。',
    editableKeys: ['reportPeriod', 'auditRetentionDays'],
    readOnlyKeys: [],
  },
  {
    id: 'access',
    label: '权限与上下文',
    description: '当前会话与运行时的只读投影。',
    editableKeys: [],
    readOnlyKeys: [
      'roleCode', 'dataScope', 'shiftId', 'online', 'timezone', 'demoTime', 'activeScenarioId',
    ],
  },
]);

export function projectSystemSettings(
  state: DemoRootState,
  query: SystemSettingsQuery,
  runtime: SystemSettingsRuntimeContext,
): SystemSettingsProjection {
  const requested = query.configId;
  const visible = requested
    ? state.systemConfig.configVersions.find(({ id }) => id === requested)
    : state.systemConfig.configVersions[0];
  const config = visible ? do015Schema.parse(structuredClone(visible)) : undefined;
  const emptyReason = config
    ? undefined
    : state.systemConfig.configVersions.length === 0
      ? 'STORE' as const
      : 'NOT_FOUND' as const;

  return deepFreeze({
    ...(config ? { config } : {}),
    selectedGroup: query.group,
    groups,
    context: {
      roleCode: state.session.roleCode,
      dataScope: [...state.session.dataScope],
      shiftId: state.session.shiftId,
      online: state.session.online,
      timezone: runtime.timezone,
      demoTime: state.session.demoTime,
      activeScenarioId: state.scenario.activeScenarioId,
    },
    ...(emptyReason ? { emptyReason } : {}),
  });
}
