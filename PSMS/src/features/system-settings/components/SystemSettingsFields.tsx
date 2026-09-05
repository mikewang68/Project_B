import type { Api022SuccessEnvelope, ConfigVersion } from '../../../contracts';
import { businessLabel } from '../../../presentation/businessCopy';
import type {
  EditableConfigKey,
  EditableConfigValues,
  SystemSettingsContextProjection,
  SystemSettingsDraft,
  SystemSettingsGroupId,
} from '../systemSettingsTypes';

type Props = Readonly<{
  config: ConfigVersion;
  context: SystemSettingsContextProjection;
  group: SystemSettingsGroupId;
  draft?: SystemSettingsDraft;
  disabled: boolean;
  fieldErrors: Readonly<Partial<Record<EditableConfigKey, string>>>;
  readObservation?: Api022SuccessEnvelope;
  onChange<K extends EditableConfigKey>(key: K, value: EditableConfigValues[K]): void;
}>;

const strategyLabels = {
  BALANCED: '均衡调度',
  PRIORITY_FIRST: '优先级优先',
  RESOURCE_FIRST: '资源优先',
} as const;

const reportLabels = { SHIFT: '班报', DAILY: '日报', MONTHLY: '月报' } as const;

type TextValueEvent = Readonly<{ currentTarget: Readonly<{ value: string }> }>;
type CheckedValueEvent = Readonly<{ currentTarget: Readonly<{ checked: boolean }> }>;

function ReadRow({ label, value }: { label: string; value: string | number | boolean }) {
  return (
    <div className="settings-read-row">
      <span>{label}</span>
      <strong>{typeof value === 'boolean' ? (value ? '是' : '否') : value}</strong>
    </div>
  );
}

function FieldError({ message }: { message?: string }) {
  return message ? <span className="settings-field-error">{message}</span> : null;
}

