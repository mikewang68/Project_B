import { Alert, Button, Card, Skeleton, Space, Tag, Typography } from 'antd';

import type { AuditReadObservation, AuditReadState } from '../auditTypes';

export default function AuditReadBanner({
  state,
  observation,
  pending,
  onRetry,
}: Readonly<{
  state: AuditReadState;
  observation?: AuditReadObservation;
  pending: boolean;
  onRetry: () => void;
}>) {
  if (state.kind === 'idle' || state.kind === 'loading') {
    return (
      <Card className="audit-loading-card" size="small" aria-label="审计日志加载中">
        <Skeleton active paragraph={{ rows: 1 }} />
        <Button size="small" disabled aria-label="重新加载">重新加载</Button>
      </Card>
    );
  }

  if (state.kind === 'success' && observation?.kind === 'success') {
    return (
      <Alert
        className="audit-read-alert"
        type="success"
        showIcon
        title="API-024 核验成功"
        aria-label="API-024 读取状态"
        description={(
          <Space size={[6, 6]} wrap>
            <Tag>{observation.scenarioId}</Tag>
            <Typography.Text code>{observation.traceId}</Typography.Text>
            <Typography.Text code>{observation.auditLogId}</Typography.Text>
        <Typography.Text type="secondary">读取身份仅属于响应封装，不计入 DO-013。</Typography.Text>
            <Button size="small" disabled={pending} onClick={onRetry}>重新加载</Button>
          </Space>
        )}
      />
    );
  }

  const title = state.kind === 'network-error'
    ? '审计日志读取暂不可用'
    : state.kind === 'malformed-response'
      ? '审计响应契约不完整'
      : state.kind === 'business-error'
        ? state.message
        : '审计读取状态不可用';
  return (
    <Alert
      className="audit-read-alert"
      type={state.kind === 'network-error' ? 'warning' : 'error'}
      showIcon
      title={title}
      aria-label="API-024 读取状态"
      description={(
        <Space size={[6, 6]} wrap>
          {'errorCode' in state ? <Tag color="error">{state.errorCode}</Tag> : null}
          {'message' in state && state.kind !== 'business-error' ? <Typography.Text>{state.message}</Typography.Text> : null}
          {observation && 'traceId' in observation ? <Typography.Text code>{observation.traceId}</Typography.Text> : null}
          {observation && 'auditLogId' in observation ? <Typography.Text code>{observation.auditLogId}</Typography.Text> : null}
          <Button size="small" disabled={pending} onClick={onRetry}>重新加载</Button>
        </Space>
      )}
    />
  );
}
