import { Button, Card, Input, Space, Typography } from 'antd';

import type { ExceptionLedgerItem } from '../types';

export default function ExceptionActionPanel({
  item,
  loading,
  interlocked,
  reason,
  owner,
  evidence,
  onReason,
  onOwner,
  onEvidence,
  onAck,
  onAssign,
  onHandle,
  onReview,
  onClose,
  onReopen,
}: {
  item?: ExceptionLedgerItem;
  loading: boolean;
  interlocked: boolean;
  reason: string;
  owner: string;
  evidence: readonly string[];
  onReason: (value: string) => void;
  onOwner: (value: string) => void;
  onEvidence: (value: readonly string[]) => void;
  onAck: () => void;
  onAssign: () => void;
  onHandle: () => void;
  onReview: () => void;
  onClose: () => void;
  onReopen: () => void;
}) {
  const actions = item?.availableActions;
  const reasonReady = reason.trim().length > 0;
  const disabled = (allowed: boolean | undefined) => loading || interlocked || !allowed;
  return (
    <Card className="exception-section" title="异常处置" aria-label="异常处置面板">
      <Space orientation="vertical" className="exception-full-width" size={12}>
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
          <Typography.Text>处理负责人</Typography.Text>
          <Input
            aria-label="处理负责人"
            value={owner}
            onChange={(event: { target: { value: string } }) => onOwner(event.target.value)}
          />
        </label>
        <label>
          <Typography.Text>证据条目</Typography.Text>
          <Input.TextArea
            aria-label="证据条目"
            rows={3}
            value={evidence.join('\n')}
            onChange={(event: { target: { value: string } }) => onEvidence(
              event.target.value.split(/[\n,]/).map((value: string) => value.trim()).filter(Boolean),
            )}
          />
        </label>
        <div className="exception-action-grid">
          <Button disabled={disabled(actions?.ack) || !reasonReady} onClick={onAck}>确认异常</Button>
          <Button disabled={disabled(actions?.assign) || !reasonReady || !owner.trim()} onClick={onAssign}>分派处理</Button>
          <Button disabled={disabled(actions?.handle) || !reasonReady || evidence.length === 0} onClick={onHandle}>提交处理</Button>
          <Button disabled={disabled(actions?.review) || !reasonReady} onClick={onReview}>复核退回</Button>
          <Button type="primary" disabled={disabled(actions?.close) || !reasonReady} onClick={onClose}>关闭异常</Button>
          <Button danger disabled={disabled(actions?.reopen) || !reasonReady} onClick={onReopen}>重新打开</Button>
        </div>
        {interlocked ? (
          <Typography.Text type="danger">TOS-IL-001：请前往 UI-009 处理安全联锁。</Typography.Text>
        ) : null}
      </Space>
    </Card>
  );
}
