import type { SystemSettingsWorkflowState } from '../systemSettingsRuntime';

export function SystemSettingsReadBanner({ state }: { state: SystemSettingsWorkflowState }) {
  if (state.readState === 'loading') {
    return <div className="settings-banner is-loading" aria-label="系统配置加载中">正在校验 API-022 配置观察…</div>;
  }
  if (state.readState === 'success') {
    return (
      <div className="settings-banner is-success">
        <strong>API-022 已校验</strong>
        <span>接口回执审计号：<code>{state.readObservation?.auditLogId}</code></span>
        <span>链路编号：<code>{state.readObservation?.traceId}</code></span>
      </div>
    );
  }
  if (state.readState === 'error') {
    const observation = state.readObservation;
    return (
      <div className="settings-banner is-error" role="status">
        <strong>{state.readError}</strong>
        {observation && <span>链路编号：<code>{observation.traceId}</code></span>}
        {observation && <span>接口回执审计号：<code>{observation.auditLogId}</code></span>}
      </div>
    );
  }
  return null;
}
