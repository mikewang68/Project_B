import { Button, Card, Input, Table, Tag } from 'antd';

import { generateStatuses, reportTypes } from '../../../contracts';
import {
  generateStatusLabels,
  reportTypeLabels,
  type GenerateStatus,
  type ReportLedgerItem,
  type ReportType,
} from '../reportTypes';
import type { ReportFilters } from '../reportRuntime';

type ReportLedgerProps = {
  items: readonly ReportLedgerItem[];
  filters: ReportFilters;
  selectedReportId?: string;
  onFilters: (filters: ReportFilters) => void;
  onSelect: (reportId: string) => void;
};

export default function ReportLedger({
  items,
  filters,
  selectedReportId,
  onFilters,
  onSelect,
}: ReportLedgerProps) {
  return (
    <Card className="report-ledger-card" title="报表台账" aria-label="报表台账">
      <div className="report-filter-grid">
        <label>
          <span>报表类型</span>
          <select
            aria-label="报表类型"
            value={filters.reportType ?? ''}
            onChange={(event: { target: { value: string } }) => onFilters({
              ...filters,
              reportType: (event.target.value || undefined) as ReportType | undefined,
            })}
          >
            <option value="">全部类型</option>
            {reportTypes.map((type) => <option value={type} key={type}>{reportTypeLabels[type]}</option>)}
          </select>
        </label>
        <label>
          <span>统计周期</span>
          <Input
            aria-label="统计周期"
            value={filters.period ?? ''}
            placeholder="例如 2026-07-17"
            onChange={(event: { target: { value: string } }) => onFilters({ ...filters, period: event.target.value || undefined })}
          />
        </label>
        <label>
          <span>生成状态</span>
          <select
            aria-label="生成状态"
            value={filters.generateStatus ?? ''}
            onChange={(event: { target: { value: string } }) => onFilters({
              ...filters,
              generateStatus: (event.target.value || undefined) as GenerateStatus | undefined,
            })}
          >
            <option value="">全部状态</option>
            {generateStatuses.map((status) => (
              <option value={status} key={status}>{generateStatusLabels[status]}</option>
            ))}
          </select>
        </label>
      </div>
      <Table
        className="report-ledger-table"
        rowKey={(item: ReportLedgerItem) => item.report.id}
        size="small"
        pagination={false}
        dataSource={[...items]}
        rowClassName={(item: ReportLedgerItem) => item.report.id === selectedReportId ? 'report-row-selected' : ''}
        onRow={(item: ReportLedgerItem) => ({ onClick: () => onSelect(item.report.id) })}
        columns={[
          { title: '报表编号', dataIndex: ['report', 'id'], key: 'id' },
          { title: '类型', dataIndex: 'reportTypeLabel', key: 'type' },
          { title: '周期', dataIndex: ['report', 'period'], key: 'period' },
          {
            title: '状态', key: 'status', render: (_: unknown, item: ReportLedgerItem) => (
              <Tag color={item.report.generateStatus === 'SUCCESS' ? 'success' : item.report.generateStatus === 'FAILED' ? 'error' : 'processing'}>
                {item.generateStatusLabel}
              </Tag>
            ),
          },
          {
            title: '操作', key: 'action', render: (_: unknown, item: ReportLedgerItem) => (
              <Button type="link" size="small" onClick={(event: { stopPropagation(): void }) => {
                event.stopPropagation();
                onSelect(item.report.id);
              }}>
                查看 {item.report.id}
              </Button>
            ),
          },
        ]}
      />
    </Card>
  );
}
