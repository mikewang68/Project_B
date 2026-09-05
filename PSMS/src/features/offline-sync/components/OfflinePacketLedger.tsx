import { Button, Card, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { OfflinePacketLedgerItem } from '../offlinePacketTypes';

function statusColor(status: OfflinePacketLedgerItem['packet']['mergeStatus']) {
  if (status === 'CONFLICT' || status === 'REJECTED') return 'error';
  if (status === 'MERGED') return 'success';
  if (status === 'VALIDATING' || status === 'PENDING_UPLOAD') return 'processing';
  return 'default';
}

export default function OfflinePacketLedger({
  items,
  selectedPacketId,
  onSelect,
}: {
  items: readonly OfflinePacketLedgerItem[];
  selectedPacketId?: string;
  onSelect: (packetId: string) => void;
}) {
  const selected = items.find(({ packet }) => packet.id === selectedPacketId);
  const orderedItems = selected
    ? [selected, ...items.filter(({ packet }) => packet.id !== selectedPacketId)]
    : items;
  return (
    <Card className="offline-section offline-ledger" title="离线包台账" aria-label="离线包台账">
      <div className="offline-ledger-list">
        {orderedItems.map((item) => {
          const { packet } = item;
          return (
            <Button
              block
              key={packet.id}
              type={selectedPacketId === packet.id ? 'primary' : 'default'}
              className="offline-ledger-button"
              aria-label={`${packet.offlinePackageNo} ${packet.terminalId} ${businessLabel(packet.mergeStatus)}`}
              onClick={() => onSelect(packet.id)}
            >
              <div className="offline-ledger-copy">
                <Space wrap size={6}>
                  <Typography.Text strong>{packet.offlinePackageNo}</Typography.Text>
              <Tag color={statusColor(packet.mergeStatus)} title={businessLabel(packet.mergeStatus)}>
                    {businessLabel(packet.mergeStatus)}
                  </Tag>
                </Space>
                <Typography.Text>{packet.terminalId} · {packet.workOrderNo}</Typography.Text>
                <Typography.Text>
                  包 v{packet.packageVersion} / 服务端 v{packet.serverVersion}
                </Typography.Text>
                <Typography.Text type="secondary">
                  记录 v{packet.version} · 差值 {item.versionDelta >= 0 ? '+' : ''}{item.versionDelta}
                </Typography.Text>
                <Tag className="offline-progress-tag" title={businessLabel(item.progressState)}>
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
