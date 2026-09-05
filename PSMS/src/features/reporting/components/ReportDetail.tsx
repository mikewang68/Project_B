import { Button, Card, Descriptions, Input, Space, Tag, Tooltip, Typography } from 'antd';

import type { ReportLedgerItem } from '../reportTypes';
import { businessLabel } from '../../../presentation/businessCopy';

const metricLabels: Readonly<Record<string, string>> = {
  completedWorkOrders: '已完成工单',
  exceptions: '异常数量',
  planTotal: '计划总数',
  confirmedPlanCount: '已确认计划数',
  appliedRecommendationCount: '推荐已应用数',
  generatedWorkOrderCount: '已生成任务数',
  dispatchedWorkOrderCount: '已派工任务数',
  exceptionCount: '异常数量',
  interlockCount: '安全联锁数量',
  mergedOfflinePacketCount: '离线包已合并数量',
  planConfirmationRate: '计划确认率',
  taskDecompositionRate: '任务拆解完成率',
  dispatchRate: '派工完成率',
  exceptionClosureRate: '异常关闭率',
  offlineMergeRate: '离线同步合并率',
};

type ReportDetailProps = {
  item?: ReportLedgerItem;
  pending: boolean;
  canGenerate: boolean;
  disabledReason?: string;
  reason: string;
  snapshotMetricCount: number;
  snapshotAsOf: string;
  onReason: (reason: string) => void;
  onRefresh: () => void;
  onOpenMetrics: () => void;
};

export default function ReportDetail({
  item,
  pending,
  canGenerate,
  disabledReason,
  reason,
  snapshotMetricCount,
  snapshotAsOf,
  onReason,
  onRefresh,
  onOpenMetrics,
}: ReportDetailProps) {
  return (
    <Card className="report-detail-card" title="报表详情" aria-label="报表详情">
      {item ? (
        <div className="report-detail-stack">
          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="报表编号">{item.report.id}</Descriptions.Item>
            <Descriptions.Item label="报表类型">{businessLabel(item.report.reportType)}</Descriptions.Item>
            <Descriptions.Item label="统计周期">{item.report.period}</Descriptions.Item>
            <Descriptions.Item label="生成状态">
              <Tag color={item.report.generateStatus === 'SUCCESS' ? 'success' : 'error'}>
                {businessLabel(item.report.generateStatus)}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="指标快照">
              <Space className="report-metrics-json" size={[6, 6]} wrap>
                {Object.entries(item.report.metrics).map(([key, value]) => (
                  <Tag key={key}>{metricLabels[key] ?? '业务指标'}：{String(value)}</Tag>
                ))}
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label="生成时间">{item.report.generatedAt}</Descriptions.Item>
          </Descriptions>
          <div className="report-snapshot-summary" aria-label="报表快照摘要">
            <Tag color="cyan">{snapshotMetricCount} 项确定性指标</Tag>
            <Typography.Text type="secondary">当前派生时点 {snapshotAsOf}</Typography.Text>
          </div>
          <label className="report-reason-field">
            <span>生成理由</span>
            <Input
              aria-label="生成理由"
              value={reason}
              maxLength={128}
              onChange={(event: { target: { value: string } }) => onReason(event.target.value)}
            />
          </label>
          <Space size={8} wrap>
            <Tooltip title={!canGenerate ? disabledReason : undefined}>
              <span>
                <Button
                  type="primary"
                  loading={pending}
                  disabled={!canGenerate || pending || !reason.trim()}
                  onClick={onRefresh}
                >
                  生成或刷新快照
                </Button>
              </span>
            </Tooltip>
            <Button onClick={onOpenMetrics}>查看指标口径</Button>
          </Space>
        </div>
      ) : <Typography.Text type="secondary">请选择一份报表查看严格字段。</Typography.Text>}
    </Card>
  );
}
