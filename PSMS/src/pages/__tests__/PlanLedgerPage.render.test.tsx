import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot, type DemoScenario } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

const LAZY_ROUTE_TIMEOUT = { timeout: 5_000 } as const;

function response(envelope: ApiSuccessEnvelope | ApiErrorEnvelope): Response {
  return new Response(JSON.stringify(envelope), {
    status: envelope.ok ? 200 : 409,
    headers: { 'content-type': 'application/json' },
  });
}

function successEnvelope(data: Record<string, unknown> = {}): ApiSuccessEnvelope {
  return {
    ok: true,
    data,
    traceId: 'TRACE-READ-001',
    auditLogId: 'AUD-READ-001',
  };
}

function errorEnvelope(errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Read failed with ${errorCode}`,
    traceId: 'TRACE-READ-ERROR',
    auditLogId: 'AUD-READ-ERROR',
  };
}

function scenarioFromEntry(initialEntry: string): DemoScenario['id'] {
  if (initialEntry.includes('scenarioId=SCN-02')) return 'SCN-02';
  if (initialEntry.includes('scenarioId=SCN-03')) return 'SCN-03';
  return 'SCN-01';
}

function renderLedger(
  initialEntry = '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01',
  fetcher: typeof fetch = vi.fn(async () => response(successEnvelope())),
) {
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({
      actorId: 'E2E-DISPATCHER',
      roleCode: 'DISPATCHER',
      dataScope: ['AREA-A'],
      online: true,
    }),
  );
  const runtime = createDemoRuntime(fetcher);
  runtime.store.resetFromSnapshot(createFixtureSnapshot(), {
    actorId: 'E2E-DISPATCHER',
    roleCode: 'DISPATCHER',
    dataScope: ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: scenarioFromEntry(initialEntry),
  });
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] });
  render(<App router={router} runtime={runtime} />);
  return { runtime, fetcher, router };
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-002 外部到发信息台账内容', () => {
  it('呈现精确烟测标记、接口健康条和五组 URL 筛选', async () => {
    renderLedger();

    expect(window.getComputedStyle).toHaveLength(2);

    expect(
      await screen.findByRole(
        'heading',
        { name: '外部到发信息台账', level: 2 },
        LAZY_ROUTE_TIMEOUT,
      ),
    ).toBeVisible();
    expect(screen.getByText('UI-002')).toBeVisible();
    expect(screen.getByText('当前路由：/dispatch/plans')).toBeVisible();

    const interfaceHealth = screen.getByLabelText('计划接口健康');
    expect(within(interfaceHealth).getByText('95306 / 铁路计划接入通道')).toBeVisible();
    expect(within(interfaceHealth).getByText('正常')).toBeVisible();
    expect(within(interfaceHealth).getByText('重试 0 次')).toBeVisible();
    expect(within(interfaceHealth).getByText('未熔断')).toBeVisible();

    const filters = screen.getByLabelText('计划筛选');
    for (const label of ['计划批次', '车次', '时间范围', '状态', '异常类型']) {
      expect(within(filters).getByText(label)).toBeVisible();
    }
    expect(within(filters).getByRole('textbox', { name: '计划批次' })).toHaveValue('');
    expect(within(filters).getByRole('textbox', { name: '车次' })).toHaveValue('');
    expect(within(filters).getByRole('textbox', { name: '时间范围' })).toHaveValue('2026-07-16');
    expect(within(filters).getByRole('combobox', { name: '状态' })).toBeVisible();
    expect(within(filters).getByRole('combobox', { name: '异常类型' })).toBeVisible();
  });

  it('呈现九项业务列、受控操作列和三条严格 Store 计划', async () => {
    renderLedger();
    await screen.findByRole('heading', { name: '外部到发信息台账' }, LAZY_ROUTE_TIMEOUT);

    const ledger = screen.getByLabelText('计划台账');
    expect(
      within(ledger).getAllByRole('columnheader', { hidden: true }).map(({ textContent }) =>
        textContent,
      ),
    ).toEqual([
      '来源',
      '计划批次',
      '车次',
      '股道',
      '货类',
      '缺失字段',
      '校验状态',
      '同步状态',
      '更新时间',
      '操作',
    ]);
    for (const [planId, trainNo, batchNo] of [
      ['PLAN-001', '75001', 'PB-20260716-01'],
      ['PLAN-002', '75002', 'PB-20260716-02'],
      ['PLAN-003', '75003', 'PB-20260716-03'],
    ] as const) {
      const planLabel = within(ledger).getByText(planId, { selector: 'button span' });
      const planButton = planLabel.closest('button');
      const row = planLabel.closest('tr');
      expect(planButton).not.toBeNull();
      expect(planButton).toBeVisible();
      expect(row).not.toBeNull();
      expect(row).toHaveTextContent(trainNo);
      expect(row).toHaveTextContent(batchNo);
    }
    expect(within(ledger).getByText('共 3 条')).toBeVisible();
  });

  it('详情抽屉使用固定五段流程并呈现 selector 的详情分区', async () => {
    const user = userEvent.setup();
    renderLedger();

    const planLabel = await screen.findByText('PLAN-001', { selector: 'button span' });
    const planButton = planLabel.closest('button');
    expect(planButton).not.toBeNull();
    await user.click(planButton!);
    const drawerTitle = await screen.findByText('PLAN-001 计划详情', {
      selector: '.ant-drawer-title',
    });
    const drawer = drawerTitle.closest<HTMLElement>('[role="dialog"]');
    expect(drawer).not.toBeNull();
    const workflow = within(drawer!).getByLabelText('计划确认流程');
    for (const label of [
      '计划同步',
      '自动校验',
      '字段补录/冲突处理',
      '计划确认',
      '开放推荐入口',
    ]) {
      expect(within(workflow).getByText(label)).toBeVisible();
    }
    for (const section of [
      '计划摘要',
      '字段来源',
      '脱敏原始摘要',
      '校验问题',
      '重试记录',
      '变更历史',
    ]) {
      expect(within(drawer!).getByText(section)).toBeVisible();
    }
  });

  it('AREA-A session with AREA-B query and planId renders no ledger object and never mounts the detail Drawer', async () => {
    renderLedger(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-B&scenarioId=SCN-02&planId=PLAN-002',
    );

    await screen.findByRole('heading', { name: '外部到发信息台账' }, LAZY_ROUTE_TIMEOUT);
    expect(screen.queryByLabelText('计划台账')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('PLAN-002 计划详情')).not.toBeInTheDocument();
    expect(screen.queryByText('PB-20260716-02')).not.toBeInTheDocument();
  });

  it('uses API-002 pending and success states without importing GET objects into Store', async () => {
    let release: ((value: Response) => void) | undefined;
    const fetcher = vi.fn(
      () => new Promise<Response>((resolve) => {
        release = resolve;
      }),
    );
    const { runtime } = renderLedger(undefined, fetcher as typeof fetch);
    const plansBefore = structuredClone(runtime.store.getState().plan);

    expect(await screen.findByLabelText('页面加载中', {}, LAZY_ROUTE_TIMEOUT)).toBeVisible();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith('/mock/plans', undefined);

    act(() => {
      release?.(
        response(
          successEnvelope({
            items: [{ id: 'PLAN-GET-MUST-NOT-WIN', status: 'CANCELLED', version: 999 }],
          }),
        ),
      );
    });
    expect(await screen.findByLabelText('计划台账', {}, LAZY_ROUTE_TIMEOUT)).toBeVisible();
    expect(runtime.store.getState().plan).toEqual(plansBefore);
    expect(JSON.stringify(runtime.store.getState().plan)).not.toContain('PLAN-GET-MUST-NOT-WIN');
  });

  it.each([
    ['TOS-AUTH-001', '业务处理失败'],
    ['TOS-EXT-001', '计划接口暂不可用'],
  ] as const)('maps API-002 %s error envelope to %s', async (errorCode, title) => {
    const fetcher = vi.fn(async () => response(errorEnvelope(errorCode)));
    const { runtime } = renderLedger(undefined, fetcher as typeof fetch);
    const plansBefore = structuredClone(runtime.store.getState().plan);

    expect(await screen.findByText(title, {}, LAZY_ROUTE_TIMEOUT)).toBeVisible();
    expect(screen.getByText(errorCode)).toBeVisible();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(runtime.store.getState().plan).toEqual(plansBefore);
  });

  it('keeps TOS-EXT-002 recoverable and renders strict Store ledger plus SCN-02 projection', async () => {
    const fetcher = vi.fn(async () => response(errorEnvelope('TOS-EXT-002')));
    const { runtime } = renderLedger(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-02',
      fetcher as typeof fetch,
    );
    const plansBefore = structuredClone(runtime.store.getState().plan);

    const ledger = await screen.findByLabelText('计划台账', {}, LAZY_ROUTE_TIMEOUT);
    const planLabel = within(ledger).getByText('PLAN-002', { selector: 'button span' });
    expect(planLabel.closest('tr')).toHaveTextContent('股道');
    expect(screen.queryByText('业务处理失败')).not.toBeInTheDocument();
    expect(screen.queryByText('计划接口暂不可用')).not.toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(runtime.store.getState().plan).toEqual(plansBefore);
  });

  it('round-trips the SCN-02 missing-field exception filter through URL replacement and remount', async () => {
    const user = userEvent.setup();
    const { router } = renderLedger(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-02',
    );
    await screen.findByLabelText('计划台账', {}, LAZY_ROUTE_TIMEOUT);

    await user.click(
      within(screen.getByLabelText('计划筛选')).getByRole('combobox', {
        name: '异常类型',
      }),
    );
    await user.click(
      await screen.findByText('校验字段缺失', {
        selector: '.ant-select-item-option-content',
      }),
    );

    await waitFor(() =>
      expect(router.state.location.search).toContain(
        'exceptionType=VALIDATION_MISSING_FIELD',
      ),
    );
    let ledger = screen.getByLabelText('计划台账');
    expect(within(ledger).getByText('PLAN-002', { selector: 'button span' })).toBeVisible();
    expect(within(ledger).queryByText('PLAN-001', { selector: 'button span' })).not.toBeInTheDocument();
    expect(within(ledger).queryByText('PLAN-003', { selector: 'button span' })).not.toBeInTheDocument();

    const remountEntry = `${router.state.location.pathname}${router.state.location.search}`;
    cleanup();
    renderLedger(remountEntry);

    ledger = await screen.findByLabelText('计划台账', {}, LAZY_ROUTE_TIMEOUT);
    expect(within(ledger).getByText('PLAN-002', { selector: 'button span' })).toBeVisible();
    expect(within(ledger).queryByText('PLAN-001', { selector: 'button span' })).not.toBeInTheDocument();
    expect(within(ledger).queryByText('PLAN-003', { selector: 'button span' })).not.toBeInTheDocument();
  });

  it.each([
    ['malformed', vi.fn(async () => response(successEnvelope({ unexpected: true }))).mockImplementation(
      async () =>
        new Response(
          JSON.stringify({
            ok: true,
            data: {},
            traceId: 'TRACE-MALFORMED',
            auditLogId: 'AUD-MALFORMED',
            extra: true,
          }),
          { headers: { 'content-type': 'application/json' } },
        ),
    )],
    ['thrown', vi.fn(async () => { throw new TypeError('network down'); })],
  ] as const)('maps API-002 %s rejection to network-error without changing Store', async (_case, fetcher) => {
    const { runtime } = renderLedger(undefined, fetcher as typeof fetch);
    const plansBefore = structuredClone(runtime.store.getState().plan);

    expect(
      await screen.findByText('计划接口暂不可用', {}, LAZY_ROUTE_TIMEOUT),
    ).toBeVisible();
    expect(fetcher).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(runtime.store.getState().plan).toEqual(plansBefore));
  });
});
