import { Card, Select, Space, Tag, Typography, message, theme } from 'antd';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { routePolicies, type PolicyContext, type RoleCode } from '../../auth';
import type { PublicErrorCode } from '../../contracts';
import InterfaceHealthBanner from '../../features/plan-entry/components/InterfaceHealthBanner';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel, {
  type PageState,
} from '../../features/plan-entry/components/PageStatePanel';
import PlanDetailDrawer from '../../features/plan-entry/components/PlanDetailDrawer';
import PlanFilterBar from '../../features/plan-entry/components/PlanFilterBar';
import PlanLedgerTable from '../../features/plan-entry/components/PlanLedgerTable';
import type {
  SupplementPlanResult,
  SupplementPlanValues,
} from '../../features/plan-entry/components/SupplementPlanForm';
import {
  parsePlanEntryQuery,
  selectInterfaceHealth,
  selectPlanDetails,
  selectPlanLedger,
  selectValidationIssues,
  selectVisibleYardObjects,
  serializePlanEntryQuery,
  usePlanEntryReadState,
  type PlanEntryQuery,
} from '../../features/plan-entry';
import '../../features/plan-entry/plan-entry.css';
import {
  useDemoRuntime,
  useDemoSelector,
  usePlanEntryWorkflow,
} from '../../runtime';
import { businessLabel } from '../../presentation/businessCopy';
import { selectSession } from '../../stores';

export type PlanLedgerPageProps = {
  viewState?: 'data' | PageState;
  errorCode?: PublicErrorCode;
};

type LedgerCssVariables = {
  [key: `--overview-${string}`]: string | number;
};

