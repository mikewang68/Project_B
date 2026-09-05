import { Button, Card, Input, Space, Typography } from 'antd';

import type { InterlockLedgerItem } from '../types';

export default function InterlockActionPanel({
  item,
  loading,
  reason,
  approvalUserId,
  approvalUserValid,
  resetRequestText,
  resetRequestReady,
  onReason,
  onApprovalUser,
  onResetRequest,
  onTrigger,
  onReceipt,
  onRequestReset,
  onApprove,
  onRestore,
  onRequestOverride,
}: {
  item?: InterlockLedgerItem;
  loading: boolean;
  reason: string;
  approvalUserId: string;
  approvalUserValid: boolean;
  resetRequestText: string;
  resetRequestReady: boolean;
  onReason: (value: string) => void;
  onApprovalUser: (value: string) => void;
  onResetRequest: (value: string) => void;
  onTrigger: () => void;
  onReceipt: () => void;
  onRequestReset: () => void;
  onApprove: () => void;
  onRestore: () => void;
  onRequestOverride: () => void;
}) {
  const actions = item?.availableActions;
  const reasonReady = reason.trim().length > 0;
  const disabled = (allowed: boolean | undefined) => loading || !allowed || !reasonReady;
  const resetApproval = item?.interlock.status === 'RESET_REQUESTED';
  const overrideApproval = item?.interlock.status === 'OVERRIDE_PENDING';

  return (
    <Card className="interlock-section" title="联锁处置" aria-label="联锁处置面板">
      <Space orientation="vertical" className="interlock-full-width" size={12}>
        <label>
          <Typography.Text>处置原因</Typography.Text>
          <Input.TextArea
            aria-label="处置原因"
            rows={3}
            value={reason}
            onChange={(event: { target: { value: string } }) => onReason(event.target.value)}
          />
        </label>
        <label>
          <Typography.Text>审批人</Typography.Text>
          <Input
            aria-label="审批人"
            placeholder="输入具备联锁审批权限的用户 ID"
            value={approvalUserId}
            onChange={(event: { target: { value: string } }) => onApprovalUser(event.target.value)}
          />
        </label>
        <label>
          <Typography.Text>复位申请</Typography.Text>
          <Input.TextArea
            aria-label="复位申请"
            rows={3}
            placeholder="记录现场清场与传感器复核；不触发真实设备复位"
            value={resetRequestText}
            onChange={(event: { target: { value: string } }) => onResetRequest(event.target.value)}
          />
        </label>
        <div className="interlock-action-grid">
          <Button disabled={disabled(actions?.trigger)} onClick={onTrigger}>触发联锁</Button>
          <Button disabled={disabled(actions?.receipt)} onClick={onReceipt}>确认回执</Button>
          <Button
            disabled={disabled(actions?.requestReset) || !resetRequestReady}
            onClick={onRequestReset}
          >
            申请复位
          </Button>
          <Button
            disabled={disabled(actions?.approve) || !resetApproval || !approvalUserValid}
            onClick={onApprove}
          >
            审批复位
          </Button>
          <Button type="primary" disabled={disabled(actions?.restore)} onClick={onRestore}>
            登记恢复
          </Button>
          <Button danger disabled={disabled(actions?.requestOverride)} onClick={onRequestOverride}>
            申请旁路
          </Button>
          <Button
            danger
            disabled={disabled(actions?.approve) || !overrideApproval || !approvalUserValid}
            onClick={onApprove}
          >
            审批旁路
          </Button>
        </div>
        <Typography.Text type="warning">
          登记恢复仅表示演示恢复记录，不代表真实设备已复位；本页不执行可编程逻辑控制器（PLC）或设备控制系统（ECS）控制。
        </Typography.Text>
      </Space>
    </Card>
  );
}
