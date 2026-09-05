import { Alert, Card, Space, Typography } from 'antd';

import type { CommandAuditEntry } from '../../../governance/audit';
import { businessLabel } from '../../../presentation/businessCopy';
import type {
  OfflinePacketCommandFeedback,
  OfflinePacketPendingAction,
} from '../offlinePacketTypes';

export default function OfflineCommandFeedback({
  feedback,
  pendingAction,
  audits,
}: {
  feedback?: OfflinePacketCommandFeedback;
  pendingAction?: OfflinePacketPendingAction;
  audits: readonly CommandAuditEntry[];
}) {
  const pendingLabels: Readonly<Record<OfflinePacketPendingAction, string>> = {
    UPLOAD: '上传',
    VALIDATE: '校验',
    MERGE: '合并',
    REJECT: '驳回',
    RETRY: '重试',
  };
  return (
    <Card
      className="offline-section offline-command-feedback"
      size="small"
      title="最新命令"
      aria-label="C10 命令反馈"
      aria-live="polite"
    >
      <Space orientation="vertical" className="offline-full-width" size={8}>
        {pendingAction ? (
          <Alert type="info" showIcon title={`正在执行${pendingLabels[pendingAction]}`} />
        ) : null}
        {!feedback && !pendingAction ? (
          <Typography.Text type="secondary">尚未执行 C10 离线同步命令</Typography.Text>
        ) : null}
        {feedback ? (
          <Alert
            type={feedback.ok ? 'success' : 'error'}
            showIcon
            title={feedback.ok ? feedback.message : '命令执行失败'}
            description={
              <Space orientation="vertical" size={2}>
                {!feedback.ok ? <Typography.Text>{feedback.message}</Typography.Text> : null}
                <Typography.Text>{feedback.commandId}</Typography.Text>
                <Typography.Text>{feedback.traceId}</Typography.Text>
                <Typography.Text>{feedback.auditLogId}</Typography.Text>
              </Space>
            }
          />
        ) : null}
        {audits.length > 0 ? (
          <div className="offline-feedback-audits">
            {audits.map(({ record, metadata }) => (
              <Typography.Text key={record.id}>
          {businessLabel(record.action)} · {businessLabel(metadata.result)}
              </Typography.Text>
            ))}
          </div>
        ) : null}
      </Space>
    </Card>
  );
}
