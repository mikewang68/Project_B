import { Button, Card, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { InterlockLedgerItem } from '../types';

export default function InterlockLedger({
  items,
  selectedInterlockId,
  onSelect,
}: {
  items: readonly InterlockLedgerItem[];
  selectedInterlockId?: string;
  onSelect: (interlockId: string) => void;
}) {
  return (
    <Card className="interlock-section" title="联锁台账" aria-label="联锁台账">
      <div className="interlock-ledger-list">
        {items.map((item) => {
          const { interlock } = item;
          return (
            <Button
              block
              key={interlock.id}
              type={selectedInterlockId === interlock.id ? 'primary' : 'default'}
              className="interlock-ledger-button"
              aria-label={`${interlock.interlockNo} ${businessLabel(interlock.riskType)} ${businessLabel(interlock.status)}`}
              onClick={() => onSelect(interlock.id)}
            >
              <div className="interlock-ledger-copy">
                <Space wrap size={6}>
                  <Typography.Text strong>{interlock.interlockNo}</Typography.Text>
                  <Tag color={interlock.actionLevel === 'FORCE_STOP' ? 'error' : 'warning'}>
                  <span title={businessLabel(interlock.actionLevel)}>{businessLabel(interlock.actionLevel)}</span>
                  </Tag>
                  <Tag color="processing" title={businessLabel(interlock.status)}>
                    {businessLabel(interlock.status)}
                  </Tag>
                </Space>
                <Typography.Text title={businessLabel(interlock.riskType)}>
                  {businessLabel(interlock.riskType)}
                </Typography.Text>
                <Typography.Text>
                  回执 <span title={businessLabel(interlock.receiptStatus)}>{businessLabel(interlock.receiptStatus)}</span>
                </Typography.Text>
                <Typography.Text type="secondary">
                  v{interlock.version} · {interlock.updatedAt}
                </Typography.Text>
                <Tag className="interlock-progress-tag" title={businessLabel(item.progressState)}>
                  {businessLabel(item.progressState)}
                </Tag>
              </div>
            </Button>
          );
        })}
      </div>
    </Card>
  );
}
