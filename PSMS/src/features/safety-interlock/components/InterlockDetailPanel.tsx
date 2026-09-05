import { Alert, Card, Descriptions, Empty, Space, Tag, Typography } from 'antd';

import type { CommandAuditEntry } from '../../../governance/audit';
import { businessLabel } from '../../../presentation/businessCopy';
import type { InterlockLedgerItem } from '../types';
import InterlockSnapshotPanel from './InterlockSnapshotPanel';

export default function InterlockDetailPanel({
  item,
  audits,
}: {
  item?: InterlockLedgerItem;
  audits: readonly CommandAuditEntry[];
}) {
  return (
    <div className="interlock-detail-stack">
      <Card className="interlock-section" title="联锁详情" aria-label="联锁详情">
        {!item ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择联锁" /> : (
          <Space orientation="vertical" className="interlock-full-width" size={12}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="联锁编号">{item.interlock.interlockNo}</Descriptions.Item>
              <Descriptions.Item label="状态">{businessLabel(item.interlock.status)}</Descriptions.Item>
              <Descriptions.Item label="风险类型">{businessLabel(item.interlock.riskType)}</Descriptions.Item>
              <Descriptions.Item label="动作级别">{businessLabel(item.interlock.actionLevel)}</Descriptions.Item>
              <Descriptions.Item label="回执状态">{businessLabel(item.interlock.receiptStatus)}</Descriptions.Item>
              <Descriptions.Item label="版本">v{item.interlock.version}</Descriptions.Item>
            </Descriptions>
            <div className="interlock-state-flow" aria-label="联锁状态流">
              {item.stateFlow.map(({ status, current }) => (
                <Tag key={status} color={current ? 'processing' : 'default'} title={businessLabel(status)}>
                  {businessLabel(status)}
                </Tag>
              ))}
            </div>
            <div className="interlock-summary-block">
              <Typography.Text strong>复位申请</Typography.Text>
              <Space wrap>
                {item.resetRequestSummary.length > 0
                  ? item.resetRequestSummary.map((entry) => <Tag key={entry}>{entry}</Tag>)
                  : <Typography.Text type="secondary">暂无复位申请</Typography.Text>}
              </Space>
            </div>
            <div className="interlock-summary-block">
              <Typography.Text strong>审批链</Typography.Text>
              <Space wrap>
                {item.approvalSummary.length > 0
                  ? item.approvalSummary.map((entry) => <Tag key={entry}>{entry}</Tag>)
                  : <Typography.Text type="secondary">暂无审批记录</Typography.Text>}
              </Space>
            </div>
            {item.forceStopWarning ? (
              <Alert
                type="error"
                showIcon
                title={item.forceStopWarning
                  .replace('FORCE_STOP ', '强制停机')
                  .replace('Demo ', '演示')}
              />
            ) : null}
            {item.receiptFailed ? (
              <Alert type="warning" showIcon title="动作回执失败，需人工核验。" />
            ) : null}
          </Space>
        )}
      </Card>
      <InterlockSnapshotPanel item={item} />
      <Card className="interlock-section" size="small" title="处理记录摘要" aria-label="C09 审计摘要">
        {audits.length === 0 ? (
          <Typography.Text type="secondary">暂无 C09 联锁记录</Typography.Text>
        ) : (
          <div className="interlock-audit-list">
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
