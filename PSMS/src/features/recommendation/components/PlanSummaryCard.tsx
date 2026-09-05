import { Card, Space, Tag, Typography } from 'antd';

import type { Plan } from '../../../contracts';
import { businessLabel } from '../../../presentation/businessCopy';

export type PlanSummaryCardProps = {
  plan: Readonly<Plan>;
};

export default function PlanSummaryCard({ plan }: PlanSummaryCardProps) {
  const [arrival = plan.arrivalDepartureTime, departure = '数据未提供'] =
    plan.arrivalDepartureTime.split('/');
  const conflicts = plan.conflicts.length === 0 ? '无' : JSON.stringify(plan.conflicts);
  const facts = [
    ['计划批次', plan.planBatchNo],
    ['车次', plan.trainNo],
    ['货类', businessLabel(plan.cargoType)],
    ['计划到达', arrival],
    ['计划离开', departure],
    ['当前股道', plan.trackNo],
    ['计划状态', businessLabel(plan.status)],
    ['数据版本', String(plan.version)],
    ['来源系统', businessLabel(plan.sourceSystem)],
    ['来源时间', plan.sourceTime],
    ['冲突', conflicts],
  ] as const;

  return (
    <section aria-label="计划摘要" className="recommendation-section">
      <Card
        title="计划摘要"
        extra={
          <Space size={6} wrap>
            <Tag color="success">{businessLabel(plan.status)}</Tag>
            <Tag>v{plan.version}</Tag>
          </Space>
        }
      >
        <dl className="recommendation-facts">
          {facts.map(([label, value]) => (
            <div key={label} className="recommendation-fact">
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
        <Space orientation="vertical" size={4} className="recommendation-missing-data">
          <Typography.Text type="secondary">计划优先级：数据未提供</Typography.Text>
          <Typography.Text type="secondary">关联箱量：数据未提供</Typography.Text>
          <Typography.Text type="secondary">
            权威来源：{businessLabel(plan.sourceSystem)} · {plan.sourceTime}
          </Typography.Text>
        </Space>
      </Card>
    </section>
  );
}
