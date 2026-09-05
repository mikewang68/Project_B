import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const loadModule = <T>(path: string): Promise<T> =>
  import(/* @vite-ignore */ path) as Promise<T>;

const expectedRoutes = [
  ['UI-001', '调度总览', '/dispatch/overview', 'src/pages/dispatch/OverviewPage.tsx'],
  ['UI-002', '外部到发信息台账', '/dispatch/plans', 'src/pages/dispatch/PlanLedgerPage.tsx'],
  ['UI-003', '生产准备建议', '/dispatch/plans/:planId/recommendation', 'src/pages/dispatch/ReceptionRecommendationPage.tsx'],
  ['UI-004', '任务拆解', '/dispatch/plans/:planId/tasks', 'src/pages/dispatch/TaskDecompositionPage.tsx'],
  ['UI-005', '派工看板', '/dispatch/work-orders', 'src/pages/dispatch/DispatchBoardPage.tsx'],
  ['UI-006', '公路预约与叫号', '/yard/appointments', 'src/pages/yard/RoadAppointmentPage.tsx'],
  ['UI-007', '全流程监控', '/monitor/operations', 'src/pages/monitor/OperationMonitorPage.tsx'],
  ['UI-008', '异常处置', '/monitor/exceptions', 'src/pages/monitor/ExceptionHandlingPage.tsx'],
  ['UI-009', '安全联锁', '/safety/interlocks', 'src/pages/safety/SafetyInterlockPage.tsx'],
  ['UI-010', '离线同步', '/operations/offline-sync', 'src/pages/operations/OfflineSyncPage.tsx'],
  ['UI-011', '统计报表', '/reports/operations', 'src/pages/reports/OperationReportPage.tsx'],
  ['UI-012', '系统配置', '/settings/system', 'src/pages/settings/SystemSettingsPage.tsx'],
  ['UI-013', '审计日志', '/governance/audit', 'src/pages/governance/AuditLogPage.tsx'],
];

describe('稳定路由目录', () => {
  it('包含恰好 13 个唯一编号、唯一路由和冻结页面文件', async () => {
    expect(existsSync(join(process.cwd(), 'src', 'app', 'routeCatalog.ts'))).toBe(true);
    const { routeCatalog } = await loadModule<typeof import('../routeCatalog')>(
      '../routeCatalog.ts',
    );

    expect(routeCatalog).toHaveLength(13);
    expect(new Set(routeCatalog.map((route) => route.id)).size).toBe(13);
    expect(new Set(routeCatalog.map((route) => route.path)).size).toBe(13);
    expect(
      routeCatalog.map(({ id, name, path, sourceFile }) => [id, name, path, sourceFile]),
    ).toEqual(expectedRoutes);
  });

  it('参数路由统一使用 PLAN-DEMO-001 作为冒烟访问值', async () => {
    expect(existsSync(join(process.cwd(), 'src', 'app', 'routeCatalog.ts'))).toBe(true);
    const { routeCatalog } = await loadModule<typeof import('../routeCatalog')>(
      '../routeCatalog.ts',
    );

    for (const route of routeCatalog) {
      const expectedSmokePath = route.path.replace(':planId', 'PLAN-DEMO-001');
      expect(route.smokePath).toBe(expectedSmokePath);
    }
  });
});
