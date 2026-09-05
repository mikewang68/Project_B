import { Button, Card, Empty, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { DispatchWorkOrderView } from '../types';

export type WorkOrderQueueProps = {
  orders: readonly DispatchWorkOrderView[];
  selectedWorkOrderId?: string;
  onSelect: (workOrderId: string) => void;
};

function compactTime(value: string): string {
  const match = value.match(/^\d{4}-(\d{2})-(\d{2})T(\d{2}):(\d{2})/);
  return match ? `${match[1]}-${match[2]} ${match[3]}:${match[4]}` : value;
}

export default function WorkOrderQueue({
  orders,
  selectedWorkOrderId,
  onSelect,
}: WorkOrderQueueProps) {
  return (
    <section aria-label="C06 工单队列" className="dispatch-board-section">
      <Card title="C06 工单队列" extra={<Tag>{orders.length} 项</Tag>}>
        {orders.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} /> : (
          <div className="dispatch-order-list">
            {orders.map((item) => {
              const selected = item.workOrder.id === selectedWorkOrderId;
              return (
                <Button
                  key={item.workOrder.id}
                  type={selected ? 'primary' : 'default'}
                  className="dispatch-order-button"
                  aria-pressed={selected}
                  onClick={() => onSelect(item.workOrder.id)}
                >
                  <span className="dispatch-order-sequence">{item.workNode.sequence}</span>
                  <span className="dispatch-order-copy">
                    <Typography.Text strong>{item.workOrder.id}</Typography.Text>
                    <Typography.Text>{item.workOrder.title}</Typography.Text>
                    <Space size={5} wrap>
                      <Tag color="blue" title={businessLabel(item.stage)}>{businessLabel(item.stage)}</Tag>
                      <Tag title={businessLabel(item.requiredResourceType)}>
                        {businessLabel(item.requiredResourceType)}
                      </Tag>
                      <Tag>{item.workOrder.resourceId || '未绑定'}</Tag>
                      <Tag>v{item.workOrder.version}</Tag>
                      <Tag color={item.progressState === 'READY_QUEUE' ? 'default' : 'processing'}>
                        <span title={businessLabel(item.progressState)}>{businessLabel(item.progressState)}</span>
                      </Tag>
                    </Space>
                    <Typography.Text type="secondary">
                      {compactTime(item.workNode.plannedStartTime)} →{' '}
                      {compactTime(item.workNode.plannedFinishTime)}
                    </Typography.Text>
                  </span>
                </Button>
              );
            })}
          </div>
        )}
      </Card>
    </section>
  );
}
