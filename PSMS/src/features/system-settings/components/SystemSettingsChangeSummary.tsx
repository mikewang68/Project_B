import type {
  SystemSettingsChangePreview,
  SystemSettingsDraft,
  SystemSettingsValidation,
} from '../systemSettingsTypes';
import { SystemSettingsCommandFeedbackPanel } from './SystemSettingsCommandFeedback';

type Props = Readonly<{
  draft?: SystemSettingsDraft;
  preview: readonly SystemSettingsChangePreview[];
  validation: Pick<SystemSettingsValidation, 'fieldErrors' | 'formErrors'>;
  pending: boolean;
  canEdit: boolean;
  editReason?: string;
  feedback?: Parameters<typeof SystemSettingsCommandFeedbackPanel>[0]['feedback'];
  onEdit(): void;
  onReason(reason: string): void;
  onSave(): void;
  onDiscard(): void;
}>;

export function SystemSettingsChangeSummary({
  draft,
  preview,
  validation,
  pending,
  canEdit,
  editReason,
  feedback,
  onEdit,
  onReason,
  onSave,
  onDiscard,
}: Props) {
  const fieldErrors = Object.values(validation.fieldErrors)
    .filter((message): message is string => Boolean(message));
  return (
    <aside className="system-settings-summary" aria-label="配置变更摘要">
      <div className="system-settings-summary-sticky">
        <p className="system-settings-section-kicker">页面内固定摘要</p>
        <h3>配置变更摘要</h3>
        {preview.length === 0 ? (
          <p className="settings-empty-preview">暂无待提交变更</p>
        ) : (
          <ol className="settings-change-list">
            {preview.map((item) => (
              <li key={item.field}>
                <strong>{item.label}</strong>
                <span>{item.before}</span>
                <span aria-hidden="true">→</span>
                <span>{item.after}</span>
              </li>
            ))}
          </ol>
        )}

        {draft ? (
          <>
            <label className="settings-reason-field">
              <span>变更说明</span>
              <textarea
                aria-label="变更说明"
                disabled={pending}
                value={draft.reason}
                onChange={(event: { currentTarget: { value: string } }) => {
                  onReason(event.currentTarget.value);
                }}
              />
            </label>
            {validation.formErrors.map((error) => (
              <p className="settings-form-error" key={error}>{error}</p>
            ))}
            {fieldErrors.length > 0 && (
              <div className="settings-field-errors" aria-label="字段校验问题">
                <strong>字段校验问题</strong>
                {fieldErrors.map((error) => (
                  <p className="settings-form-error" key={error}>{error}</p>
                ))}
              </div>
            )}
            <div className="settings-actions">
              <button type="button" disabled={pending} onClick={onDiscard}>放弃修改</button>
              <button className="is-primary" type="button" disabled={pending} onClick={onSave}>
                {pending ? '保存中…' : '保存配置'}
              </button>
            </div>
          </>
        ) : (
          <>
            <button
              className="settings-edit-button"
              type="button"
              disabled={!canEdit}
              onClick={onEdit}
            >
              编辑配置
            </button>
            {!canEdit && editReason && <p className="settings-permission-note">{editReason}</p>}
          </>
        )}

        <div className="settings-reset-boundary">
          <button type="button" disabled>重置到冻结场景</button>
          <p>当前角色缺少场景重置权限，页面不提供场景重置</p>
        </div>
        <SystemSettingsCommandFeedbackPanel feedback={feedback} />
      </div>
    </aside>
  );
}
