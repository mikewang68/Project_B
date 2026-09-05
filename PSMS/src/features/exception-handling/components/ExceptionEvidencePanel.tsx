import { Card, Empty, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import type { ExceptionLedgerItem } from '../types';

export default function ExceptionEvidencePanel({ item }: { item?: ExceptionLedgerItem }) {
  return (
    <Card className="exception-section" size="small" title="证据与影响" aria-label="异常证据">
      {!item ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择异常" /> : (
        <Space orientation="vertical" className="exception-full-width">
          <Typography.Text>
            影响说明：{businessLabel(item.exception.type)}需按冻结流程处置。
          </Typography.Text>
          <Space wrap>
            {item.exception.evidence.length > 0
              ? item.exception.evidence.map((evidence) => <Tag key={evidence}>{evidence}</Tag>)
              : <Typography.Text type="secondary">暂无证据</Typography.Text>}
          </Space>
          {item.interlockEntryUrl ? (
            <Typography.Text type="danger">
              安全联锁异常仅链接 UI-009；异常处置页不解除、覆盖或复位联锁。
            </Typography.Text>
          ) : null}
        </Space>
      )}
    </Card>
  );
}
