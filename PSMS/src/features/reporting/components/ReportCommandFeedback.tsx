import { Alert, Card, Descriptions, Tag, Typography } from 'antd';

import type { ReportCommandFeedback as Feedback } from '../reportTypes';

export default function ReportCommandFeedback({
  feedback,
  pendingReportId,
}: {
  feedback?: Feedback;
  pendingReportId?: string;
}) {
  return (
    <Card className="report-feedback-card" size="small" title="生成反馈" aria-label="C11 命令反馈">
      {pendingReportId ? (
        <Alert type="info" showIcon title={`正在生成 ${pendingReportId}`} />
      ) : feedback ? (
        <div className="report-feedback-stack">
          <Alert
            type={feedback.ok ? 'success' : 'error'}
            showIcon
            title={feedback.message}
            description={feedback.errorCode}
          />
          <Descriptions size="small" column={1}>
            <Descriptions.Item label="命令编号">{feedback.commandId}</Descriptions.Item>
            <Descriptions.Item label="链路编号">{feedback.traceId}</Descriptions.Item>
            <Descriptions.Item label="审计编号">{feedback.auditLogId}</Descriptions.Item>
          </Descriptions>
          {feedback.idempotent ? <Tag>幂等重放</Tag> : null}
        </div>
      ) : (
        <Typography.Text type="secondary">尚无生成命令；读取 API 不会修改报表事实。</Typography.Text>
      )}
    </Card>
  );
}
