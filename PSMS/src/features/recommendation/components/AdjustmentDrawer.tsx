import { useEffect } from 'react';
import { Alert, Button, Drawer, Form, Input, List, Select, Space, Tag, Typography } from 'antd';

import type { PublicErrorCode, UserRole, WorkOrder } from '../../../contracts';
import { businessLabel } from '../../../presentation/businessCopy';
import type { RecommendationCandidate } from '../schemas';

export type RecommendationAdjustmentValues = {
  candidateId: string;
  reason: string;
  reviewerId?: string;
};

export type AdjustmentDrawerProps = {
  open: boolean;
  candidate?: RecommendationCandidate;
  affectedWorkOrders: readonly Readonly<WorkOrder>[];
  reviewers: readonly Readonly<UserRole>[];
  actorId: string;
  loading: boolean;
  error?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
  onClose: () => void;
  onSubmit: (values: RecommendationAdjustmentValues) => void | Promise<void>;
  onRecalculate?: () => void;
};

const highRiskStatuses = new Set(['DISPATCHED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PAUSED']);

export default function AdjustmentDrawer({
  open,
  candidate,
  affectedWorkOrders,
  reviewers,
  actorId,
  loading,
  error,
  onClose,
  onSubmit,
  onRecalculate,
}: AdjustmentDrawerProps) {
  const [form] = Form.useForm<RecommendationAdjustmentValues>();
  const alternative = (candidate?.rank ?? 1) > 1;
  const highRisk =
    alternative && affectedWorkOrders.some(({ status }) => highRiskStatuses.has(status));
  const candidateId = candidate?.candidateId;

  useEffect(() => {
    if (!open || !candidateId) return;
    form.setFieldsValue({ candidateId, reason: '' });
  }, [candidateId, form, open]);

  return (
    <Drawer
      title={alternative ? '调整并确认接车推荐' : '确认接车推荐'}
      open={open}
      size="min(620px, calc(100vw - 24px))"
      onClose={onClose}
      maskClosable={!loading}
    >
      <Space orientation="vertical" size={16} className="recommendation-drawer-content">
        {candidate ? (
          <Alert
            type={alternative ? 'warning' : 'success'}
            showIcon
            title={`${candidate.trackNo} · 第 ${candidate.rank} 名 · ${candidate.score} 分`}
            description={`${candidate.windowStart} → ${candidate.windowEnd}`}
          />
        ) : null}
        {alternative ? (
          <section aria-label="受影响工单">
            <Typography.Title level={5}>受影响工单</Typography.Title>
            <List
              size="small"
              dataSource={[...affectedWorkOrders]}
              renderItem={(workOrder) => (
                <List.Item key={workOrder.id}>
                  <Space size={8} wrap>
                    <Typography.Text>{workOrder.workOrderNo}</Typography.Text>
                    <Tag color={highRiskStatuses.has(workOrder.status) ? 'warning' : 'default'}>
                      {businessLabel(workOrder.status)}
                    </Tag>
                  </Space>
                </List.Item>
              )}
            />
          </section>
        ) : null}
        {error ? (
          <Alert
            type="error"
            showIcon
            title={`${error.errorCode}: ${error.message}`}
            action={
              error.errorCode === 'DEMO-VERSION-001' && onRecalculate ? (
                <Button onClick={onRecalculate}>重新计算</Button>
              ) : undefined
            }
          />
        ) : null}
        <Form<RecommendationAdjustmentValues>
          form={form}
          layout="vertical"
          preserve
          initialValues={{ candidateId, reason: '' }}
          onFinish={(values: RecommendationAdjustmentValues) => void onSubmit(values)}
        >
          <Form.Item name="candidateId" hidden rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          {alternative ? (
            <Form.Item
              name="reason"
              label="调整原因"
              rules={[{ required: true, whitespace: true, message: '请输入调整原因' }]}
            >
              <Input.TextArea rows={4} placeholder="说明偏离系统首选方案的业务原因" />
            </Form.Item>
          ) : null}
          {highRisk ? (
            <Form.Item
              name="reviewerId"
              label="异人复核员"
              rules={[{ required: true, message: '请选择异人复核员' }]}
            >
              <Select
                aria-label="异人复核员"
                options={reviewers
                  .filter(({ status }) => status === 'ACTIVE')
                  .map(({ id, roleCode }) => ({
                    value: id,
                    label: `${id} · ${businessLabel(roleCode)}${id === actorId ? '（本人，不能复核）' : ''}`,
                  }))}
              />
            </Form.Item>
          ) : null}
          <Space>
            <Button onClick={onClose} disabled={loading}>取消</Button>
            <Button type="primary" htmlType="submit" loading={loading} disabled={!candidate}>
              提交确认
            </Button>
          </Space>
        </Form>
      </Space>
    </Drawer>
  );
}
