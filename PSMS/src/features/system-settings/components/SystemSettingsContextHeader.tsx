import type { Api022SuccessEnvelope, ConfigVersion } from '../../../contracts';
import { businessLabel } from '../../../presentation/businessCopy';
import type { SystemSettingsContextProjection } from '../systemSettingsTypes';

type Props = Readonly<{
  config: ConfigVersion;
  context: SystemSettingsContextProjection;
  observation?: Api022SuccessEnvelope;
}>;

export function SystemSettingsContextHeader({ config, context, observation }: Props) {
  return (
    <header className="system-settings-context">
      <div>
        <div className="system-settings-eyebrow">
          <span className="system-settings-id">UI-012</span>
          <span>当前路由：/settings/system</span>
          <span>API-022 / API-023</span>
        </div>
        <h2>系统配置</h2>
        <p className="system-settings-disclosure">演示系统配置视图，非生产配置中心</p>
      </div>
      <div className="system-settings-context-grid" aria-label="系统配置上下文摘要">
        <span>场景 <strong>{context.activeScenarioId}</strong></span>
        <span>配置版本 <strong>{config.configVersion}</strong></span>
        <span>规则 <strong>{config.ruleVersion}</strong></span>
        <span>状态 <strong>{businessLabel(config.status)}</strong></span>
        <span>对象版本 <strong>v{config.version}</strong></span>
        <span>更新时间 <strong>{config.updatedAt}</strong></span>
        <span>读取链路 <strong>{observation?.traceId ?? '等待 API-022'}</strong></span>
      </div>
    </header>
  );
}