export function SystemSettingsFields({
  config,
  context,
  group,
  draft,
  disabled,
  fieldErrors,
  readObservation,
  onChange,
}: Props) {
  const values = draft?.values ?? config;
  return (
    <section className="system-settings-detail" aria-label="系统配置详情">
      {group === 'overview' && (
        <>
          <h3>基础与版本</h3>
          <div className="settings-field-grid">
            {draft ? (
              <label>
                <span>配置名称</span>
                <input
                  aria-label="配置名称"
                  disabled={disabled}
                  value={values.displayName ? businessLabel(values.displayName) : ''}
                  onChange={(event: TextValueEvent) => onChange('displayName', event.currentTarget.value)}
                />
                <FieldError message={fieldErrors.displayName} />
              </label>
            ) : <ReadRow label="配置名称" value={businessLabel(config.displayName)} />}
            {draft ? (
              <label>
                <span>默认场景</span>
                <select
                  aria-label="默认场景"
                  disabled={disabled}
                  value={values.defaultScenarioId}
                  onChange={(event: TextValueEvent) => onChange(
                    'defaultScenarioId',
                    event.currentTarget.value as EditableConfigValues['defaultScenarioId'],
                  )}
                >
                  {['SCN-01', 'SCN-02', 'SCN-03', 'SCN-04', 'SCN-05', 'SCN-06', 'SCN-07']
                    .map((value) => <option key={value} value={value}>{value}</option>)}
                </select>
                <FieldError message={fieldErrors.defaultScenarioId} />
              </label>
            ) : <ReadRow label="默认场景" value={config.defaultScenarioId} />}
          </div>
          <div className="settings-read-grid">
            <ReadRow label="配置编号" value={config.id} />
            <ReadRow label="配置版本" value={config.configVersion} />
            <ReadRow label="状态" value={businessLabel(config.status)} />
            <ReadRow label="对象版本" value={config.version} />
            <ReadRow label="创建时间" value={config.createdAt} />
            <ReadRow label="更新时间" value={config.updatedAt} />
            <ReadRow label="更新人" value={config.updatedBy} />
          </div>
        </>
      )}

      {group === 'dispatch' && (
        <>
          <h3>调度规则</h3>
          <div className="settings-field-grid">
            {draft ? (
              <label>
                <span>规则版本</span>
                <input
                  aria-label="规则版本"
                  disabled={disabled}
                  value={values.ruleVersion}
                  onChange={(event: TextValueEvent) => onChange('ruleVersion', event.currentTarget.value)}
                />
                <FieldError message={fieldErrors.ruleVersion} />
              </label>
            ) : <ReadRow label="规则版本" value={config.ruleVersion} />}
            {draft ? (
              <label>
                <span>调度策略</span>
                <select
                  aria-label="调度策略"
                  disabled={disabled}
                  value={values.dispatchStrategy}
                  onChange={(event: TextValueEvent) => onChange(
                    'dispatchStrategy',
                    event.currentTarget.value as EditableConfigValues['dispatchStrategy'],
                  )}
                >
                  {Object.entries(strategyLabels).map(([value, label]) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
                <FieldError message={fieldErrors.dispatchStrategy} />
              </label>
            ) : <ReadRow label="调度策略" value={strategyLabels[config.dispatchStrategy]} />}
            <label className="settings-toggle-row">
              <span>启用智能推荐</span>
              {draft ? (
                <input
                  aria-label="启用智能推荐"
                  type="checkbox"
                  disabled={disabled}
                  checked={values.recommendationEnabled}
                  onChange={(event: CheckedValueEvent) => onChange(
                    'recommendationEnabled', event.currentTarget.checked,
                  )}
                />
              ) : <strong>{config.recommendationEnabled ? '已启用' : '已停用'}</strong>}
            </label>
          </div>
        </>
      )}

      {group === 'integration' && (
        <>
          <h3>接口与离线</h3>
          <label className="settings-toggle-row">
            <span>启用离线同步</span>
            {draft ? (
              <input
                aria-label="启用离线同步"
                type="checkbox"
                disabled={disabled}
                checked={values.offlineSyncEnabled}
                onChange={(event: CheckedValueEvent) => onChange(
                  'offlineSyncEnabled', event.currentTarget.checked,
                )}
              />
            ) : <strong>{config.offlineSyncEnabled ? '已启用' : '已停用'}</strong>}
          </label>
          <div className="settings-read-grid">
            <ReadRow label="API-022 读取" value={readObservation ? '已校验' : '等待观察'} />
            <ReadRow label="API-023 命令" value="仅编辑时调用" />
          </div>
          <p className="settings-boundary-note">未纳入 DO-015，不提供生产连接参数</p>
        </>
      )}

      {group === 'governance' && (
        <>
          <h3>报表与审计</h3>
          <div className="settings-field-grid">
            {draft ? (
              <label>
                <span>默认报表周期</span>
                <select
                  aria-label="默认报表周期"
                  disabled={disabled}
                  value={values.reportPeriod}
                  onChange={(event: TextValueEvent) => onChange(
                    'reportPeriod',
                    event.currentTarget.value as EditableConfigValues['reportPeriod'],
                  )}
                >
                  {Object.entries(reportLabels).map(([value, label]) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
                <FieldError message={fieldErrors.reportPeriod} />
              </label>
            ) : <ReadRow label="默认报表周期" value={reportLabels[config.reportPeriod]} />}
            {draft ? (
              <label>
                <span>审计保留天数</span>
                <input
                  aria-label="审计保留天数"
                  type="number"
                  min={1}
                  max={3650}
                  disabled={disabled}
                  value={values.auditRetentionDays}
                  onChange={(event: TextValueEvent) => onChange(
                    'auditRetentionDays', Number(event.currentTarget.value),
                  )}
                />
                <FieldError message={fieldErrors.auditRetentionDays} />
              </label>
            ) : <ReadRow label="审计保留天数" value={config.auditRetentionDays} />}
          </div>
          <p className="settings-boundary-note">演示审计记录不等同于合规归档</p>
        </>
      )}

      {group === 'access' && (
        <>
          <h3>权限与上下文</h3>
          <div className="settings-read-grid">
            <ReadRow label="当前角色" value={businessLabel(context.roleCode)} />
            <ReadRow label="数据域" value={context.dataScope.map(businessLabel).join('、')} />
            <ReadRow label="班次" value={context.shiftId} />
            <ReadRow label="在线状态" value={context.online ? '在线' : '离线'} />
            <ReadRow label="时区" value={context.timezone} />
            <ReadRow label="演示时间" value={context.demoTime} />
            <ReadRow label="当前场景" value={context.activeScenarioId} />
          </div>
        </>
      )}
    </section>
  );
}
