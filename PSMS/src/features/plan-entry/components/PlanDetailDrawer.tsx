import {
  Alert,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Space,
  Steps,
  Tag,
  Typography,
} from 'antd';
import { Link } from 'react-router-dom';

import type { PolicyContext } from '../../../auth';
import { createC03Element } from '../../../app/routeCatalog';
import PermissionGate from '../../../components/PermissionGate';
import type { PublicErrorCode } from '../../../contracts';
import { businessJson, businessLabel } from '../../../presentation/businessCopy';
import type { PlanDetailsViewModel, YardTrackViewModel } from '../types';
import SupplementPlanForm, {
  type SupplementPlanResult,
  type SupplementPlanValues,
} from './SupplementPlanForm';
import ValidationIssueList from './ValidationIssueList';

export const planWorkflowSteps = [
  '计划同步',
  '自动校验',
  '字段补录/冲突处理',
  '计划确认',
  '开放推荐入口',
] as const;

const fieldLabels: Readonly<Record<string, string>> = {
  planBatchNo: '计划批次',
  trainNo: '车次',
  arrivalDepartureTime: '到发时间',
  trackNo: '股道',
  cargoType: '货类',
  status: '计划状态',
};

function localizedSummary(value: string): string {
  try {
    return businessJson(JSON.parse(value) as unknown);
  } catch {
    return value;
  }
}

export type PlanDetailDrawerProps = {
  open: boolean;
  details?: PlanDetailsViewModel;
  policyContext: PolicyContext;
  tracks: readonly YardTrackViewModel[];
  supplementLoading?: boolean;
  confirmLoading?: boolean;
  confirmDisabled?: boolean;
  supplementError?: Readonly<{ errorCode?: PublicErrorCode; message: string }>;
  confirmError?: Readonly<{ errorCode?: PublicErrorCode; message: string }>;
  onClose: () => void;
  onSupplement?: (values: SupplementPlanValues) => Promise<SupplementPlanResult>;
  onConfirm?: () => void | Promise<void>;
};

function gatedConfirmButton(
  policyContext: PolicyContext,
  disabled: boolean,
  loading: boolean,
  onConfirm?: () => void | Promise<void>,
) {
  return createC03Element(
    PermissionGate,
    {
      permission: 'plan:confirm',
      context: { ...policyContext, permission: 'plan:confirm' },
      mode: 'disable',
    },
    <Button
      type="primary"
      loading={loading}
      disabled={disabled}
      onClick={() => void onConfirm?.()}
    >
      确认计划
    </Button>,
  );
}

