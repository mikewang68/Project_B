import { Button, Descriptions, Drawer, Space, Tag, Typography } from 'antd';

import { businessJson, businessLabel } from '../../../presentation/businessCopy';
import type { AuditLedgerItem } from '../auditTypes';

export default function AuditDetailDrawer({
  item,
  open,
  onClose,
  onOpenTrace,
}: Readonly<{
  item?: AuditLedgerItem;
  open: boolean;
  onClose: () => void;
  onOpenTrace: (traceId: string) => void;
}>) {
  return (
    <Drawer
      className="audit-detail-drawer"
      size="large"
      open={open && item !== undefined}
      destroyOnHidden
      onClose={onClose}
      title={item ? `审计详情 ${item.record.id}` : '审计详情'}
      aria-label={item ? `审计详情 ${item.record.id}` : '审计详情'}
    >
      {item ? (
        <div className="audit-detail-content">
          <Space size={[6, 6]} wrap>
            <Tag>{businessLabel(item.sourceModule)}</Tag>
            <Tag>{businessLabel(item.resultCategory)}</Tag>
            <Typography.Text type="secondary">领域对象 DO-013 以编号字段为主键；接口回执审计号不写入本条记录。</Typography.Text>
          </Space>
          <Descriptions className="audit-detail-fields" size="small" column={1} bordered>
            <Descriptions.Item label="审计编号">{item.record.id}</Descriptions.Item>
            <Descriptions.Item label="操作人编号">{item.record.actorId}</Descriptions.Item>
            <Descriptions.Item label="操作终端">{item.record.operatorTerminal}</Descriptions.Item>
            <Descriptions.Item label="业务动作">{businessLabel(item.record.action)}</Descriptions.Item>
            <Descriptions.Item label="对象类型">{businessLabel(item.record.objectType)}</Descriptions.Item>
            <Descriptions.Item label="对象编号">{item.record.objectId}</Descriptions.Item>
            <Descriptions.Item label="变更前"><pre>{businessJson(item.record.before)}</pre></Descriptions.Item>
            <Descriptions.Item label="变更后"><pre>{businessJson(item.record.after)}</pre></Descriptions.Item>
            <Descriptions.Item label="操作原因">{item.record.reason || '未提供'}</Descriptions.Item>
            <Descriptions.Item label="链路编号">{item.record.traceId}</Descriptions.Item>
            <Descriptions.Item label="发生时间">{item.record.occurredAt}</Descriptions.Item>
          </Descriptions>
          <Descriptions title="命令增强信息" size="small" column={1} bordered>
            <Descriptions.Item label="命令元数据">{item.commandMetadata ? businessJson(item.commandMetadata) : '未提供'}</Descriptions.Item>
            <Descriptions.Item label="请求摘要">未提供</Descriptions.Item>
            <Descriptions.Item label="响应摘要">未提供</Descriptions.Item>
          </Descriptions>
          <Space wrap>
            <Button type="primary" onClick={() => onOpenTrace(item.record.traceId)}>
              打开 {item.record.traceId} 链路
            </Button>
            <Button onClick={onClose}>关闭审计详情</Button>
          </Space>
        </div>
      ) : null}
    </Drawer>
  );
}
