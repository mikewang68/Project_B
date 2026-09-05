import { Card, Descriptions, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { CargoSummaryView } from '../types';

export type CargoSummaryCardProps = {
  summary: CargoSummaryView;
};

export default function CargoSummaryCard({ summary }: CargoSummaryCardProps) {
  return (
    <section aria-label="货物摘要" className="task-decomposition-section">
      <Card title="货物摘要" extra={<Tag color="geekblue">演示稳定映射</Tag>}>
        <Descriptions size="small" column={1}>
          <Descriptions.Item label="货类">
            <span title={businessLabel(summary.cargoType)}>{businessLabel(summary.cargoType)}</span>
          </Descriptions.Item>
          <Descriptions.Item label="货票">
            {summary.waybillIds.join('、') || '数据未提供'}
          </Descriptions.Item>
          <Descriptions.Item label="物料">
            {summary.materialIds.join('、') || '数据未提供'}
          </Descriptions.Item>
        </Descriptions>
        <Space orientation="vertical" size={4} className="task-decomposition-disclosure">
          <Typography.Text strong>
            高峰验收基准：{summary.acceptanceBenchmark.trains} 列 / {summary.acceptanceBenchmark.cars} 节 / {summary.acceptanceBenchmark.containers} 箱
          </Typography.Text>
          <Typography.Text type="secondary">
            {summary.acceptanceBenchmark.disclosure}
          </Typography.Text>
          <Typography.Text>本计划实际箱量：{summary.actualContainerCount}</Typography.Text>
          <Typography.Text type="secondary">
            缺失数据：{summary.missingData.join('、')}
          </Typography.Text>
        </Space>
      </Card>
    </section>
  );
}
