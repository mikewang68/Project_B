import { Card, Descriptions, Empty, Space, Tag, Typography } from 'antd';

import type { CommandAuditEntry } from '../../../governance/audit';
import { businessLabel } from '../../../presentation/businessCopy';
import type { ExceptionLedgerItem } from '../types';
import ExceptionEvidencePanel from './ExceptionEvidencePanel';

export default function ExceptionDetailPanel({
  item,
  audits,
}: {
  item?: ExceptionLedgerItem;
  audits: readonly CommandAuditEntry[];
}) {
  return (
    <div className="exception-detail-stack">
      <Card className="exception-section" title="异常详情" aria-label="异常详情">
        {!item ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择异常" /> : (
          <Space orientation="vertical" className="exception-full-width" size={12}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="异常编号">{item.exception.exceptionNo}</Descriptions.Item>
              <Descriptions.Item label="状态">{businessLabel(item.exception.status)}</Descriptions.Item>
              <Descriptions.Item label="类型">{businessLabel(item.exception.type)}</Descriptions.Item>
              <Descriptions.Item label="等级">{businessLabel(item.exception.level)}</Descriptions.Item>
              <Descriptions.Item label="负责人">{item.exception.owner || '未分派'}</Descriptions.Item>
              <Descriptions.Item label="版本">v{item.exception.version}</Descriptions.Item>
              <Descriptions.Item label="截止时间">{item.exception.dueAt}</Descriptions.Item>
              <Descriptions.Item label="到期状态">{businessLabel(item.dueState)}</Descriptions.Item>
            </Descriptions>
            <div className="exception-state-flow" aria-label="异常状态流">
              {item.stateFlow.map(({ status, current }) => (
                <Tag key={status} color={current ? 'processing' : 'default'} title={businessLabel(status)}>
                  {businessLabel(status)}
                </Tag>
              ))}
            </div>
          </Space>
        )}
      </Card>
      <ExceptionEvidencePanel item={item} />
      <Card className="exception-section" size="small" title="处理记录摘要" aria-label="C08 审计摘要">
        {audits.length === 0 ? (
          <Typography.Text type="secondary">暂无 C08 处置记录</Typography.Text>
        ) : (
          <div className="exception-audit-list">
            {audits.map(({ record, metadata }) => (
              <Typography.Text key={record.id}>
                {businessLabel(record.action)} · {businessLabel(metadata.result)} · {record.traceId} · {record.id}
              </Typography.Text>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
