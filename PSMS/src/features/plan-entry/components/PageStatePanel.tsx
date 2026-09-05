import { Alert, Button, Card, Empty, Result, Skeleton, Space, Typography } from 'antd';

import type { PublicErrorCode } from '../../../contracts';

export type PageState =
  | 'loading'
  | 'empty'
  | 'business-error'
  | 'network-error'
  | 'not-found';

export type PageStatePanelProps = {
  state: PageState;
  errorCode?: PublicErrorCode;
  recoveryLabel?: string;
  onRecover?: () => void;
};

function RecoveryAction({
  recoveryLabel,
  onRecover,
}: Pick<PageStatePanelProps, 'recoveryLabel' | 'onRecover'>) {
  if (!recoveryLabel) return null;
  return (
    <Button type="primary" disabled={!onRecover} onClick={onRecover}>
      {recoveryLabel}
    </Button>
  );
}

export default function PageStatePanel({
  state,
  errorCode,
  recoveryLabel,
  onRecover,
}: PageStatePanelProps) {
  if (state === 'loading') {
    return (
      <Card className="page-state-panel" aria-label="页面加载中">
        <Skeleton active paragraph={{ rows: 4 }} />
      </Card>
    );
  }

  if (state === 'empty') {
    return (
      <div className="page-state-panel page-state-empty">
        <Empty description="当前作业区暂无可见计划">
          <RecoveryAction recoveryLabel={recoveryLabel} onRecover={onRecover} />
        </Empty>
      </div>
    );
  }

  if (state === 'business-error') {
    return (
      <Alert
        className="page-state-panel"
        type="error"
        showIcon
        title="业务处理失败"
        description={
          <Space orientation="vertical">
            <Typography.Text>请根据公开错误码核对恢复条件。</Typography.Text>
            {errorCode ? <Typography.Text code>{errorCode}</Typography.Text> : null}
            <RecoveryAction recoveryLabel={recoveryLabel} onRecover={onRecover} />
          </Space>
        }
      />
    );
  }

  if (state === 'network-error') {
    return (
      <Result
        className="page-state-panel"
        status="warning"
        title="计划接口暂不可用"
        subTitle={errorCode ?? '筛选条件已保留，请稍后重试。'}
        extra={<RecoveryAction recoveryLabel={recoveryLabel} onRecover={onRecover} />}
      />
    );
  }

  return (
    <Result
      className="page-state-panel"
      status="404"
      title="对象已变化或不存在"
      subTitle={errorCode ?? '请返回列表刷新后重试。'}
      extra={<RecoveryAction recoveryLabel={recoveryLabel} onRecover={onRecover} />}
    />
  );
}
