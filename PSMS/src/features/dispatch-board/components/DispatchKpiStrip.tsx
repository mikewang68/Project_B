import { Card, Statistic } from 'antd';

import type { DispatchKpis } from '../types';

const items: ReadonlyArray<readonly [keyof DispatchKpis, string]> = [
  ['ready', '就绪'],
  ['assigned', '已绑定'],
  ['dispatched', '已下发'],
  ['executing', '执行中'],
  ['completed', '已完成'],
  ['exceptionEntry', '异常入口'],
];

export default function DispatchKpiStrip({ kpis }: { kpis: DispatchKpis }) {
  return (
    <section aria-label="派工指标" className="dispatch-kpi-strip">
      {items.map(([key, label]) => (
        <Card key={key} size="small" className="dispatch-kpi-card">
          <Statistic title={label} value={kpis[key]} />
        </Card>
      ))}
    </section>
  );
}
