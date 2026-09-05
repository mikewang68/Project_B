import { Button, Card, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';

import { businessLabel } from '../../../presentation/businessCopy';
import type { DispatchWorkOrderView } from '../types';

export type ExecutionFeedbackPanelProps = {
  item?: DispatchWorkOrderView;
  loading: boolean;
  interlocked: boolean;
  canSend: boolean;
  canPause: boolean;
  onDispatch: () => void;
  onAcknowledge: () => void;
  onStart: () => void;
  onPause: () => void;
  onComplete: () => void;
};

export default function ExecutionFeedbackPanel({
  item,
  loading,
  interlocked,
  canSend,
  canPause,
  onDispatch,
  onAcknowledge,
  onStart,
  onPause,
  onComplete,
}: ExecutionFeedbackPanelProps) {
  const status = item?.workOrder.status;
  const writesBlocked = loading || interlocked || !item;
  return (
    <section aria-label="派工与执行反馈" className="dispatch-board-section">
      <Card
        title="派工与执行反馈"
        extra={status && status !== 'READY' && status !== 'DISPATCHED'
          ? <Tag color="processing" title={businessLabel(status)}>{businessLabel(status)}</Tag>
          : undefined}
      >
        <Space className="dispatch-action-list" size={[8, 8]} wrap>
          <Button
            type="primary"
            disabled={writesBlocked || !canSend || status !== 'READY'
              || !item?.workOrder.resourceId || !item.workOrder.teamId}
            onClick={onDispatch}
          >
            下发工单
          </Button>
          <Button
            aria-label="接单"
            disabled={writesBlocked || !canSend || status !== 'DISPATCHED'}
            onClick={onAcknowledge}
          >
            接单
          </Button>
          <Button
            aria-label={status === 'PAUSED' ? '继续' : '开始'}
            disabled={writesBlocked || !canSend
              || (status !== 'ACKNOWLEDGED' && status !== 'PAUSED')}
            onClick={onStart}
          >
            {status === 'PAUSED' ? '继续' : '开始'}
          </Button>
          <Button
            aria-label="暂停"
            disabled={writesBlocked || !canPause || status !== 'IN_PROGRESS'}
            onClick={onPause}
          >
            暂停
          </Button>
          <Button
            aria-label="完成"
            disabled={writesBlocked || !canSend || status !== 'IN_PROGRESS'}
            onClick={onComplete}
          >
            完成
          </Button>
          {item ? <Link to={item.exceptionEntryUrl}>异常入口</Link> : null}
        </Space>
        <Typography.Paragraph className="dispatch-local-disclosure" type="secondary">
          本地演示执行反馈，不代表现场系统回执
        </Typography.Paragraph>
      </Card>
    </section>
  );
}
