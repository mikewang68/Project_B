import { Card, Statistic } from 'antd';

import type { AuditKpis } from '../auditTypes';

export default function AuditKpiGrid({
  kpis,
  recentTraceId,
}: Readonly<{ kpis: AuditKpis; recentTraceId?: string }>) {
  const cards = [
    ['领域审计记录', kpis.total],
    ['显式成功', kpis.success],
    ['权限拒绝', kpis.denied],
    ['版本冲突', kpis.versionConflict],
    ['幂等命中', kpis.idempotentHit],
    ['业务错误', kpis.businessError],
    ['最近链路编号', recentTraceId ?? kpis.recentTraceId ?? '未提供'],
  ] as const;
  return (
    <section className="audit-kpi-grid" aria-label="审计指标">
      {cards.map(([title, value], index) => (
        <Card className={`audit-kpi-card audit-kpi-tone-${index}`} size="small" key={title}>
          <Statistic title={title} value={value} />
        </Card>
      ))}
    </section>
  );
}
