import { Badge, Card, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { InterfaceHealthViewModel, OpenRiskViewModel } from '../types';

export type RiskInterfacePanelProps = {
  health: InterfaceHealthViewModel;
  risks: readonly OpenRiskViewModel[];
};

const healthPresentation = {
  HEALTHY: { label: '正常', status: 'success' },
  DEGRADED: { label: '降级', status: 'warning' },
  UNAVAILABLE: { label: '不可用', status: 'error' },
  CIRCUIT_OPEN: { label: '已熔断', status: 'error' },
} as const;

function formatLastSuccess(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '暂无成功记录';
}

export default function RiskInterfacePanel({ health, risks }: RiskInterfacePanelProps) {
  const presentation = healthPresentation[health.status];
  return (
    <div className="risk-interface-panel">
      <Card
        className="overview-section-card"
        title="接口健康"
        size="small"
        aria-label="接口状态"
      >
        <section>
          <div className="interface-health-heading">
            <div>
              <Typography.Text strong>计划接口</Typography.Text>
              <Typography.Paragraph type="secondary">
                95306 / 铁路计划接入通道
              </Typography.Paragraph>
            </div>
            <Badge status={presentation.status} text={presentation.label} />
          </div>
          <dl className="interface-health-facts">
            <div>
              <dt>最近成功</dt>
              <dd>{formatLastSuccess(health.lastSuccessAt)}</dd>
            </div>
            <div>
              <dt>重试</dt>
              <dd>重试 {health.retryCount} 次</dd>
            </div>
            <div>
              <dt>熔断</dt>
              <dd>{health.circuitOpen ? '已熔断' : '未熔断'}</dd>
            </div>
            {health.errorCode ? (
              <div>
                <dt>公开错误码</dt>
                <dd>
                  <Tag color="error">{health.errorCode}</Tag>
                </dd>
              </div>
            ) : null}
          </dl>
        </section>
      </Card>

      <Card
        className="overview-section-card overview-risk-card"
        title="风险待办"
        size="small"
        extra={<Badge count={risks.length} showZero />}
        aria-label="风险待办"
      >
        <section>
          <div className="overview-risk-list">
            {risks.length === 0 ? (
              <Typography.Text type="secondary">当前无未闭环风险</Typography.Text>
            ) : null}
            {risks.map((risk) => (
              <article
                key={risk.id}
                className="overview-risk-list-item"
                aria-label={`风险 ${risk.id}`}
              >
                <div className="overview-risk-item">
                  <Space size={6} wrap>
                    <Typography.Text strong>{risk.id}</Typography.Text>
                    <Tag title={businessLabel(risk.type)}>{businessLabel(risk.type)}</Tag>
                    <Tag title={businessLabel(risk.level)}>{businessLabel(risk.level)}</Tag>
                    <Typography.Text type="secondary" title={businessLabel(risk.status)}>
                      {businessLabel(risk.status)}
                    </Typography.Text>
                  </Space>
                  <Space size={6} wrap>
                    <Typography.Text type="secondary" title={businessLabel(risk.source)}>
                      {businessLabel(risk.source)}
                    </Typography.Text>
                    {risk.objectId ? <Typography.Text>{risk.objectId}</Typography.Text> : null}
                    {risk.errorCode ? <Tag color="error">{risk.errorCode}</Tag> : null}
                  </Space>
                </div>
              </article>
            ))}
          </div>
        </section>
      </Card>
    </div>
  );
}
