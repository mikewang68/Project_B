import { Card, Statistic } from 'antd';

import type { ExceptionKpis } from '../types';

const entries: ReadonlyArray<readonly [keyof ExceptionKpis, string]> = [
  ['unacknowledged', '未确认'],
  ['handling', '处理中'],
  ['pendingReview', '待复核'],
  ['closed', '已关闭'],
  ['overdue', '超期'],
  ['interlock', '联锁类'],
];

export default function ExceptionKpiStrip({ kpis }: { kpis: ExceptionKpis }) {
  return (
    <div className="exception-kpi-strip" aria-label="异常处置指标">
      {entries.map(([key, label]) => (
        <Card key={key} size="small" className="exception-kpi-card">
          <Statistic title={label} value={kpis[key]} />
        </Card>
      ))}
    </div>
  );
}
