import { Alert, Button, Form, Input, Select, Space, Typography } from 'antd';
import { useState } from 'react';

import type { PublicErrorCode } from '../../../contracts';
import type { PlanReviewerOptionViewModel, YardTrackViewModel } from '../types';

export type SupplementPlanValues = {
  trackNo: string;
  reason: string;
  effectiveUntil: string;
  reviewerId: string;
};

export type SupplementPlanResult =
  | Readonly<{ ok: true }>
  | Readonly<{ ok: false; errorCode?: PublicErrorCode; message: string }>;

export type SupplementPlanFormProps = {
  actorId: string;
  tracks: readonly YardTrackViewModel[];
  reviewerOptions: readonly PlanReviewerOptionViewModel[];
  loading?: boolean;
  error?: Readonly<{ errorCode?: PublicErrorCode; message: string }>;
  onSubmit?: (values: SupplementPlanValues) => Promise<SupplementPlanResult>;
};

export const SEPARATION_OF_DUTIES_MESSAGE =
  'High-risk applicant and approver must differ.';

export default function SupplementPlanForm({
  actorId,
  tracks,
  reviewerOptions,
  loading = false,
  error,
  onSubmit,
}: SupplementPlanFormProps) {
  const [form] = Form.useForm<SupplementPlanValues>();
  const [localError, setLocalError] = useState<SupplementPlanFormProps['error']>();
  const submit = async (values: SupplementPlanValues): Promise<void> => {
    if (values.reviewerId === actorId) {
      form.setFields([{ name: 'reviewerId', errors: [SEPARATION_OF_DUTIES_MESSAGE] }]);
      return;
    }
    if (!onSubmit) return;
    setLocalError(undefined);
    try {
      const result = await onSubmit(values);
      if (!result.ok) setLocalError({ errorCode: result.errorCode, message: result.message });
    } catch {
      setLocalError({ message: '网络请求失败，已保留当前输入，请稍后重试。' });
    }
  };

  const visibleError = localError ?? error;

  return (
    <section className="plan-supplement-form" aria-label="计划字段补录">
      <Typography.Title level={5}>字段补录</Typography.Title>
      {visibleError ? (
        <Alert
          type="error"
          showIcon
          title={visibleError.message}
          description={visibleError.errorCode}
        />
      ) : null}
      <Form<SupplementPlanValues>
        form={form}
        layout="vertical"
        requiredMark
        onFinish={(values: SupplementPlanValues) => void submit(values)}
      >
        <div className="plan-supplement-grid">
          <Form.Item
            name="trackNo"
            label="股道"
            rules={[{ required: true, message: '请选择股道' }]}
          >
            <Select
              aria-label="补录股道"
              options={tracks.map(({ trackNo }) => ({ label: trackNo, value: trackNo }))}
              placeholder="选择可见股道"
            />
          </Form.Item>
          <Form.Item
            name="reviewerId"
            label="异人复核员"
            rules={[{ required: true, message: '请选择异人复核员' }]}
          >
            <Select
              aria-label="异人复核员"
              options={reviewerOptions}
              placeholder="选择另一名在岗调度员"
            />
          </Form.Item>
          <Form.Item
            name="effectiveUntil"
            label="有效期至"
            rules={[{ required: true, message: '请输入有效期' }]}
          >
            <Input aria-label="有效期至" type="datetime-local" />
          </Form.Item>
          <Form.Item
            className="plan-supplement-reason"
            name="reason"
            label="补录原因"
            rules={[{ required: true, message: '请输入补录原因' }]}
          >
            <Input.TextArea aria-label="补录原因" rows={2} maxLength={200} showCount />
          </Form.Item>
        </div>
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            loading={loading}
            disabled={loading || !onSubmit}
          >
            提交补录
          </Button>
          <Typography.Text type="secondary">失败时保留以上全部输入</Typography.Text>
        </Space>
      </Form>
    </section>
  );
}
