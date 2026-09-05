import { Button, Card, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';

import { businessLabel } from '../../../presentation/businessCopy';
import type { ExceptionLedgerItem } from '../types';

export default function ExceptionLedger({
  items,
  selectedExceptionId,
  onSelect,
}: {
  items: readonly ExceptionLedgerItem[];
  selectedExceptionId?: string;
  onSelect: (exceptionId: string) => void;
}) {
  return (
    <Card className="exception-section" title="异常台账" aria-label="异常台账">
      <div className="exception-ledger-list">
        {items.map((item) => {
          const { exception } = item;
          return (
            <div className="exception-ledger-entry" key={exception.id}>
              <Button
                block
                type={selectedExceptionId === exception.id ? 'primary' : 'default'}
                className="exception-ledger-button"
                aria-label={`${exception.exceptionNo} ${businessLabel(exception.type)} ${businessLabel(exception.status)}`}
                onClick={() => onSelect(exception.id)}
              >
                <div className="exception-ledger-copy">
                  <Space wrap size={6}>
                    <Typography.Text strong>{exception.exceptionNo}</Typography.Text>
                    <Tag title={businessLabel(exception.level)}>{businessLabel(exception.level)}</Tag>
                    <Tag color="processing" title={businessLabel(exception.status)}>
                      {businessLabel(exception.status)}
                    </Tag>
                  </Space>
                  <Typography.Text title={businessLabel(exception.type)}>
                    {businessLabel(exception.type)}
                  </Typography.Text>
                  <Typography.Text>{exception.owner || '未分派'}</Typography.Text>
                  <Typography.Text type="secondary">
                    截止 {exception.dueAt} · v{exception.version} · 证据 {item.evidenceCount}
                  </Typography.Text>
                  <Tag className="exception-progress-tag" title={businessLabel(item.progressState)}>
                    {businessLabel(item.progressState)}
                  </Tag>
                </div>
              </Button>
              {item.interlockEntryUrl ? (
                <Link to={item.interlockEntryUrl}>前往 UI-009 安全联锁</Link>
              ) : null}
            </div>
          );
        })}
      </div>
    </Card>
  );
}