function ChangeHistory({ details }: { details: PlanDetailsViewModel }) {
  return (
    <section className="plan-detail-section" aria-label="变更历史">
      <Typography.Title level={5}>变更历史</Typography.Title>
      {details.changeHistory.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无命令变更记录" />
      ) : (
        <ul className="plan-detail-list">
          {details.changeHistory.map((entry) => (
            <li key={entry.record.id}>
              <Space wrap>
                <Typography.Text strong>{businessLabel(entry.record.action)}</Typography.Text>
                <Tag>{businessLabel(entry.metadata.result)}</Tag>
                <Typography.Text type="secondary">{entry.metadata.serverTime}</Typography.Text>
                {entry.metadata.errorCode ? (
                  <Tag color="error">{entry.metadata.errorCode}</Tag>
                ) : null}
              </Space>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export default function PlanDetailDrawer({
  open,
  details,
  policyContext,
  tracks,
  supplementLoading = false,
  confirmLoading = false,
  confirmDisabled = false,
  supplementError,
  confirmError,
  onClose,
  onSupplement,
  onConfirm,
}: PlanDetailDrawerProps) {
  const hasIssues = (details?.validationIssues.length ?? 0) > 0;
  const showSupplement = details?.supplement.visible === true;

  return (
    <Drawer
      className="plan-detail-drawer"
      title={details ? `${details.id} 计划详情` : '计划详情'}
      open={open && details !== undefined}
      size={760}
      destroyOnHidden
      onClose={onClose}
    >
      {details ? (
        <div className="plan-detail-content">
          <section className="plan-detail-section" aria-label="计划确认流程">
            <Typography.Title level={5}>确认流程</Typography.Title>
            <Steps
              current={details.workflowStepIndex}
              responsive={false}
              size="small"
              items={planWorkflowSteps.map((title) => ({ title }))}
            />
          </section>

          <section className="plan-detail-section" aria-label="计划摘要">
            <Typography.Title level={5}>计划摘要</Typography.Title>
            <Descriptions
              size="small"
              bordered
              column={2}
              items={details.summary.map(({ label, value }) => ({
                key: label,
                label,
                children: businessLabel(value),
              }))}
            />
          </section>

          <section className="plan-detail-section" aria-label="字段来源">
            <Typography.Title level={5}>字段来源</Typography.Title>
            <ul className="plan-detail-list plan-field-source-list">
              {details.fieldSources.map((item) => (
                <li key={item.field}>
                  <Space wrap>
                    <Typography.Text>{fieldLabels[item.field] ?? item.field}</Typography.Text>
                    <Tag>{businessLabel(item.source)}</Tag>
                    <Typography.Text>{businessLabel(item.value)}</Typography.Text>
                  </Space>
                </li>
              ))}
            </ul>
          </section>

          <section className="plan-detail-section" aria-label="脱敏原始摘要">
            <Typography.Title level={5}>脱敏原始摘要</Typography.Title>
            <pre className="plan-masked-raw-summary">{localizedSummary(details.maskedRawSummary)}</pre>
          </section>

          <ValidationIssueList issues={details.validationIssues} />

          <section className="plan-detail-section" aria-label="重试记录">
            <Typography.Title level={5}>重试记录</Typography.Title>
            {details.retryHistory.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无接口重试记录" />
            ) : (
              <ul className="plan-detail-list">
                {details.retryHistory.map((entry) => (
                  <li key={entry.attempt}>
                    <Space wrap>
                      <Typography.Text>第 {entry.attempt} 次</Typography.Text>
                      <Tag color="error">{businessLabel(entry.status)}</Tag>
                      {entry.errorCode ? <Tag color="error">{entry.errorCode}</Tag> : null}
                    </Space>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <ChangeHistory details={details} />

          {showSupplement
            ? createC03Element(
                PermissionGate,
                {
                  permission: 'plan:adjust',
                  context: { ...policyContext, permission: 'plan:adjust' },
                  mode: 'hide',
                },
                <SupplementPlanForm
                  actorId={details.supplement.actorId}
                  tracks={tracks}
                  reviewerOptions={details.supplement.reviewerOptions}
                  loading={supplementLoading}
                  error={supplementError}
                  onSubmit={onSupplement}
                />,
              )
            : null}

          <Divider />
          {confirmError ? (
            <Alert
              className="plan-confirm-error"
              type="error"
              showIcon
              title={confirmError.message}
              description={confirmError.errorCode}
            />
          ) : null}
          <div className="plan-detail-footer">
            <Typography.Text type={hasIssues ? 'danger' : 'secondary'}>
              {hasIssues ? '校验问题处理完成前不可确认。' : '确认将由命令服务执行并审计。'}
            </Typography.Text>
            {gatedConfirmButton(
              policyContext,
              confirmLoading || confirmDisabled || hasIssues || !onConfirm,
              confirmLoading,
              onConfirm,
            )}
            {details.status === 'CONFIRMED'
              ? createC03Element(
                  PermissionGate,
                  {
                    permission: 'plan:recommend',
                    context: { ...policyContext, permission: 'plan:recommend' },
                    mode: 'hide',
                  },
                  <Link to={`/dispatch/plans/${encodeURIComponent(details.id)}/recommendation`}>
                    开放推荐入口
                  </Link>,
                )
              : null}
          </div>
        </div>
      ) : null}
    </Drawer>
  );
}
