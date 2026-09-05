import { Alert, Badge, Button, Space, Tag, Typography } from 'antd';

import type { PolicyContext } from '../../../auth';
import { createC03Element } from '../../../app/routeCatalog';
import PermissionGate from '../../../components/PermissionGate';
import type { PublicErrorCode } from '../../../contracts';
import type { InterfaceHealthViewModel } from '../types';

export type InterfaceHealthBannerProps = {
  health: InterfaceHealthViewModel;
  policyContext: PolicyContext;
  syncLoading?: boolean;
  recoverLoading?: boolean;
  commandError?: Readonly<{ errorCode?: PublicErrorCode; message: string }>;
  onSync?: () => void;
  onRecover?: () => void;
};

const healthPresentation = {
  HEALTHY: { label: '正常', badge: 'success' },
  DEGRADED: { label: '降级', badge: 'warning' },
  UNAVAILABLE: { label: '不可用', badge: 'error' },
  CIRCUIT_OPEN: { label: '已熔断', badge: 'error' },
} as const;

function gatedButton(
  permission: 'plan:view' | 'interface:retry',
  policyContext: PolicyContext,
  button: unknown,
) {
  return createC03Element(
    PermissionGate,
    {
      permission,
      context: { ...policyContext, permission },
      mode: 'hide',
    },
    button,
  );
}

function formatTimestamp(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '暂无成功记录';
}

export default function InterfaceHealthBanner({
  health,
  policyContext,
  syncLoading = false,
  recoverLoading = false,
  commandError,
  onSync,
  onRecover,
}: InterfaceHealthBannerProps) {
  const presentation = healthPresentation[health.status];
  return (
    <section className="plan-interface-banner" aria-label="计划接口健康">
      <div className="plan-interface-main">
        <div className="plan-interface-title">
          <Typography.Text strong>95306 / 铁路计划接入通道</Typography.Text>
          <Badge status={presentation.badge} text={presentation.label} />
        </div>
        <Space size={[10, 6]} wrap>
          <Typography.Text type="secondary">
            最近成功：{formatTimestamp(health.lastSuccessAt)}
          </Typography.Text>
          <Typography.Text>重试 {health.retryCount} 次</Typography.Text>
          <Typography.Text>{health.circuitOpen ? '已熔断' : '未熔断'}</Typography.Text>
          {health.errorCode ? <Tag color="error">{health.errorCode}</Tag> : null}
        </Space>
      </div>
      <Space className="plan-interface-actions" wrap>
        {gatedButton(
          'plan:view',
          policyContext,
          <Button
            loading={syncLoading}
            disabled={syncLoading || !onSync || health.circuitOpen}
            onClick={onSync}
          >
            同步计划
          </Button>,
        )}
        {health.recoveryVisible
          ? gatedButton(
              'interface:retry',
              policyContext,
              <Button
                type="primary"
                loading={recoverLoading}
                disabled={recoverLoading || !onRecover}
                onClick={onRecover}
              >
                恢复接口
              </Button>,
            )
          : null}
      </Space>
      {commandError ? (
        <Alert
          className="plan-interface-error"
          type="error"
          showIcon
          title={commandError.message}
          description={commandError.errorCode}
        />
      ) : null}
    </section>
  );
}