export default function PlanLedgerPage({
  viewState,
  errorCode,
}: PlanLedgerPageProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const runtime = useDemoRuntime();
  const { token } = theme.useToken();
  const [messageApi, messageContext] = message.useMessage();
  const [syncLoading, setSyncLoading] = useState(false);
  const [recoverLoading, setRecoverLoading] = useState(false);
  const [supplementLoading, setSupplementLoading] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [interfaceError, setInterfaceError] = useState<Readonly<{
    errorCode?: PublicErrorCode;
    message: string;
  }>>();
  const [supplementError, setSupplementError] = useState<Readonly<{
    errorCode?: PublicErrorCode;
    message: string;
  }>>();
  const [confirmError, setConfirmError] = useState<Readonly<{
    errorCode?: PublicErrorCode;
    message: string;
  }>>();
  const [supplementValues, setSupplementValues] = useState<
    Readonly<Record<string, SupplementPlanValues>>
  >({});
  const { readState, refresh } = usePlanEntryReadState(runtime.gateway.getPlans, {
    enabled: viewState === undefined,
    recoverableErrorCode: 'TOS-EXT-002',
  });
  const query = parsePlanEntryQuery(location.search);
  const rootState = useDemoSelector((state) => state);
  const session = useDemoSelector(selectSession);
  const workflow = usePlanEntryWorkflow((state) => state);
  const ledger = selectPlanLedger(rootState, query, workflow);
  const health = selectInterfaceHealth(rootState, workflow);
  const yard = selectVisibleYardObjects(rootState, query);
  const validationIssues = selectValidationIssues(rootState, query, workflow);
  const details = query.planId
    ? selectPlanDetails(rootState, query, query.planId, workflow)
    : undefined;
  const policyContext: PolicyContext = {
    session,
    pageId: 'UI-002',
    permission: 'plan:view',
    objectScope: { type: 'AREA', value: query.workArea },
  };
  const pageStyle: LedgerCssVariables = {
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

  const replaceQuery = (nextQuery: PlanEntryQuery): void => {
    const search = serializePlanEntryQuery(nextQuery).toString();
    void navigate(
      { pathname: '/dispatch/plans', search: search ? `?${search}` : '' },
      { replace: true },
    );
  };

  const resetFilters = (): void => {
    replaceQuery({
      ...query,
      planBatchNo: '',
      trainNo: '',
      statuses: [],
      exceptionTypes: [],
      page: 1,
      sort: 'updatedAt:desc',
      planId: undefined,
    });
  };

  const openPlan = (planId: string): void => {
    runtime.workflow.selectPlan(planId);
    replaceQuery({ ...query, planId });
  };

  const closePlan = (): void => {
    runtime.workflow.selectPlan(undefined);
    replaceQuery({ ...query, planId: undefined });
  };

  useEffect(() => {
    runtime.workflow.selectPlan(query.planId);
  }, [query.planId, runtime.workflow]);

  const effectiveViewState = viewState ?? readState.state;
  const effectiveErrorCode = errorCode ?? readState.errorCode;
  const nonDataState = effectiveViewState === 'data' ? undefined : effectiveViewState;
  const visibleDetails = effectiveViewState === 'data' ? details : undefined;
  const selectedNotFound = effectiveViewState === 'data' && query.planId && !details;
  const pageLoading = effectiveViewState === 'loading';

  const syncPlans = async (): Promise<void> => {
    setSyncLoading(true);
    setInterfaceError(undefined);
    const result = await runtime.commands.syncPlans(rootState.scenario.activeScenarioId);
    setSyncLoading(false);
    if (result.ok) {
      await refresh({ showLoading: false });
      void messageApi.success('计划同步成功');
    } else {
      const feedback = { errorCode: result.errorCode, message: result.message };
      setInterfaceError(feedback);
      void messageApi.error(`${result.errorCode}: ${result.message}`);
    }
  };

  const recoverInterface = async (): Promise<void> => {
    setRecoverLoading(true);
    setInterfaceError(undefined);
    const result = await runtime.commands.resetScenario('SCN-01');
    setRecoverLoading(false);
    if (result.ok) {
      await refresh({ showLoading: false });
      replaceQuery({ ...query, scenarioId: 'SCN-01', planId: undefined });
      void messageApi.success('接口已恢复并重置到 SCN-01');
    } else {
      const feedback = { errorCode: result.errorCode, message: result.message };
      setInterfaceError(feedback);
      void messageApi.error(`${result.errorCode}: ${result.message}`);
    }
  };

  const supplementPlan = async (
    planId: string,
    values: SupplementPlanValues,
  ): Promise<SupplementPlanResult> => {
    setSupplementLoading(true);
    setSupplementError(undefined);
    const result = await runtime.commands.supplementPlan(planId, values);
    setSupplementLoading(false);
    if (result.ok) {
      setSupplementValues((current: Readonly<Record<string, SupplementPlanValues>>) => ({
        ...current,
        [planId]: structuredClone(values),
      }));
      await refresh({ showLoading: false });
      void messageApi.success('计划字段补录成功');
      return { ok: true };
    }
    const feedback = { errorCode: result.errorCode, message: result.message };
    setSupplementError(feedback);
    return { ok: false, ...feedback };
  };

  const confirmPlan = async (planId: string): Promise<void> => {
    setConfirmLoading(true);
    setConfirmError(undefined);
    const result = await runtime.commands.confirmPlan(planId, supplementValues[planId]);
    setConfirmLoading(false);
    if (result.ok) {
      await refresh({ showLoading: false });
      void messageApi.success('计划确认成功');
    } else {
      const feedback = { errorCode: result.errorCode, message: result.message };
      setConfirmError(feedback);
      void messageApi.error(`${result.errorCode}: ${result.message}`);
    }
  };

  return (
    <main className="plan-ledger-page" style={pageStyle}>
      {messageContext}
      <header className="plan-ledger-header">
        <PageIdentity pageId="UI-002" name="外部到发信息台账" path="/dispatch/plans" />
        <Space size={[8, 6]} wrap>
          <Tag>{query.date}</Tag>
          <Tag title={businessLabel(query.workArea)}>{businessLabel(query.workArea)}</Tag>
          <Tag color="processing">{query.scenarioId}</Tag>
          <Space size={4}>
            <Typography.Text type="secondary">当前角色</Typography.Text>
            <Select<RoleCode>
              aria-label="当前角色"
              value={session.roleCode}
              onChange={(roleCode: RoleCode) => runtime.commands.switchRole(roleCode)}
              options={routePolicies['UI-002'].map((roleCode) => ({
                value: roleCode,
                label: businessLabel(roleCode),
              }))}
            />
          </Space>
        </Space>
      </header>

      <InterfaceHealthBanner
        health={health}
        policyContext={policyContext}
        syncLoading={pageLoading || syncLoading}
        recoverLoading={pageLoading || recoverLoading}
        commandError={interfaceError}
        onSync={() => {
          void syncPlans();
        }}
        onRecover={() => {
          void recoverInterface();
        }}
      />

      <Card className="plan-filter-card">
        <PlanFilterBar query={query} onChange={replaceQuery} />
      </Card>

      <Card
        className="plan-ledger-card"
        title="计划台账"
        extra={<Typography.Text type="secondary">每页 20 条</Typography.Text>}
      >
        {nonDataState ? (
          <PageStatePanel
            state={nonDataState}
            errorCode={effectiveErrorCode}
            recoveryLabel={
              nonDataState === 'empty'
                ? '重置筛选'
                : nonDataState === 'not-found'
                  ? '返回列表'
                  : '重试'
            }
            onRecover={
              nonDataState === 'empty' || nonDataState === 'not-found'
                ? resetFilters
                : () => {
                    void refresh();
                  }
            }
          />
        ) : selectedNotFound ? (
          <PageStatePanel state="not-found" recoveryLabel="返回列表" onRecover={closePlan} />
        ) : ledger.items.length === 0 ? (
          <PageStatePanel state="empty" recoveryLabel="重置筛选" onRecover={resetFilters} />
        ) : (
          <PlanLedgerTable
            ledger={ledger}
            policyContext={policyContext}
            onOpen={openPlan}
            onPageChange={(page) => replaceQuery({ ...query, page, planId: undefined })}
          />
        )}
      </Card>

      <PlanDetailDrawer
        open={visibleDetails !== undefined}
        details={visibleDetails}
        policyContext={policyContext}
        tracks={yard.tracks}
        supplementLoading={pageLoading || supplementLoading}
        confirmLoading={pageLoading || confirmLoading}
        confirmDisabled={
          visibleDetails === undefined ||
          validationIssues.some(({ planId }) => planId === visibleDetails.id)
        }
        supplementError={supplementError}
        confirmError={confirmError}
        onClose={closePlan}
        onSupplement={
          visibleDetails
            ? (values) => supplementPlan(visibleDetails.id, values)
            : undefined
        }
        onConfirm={visibleDetails ? () => confirmPlan(visibleDetails.id) : undefined}
      />
    </main>
  );
}
