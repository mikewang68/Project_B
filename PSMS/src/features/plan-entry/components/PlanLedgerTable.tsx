import { Button, Space, Table, Tag, Typography, type TableColumnsType } from 'antd';

import type { PolicyContext } from '../../../auth';
import { createC03Element } from '../../../app/routeCatalog';
import PermissionGate from '../../../components/PermissionGate';
import type { PlanLedgerRowViewModel, PlanLedgerViewModel } from '../types';
import { businessLabel } from '../../../presentation/businessCopy';

const fieldLabels: Readonly<Record<string, string>> = {
  trackNo: '股道',
  arrivalDepartureTime: '到发时间',
  cargoType: '货类',
  trainNo: '车次',
};

export type PlanLedgerTableProps = {
  ledger: PlanLedgerViewModel;
  policyContext: PolicyContext;
  onOpen: (planId: string) => void;
  onPageChange: (page: number) => void;
};

function planAction(
  planId: string,
  label: string,
  policyContext: PolicyContext,
  onOpen: (planId: string) => void,
) {
  return createC03Element(
    PermissionGate,
    {
      permission: 'plan:view',
      context: { ...policyContext, permission: 'plan:view' },
      mode: 'hide',
    },
    <Button
      type="link"
      size="small"
      onClick={(event: { stopPropagation: () => void }) => {
        event.stopPropagation();
        onOpen(planId);
      }}
    >
      {label}
    </Button>,
  );
}

export default function PlanLedgerTable({
  ledger,
  policyContext,
  onOpen,
  onPageChange,
}: PlanLedgerTableProps) {
  const columns: TableColumnsType<PlanLedgerRowViewModel> = [
    {
      title: '来源',
      dataIndex: 'sourceSystem',
      width: 150,
      render: (sourceSystem: string, row) => (
        <Space orientation="vertical" size={1}>
          {planAction(row.id, row.id, policyContext, onOpen)}
          <Typography.Text type="secondary">{businessLabel(sourceSystem)}</Typography.Text>
        </Space>
      ),
    },
    { title: '计划批次', dataIndex: 'planBatchNo', width: 160, ellipsis: true },
    { title: '车次', dataIndex: 'trainNo', width: 90 },
    {
      title: '股道',
      dataIndex: 'trackNo',
      width: 80,
      render: (value?: string) => value ?? <Typography.Text type="danger">待补录</Typography.Text>,
    },
    { title: '货类', dataIndex: 'cargoType', width: 120, ellipsis: true, render: (value: string) => businessLabel(value) },
    {
      title: '缺失字段',
      dataIndex: 'missingFields',
      width: 120,
      render: (fields: readonly string[]) =>
        fields.length === 0 ? '无' : <Tag color="error">{fields.map((field) => fieldLabels[field] ?? field).join('、')}</Tag>,
    },
    {
      title: '校验状态',
      dataIndex: 'validationStatus',
      width: 110,
      render: (status: PlanLedgerRowViewModel['validationStatus']) => (
        <Tag color={status === 'VALID' ? 'success' : 'error'}>{businessLabel(status)}</Tag>
      ),
    },
    {
      title: '同步状态',
      dataIndex: 'syncStatus',
      width: 110,
      render: (status: PlanLedgerRowViewModel['syncStatus']) => (
        <Tag color={status === 'SYNCED' ? 'success' : 'warning'}>{businessLabel(status)}</Tag>
      ),
    },
    { title: '更新时间', dataIndex: 'formattedUpdatedAt', width: 145 },
    {
      title: '操作',
      key: 'action',
      width: 88,
      fixed: 'right',
      render: (_value, row) => planAction(row.id, '查看详情', policyContext, onOpen),
    },
  ];

  return (
    <section className="plan-ledger-table-wrap" aria-label="计划台账">
      <Table<PlanLedgerRowViewModel>
        className="plan-ledger-table"
        columns={columns}
        dataSource={[...ledger.items]}
        rowKey="id"
        tableLayout="fixed"
        scroll={{ x: 1290 }}
        pagination={{
          current: ledger.page,
          pageSize: 20,
          total: ledger.total,
          showSizeChanger: false,
          showTotal: (total: number) => `共 ${total} 条`,
          onChange: onPageChange,
        }}
        onRow={(row: PlanLedgerRowViewModel) => ({
          tabIndex: 0,
          onClick: () => onOpen(row.id),
          onKeyDown: (event: { key: string }) => {
            if (event.key === 'Enter') onOpen(row.id);
          },
        })}
      />
    </section>
  );
}
