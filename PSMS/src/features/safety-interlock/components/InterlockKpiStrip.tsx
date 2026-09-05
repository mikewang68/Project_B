import { Card, Statistic } from 'antd';

import type { InterlockKpis } from '../types';

const entries: ReadonlyArray<readonly [keyof InterlockKpis, string]> = [
  ['locked', '锁定中'],
  ['pendingApproval', '待审批'],
  ['approved', '已批准'],
  ['restored', '已恢复'],
  ['forceStop', '强制停机'],
  ['receiptFailed', '回执失败'],
];

export default function InterlockKpiStrip({ kpis }: { kpis: InterlockKpis }) {
  return (
    <div className="interlock-kpi-strip" aria-label="安全联锁指标">
      {entries.map(([key, label]) => (
        <Card key={key} size="small" className="interlock-kpi-card">
          <Statistic title={label} value={kpis[key]} />
        </Card>
      ))}
    </div>
  );
}
