import { Button, Card, Empty, Radio, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { DispatchWorkOrderView } from '../types';

export type ResourceAssignmentPanelProps = {
  item?: DispatchWorkOrderView;
  selectedResourceId?: string;
  loading: boolean;
  bindDisabled: boolean;
  onSelect: (resourceId: string) => void;
  onBind: () => void;
};

export default function ResourceAssignmentPanel({
  item,
  selectedResourceId,
  loading,
  bindDisabled,
  onSelect,
  onBind,
}: ResourceAssignmentPanelProps) {
  return (
    <section aria-label="一作业区资源池" className="dispatch-board-section dispatch-resource-section">
      <Card
        title="一作业区资源池"
        extra={item ? (
          <Tag color="blue" title={businessLabel(item.requiredResourceType)}>
            {businessLabel(item.requiredResourceType)}
          </Tag>
        ) : undefined}
      >
        {!item || item.resources.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前工单无匹配资源" />
        ) : (
          <Radio.Group
            className="dispatch-resource-list"
            value={selectedResourceId}
            onChange={(event: { target: { value: unknown } }) =>
              onSelect(String(event.target.value))}
          >
            {item.resources.map((resource) => (
              <Radio
                key={resource.id}
                value={resource.id}
                disabled={!resource.assignable}
                className="dispatch-resource-option"
              >
                <span className="dispatch-resource-copy">
                  <Typography.Text strong>{resource.id}</Typography.Text>
                  <Space size={5} wrap>
                    <Tag title={businessLabel(resource.workArea)}>{businessLabel(resource.workArea)}</Tag>
                    <Tag title={businessLabel(resource.resourceType)}>{businessLabel(resource.resourceType)}</Tag>
                    <Tag
                      color={resource.assignable ? 'success' : 'default'}
                      title={businessLabel(resource.status)}
                    >
                      {businessLabel(resource.status)}
                    </Tag>
                  </Space>
                  <Typography.Text type="secondary">
                    {resource.location.replace('YARD-', '场区')} · {resource.assignable
                      ? '可分配'
                      : resource.unavailableReason ?? '不可分配'}
                  </Typography.Text>
                </span>
              </Radio>
            ))}
          </Radio.Group>
        )}
        <Button
          type="primary"
          block
          loading={loading}
          disabled={bindDisabled}
          onClick={onBind}
        >
          绑定资源
        </Button>
      </Card>
    </section>
  );
}
