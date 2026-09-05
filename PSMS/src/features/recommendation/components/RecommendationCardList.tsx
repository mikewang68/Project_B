import { Card, List, Progress, Space, Statistic, Tag, Typography } from 'antd';

import type { RecommendationCandidate } from '../schemas';

export type RecommendationCardListProps = {
  candidates: readonly RecommendationCandidate[];
  selectedCandidateId?: string;
  disabled?: boolean;
  onSelect: (candidateId: string) => void;
};

export default function RecommendationCardList({
  candidates,
  selectedCandidateId,
  disabled = false,
  onSelect,
}: RecommendationCardListProps) {
  return (
    <section aria-label="候选股道" className="recommendation-section">
      <Card title="候选股道" extra={<Typography.Text type="secondary">综合评分</Typography.Text>}>
        <List
          className="recommendation-candidate-list"
          dataSource={[...candidates]}
          renderItem={(candidate) => {
            const selected = candidate.candidateId === selectedCandidateId;
            return (
              <List.Item key={candidate.candidateId}>
                <button
                  type="button"
                  className={`recommendation-candidate${selected ? ' is-selected' : ''}`}
                  aria-label={`选择候选 ${candidate.trackNo}`}
                  aria-pressed={selected}
                  disabled={disabled}
                  onClick={() => onSelect(candidate.candidateId)}
                >
                  <div className="recommendation-candidate-heading">
                    <Space size={8} wrap>
                      <Typography.Title level={4}>{candidate.trackNo}</Typography.Title>
                      <Tag>第 {candidate.rank} 名</Tag>
                      <Tag color={candidate.occupyStatus === 'FREE' ? 'success' : 'warning'}>
                        {candidate.occupyStatus}
                      </Tag>
                      {candidate.recommended ? <Tag color="blue">系统推荐</Tag> : null}
                    </Space>
                    <Statistic value={candidate.score} suffix="分" />
                  </div>
                  <Progress percent={candidate.score} showInfo={false} />
                  <div className="recommendation-score-grid">
                    <span>可用性 {candidate.scoreBreakdown.availability}</span>
                    <span>时序 {candidate.scoreBreakdown.timing}</span>
                    <span>连续性 {candidate.scoreBreakdown.continuity}</span>
                    <span>权威性 {candidate.scoreBreakdown.authority}</span>
                  </div>
                  <Typography.Paragraph type="secondary" className="recommendation-window">
                    {candidate.windowStart} → {candidate.windowEnd}
                  </Typography.Paragraph>
                  <Space size={[6, 4]} wrap>
                    {candidate.reasons.map((reason) => (
                      <Tag key={reason}>{reason}</Tag>
                    ))}
                  </Space>
                </button>
              </List.Item>
            );
          }}
        />
      </Card>
    </section>
  );
}
