import { Card, Descriptions, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';

import type { ExceptionHandlingBoard } from '../types';
import { businessLabel } from '../../../presentation/businessCopy';

export default function ExceptionContextHeader({ board }: { board: ExceptionHandlingBoard }) {
  const { sourceContext } = board;
  const dispatchUrl = sourceContext.planId
    ? `/dispatch/work-orders?planId=${encodeURIComponent(sourceContext.planId)}`
      + `&scenarioId=${encodeURIComponent(sourceContext.scenarioId ?? 'SCN-01')}`
      + '&from=exception-handling'
    : '/dispatch/work-orders?from=exception-handling';
  return (
    <Card className="exception-section" aria-label="异常来源上下文" title="来源上下文">
      <Space orientation="vertical" size={10} className="exception-full-width">
        <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 4 }}>
          <Descriptions.Item label="来源模块">{sourceContext.from ? businessLabel(sourceContext.from) : '直接访问'}</Descriptions.Item>
          <Descriptions.Item label="工单上下文">{sourceContext.workOrderId ?? '未提供'}</Descriptions.Item>
          <Descriptions.Item label="计划上下文">{sourceContext.planId ?? '未提供'}</Descriptions.Item>
          <Descriptions.Item label="场景">{sourceContext.scenarioId ?? '当前场景'}</Descriptions.Item>
        </Descriptions>
        <Space wrap>
          {board.sourceContextLabels.map((label) => <Tag key={label}>{label}</Tag>)}
          <Typography.Text type="warning">{board.sourceDisclosure}</Typography.Text>
          <Link to={dispatchUrl}>返回 UI-005 派工看板</Link>
        </Space>
      </Space>
    </Card>
  );
}
