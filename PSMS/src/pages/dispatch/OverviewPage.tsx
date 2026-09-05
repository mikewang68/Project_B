import { Card, Progress, Space, Tag, Typography, message, theme } from 'antd';
import { useState } from 'react';
import { Link, createSearchParams, useLocation, useNavigate } from 'react-router-dom';

import type { PolicyContext, RoleCode } from '../../auth';
import { createC03Element } from '../../app/routeCatalog';
import PermissionGate from '../../components/PermissionGate';
import type { PublicErrorCode } from '../../contracts';
import DemoScenarioBar from '../../features/plan-entry/components/DemoScenarioBar';
import OverviewKpiGrid from '../../features/plan-entry/components/OverviewKpiGrid';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel, {
  type PageState,
} from '../../features/plan-entry/components/PageStatePanel';
import RiskInterfacePanel from '../../features/plan-entry/components/RiskInterfacePanel';
import YardSchematic from '../../features/plan-entry/components/YardSchematic';
import {
  parsePlanEntryQuery,
  selectInterfaceHealth,
  selectOpenRisks,
  selectOverviewKpis,
  selectPlanLedger,
  selectVisibleYardObjects,
  serializePlanEntryQuery,
  usePlanEntryReadState,
  type PlanEntryQuery,
  type PlanLedgerRowViewModel,
} from '../../features/plan-entry';
import '../../features/plan-entry/plan-entry.css';
import type { DemoScenario } from '../../mocks/fixtures';
import { businessLabel } from '../../presentation/businessCopy';
import { useDemoRuntime, useDemoSelector, usePlanEntryWorkflow } from '../../runtime';
import { selectActiveScenario, selectSession } from '../../stores';

type OverviewCssVariables = {
  [key: `--overview-${string}`]: string | number;
};

function selectRuntimeScenarios(state: Parameters<typeof selectOverviewKpis>[0]) {
  return state.scenario.scenarios;
}

function planLedgerHref(
  query: PlanEntryQuery,
  status: PlanLedgerRowViewModel['status'],
  planId: string,
): string {
  const search = createSearchParams(
    serializePlanEntryQuery({
      date: query.date,
      workArea: query.workArea,
      scenarioId: query.scenarioId,
      planBatchNo: '',
      trainNo: '',
      statuses: [status],
      exceptionTypes: [],
      page: 1,
      pageSize: 20,
      sort: 'updatedAt:desc',
      planId,
      from: 'overview',
    }),
  );
  return `/dispatch/plans?${search.toString()}`;
}

function gatedPlanLink(
  plan: PlanLedgerRowViewModel,
  href: string,
  policyContext: PolicyContext,
) {
  return createC03Element(
    PermissionGate,
    {
      permission: 'plan:view',
      context: { ...policyContext, permission: 'plan:view' },
      mode: 'hide',
    },
    <Link className="overview-plan-link" to={href} aria-label={plan.id} />,
  );
}

export type OverviewPageProps = {
  viewState?: 'data' | PageState;
  errorCode?: PublicErrorCode;
};

