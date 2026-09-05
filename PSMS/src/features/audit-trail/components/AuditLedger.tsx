import { Button, Card, Table, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { AuditLedgerItem, AuditResultCategory } from '../auditTypes';

const resultLabels: Record<AuditResultCategory, string> = {
  RECORDED: '已记录',
  SUCCESS: '显式成功',
  DENIED: '权限拒绝',
  VERSION_CONFLICT: '版本冲突',
  IDEMPOTENT_HIT: '幂等命中',
  BUSINESS_ERROR: '业务错误',
};

const resultColors: Record<AuditResultCategory, string> = {
  RECORDED: 'default',
  SUCCESS: 'success',
  DENIED: 'error',
  VERSION_CONFLICT: 'warning',
  IDEMPOTENT_HIT: 'processing',
  BUSINESS_ERROR: 'error',
};

export default function AuditLedger({
  items,
  selectedAuditId,
  onOpenDetail,
}: Readonly<{
  items: readonly AuditLedgerItem[];
  selectedAuditId?: string;
  onOpenDetail: (auditId: string) => void;
}>) {
  return (
    <Card className="audit-ledger-card" title="审计台账" aria-label="审计台账">
      <Table
        className="audit-ledger-table"
        rowKey={(item: AuditLedgerItem) => item.record.id}
        size="small"
        pagination={false}
        dataSource={[...items]}
        tableLayout="fixed"
        scroll={{ x: 920 }}
        rowClassName={(item: AuditLedgerItem) => item.record.id === selectedAuditId ? 'audit-row-selected' : ''}
        columns={[
          { title: '审计编号', dataIndex: ['record', 'id'], key: 'id', width: 108 },
          { title: '来源', key: 'module', width: 90, render: (_: unknown, item: AuditLedgerItem) => businessLabel(item.sourceModule) },
          {
            title: '操作', key: 'action', render: (_: unknown, item: AuditLedgerItem) => (
              <div className="audit-cell-stack">
                <Typography.Text>{item.record.actorId}</Typography.Text>
                <Typography.Text type="secondary">{businessLabel(item.record.action)}</Typography.Text>
              </div>
            ),
          },
          {
            title: '对象', key: 'object', render: (_: unknown, item: AuditLedgerItem) => (
              <div className="audit-cell-stack">
                <Typography.Text>{businessLabel(item.record.objectType)}</Typography.Text>
                <Typography.Text type="secondary">{item.record.objectId}</Typography.Text>
              </div>
            ),
          },
          {
            title: '结果', key: 'result', width: 104, render: (_: unknown, item: AuditLedgerItem) => (
              <Tag color={resultColors[item.resultCategory]}>{resultLabels[item.resultCategory]}</Tag>
            ),
          },
          {
            title: '链路与时间', key: 'trace', render: (_: unknown, item: AuditLedgerItem) => (
              <div className="audit-cell-stack audit-mono-cell">
                <Typography.Text code>{item.record.traceId}</Typography.Text>
                <Typography.Text type="secondary">{item.record.occurredAt}</Typography.Text>
              </div>
            ),
          },
          {
            title: '操作', key: 'detail', width: 142, render: (_: unknown, item: AuditLedgerItem) => (
              <Button type="link" size="small" onClick={() => onOpenDetail(item.record.id)}>
                查看 {item.record.id} 详情
              </Button>
            ),
          },
        ]}
      />
    </Card>
  );
}
