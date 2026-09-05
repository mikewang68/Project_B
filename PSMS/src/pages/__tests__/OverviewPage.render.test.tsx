import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import { createFixtureSnapshot, type DemoScenario } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

const LAZY_ROUTE_TIMEOUT = { timeout: 5_000 } as const;

type RuntimeOptions = {
  roleCode?: RoleCode;
  dataScope?: string[];
  scenarioId?: DemoScenario['id'];
  fetcher?: typeof fetch;
};

function overviewResponse(data: Record<string, unknown> = {}): Response {
  const envelope: ApiSuccessEnvelope = {
    ok: true,
    data,
    traceId: 'TRACE-OVERVIEW-READ',
    auditLogId: 'AUD-OVERVIEW-READ',
  };
  return new Response(JSON.stringify(envelope), {
    headers: { 'content-type': 'application/json' },
  });
}

function overviewErrorResponse(errorCode: ApiErrorEnvelope['errorCode']): Response {
  const envelope: ApiErrorEnvelope = {
    ok: false,
    errorCode,
    message: `Overview read failed with ${errorCode}`,
    traceId: 'TRACE-OVERVIEW-ERROR',
    auditLogId: 'AUD-OVERVIEW-ERROR',
  };
  return new Response(JSON.stringify(envelope), {
    status: 409,
    headers: { 'content-type': 'application/json' },
  });
}

function createInjectedRuntime({
  roleCode = 'DISPATCHER',
  dataScope = ['AREA-A'],
  scenarioId = 'SCN-01',
  fetcher = vi.fn(async () => overviewResponse()),
}: RuntimeOptions = {}) {
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({ actorId: `E2E-${roleCode}`, roleCode, dataScope, online: true }),
  );
  const runtime = createDemoRuntime(fetcher);
  runtime.store.resetFromSnapshot(createFixtureSnapshot(), {
    actorId: `E2E-${roleCode}`,
    roleCode,
    dataScope,
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId,
  });
  return runtime;
}

