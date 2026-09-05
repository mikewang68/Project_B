import { Button, Card, Space, Tag, Typography } from 'antd';

export default function AuditContextHeader({
  scenarioId,
  visibleCount,
  totalCount,
  sourceLabels,
  resetting,
  canReset,
  resetReason,
  onReset,
}: Readonly<{
  scenarioId: string;
  visibleCount: number;
  totalCount: number;
  sourceLabels: readonly string[];
  resetting: boolean;
  canReset: boolean;
  resetReason: string;
  onReset: () => void;
}>) {
  return (
    <Card className="audit-context-card" size="small" aria-label="审计上下文">
      <div className="audit-context-content">
        <div className="audit-context-copy">
          <Space size={[6, 6]} wrap>
            <Tag color="processing">{scenarioId}</Tag>
            <Tag color="cyan">演示审计投影，非真实生产日志</Tag>
            <Tag>{visibleCount} / {totalCount} 条领域记录</Tag>
          </Space>
          <Typography.Paragraph className="audit-idempotent-note">
            当前冻结场景未产生独立幂等审计记录；重放复用首次结果且不新增 DO-013。
          </Typography.Paragraph>
          {sourceLabels.length > 0 ? (
            <Space size={[4, 4]} wrap>
              {sourceLabels.map((label) => <Tag key={label}>{label}</Tag>)}
            </Space>
          ) : null}
          {!canReset ? (
            <Typography.Text type="secondary">{resetReason}</Typography.Text>
          ) : null}
        </div>
        <Button loading={resetting} disabled={!canReset} onClick={onReset}>
          重置到 SCN-01
        </Button>
      </div>
    </Card>
  );
}
