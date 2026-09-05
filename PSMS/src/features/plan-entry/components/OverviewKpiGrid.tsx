import { Card, Statistic, Typography } from 'antd';
import { Link } from 'react-router-dom';

import type { PolicyContext } from '../../../auth';
import { createC03Element } from '../../../app/routeCatalog';
import PermissionGate from '../../../components/PermissionGate';
import type { OverviewKpisViewModel } from '../types';

export type OverviewKpiGridProps = {
  kpis: OverviewKpisViewModel;
  pendingPlansHref?: string;
  policyContext: PolicyContext;
};

type KpiCardProps = {
  label: string;
  value: number;
  suffix?: string;
};

function KpiCard({ label, value, suffix }: KpiCardProps) {
  return (
    <Card className="overview-kpi-card" size="small">
      <div aria-label={`${label}值`}>
        <Statistic title={label} value={value} suffix={suffix} />
      </div>
      <Typography.Text type="secondary" className="overview-kpi-caption">
        实时业务投影
      </Typography.Text>
    </Card>
  );
}

export default function OverviewKpiGrid({
  kpis,
  pendingPlansHref,
  policyContext,
}: OverviewKpiGridProps) {
  const pendingLink = pendingPlansHref
    ? createC03Element(
        PermissionGate,
        {
          permission: 'plan:view',
          context: { ...policyContext, permission: 'plan:view' },
          mode: 'hide',
        },
        <Link
          to={pendingPlansHref}
          className="overview-card-hit-area"
          aria-label="待确认计划"
        />,
      )
    : null;

  return (
    <section aria-label="调度指标" className="overview-kpi-grid">
      <div className="overview-kpi-cards">
        <div>
          <KpiCard label="当日计划" value={kpis.totalPlans} />
        </div>
        <div>
          <div className="overview-linked-card">
            <KpiCard label="待确认计划" value={kpis.pendingConfirmPlans} />
            {pendingLink}
          </div>
        </div>
        <div>
          <KpiCard label="已完成工单" value={kpis.completedWorkOrders} />
        </div>
        <div>
          <KpiCard label="车辆等待" value={kpis.waitingVehicles} />
        </div>
        <div>
          <KpiCard
            label="设备可用率"
            value={kpis.availableResourceRate}
            suffix="%"
          />
        </div>
        <div>
          <KpiCard label="未闭环异常" value={kpis.openExceptions} />
        </div>
      </div>
    </section>
  );
}
