import { Card, Descriptions, Space, Tag, Typography } from 'antd';

import type { Plan } from '../../../contracts';
import { businessLabel } from '../../../presentation/businessCopy';
import type { RecommendationDraft } from '../../recommendation';

export type TaskContextHeaderProps = {
  plan: Readonly<Plan>;
  recommendation: RecommendationDraft;
};

export default function TaskContextHeader({
  plan,
  recommendation,
}: TaskContextHeaderProps) {
  const selected = recommendation.candidates.find(
    ({ candidateId }) => candidateId === recommendation.selectedCandidateId,
  );
  return (
    <section aria-label="计划与推荐摘要" className="task-decomposition-section">
      <Card
        title="计划与推荐摘要"
        extra={
          <Space size={6} wrap>
            <Tag
              color={plan.status === 'DECOMPOSED' ? 'success' : 'processing'}
              title={businessLabel(plan.status)}
            >
              {businessLabel(plan.status)}
            </Tag>
            <Tag>Plan v{plan.version}</Tag>
            <Tag>推荐 v{recommendation.draftVersion}</Tag>
          </Space>
        }
      >
        <Typography.Title level={5}>本计划事实</Typography.Title>
        <Descriptions size="small" column={{ xs: 1, sm: 2, xl: 3 }}>
          <Descriptions.Item label="计划批次">{plan.planBatchNo}</Descriptions.Item>
          <Descriptions.Item label="车次">{plan.trainNo}</Descriptions.Item>
          <Descriptions.Item label="货类">{businessLabel(plan.cargoType)}</Descriptions.Item>
          <Descriptions.Item label="到离窗口">{plan.arrivalDepartureTime}</Descriptions.Item>
          <Descriptions.Item label="计划股道">{plan.trackNo}</Descriptions.Item>
          <Descriptions.Item label="权威来源">{businessLabel(plan.sourceSystem)}</Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5} className="task-decomposition-subtitle">
          已确认 C05 推荐
        </Typography.Title>
        <Space size={8} wrap>
          <Tag color="blue">{selected?.trackNo ?? '数据未提供'}</Tag>
          <Typography.Text>
            {selected ? `${selected.windowStart} → ${selected.windowEnd}` : '推荐窗口：数据未提供'}
          </Typography.Text>
          <Typography.Text type="secondary">
            {recommendation.ruleVersion} · {businessLabel(recommendation.status)}
          </Typography.Text>
        </Space>
      </Card>
    </section>
  );
}
