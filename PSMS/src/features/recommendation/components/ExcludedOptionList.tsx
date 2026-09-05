import { Alert, Card, List, Space, Tag, Typography } from 'antd';

import type { RecommendationExclusion } from '../schemas';

export type ExcludedOptionListProps = {
  exclusions: readonly RecommendationExclusion[];
};

export default function ExcludedOptionList({ exclusions }: ExcludedOptionListProps) {
  return (
    <section aria-label="排除选项" className="recommendation-section">
      <Card title="排除选项">
        {exclusions.length === 0 ? (
          <Alert type="success" showIcon title="没有硬性排除项" />
        ) : (
          <List
            dataSource={[...exclusions]}
            renderItem={(item) => (
              <List.Item key={item.trackId}>
                <Space orientation="vertical" size={4}>
                  <Space size={8} wrap>
                    <Typography.Text strong>{item.trackNo}</Typography.Text>
                    <Tag color="error">{item.exclusionCode}</Tag>
                  </Space>
                  <Typography.Text>{item.reason}</Typography.Text>
                  <Typography.Text type="secondary">{item.sourceRefs.join(' · ')}</Typography.Text>
                </Space>
              </List.Item>
            )}
          />
        )}
      </Card>
    </section>
  );
}
