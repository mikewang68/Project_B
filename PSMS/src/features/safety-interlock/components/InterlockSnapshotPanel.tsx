import { Card, Descriptions, Empty, Typography } from 'antd';

import type { InterlockLedgerItem } from '../types';

function displayValue(value: unknown): string {
  if (value === null) return 'null';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'string' || typeof value === 'number') {
    return String(value);
  }
  return JSON.stringify(value);
}

export default function InterlockSnapshotPanel({ item }: { item?: InterlockLedgerItem }) {
  const entries = item ? Object.entries(item.interlock.inputSnapshot) : [];
  const keyLabels: Readonly<Record<string, string>> = {
    sensor: '传感器',
    value: '触发值',
    threshold: '阈值',
  };
  return (
    <Card className="interlock-section" size="small" title="输入快照" aria-label="输入快照">
      {!item ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择联锁" /> : (
        <Descriptions size="small" column={1}>
          {entries.map(([key, value]) => (
            <Descriptions.Item key={key} label={keyLabels[key] ?? key}>
              <Typography.Text>{displayValue(value)}</Typography.Text>
            </Descriptions.Item>
          ))}
        </Descriptions>
      )}
    </Card>
  );
}
