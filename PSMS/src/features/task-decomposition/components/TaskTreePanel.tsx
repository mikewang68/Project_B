import { Button, Card, Checkbox, Empty, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { TaskTreeNodeView } from '../types';

export type TaskTreePanelProps = {
  nodes: readonly TaskTreeNodeView[];
  selectedNodeIds: readonly string[];
  locked: boolean;
  loading: boolean;
  onToggleNode: (nodeId: string) => void;
  onGenerate: () => void;
  onOpenSplit: () => void;
  onOpenMerge: () => void;
  onRegenerate: () => void;
  onConfirm: () => void;
  visibleNodeCount?: number;
};

export default function TaskTreePanel({
  nodes,
  selectedNodeIds,
  locked,
  loading,
  onToggleNode,
  onGenerate,
  onOpenSplit,
  onOpenMerge,
  onRegenerate,
  onConfirm,
  visibleNodeCount,
}: TaskTreePanelProps) {
  const visibleNodes = visibleNodeCount === undefined ? nodes : nodes.slice(0, visibleNodeCount);
  return (
    <section aria-label="任务树" className="task-decomposition-section task-tree-section">
      <Card
        title="任务树"
        extra={
          <Space size={8} wrap>
            <Button
              type="primary"
              loading={loading && nodes.length === 0}
              disabled={nodes.length > 0 || locked}
              onClick={onGenerate}
            >
              自动拆解
            </Button>
            <Button
              disabled={locked || selectedNodeIds.length !== 1}
              onClick={onOpenSplit}
            >
              拆分所选节点
            </Button>
            <Button
              disabled={locked || selectedNodeIds.length !== 2}
              onClick={onOpenMerge}
            >
              合并所选节点
            </Button>
            <Button danger disabled={locked || nodes.length === 0} onClick={onRegenerate}>
              重新生成系统建议
            </Button>
            <Button type="primary" disabled={locked || nodes.length === 0} onClick={onConfirm}>
              确认工单草稿
            </Button>
          </Space>
        }
      >
        {visibleNodes.length === 0 ? (
          <Empty description={nodes.length === 0 ? '尚未生成任务草稿' : '正在生成任务依赖关系'} />
        ) : (
          <div className="task-tree-list">
            {visibleNodes.map((node, index) => (
              <article
                key={node.nodeId}
                className={`task-tree-row${visibleNodeCount !== undefined && index === visibleNodes.length - 1 ? ' task-tree-row-demo-current' : ''}`}
              >
                <div className="task-tree-sequence">{String(index + 1).padStart(2, '0')}</div>
                <Checkbox
                  aria-label={`选择节点 ${node.title}`}
                  checked={selectedNodeIds.includes(node.nodeId)}
                  disabled={locked}
                  onChange={() => onToggleNode(node.nodeId)}
                />
                <div className="task-tree-content">
                  <Space size={8} wrap>
                    <Typography.Text strong>{node.title}</Typography.Text>
                    <Tag color="blue" title={businessLabel(node.stage)}>{businessLabel(node.stage)}</Tag>
                    <Tag title={businessLabel(node.taskType)}>{businessLabel(node.taskType)}</Tag>
                    <Tag color={node.status === 'READY' ? 'success' : 'default'} title={businessLabel(node.status)}>
                      {businessLabel(node.status)}
                    </Tag>
                    <Tag title={businessLabel(node.nodeStatus)}>{businessLabel(node.nodeStatus)}</Tag>
                  </Space>
                  <Typography.Text code>{node.workOrderId}</Typography.Text>
                  <div className="task-tree-meta">
                    <Typography.Text type="secondary">
                      上游节点：{node.dependencyIds[0] ?? '无'}
                    </Typography.Text>
                    <Typography.Text type="secondary">
                      规则：{node.ruleVersion}
                    </Typography.Text>
                    <Typography.Text type="secondary">
                      版本：工单 v{node.workOrderVersion} / 节点 v{node.nodeVersion}
                    </Typography.Text>
                  </div>
                </div>
              </article>
            ))}
          </div>
        )}
      </Card>
    </section>
  );
}
