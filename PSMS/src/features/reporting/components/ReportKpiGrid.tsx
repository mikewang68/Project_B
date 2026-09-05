import { Card, Statistic } from 'antd';

import type { ReportKpis } from '../reportTypes';

const kpiCatalog: ReadonlyArray<readonly [keyof ReportKpis, string, string]> = [
  ['planTotal', '计划总数', '计划'],
  ['confirmedPlanCount', '已确认计划数', '计划'],
  ['appliedRecommendationCount', '推荐已应用数', '条'],
  ['generatedWorkOrderCount', '已生成任务数', '单'],
  ['dispatchedWorkOrderCount', '已派工任务数', '单'],
  ['exceptionCount', '异常数量', '项'],
  ['interlockCount', '安全联锁数量', '项'],
  ['mergedOfflinePacketCount', '离线包已合并数量', '包'],
];

export default function ReportKpiGrid({ kpis }: { kpis: ReportKpis }) {
  return (
    <section className="report-kpi-grid" aria-label="运行结果指标">
      {kpiCatalog.map(([key, label, suffix], index) => (
        <Card className={`report-kpi-card report-kpi-tone-${index % 4}`} size="small" key={key}>
          <Statistic title={label} value={kpis[key]} suffix={suffix} />
        </Card>
      ))}
    </section>
  );
}
