import { Alert, Button, Drawer, Form, Input, List, Space, Tag, Typography } from 'antd';

import type { PublicErrorCode } from '../../../contracts';
import { businessLabel } from '../../../presentation/businessCopy';
import type { TaskTreeNodeView } from '../types';

export type TaskEditValues = {
  mode: 'SPLIT' | 'MERGE';
  targetNodeId?: string;
  reason: string;
};

export type TaskEditDrawerProps = {
  open: boolean;
  mode: TaskEditValues['mode'];
  targetNodeId?: string;
  selectedNodes: readonly TaskTreeNodeView[];
  reason: string;
  invalidReason?: string;
  loading: boolean;
  error?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
  onReasonChange: (reason: string) => void;
  onClose: () => void;
  onSubmit: (values: TaskEditValues) => void | Promise<void>;
  onRetry?: () => void;
};

export default function TaskEditDrawer({
  open,
  mode,
  targetNodeId,
  selectedNodes,
  reason,
  invalidReason,
  loading,
  error,
  onReasonChange,
  onClose,
  onSubmit,
  onRetry,
}: TaskEditDrawerProps) {
  const disabled = loading || Boolean(invalidReason) || reason.trim().length === 0;
  const submit = (): void => {
    if (disabled) return;
    void onSubmit({
      mode,
      ...(targetNodeId ? { targetNodeId } : {}),
      reason: reason.trim(),
    });
  };
  return (
    <Drawer
      title={mode === 'SPLIT' ? '拆分任务节点' : '合并任务节点'}
      open={open}
      size="min(620px, calc(100vw - 24px))"
      onClose={onClose}
      maskClosable={!loading}
    >
      <Space orientation="vertical" size={16} className="task-edit-drawer-content">
        <Alert
          type="info"
          showIcon
          title={mode === 'SPLIT'
            ? `目标节点：${targetNodeId ?? '未选择'}`
            : `受影响节点：${selectedNodes.map(({ nodeId }) => nodeId).join('、') || '未选择'}`}
          description="提交前将再次校验版本；成功后只替换 C06 所有权范围内的草稿。"
        />
        <List
          size="small"
          dataSource={[...selectedNodes]}
          renderItem={(node) => (
            <List.Item key={node.nodeId}>
              <Space orientation="vertical" size={2}>
                <Space size={6} wrap>
                  <Typography.Text strong>{node.title}</Typography.Text>
                  <Tag title={businessLabel(node.taskType)}>{businessLabel(node.taskType)}</Tag>
                  <Tag title={businessLabel(node.status)}>{businessLabel(node.status)}</Tag>
                </Space>
                <Typography.Text type="secondary">
                  {node.nodeId} · 工单 v{node.workOrderVersion} / 节点 v{node.nodeVersion}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
        {invalidReason ? <Alert type="warning" showIcon title={invalidReason} /> : null}
        {error ? (
          <Alert
            type="error"
            showIcon
            title={`${error.errorCode}: ${error.message}`}
            action={onRetry ? <Button onClick={onRetry}>重试原动作</Button> : undefined}
          />
        ) : null}
        <Form layout="vertical" onFinish={submit}>
          <Form.Item label="调整原因" required>
            <Input.TextArea
              aria-label="调整原因"
              rows={4}
              value={reason}
              onChange={({ target: { value } }: { target: { value: string } }) => onReasonChange(value)}
              placeholder="填写拆分或合并的业务原因"
            />
          </Form.Item>
          <Space>
            <Button onClick={onClose} disabled={loading}>取消</Button>
            <Button type="primary" htmlType="submit" loading={loading} disabled={disabled}>
              提交编辑
            </Button>
          </Space>
        </Form>
      </Space>
    </Drawer>
  );
}
