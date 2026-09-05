import { Alert, Button, Drawer, List, Space, Tag, Typography } from 'antd';

import type { RecommendationDraft } from '../schemas';

export type RuleExplanationDrawerProps = {
  open: boolean;
  draft?: RecommendationDraft;
  onClose: () => void;
};

export default function RuleExplanationDrawer({
  open,
  draft,
  onClose,
}: RuleExplanationDrawerProps) {
  const parts = [
    '可用性：最高 45 分',
    '时序匹配：最高 30 分',
    '原股道连续性：最高 15 分',
    '权威时间完整性：最高 10 分',
  ];
  const trackVersions = draft?.candidates
    .map(({ trackNo, trackVersion }) => `${trackNo} v${trackVersion}`)
    .join(' · ');

  return (
    <Drawer
      title="推荐规则说明"
      open={open}
      size="min(520px, calc(100vw - 24px))"
      onClose={onClose}
      extra={<Button onClick={onClose}>关闭规则说明</Button>}
    >
      <Space orientation="vertical" size={16} className="recommendation-drawer-content">
        <Space size={8} wrap>
          <Tag color="blue">规则版本</Tag>
          <Typography.Text code>{draft?.ruleVersion ?? 'C05-DEMO-RULE-1.0'}</Typography.Text>
        </Space>
        <List dataSource={parts} renderItem={(item) => <List.Item>{item}</List.Item>} />
        <Alert
          type="info"
          showIcon
          title="硬排除先于评分"
          description="封锁、不兼容或无法解释释放时间的股道先排除；其余候选按总分降序、窗口开始时间和股道号排序。"
        />
        <Typography.Paragraph>
          输入 Plan 版本：v{draft?.inputPlanVersion ?? '—'}
          <br />
          输入 Track 版本：{trackVersions || '—'}
        </Typography.Paragraph>
        <Alert type="warning" showIcon title="仅供演示解释，不构成生产调度承诺" />
      </Space>
    </Drawer>
  );
}
