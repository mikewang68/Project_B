import { Card, Progress, Tag, Typography } from 'antd';

import type { ReportRate } from '../reportTypes';

export default function ReportEfficiencyPanel({ rates }: { rates: readonly ReportRate[] }) {
  return (
    <Card className="report-efficiency-card" title="作业效率" aria-label="作业效率指标">
      <div className="report-rate-list">
        {rates.map((rate) => (
          <div className="report-rate-row" key={rate.key}>
            <div className="report-rate-heading">
              <Typography.Text strong>{rate.label}</Typography.Text>
              <Tag>{rate.numerator} / {rate.denominator}</Tag>
            </div>
            <Progress percent={rate.value} size="small" strokeColor="#147d92" />
            <Typography.Text type="secondary" className="report-rate-source">
              {rate.source}
            </Typography.Text>
          </div>
        ))}
      </div>
    </Card>
  );
}