function renderOverview(
  initialEntry = '/dispatch/overview?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01',
  options: RuntimeOptions = {},
) {
  const runtime = createInjectedRuntime(options);
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] });
  return render(<App router={router} runtime={runtime} />);
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-001 调度总览内容', () => {
  it('keeps the plan primary cell in table layout and clamps the first resource marker inside the schematic', async () => {
    renderOverview();
    await screen.findByRole('heading', { name: '调度总览' }, LAZY_ROUTE_TIMEOUT);

    const planCell = within(await screen.findByLabelText('重点计划'))
      .getByRole('link', { name: 'PLAN-001' })
      .closest('td');
    expect(planCell).toBeInstanceOf(HTMLTableCellElement);
    expect(window.getComputedStyle(planCell!).display).toBe('table-cell');

    const firstResource = within(screen.getByLabelText('场区态势')).getByLabelText(
      '资源 RESOURCE-001',
    );
    expect(window.getComputedStyle(firstResource).transform).toBe('translateX(-40%)');
  });

  it('呈现 C01 标记、场景摘要、六项 KPI 和完整重点计划', async () => {
    renderOverview();

    expect(
      await screen.findByRole('heading', { name: '调度总览', level: 2 }, LAZY_ROUTE_TIMEOUT),
    ).toBeVisible();
    expect(screen.getByText('UI-001')).toBeVisible();
    expect(screen.getByText('当前路由：/dispatch/overview')).toBeVisible();

    const scenarioBar = screen.getByLabelText('演示上下文');
    expect(within(scenarioBar).getByText('2026-07-16')).toBeVisible();
    expect(within(scenarioBar).getByText('一作业区')).toBeVisible();
    expect(within(scenarioBar).getByText('货场调度员')).toBeVisible();
    expect(within(scenarioBar).queryByText('DISPATCHER', { exact: true })).not.toBeInTheDocument();
    expect(within(scenarioBar).getByText('SCN-01 · 正常整列')).toBeVisible();
    expect(within(scenarioBar).getByText('2026-07-16 09:00:00')).toBeVisible();

    const kpis = screen.getByLabelText('调度指标');
    expect(within(kpis).getByText('当日计划')).toBeVisible();
    expect(within(kpis).getByLabelText('当日计划值')).toHaveTextContent('3');
    expect(within(kpis).getByText('待确认计划')).toBeVisible();
    expect(within(kpis).getByLabelText('待确认计划值')).toHaveTextContent('1');
    expect(within(kpis).getByText('已完成工单')).toBeVisible();
    expect(within(kpis).getByLabelText('已完成工单值')).toHaveTextContent('1');
    expect(within(kpis).getByText('车辆等待')).toBeVisible();
    expect(within(kpis).getByLabelText('车辆等待值')).toHaveTextContent('1');
    expect(within(kpis).getByText('设备可用率')).toBeVisible();
    expect(within(kpis).getByLabelText('设备可用率值')).toHaveTextContent('25%');
    expect(within(kpis).getByText('未闭环异常')).toBeVisible();
    expect(within(kpis).getByLabelText('未闭环异常值')).toHaveTextContent('4');

    const plans = screen.getByLabelText('重点计划');
    expect(within(plans).getByText('计划进度')).toBeVisible();
    expect(within(plans).getAllByRole('columnheader').map(({ textContent }) => textContent)).toEqual([
      '计划 / 车次',
      '股道 / 货类',
      '计划进度',
      '状态',
      '风险',
    ]);
    expect(within(plans).getByRole('link', { name: 'PLAN-001' })).toBeVisible();
    expect(within(plans).getByText('75001')).toBeVisible();
    expect(within(plans).getByText('等待确认')).toBeVisible();
    expect(within(plans).getByText('待确认')).toBeVisible();
    expect(within(plans).getByRole('link', { name: 'PLAN-002' })).toBeVisible();
    expect(within(plans).getByText('75002')).toBeVisible();
    expect(within(plans).getByText('已阻断')).toBeVisible();
    expect(within(plans).getByRole('link', { name: 'PLAN-003' })).toBeVisible();
    expect(within(plans).getByText('75003')).toBeVisible();
    expect(within(plans).getAllByText('已接收')).toHaveLength(2);
    expect(within(plans).queryByText('PENDING_CONFIRM', { exact: true })).not.toBeInTheDocument();
    expect(within(plans).queryByText('BLOCKED', { exact: true })).not.toBeInTheDocument();
    expect(within(plans).queryByText('RECEIVED', { exact: true })).not.toBeInTheDocument();
  });

  it('呈现接口、全部风险来源以及每条股道和车辆标记', async () => {
    renderOverview();
    await screen.findByRole('heading', { name: '调度总览' }, LAZY_ROUTE_TIMEOUT);

    const interfaceStatus = screen.getByLabelText('接口状态');
    expect(within(interfaceStatus).getByText('接口健康')).toBeVisible();
    expect(within(interfaceStatus).getByText('计划接口')).toBeVisible();
    expect(within(interfaceStatus).getByText('正常')).toBeVisible();
    expect(within(interfaceStatus).getByText('重试 0 次')).toBeVisible();
    expect(within(interfaceStatus).getByText('未熔断')).toBeVisible();

    const risks = screen.getByLabelText('风险待办');
    expect(within(risks).getByText('风险待办')).toBeVisible();
    expect(within(risks).getByLabelText('风险 EX-001')).toHaveTextContent(
      'EX-001设备离线提示待处理',
    );
    expect(within(risks).getByLabelText('风险 IL-001')).toHaveTextContent(
      'IL-001人员侵入预警联锁锁定',
    );
    expect(within(risks).getByLabelText('风险 OFF-001')).toHaveTextContent(
      'OFF-001离线数据包重大版本冲突',
    );

    const yard = screen.getByLabelText('场区态势');
    expect(within(yard).getByText('场区态势')).toBeVisible();
    const trackT1 = within(yard).getByLabelText('股道 T1');
    expect(trackT1).toHaveTextContent('空闲');
    expect(trackT1).toHaveTextContent('暂无当前占用');
    expect(trackT1).not.toHaveTextContent('75001');
    expect(within(yard).getByLabelText('股道 T2')).toHaveTextContent('当前占用');
    expect(within(yard).getByLabelText('股道 T3')).toHaveTextContent('释放中');
    expect(within(yard).getByLabelText('股道 T4')).toHaveTextContent('封锁');
    expect(within(yard).queryByText(/列车 7500/)).not.toBeInTheDocument();

    for (const [id, text] of [
      ['RESOURCE-001', '翻车机可用YARD-01'],
      ['RESOURCE-004', '筒仓维护中YARD-04'],
      ['RESOURCE-007', '翻车机忙碌YARD-07'],
      ['RESOURCE-010', '筒仓联锁锁定YARD-10'],
    ] as const) {
      expect(within(yard).getByLabelText(`资源 ${id}`)).toHaveTextContent(text);
    }
    const appointmentList = within(yard).getByRole('list', {
      name: '预约车辆标记',
    });
    expect(within(appointmentList).getAllByRole('listitem')).toHaveLength(6);
    for (const [vehicleNo, status, queueNo] of [
      ['川A·样车01', '排队中', 'Q01'],
      ['川A·样车02', '已叫号', 'Q02'],
      ['川A·样车03', '已入场', 'Q03'],
      ['川A·样车04', '作业中', 'Q04'],
      ['川A·样车05', '已放行', 'Q05'],
      ['川A·样车06', '已离场', 'Q06'],
    ] as const) {
      const marker = within(appointmentList).getByLabelText(`车辆 ${vehicleNo}`);
      expect(marker).toHaveTextContent(
        `${vehicleNo}${status}${queueNo}`,
      );
      expect(marker).not.toHaveAttribute('data-position');
      expect(marker).not.toHaveAttribute('style');
    }
  });

  it('uses API-001 pending then data state exactly once and never imports GET objects into Store', async () => {
    let release: ((value: Response) => void) | undefined;
    const fetcher = vi.fn(
      () => new Promise<Response>((resolve) => {
        release = resolve;
      }),
    );
    const runtime = createInjectedRuntime({ fetcher: fetcher as typeof fetch });
    const plansBefore = structuredClone(runtime.store.getState().plan);
    const router = createMemoryRouter(appRoutes, {
      initialEntries: [
        '/dispatch/overview?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01',
      ],
    });
    render(<App router={router} runtime={runtime} />);

    expect(await screen.findByLabelText('页面加载中', {}, LAZY_ROUTE_TIMEOUT)).toBeVisible();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith('/mock/overview', undefined);

    act(() => {
      release?.(
        overviewResponse({
          items: [{ id: 'PLAN-OVERVIEW-MUST-NOT-WIN', status: 'CANCELLED', version: 999 }],
        }),
      );
    });
    expect(await screen.findByLabelText('重点计划', {}, LAZY_ROUTE_TIMEOUT)).toBeVisible();
    expect(runtime.store.getState().plan).toEqual(plansBefore);
    expect(JSON.stringify(runtime.store.getState().plan)).not.toContain(
      'PLAN-OVERVIEW-MUST-NOT-WIN',
    );
  });

  it('maps a structured API-001 business error without rendering Store projections', async () => {
    const fetcher = vi.fn(async () => overviewErrorResponse('TOS-AUTH-001'));
    renderOverview(undefined, { fetcher: fetcher as typeof fetch });

    expect(await screen.findByText('业务处理失败', {}, LAZY_ROUTE_TIMEOUT)).toBeVisible();
    expect(screen.getByText('TOS-AUTH-001')).toBeVisible();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText('调度指标')).not.toBeInTheDocument();
  });

  it('keeps SCN-03 interface health, last success, risk, and one retry beside the API-001 network panel', async () => {
    const fetcher = vi.fn(async () => overviewErrorResponse('TOS-EXT-001'));
    const runtime = createInjectedRuntime({
      scenarioId: 'SCN-03',
      fetcher: fetcher as typeof fetch,
    });
    runtime.workflow.recordSuccess('2026-07-16T08:59:00+08:00');
    const router = createMemoryRouter(appRoutes, {
      initialEntries: [
        '/dispatch/overview?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-03',
      ],
    });
    render(<App router={router} runtime={runtime} />);

    expect(
      await screen.findByText('计划接口暂不可用', {}, LAZY_ROUTE_TIMEOUT),
    ).toBeVisible();
    expect(screen.getAllByText('TOS-EXT-001').length).toBeGreaterThan(0);
    const interfaceStatus = screen.getByLabelText('接口状态');
    expect(within(interfaceStatus).getByText('不可用')).toBeVisible();
    expect(within(interfaceStatus).getByText('2026-07-16 08:59:00')).toBeVisible();
    expect(
      within(screen.getByLabelText('风险待办')).getByLabelText('风险 FAULT-SCN-03'),
    ).toHaveTextContent('接口超时重大待处理模拟场景TOS-EXT-001');
    expect(screen.getAllByRole('button', { name: /重\s*试/ })).toHaveLength(1);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['SCN-03', '接口超时', '不可用', 'INTERFACE_TIMEOUT', 'TOS-EXT-001'],
    ['SCN-04', '设备离线', '降级', 'DEVICE_OFFLINE', 'TOS-WO-001'],
    ['SCN-05', '人员侵入', '降级', 'INTERLOCK_FORCE_STOP', 'TOS-IL-001'],
  ] as const)(
    '投影 %s 的风险和接口状态',
    async (scenarioId, scenarioName, healthLabel, faultType, errorCode) => {
      renderOverview(
        `/dispatch/overview?date=2026-07-16&workArea=AREA-A&scenarioId=${scenarioId}`,
        { scenarioId },
      );
      await screen.findByRole('heading', { name: '调度总览' }, LAZY_ROUTE_TIMEOUT);

      expect(screen.getByText(`${scenarioId} · ${scenarioName}`)).toBeVisible();
      const interfaceStatus = screen.getByLabelText('接口状态');
      expect(within(interfaceStatus).getByText(healthLabel)).toBeVisible();
      expect(within(interfaceStatus).getByText(errorCode)).toBeVisible();
      const scenarioRisk = within(screen.getByLabelText('风险待办')).getByLabelText(
        `风险 FAULT-${scenarioId}`,
      );
      expect(scenarioRisk).toHaveTextContent(
        faultType === 'INTERFACE_TIMEOUT'
          ? '接口超时'
          : faultType === 'DEVICE_OFFLINE'
            ? '设备离线'
            : '联锁强制停机',
      );
      expect(scenarioRisk).toHaveTextContent(errorCode);
    },
  );
});

