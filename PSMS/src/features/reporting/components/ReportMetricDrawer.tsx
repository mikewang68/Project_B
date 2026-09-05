import { Descriptions, Drawer, Tag, Typography } from 'antd';

import type { ReportMetricSnapshot } from '../reportTypes';

export default function ReportMetricDrawer({
  open,
  metrics,
  onClose,
}: {
  open: boolean;
  metrics: ReportMetricSnapshot;
  onClose: () => void;
}) {
  return (
    <Drawer
      size="large"
      open={open}
      onClose={onClose}
      title="指标口径与公式"
      aria-label="指标口径与公式"
    >
      <div className="report-definition-list">
        <Typography.Paragraph>
          <Tag color="cyan">{metrics.disclosure}</Tag>
          所有百分比均由当前应用状态快照计算，分母为零时按 0% 展示。
        </Typography.Paragraph>
        {metrics.definitions.map((definition) => (
          <Descriptions
            className="report-definition"
            size="small"
            column={1}
            bordered
            title={definition.label}
            key={definition.key}
          >
            <Descriptions.Item label="来源">{definition.source}</Descriptions.Item>
            <Descriptions.Item label="公式">{definition.formula}</Descriptions.Item>
          </Descriptions>
        ))}
      </div>
    </Drawer>
  );
}
