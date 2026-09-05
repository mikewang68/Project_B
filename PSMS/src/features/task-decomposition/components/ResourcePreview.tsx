import { Card, Empty, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { ResourcePreviewItem, TaskTreeNodeView } from '../types';

export type ResourcePreviewProps = {
  items: readonly ResourcePreviewItem[];
  nodes: readonly TaskTreeNodeView[];
  confirmed: boolean;
};

export default function ResourcePreview({ items, nodes, confirmed }: ResourcePreviewProps) {
  const titleByNodeId = new Map(nodes.map(({ nodeId, title }) => [nodeId, title]));
  return (
    <section aria-label="资源预览" className="task-decomposition-section">
      <Card title="资源预览" extra={<Tag color="gold">UI-005 前置预览</Tag>}>
        {items.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="生成任务后展示资源类型候选" />
        ) : (
          <div className="task-resource-list">
            {items.map((item) => (
              <article key={item.nodeId} className="task-resource-row">
                <Space size={6} wrap>
                  <Typography.Text strong>{titleByNodeId.get(item.nodeId) ?? item.stage}</Typography.Text>
                  <Tag color="blue" title={businessLabel(item.requiredResourceType)}>
                    {businessLabel(item.requiredResourceType)}
                  </Tag>
                  <Tag title={businessLabel(item.stage)}>{businessLabel(item.stage)}</Tag>
                </Space>
                <Typography.Text type="secondary">
                  {confirmed ? '待 UI-005 分配' : '仅预览，不分配资源实例'}
                </Typography.Text>
                <Space size={6} wrap>
                  {item.candidates.length === 0 ? (
                    <Tag>{businessLabel('AREA-A')}无实例候选</Tag>
                  ) : item.candidates.map((candidate) => (
                    <Tag
                      key={candidate.id}
                      color={candidate.status === 'AVAILABLE' ? 'success' : 'default'}
                      title={businessLabel(candidate.status)}
                    >
                      {candidate.id} · {businessLabel(candidate.status)}
                    </Tag>
                  ))}
                </Space>
              </article>
            ))}
          </div>
        )}
      </Card>
    </section>
  );
}