describe('PageStatePanel 直接状态投影', () => {
  it('呈现 loading', () => {
    render(<PageStatePanel state="loading" />);
    expect(screen.getByLabelText('页面加载中')).toBeVisible();
  });

  it('呈现 empty', () => {
    render(<PageStatePanel state="empty" />);
    expect(screen.getByText('当前作业区暂无可见计划')).toBeVisible();
  });

  it('呈现 business-error 及公开错误码', () => {
    render(<PageStatePanel state="business-error" errorCode="TOS-EXT-002" />);
    expect(screen.getByText('业务处理失败')).toBeVisible();
    expect(screen.getByText('TOS-EXT-002')).toBeVisible();
  });

  it('呈现 network-error 且未注入恢复命令时禁用恢复入口', () => {
    render(
      <PageStatePanel
        state="network-error"
        errorCode="TOS-EXT-001"
        recoveryLabel="重试"
      />,
    );
    expect(screen.getByText('计划接口暂不可用')).toBeVisible();
    expect(screen.getByText('TOS-EXT-001')).toBeVisible();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeDisabled();
  });

  it('呈现 not-found 并仅调用注入的恢复命令', async () => {
    const user = userEvent.setup();
    const onRecover = vi.fn();
    render(
      <PageStatePanel state="not-found" recoveryLabel="返回列表" onRecover={onRecover} />,
    );

    expect(screen.getByText('对象已变化或不存在')).toBeVisible();
    await user.click(screen.getByRole('button', { name: '返回列表' }));
    expect(onRecover).toHaveBeenCalledTimes(1);
  });
});
