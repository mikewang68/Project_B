import { Button, Card, Drawer, List, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { TaskRouteExplanation } from '../types';

export type RuleExplainPanelProps = {
  explanation: TaskRouteExplanation;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

function RuleContent({ explanation }: Pick<RuleExplainPanelProps, 'explanation'>) {
  return (
    <Space orientation="vertical" size={12} className="task-rule-content">
      <Space size={8} wrap>
        <Tag color="blue">{explanation.ruleVersion}</Tag>
        <Tag>演示稳定映射</Tag>
      </Space>
      <List
        size="small"
        dataSource={[...explanation.route]}
        renderItem={(step, index) => (
          <List.Item key={`${step.stage}-${step.taskType}`}>
            <Typography.Text>
              {index + 1}. {step.title} · {businessLabel(step.taskType)} · {businessLabel(step.requiredResourceType)}
            </Typography.Text>
          </List.Item>
        )}
      />
      <Typography.Text type="secondary">来源：{explanation.source}</Typography.Text>
      {explanation.limitations.map((limitation) => (
        <Typography.Text key={limitation} type="secondary">限制：{limitation}</Typography.Text>
      ))}
      {explanation.cargoType === 'GENERAL_CARGO' ? (
        <Typography.Text type="warning">
          通用货物未细分机电设备与生活物资
        </Typography.Text>
      ) : null}
    </Space>
  );
}

export default function RuleExplainPanel({
  explanation,
  open,
  onOpenChange,
}: RuleExplainPanelProps) {
  return (
    <section aria-label="规则说明" className="task-decomposition-section">
      <Card
        title="规则说明"
        extra={<Button onClick={() => onOpenChange(true)}>查看完整规则</Button>}
      >
        <Space orientation="vertical" size={6}>
          <Typography.Text strong>{explanation.ruleVersion}</Typography.Text>
          <Typography.Text>演示稳定映射</Typography.Text>
          <Typography.Text type="secondary">{explanation.route.map(({ title }) => title).join(' → ')}</Typography.Text>
        </Space>
      </Card>
      <Drawer
        title="C06 规则说明"
        open={open}
        size="min(620px, calc(100vw - 24px))"
        onClose={() => onOpenChange(false)}
        extra={<Button onClick={() => onOpenChange(false)}>关闭规则说明</Button>}
      >
        <RuleContent explanation={explanation} />
      </Drawer>
    </section>
  );
}
