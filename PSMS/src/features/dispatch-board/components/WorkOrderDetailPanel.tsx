import { Card, Descriptions, Empty, Space, Tag, Typography } from 'antd';

import type { CommandAuditEntry } from '../../../governance/audit';
import { businessLabel } from '../../../presentation/businessCopy';
import type { DispatchWorkOrderView } from '../types';

export type WorkOrderDetailPanelProps = {
  item?: DispatchWorkOrderView;
  audits: readonly CommandAuditEntry[];
};

function relationLabel(ids: readonly string[]): string {
  return ids.length > 0 ? ids.join('、') : '无';
}

export default function WorkOrderDetailPanel({ item, audits }: WorkOrderDetailPanelProps) {
  return (
    <section aria-label="工单详情" className="dispatch-board-section">
      <Card title="工单详情" extra={item ? <Tag>节点 v{item.workNode.version}</Tag> : undefined}>
        {!item ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择工单" /> : (
          <div className="dispatch-detail-content">
            <Typography.Title level={5}>{item.workOrder.title}</Typography.Title>
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="工单编号">{item.workOrder.workOrderNo}</Descriptions.Item>
              <Descriptions.Item label="工单状态">
                工单状态：<span title={businessLabel(item.workOrder.status)}>{businessLabel(item.workOrder.status)}</span>
              </Descriptions.Item>
              <Descriptions.Item label="节点状态">
                节点状态：<span title={businessLabel(item.workNode.status)}>{businessLabel(item.workNode.status)}</span>
              </Descriptions.Item>
              <Descriptions.Item label="上游">
                {item.upstreamWorkOrderId ?? '无'}
              </Descriptions.Item>
              <Descriptions.Item label="下游">
                {relationLabel(item.downstreamWorkOrderIds)}
              </Descriptions.Item>
              <Descriptions.Item label="依赖链">
                {relationLabel(item.dependencyIds)}
              </Descriptions.Item>
              <Descriptions.Item label="实际开始">
                {item.workNode.actualStartTime || '尚未开始'}
              </Descriptions.Item>
              <Descriptions.Item label="实际完成">
                {item.workNode.actualFinishTime || '尚未完成'}
              </Descriptions.Item>
            </Descriptions>
            <div className="dispatch-audit-list">
          <Typography.Text strong>命令反馈 / 追踪号 / 审计号</Typography.Text>
              {audits.length === 0 ? (
                <Typography.Text type="secondary">暂无 C07 命令记录</Typography.Text>
              ) : audits.slice(-4).map(({ record, metadata }) => (
                <Space key={record.id} size={5} wrap>
              <Tag color={metadata.result === 'SUCCESS' ? 'success' : 'error'}>{businessLabel(record.action)}</Tag>
                  <Typography.Text code>{record.traceId}</Typography.Text>
                  <Typography.Text code>{record.id}</Typography.Text>
                </Space>
              ))}
            </div>
          </div>
        )}
      </Card>
    </section>
  );
}
