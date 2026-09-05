import { Button, Card, Checkbox, Input, Space, Typography } from 'antd';

import type {
  OfflinePacketLedgerItem,
  OfflinePacketPendingAction,
} from '../offlinePacketTypes';

function validationIssues(input: Readonly<Record<string, unknown>>): string {
  return Array.isArray(input.issues)
    ? input.issues.filter((issue): issue is string => typeof issue === 'string').join('\n')
    : '';
}

export default function OfflinePacketActionPanel({
  item,
  pendingAction,
  reason,
  validationDraft,
  onReason,
  onValidationDraft,
  onUpload,
  onValidate,
  onMerge,
  onReject,
  onRetry,
}: {
  item?: OfflinePacketLedgerItem;
  pendingAction?: OfflinePacketPendingAction;
  reason: string;
  validationDraft: Readonly<Record<string, unknown>>;
  onReason: (value: string) => void;
  onValidationDraft: (value: Readonly<Record<string, unknown>>) => void;
  onUpload: () => void;
  onValidate: () => void;
  onMerge: () => void;
  onReject: () => void;
  onRetry: () => void;
}) {
  const actions = item?.availableActions;
  const loading = pendingAction !== undefined;
  const reasonReady = reason.trim().length > 0;
  const valid = validationDraft.valid === true;
  const issues = validationIssues(validationDraft);
  const validationReady = typeof validationDraft.valid === 'boolean'
    && Array.isArray(validationDraft.issues)
    && validationDraft.issues.every((issue) => typeof issue === 'string');
  const disabled = (allowed: boolean | undefined) => loading || !allowed || !reasonReady;

  return (
    <Card
      className="offline-section offline-action-card"
      title="离线包处置"
      aria-label="离线包处置面板"
    >
      <Space orientation="vertical" className="offline-full-width" size={10}>
        <label>
          <Typography.Text>处理原因</Typography.Text>
          <Input.TextArea
            aria-label="处理原因"
            rows={2}
            value={reason}
            onChange={(event: { target: { value: string } }) => onReason(event.target.value)}
          />
        </label>
        <Space wrap className="offline-validation-controls">
          <Checkbox
            aria-label="校验通过"
            checked={valid}
            onChange={(event: { target: { checked: boolean } }) => onValidationDraft({
              valid: event.target.checked,
              issues: event.target.checked ? [] : ['MANUAL_REVIEW'],
            })}
          >
            校验通过
          </Checkbox>
          <Input.TextArea
            aria-label="校验问题"
            rows={2}
            placeholder="每行一个校验问题；留空表示无问题"
            value={issues}
            onChange={(event: { target: { value: string } }) => {
              const nextIssues = event.target.value
                .split(/[,\n]/)
                .map((issue) => issue.trim())
                .filter(Boolean);
              onValidationDraft({ valid: nextIssues.length === 0 && valid, issues: nextIssues });
            }}
          />
        </Space>
        <div className="offline-action-grid">
          <Button aria-label="上传" disabled={disabled(actions?.upload)} onClick={onUpload}>上传</Button>
          <Button
            aria-label="校验"
            disabled={disabled(actions?.validate) || !validationReady}
            onClick={onValidate}
          >
            校验
          </Button>
          <Button
            aria-label="合并"
            type="primary"
            disabled={disabled(actions?.merge)}
            onClick={onMerge}
          >
            合并
          </Button>
          <Button aria-label="驳回" danger disabled={disabled(actions?.reject)} onClick={onReject}>
            驳回
          </Button>
          <Button aria-label="重试" disabled={disabled(actions?.retry)} onClick={onRetry}>重试</Button>
        </div>
        <Typography.Text type="secondary">
          操作仅推进冻结 DO-011 状态机；不会修改作业单、异常、联锁、计划或资源数据。
        </Typography.Text>
      </Space>
    </Card>
  );
}
