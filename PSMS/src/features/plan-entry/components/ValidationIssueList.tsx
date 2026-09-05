import { Alert, Empty, Space, Tag, Typography } from 'antd';

import type { PlanValidationIssueViewModel } from '../types';

export type ValidationIssueListProps = {
  issues: readonly PlanValidationIssueViewModel[];
};

export default function ValidationIssueList({ issues }: ValidationIssueListProps) {
  return (
    <section className="plan-detail-section" aria-label="校验问题">
      <Typography.Title level={5}>校验问题</Typography.Title>
      {issues.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前无校验问题" />
      ) : (
        <Alert
          type="error"
          showIcon
          title="计划存在待处理字段"
          description={
            <ul className="plan-detail-list">
              {issues.map((issue) => (
                <li key={`${issue.planId}-${issue.field}`}>
                  <Space wrap>
                    <Typography.Text>缺失字段：{issue.field}</Typography.Text>
                    <Tag color="error">{issue.errorCode}</Tag>
                    <Typography.Text type="secondary">
                      补齐所列字段后重新校验
                    </Typography.Text>
                  </Space>
                </li>
              ))}
            </ul>
          }
        />
      )}
    </section>
  );
}
