import { Button, Card, Space, Tag, Typography } from 'antd';

import type { ReportLedger } from '../reportTypes';

type ReportContextHeaderProps = {
  ledger: ReportLedger;
  activeScenarioId: string;
  asOf: string;
  resetting: boolean;
  onReset: () => void;
};

export default function ReportContextHeader({
  ledger,
  activeScenarioId,
  asOf,
  resetting,
  onReset,
}: ReportContextHeaderProps) {
  return (
    <Card className="report-context-card" size="small" aria-label="统计上下文">
      <div className="report-context-content">
        <div>
          <Space size={[6, 6]} wrap>
            <Tag color="processing">{activeScenarioId}</Tag>
            <Tag color="cyan">{ledger.disclosure}</Tag>
            <Tag>统计时点 {asOf}</Tag>
            <Tag>{ledger.filteredCount} / {ledger.totalCount} 份报表</Tag>
          </Space>
          {ledger.sourceContextLabels.length > 0 ? (
            <div className="report-context-labels">
              {ledger.sourceContextLabels.map((label) => <Tag key={label}>{label}</Tag>)}
            </div>
          ) : (
            <Typography.Text type="secondary">当前显示一作业区全部确定性统计事实</Typography.Text>
          )}
        </div>
        <Button loading={resetting} onClick={onReset}>重置到 SCN-01</Button>
      </div>
    </Card>
  );
}
