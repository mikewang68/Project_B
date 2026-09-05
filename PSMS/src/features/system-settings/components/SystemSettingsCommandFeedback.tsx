import type { SystemSettingsCommandFeedback } from '../systemSettingsTypes';

export function SystemSettingsCommandFeedbackPanel({
  feedback,
}: { feedback?: SystemSettingsCommandFeedback }) {
  if (!feedback) return null;
  return (
    <section
      className={`settings-command-feedback ${feedback.ok ? 'is-success' : 'is-error'}`}
      aria-label="C13 命令反馈"
    >
      <strong>{feedback.ok ? feedback.message : '保存失败'}</strong>
      {!feedback.ok && <p>{feedback.message}</p>}
      {feedback.version !== undefined && <span>当前版本：v{feedback.version}</span>}
      <span>命令编号：{feedback.commandId}</span>
      <span>链路编号：{feedback.traceId}</span>
      <span>接口回执审计号：{feedback.transportAuditLogId}</span>
      {feedback.domainAuditLogId && (
        <span>领域变更审计号：{feedback.domainAuditLogId}</span>
      )}
      {feedback.errorCode && <span>错误码：{feedback.errorCode}</span>}
      {feedback.idempotent && <span>幂等重放：已命中首次结果</span>}
    </section>
  );
}
