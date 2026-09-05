import { Alert, Button, Card, Descriptions, Space, Tag, Typography } from 'antd';

import type { OfflinePacketBoard } from '../offlinePacketTypes';
import { businessLabel } from '../../../presentation/businessCopy';

export default function OfflineContextHeader({
  board,
  activeScenarioId,
  resetting,
  onReset,
}: {
  board: OfflinePacketBoard;
  activeScenarioId: string;
  resetting: boolean;
  onReset: () => void;
}) {
  const { sourceContext } = board;
  return (
    <Card className="offline-section" aria-label="离线同步来源上下文" title="来源上下文">
      <Space orientation="vertical" size={10} className="offline-full-width">
        <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }}>
          <Descriptions.Item label="来源模块">{sourceContext.from ? businessLabel(sourceContext.from) : '直接访问'}</Descriptions.Item>
          <Descriptions.Item label="场景">{sourceContext.scenarioId ?? activeScenarioId}</Descriptions.Item>
          <Descriptions.Item label="离线包">{sourceContext.packetId ?? '全部'}</Descriptions.Item>
          <Descriptions.Item label="终端">{sourceContext.terminalId ?? '全部'}</Descriptions.Item>
          <Descriptions.Item label="作业单文本">{sourceContext.workOrderNo ?? '全部'}</Descriptions.Item>
          <Descriptions.Item label="状态">
            {sourceContext.mergeStatus ? businessLabel(sourceContext.mergeStatus) : '全部'}
          </Descriptions.Item>
        </Descriptions>
        <Space wrap>
          {board.sourceContextLabels.map((label) => (
            <Tag key={label}>
              {label.startsWith('状态：')
                ? `状态：${businessLabel(label.slice(3))}`
                : label.startsWith('来源模块：')
                  ? `来源模块：${businessLabel(label.slice('来源模块：'.length))}`
                : label}
            </Tag>
          ))}
          <Typography.Text type="warning">{board.sourceDisclosure}</Typography.Text>
          <Button loading={resetting} onClick={onReset}>重置到 SCN-01</Button>
        </Space>
        <Typography.Text type="secondary">
          对外口径：SCN-06 是“冲突识别与恢复引导”，SCN-01 是“标准离线包处理闭环”。
        </Typography.Text>
        {board.recoveryGuidance ? (
          <Alert type="warning" showIcon title={board.recoveryGuidance} />
        ) : null}
      </Space>
    </Card>
  );
}
