import { Button, Card, Space, Tag, Timeline, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { AuditTrace } from '../auditTypes';

export default function AuditTracePanel({
  trace,
  onClose,
}: Readonly<{ trace?: AuditTrace; onClose: () => void }>) {
  if (!trace) {
    return (
      <Card className="audit-trace-card" title="审计链路">
        <Typography.Text type="secondary">选择一条审计记录后查看确定性链路。</Typography.Text>
      </Card>
    );
  }
  return (
    <Card
      className="audit-trace-card"
      title={`审计链路 · ${trace.traceId}`}
      aria-label={`审计链路 ${trace.traceId}`}
      extra={<Button size="small" onClick={onClose}>关闭链路</Button>}
    >
      <Typography.Paragraph>当前链路共 {trace.count} 条</Typography.Paragraph>
      <Timeline
        items={trace.entries.map((entry) => ({
          content: (
            <div className="audit-trace-entry">
              <Space size={[4, 4]} wrap>
                <Typography.Text strong>{entry.record.id}</Typography.Text>
                <Tag>{businessLabel(entry.sourceModule)}</Tag>
                <Tag>{businessLabel(entry.resultCategory)}</Tag>
              </Space>
              <Typography.Text>{businessLabel(entry.record.action)} · {businessLabel(entry.record.objectType)}/{entry.record.objectId}</Typography.Text>
              <Typography.Text type="secondary">{entry.record.occurredAt}</Typography.Text>
            </div>
          ),
        }))}
      />
    </Card>
  );
}
