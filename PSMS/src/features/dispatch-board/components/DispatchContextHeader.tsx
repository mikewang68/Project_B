import { Card, Descriptions, Space, Tag } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { DispatchBoardView } from '../types';

export type DispatchContextHeaderProps = Pick<
  DispatchBoardView,
  'plan' | 'ruleVersion' | 'sourceUi'
> & {
  scenarioId: string;
};

export default function DispatchContextHeader({
  plan,
  ruleVersion,
  sourceUi,
  scenarioId,
}: DispatchContextHeaderProps) {
  return (
    <section aria-label="计划与派工上下文" className="dispatch-board-section">
      <Card
        title="计划与派工上下文"
        extra={
          <Space size={6} wrap>
            <Tag color="success" title={businessLabel(plan.status)}>{businessLabel(plan.status)}</Tag>
            <Tag>计划版本 {plan.version}</Tag>
          </Space>
        }
      >
        <Descriptions size="small" column={{ xs: 1, sm: 2, xl: 4 }}>
          <Descriptions.Item label="计划批次">{plan.planBatchNo}</Descriptions.Item>
          <Descriptions.Item label="车次">{plan.trainNo}</Descriptions.Item>
          <Descriptions.Item label="货类">{businessLabel(plan.cargoType)}</Descriptions.Item>
          <Descriptions.Item label="计划标识">{plan.id}</Descriptions.Item>
          <Descriptions.Item label="任务规则">
            {ruleVersion.replace('C06-DEMO-RULE-', '派工规则 ')}
          </Descriptions.Item>
          <Descriptions.Item label="任务来源">来源 {sourceUi}</Descriptions.Item>
          <Descriptions.Item label="演示场景">{scenarioId}</Descriptions.Item>
        </Descriptions>
      </Card>
    </section>
  );
}
