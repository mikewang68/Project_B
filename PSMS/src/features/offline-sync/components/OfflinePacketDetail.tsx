import { Alert, Card, Descriptions, Empty, Space, Tag, Typography } from 'antd';

import type { CommandAuditEntry } from '../../../governance/audit';
import { businessLabel } from '../../../presentation/businessCopy';
import type { OfflinePacketLedgerItem } from '../offlinePacketTypes';

export default function OfflinePacketDetail({
  item,
  audits,
}: {
  item?: OfflinePacketLedgerItem;
  audits: readonly CommandAuditEntry[];
}) {
  return (
    <div className="offline-detail-stack">
      <Card className="offline-section" title="离线包详情" aria-label="离线包详情">
        {!item ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择离线包" /> : (
          <Space orientation="vertical" className="offline-full-width" size={12}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="包编号">{item.packet.offlinePackageNo}</Descriptions.Item>
              <Descriptions.Item label="状态">{businessLabel(item.packet.mergeStatus)}</Descriptions.Item>
              <Descriptions.Item label="终端">{item.packet.terminalId}</Descriptions.Item>
              <Descriptions.Item label="作业单文本">{item.packet.workOrderNo}</Descriptions.Item>
              <Descriptions.Item label="记录版本">v{item.packet.version}</Descriptions.Item>
              <Descriptions.Item label="更新时间">{item.packet.updatedAt}</Descriptions.Item>
            </Descriptions>
            <Space wrap aria-label="离线包版本比较">
              <Tag>包版本 {item.packet.packageVersion}</Tag>
              <Tag>服务端版本 {item.packet.serverVersion}</Tag>
              <Tag color={item.versionDelta === 0 ? 'success' : 'warning'}>
                版本差 {item.versionDelta >= 0 ? '+' : ''}{item.versionDelta}
              </Tag>
            </Space>
            <div className="offline-state-flow" aria-label="离线包状态流">
              {item.stateFlow.map(({ status, current }) => (
                <Tag key={status} color={current ? 'processing' : 'default'} title={businessLabel(status)}>
                  {businessLabel(status)}
                </Tag>
              ))}
            </div>
            <div className="offline-summary-block">
              <Typography.Text strong>校验结果</Typography.Text>
              <Tag color={item.validationValid === true ? 'success' : 'error'}>
                {businessLabel(item.validationValid === true ? 'VALID' : 'INVALID')}
              </Tag>
              <Space wrap>
                {item.validationIssues.length > 0
                  ? item.validationIssues.map((issue) => (
                      <Tag key={issue} title={businessLabel(issue)}>{businessLabel(issue)}</Tag>
                    ))
                  : <Typography.Text type="secondary">无校验问题</Typography.Text>}
              </Space>
            </div>
            {item.conflict ? (
              <Alert
                type="warning"
                showIcon
                title="检测到版本冲突"
                description="离线包版本与服务端版本不一致，请按场景口径处理。"
              />
            ) : null}
          </Space>
        )}
      </Card>
      <Card className="offline-section" size="small" title="处理记录摘要" aria-label="C10 审计摘要">
        {audits.length === 0 ? (
          <Typography.Text type="secondary">暂无 C10 离线同步记录</Typography.Text>
        ) : (
          <div className="offline-audit-list">
            {audits.map(({ record, metadata }) => (
              <Typography.Text key={record.id}>
                {businessLabel(record.action)} · {businessLabel(metadata.result)} · {record.traceId} · {record.id}
              </Typography.Text>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