export default function OverviewPage({ viewState, errorCode }: OverviewPageProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const runtime = useDemoRuntime();
  const { token } = theme.useToken();
  const [messageApi, messageContext] = message.useMessage();
  const [scenarioLoading, setScenarioLoading] = useState(false);
  const [resetLoading, setResetLoading] = useState(false);
  const { readState, refresh } = usePlanEntryReadState(runtime.gateway.getOverview, {
    enabled: viewState === undefined,
  });
  const query = parsePlanEntryQuery(location.search);
  const rootState = useDemoSelector((state) => state);
  const workflow = usePlanEntryWorkflow((state) => state);
  const session = useDemoSelector(selectSession);
  const scenarios = useDemoSelector(selectRuntimeScenarios);
  const activeScenario = useDemoSelector(selectActiveScenario);
  const effectiveViewState = viewState ?? readState.state;
  const effectiveErrorCode = errorCode ?? readState.errorCode;
  const pageLoading = effectiveViewState === 'loading';
  const projections = {
    kpis: selectOverviewKpis(rootState, query, workflow),
    ledger: selectPlanLedger(rootState, query, workflow),
    yard: selectVisibleYardObjects(rootState, query),
    risks: selectOpenRisks(rootState, query),
    health: selectInterfaceHealth(rootState, workflow),
  };
  const policyContext: PolicyContext = {
    session,
    pageId: 'UI-001',
    permission: 'overview:view',
    objectScope: { type: 'AREA', value: query.workArea },
  };
  const pendingPlan = projections.ledger.items.find(
    ({ status }) => status === 'PENDING_CONFIRM',
  );
  const pendingPlansHref = pendingPlan
    ? planLedgerHref(query, 'PENDING_CONFIRM', pendingPlan.id)
    : undefined;
  const overviewStyle: OverviewCssVariables = {
    '--overview-color-primary': token.colorPrimary,
    '--overview-color-primary-bg': token.colorPrimaryBg,
    '--overview-color-bg-container': token.colorBgContainer,
    '--overview-color-border': token.colorBorder,
    '--overview-color-border-secondary': token.colorBorderSecondary,
    '--overview-color-text': token.colorText,
    '--overview-color-text-secondary': token.colorTextSecondary,
    '--overview-color-fill': token.colorFill,
    '--overview-color-fill-secondary': token.colorFillSecondary,
    '--overview-border-radius': `${token.borderRadiusLG}px`,
    '--overview-border-radius-small': `${token.borderRadiusSM}px`,
    '--overview-box-shadow': token.boxShadowTertiary,
  };

  const replaceScenarioQuery = (scenarioId: DemoScenario['id']): void => {
    const search = serializePlanEntryQuery({ ...query, scenarioId, planId: undefined }).toString();
    void navigate(
      { pathname: '/dispatch/overview', search: search ? `?${search}` : '' },
      { replace: true },
    );
  };

  const runScenarioReset = async (
    scenarioId: DemoScenario['id'],
    setLoading: (value: boolean) => void,
  ): Promise<void> => {
    setLoading(true);
    const result = await runtime.commands.resetScenario(scenarioId);
    setLoading(false);
    if (result.ok) {
      await refresh({ showLoading: false });
      replaceScenarioQuery(scenarioId);
      void messageApi.success(`演示场景已重置为 ${scenarioId}`);
    } else {
      void messageApi.error(`${result.errorCode}: ${result.message}`);
    }
  };

  const switchRole = (roleCode: RoleCode): void => {
    runtime.commands.switchRole(roleCode);
    void messageApi.success(`当前角色已切换为${businessLabel(roleCode)}`);
  };

  return (
    <main className="dispatch-overview-page" style={overviewStyle}>
      {messageContext}
      <header className="overview-page-header">
        <PageIdentity pageId="UI-001" name="调度总览" path="/dispatch/overview" />
        <DemoScenarioBar
          date={query.date}
          workArea={query.workArea}
          role={session.roleCode}
          scenarioId={activeScenario.id}
          scenarios={scenarios}
          demoTime={session.demoTime}
          policyContext={policyContext}
          scenarioLoading={pageLoading || scenarioLoading}
          resetLoading={pageLoading || resetLoading}
          onScenarioChange={
            pageLoading
              ? undefined
              : (scenarioId) => {
                  void runScenarioReset(scenarioId, setScenarioLoading);
                }
          }
          onRoleChange={pageLoading ? undefined : switchRole}
          onReset={
            pageLoading
              ? undefined
              : () => {
                  void runScenarioReset(activeScenario.id, setResetLoading);
                }
          }
        />
      </header>

      {effectiveViewState === 'network-error' ? (
        <>
          <PageStatePanel
            state="network-error"
            errorCode={effectiveErrorCode}
            recoveryLabel="重试"
            onRecover={() => {
              void refresh();
            }}
          />
          <RiskInterfacePanel health={projections.health} risks={projections.risks} />
        </>
      ) : effectiveViewState !== 'data' ? (
        <PageStatePanel
          state={effectiveViewState}
          errorCode={effectiveErrorCode}
          recoveryLabel="重试"
          onRecover={() => {
            void refresh();
          }}
        />
      ) : (
        <>
          <OverviewKpiGrid
            kpis={projections.kpis}
            pendingPlansHref={pendingPlansHref}
            policyContext={policyContext}
          />

          <div className="overview-main-grid">
        <Card
          className="overview-section-card overview-plan-card"
          title="重点计划"
          extra={<Typography.Text type="secondary">计划进度</Typography.Text>}
        >
          <section aria-label="重点计划">
            {projections.ledger.items.length === 0 ? (
              <PageStatePanel state="empty" />
            ) : (
              <div className="overview-plan-table-scroll">
                <table className="overview-plan-table">
                  <thead>
                    <tr>
                      <th scope="col">计划 / 车次</th>
                      <th scope="col">股道 / 货类</th>
                      <th scope="col">计划进度</th>
                      <th scope="col">状态</th>
                      <th scope="col">风险</th>
                    </tr>
                  </thead>
                  <tbody>
                    {projections.ledger.items.map((plan) => {
                      const href = planLedgerHref(query, plan.status, plan.id);
                      return (
                        <tr key={plan.id}>
                          <td className="overview-plan-primary">
                            <Typography.Text className="overview-plan-id">
                              {plan.id}
                            </Typography.Text>
                            <span className="overview-plan-reference">
                              <Typography.Text type="secondary">
                                {plan.planBatchNo}
                              </Typography.Text>
                              <Typography.Text type="secondary">{plan.trainNo}</Typography.Text>
                            </span>
                            {gatedPlanLink(plan, href, policyContext)}
                          </td>
                          <td>
                            <Space orientation="vertical" size={2}>
                              <Typography.Text>{plan.trackNo ?? '待补充'}</Typography.Text>
                              <Typography.Text type="secondary" title={businessLabel(plan.cargoType)}>
                                {businessLabel(plan.cargoType)}
                              </Typography.Text>
                            </Space>
                          </td>
                          <td className="overview-plan-progress">
                            <Progress
                              aria-label={`${plan.id} 计划进度`}
                              percent={plan.progress.percent}
                              size="small"
                              status={plan.progress.status}
                              format={() => plan.progress.label}
                            />
                          </td>
                          <td>
                            <Tag color={plan.statusTone} title={businessLabel(plan.status)}>
                              {businessLabel(plan.status)}
                            </Tag>
                          </td>
                          <td className="overview-plan-risk">
                            <Tag color={plan.risk.tone}>{plan.risk.label}</Tag>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </Card>

        <RiskInterfacePanel health={projections.health} risks={projections.risks} />
          </div>

          <YardSchematic yard={projections.yard} />
        </>
      )}
    </main>
  );
}
