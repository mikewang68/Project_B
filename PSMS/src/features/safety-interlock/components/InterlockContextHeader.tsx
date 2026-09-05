import { Card, Descriptions, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';

import type { SafetyInterlockBoard } from '../types';
import { businessLabel } from '../../../presentation/businessCopy';

export default function InterlockContextHeader({ board }: { board: SafetyInterlockBoard }) {
  const { sourceContext } = board;
  return (
    <Card className="interlock-section" aria-label="联锁来源上下文" title="来源上下文">
      <Space orientation="vertical" size={10} className="interlock-full-width">
        <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }}>
          <Descriptions.Item label="来源模块">{sourceContext.from ? businessLabel(sourceContext.from) : '直接访问'}</Descriptions.Item>
          <Descriptions.Item label="异常上下文">
            {sourceContext.exceptionId ?? '未提供'}
          </Descriptions.Item>
          <Descriptions.Item label="场景">{sourceContext.scenarioId ?? '当前场景'}</Descriptions.Item>
        </Descriptions>
        <Space wrap>
          {board.sourceContextLabels.map((label) => <Tag key={label}>{label}</Tag>)}
          <Typography.Text type="warning">{board.sourceDisclosure}</Typography.Text>
          <Link to={board.returnExceptionUrl}>返回 UI-008 异常处置</Link>
        </Space>
      </Space>
    </Card>
  );
}
