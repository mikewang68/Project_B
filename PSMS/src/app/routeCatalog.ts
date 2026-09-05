import {
  Suspense as ReactSuspense,
  cloneElement as reactCloneElement,
  createElement as reactCreateElement,
  isValidElement as reactIsValidElement,
  lazy as reactLazy,
  type ComponentType,
} from 'react';
import { routePolicies, routeRequiredPermissions } from '../auth/permissionCatalog';
import type { PageId, RoleCode } from '../auth/types';
import type { PermissionCode } from '../auth/permissionCatalog';

export type RouteGroup = '调度' | '执行' | '安全' | '治理';

export type RouteCatalogItem = {
  id: PageId;
  name: string;
  path: string;
  smokePath: string;
  sourceFile: string;
  group: RouteGroup;
  requiredPermission: PermissionCode;
  allowedRoles: readonly RoleCode[];
  loadPage: () => Promise<{ default: ComponentType }>;
};

type BaseRouteCatalogItem = Omit<RouteCatalogItem, 'requiredPermission' | 'allowedRoles'>;

export const C03Suspense = ReactSuspense;

export function createC03Element(
  type: unknown,
  props: Record<string, unknown> | null,
  ...children: unknown[]
) {
  return reactCreateElement(type, props, ...children);
}

export function cloneC03Element(element: unknown, props: Record<string, unknown>) {
  return reactCloneElement(element, props);
}

export function isC03Element(value: unknown): boolean {
  return reactIsValidElement(value);
}

export function createC03Lazy(loadPage: RouteCatalogItem['loadPage']) {
  return reactLazy(loadPage);
}

const baseRouteCatalog = [
  {
    id: 'UI-001',
    name: '调度总览',
    path: '/dispatch/overview',
    smokePath: '/dispatch/overview',
    sourceFile: 'src/pages/dispatch/OverviewPage.tsx',
    group: '调度',
    loadPage: () => import('../pages/dispatch/OverviewPage'),
  },
  {
    id: 'UI-002',
    name: '外部到发信息台账',
    path: '/dispatch/plans',
    smokePath: '/dispatch/plans',
    sourceFile: 'src/pages/dispatch/PlanLedgerPage.tsx',
    group: '调度',
    loadPage: () => import('../pages/dispatch/PlanLedgerPage'),
  },
  {
    id: 'UI-003',
    name: '生产准备建议',
    path: '/dispatch/plans/:planId/recommendation',
    smokePath: '/dispatch/plans/PLAN-DEMO-001/recommendation',
    sourceFile: 'src/pages/dispatch/ReceptionRecommendationPage.tsx',
    group: '调度',
    loadPage: () => import('../pages/dispatch/ReceptionRecommendationPage'),
  },
  {
    id: 'UI-004',
    name: '任务拆解',
    path: '/dispatch/plans/:planId/tasks',
    smokePath: '/dispatch/plans/PLAN-DEMO-001/tasks',
    sourceFile: 'src/pages/dispatch/TaskDecompositionPage.tsx',
    group: '调度',
    loadPage: () => import('../pages/dispatch/TaskDecompositionPage'),
  },
  {
    id: 'UI-005',
    name: '派工看板',
    path: '/dispatch/work-orders',
    smokePath: '/dispatch/work-orders',
    sourceFile: 'src/pages/dispatch/DispatchBoardPage.tsx',
    group: '执行',
    loadPage: () => import('../pages/dispatch/DispatchBoardPage'),
  },
  {
    id: 'UI-006',
    name: '公路预约与叫号',
    path: '/yard/appointments',
    smokePath: '/yard/appointments',
    sourceFile: 'src/pages/yard/RoadAppointmentPage.tsx',
    group: '执行',
    loadPage: () => import('../pages/yard/RoadAppointmentPage'),
  },
  {
    id: 'UI-007',
    name: '全流程监控',
    path: '/monitor/operations',
    smokePath: '/monitor/operations',
    sourceFile: 'src/pages/monitor/OperationMonitorPage.tsx',
    group: '执行',
    loadPage: () => import('../pages/monitor/OperationMonitorPage'),
  },
  {
    id: 'UI-008',
    name: '异常处置',
    path: '/monitor/exceptions',
    smokePath: '/monitor/exceptions',
    sourceFile: 'src/pages/monitor/ExceptionHandlingPage.tsx',
    group: '安全',
    loadPage: () => import('../pages/monitor/ExceptionHandlingPage'),
  },
  {
    id: 'UI-009',
    name: '安全联锁',
    path: '/safety/interlocks',
    smokePath: '/safety/interlocks',
    sourceFile: 'src/pages/safety/SafetyInterlockPage.tsx',
    group: '安全',
    loadPage: () => import('../pages/safety/SafetyInterlockPage'),
  },
  {
    id: 'UI-010',
    name: '离线同步',
    path: '/operations/offline-sync',
    smokePath: '/operations/offline-sync',
    sourceFile: 'src/pages/operations/OfflineSyncPage.tsx',
    group: '安全',
    loadPage: () => import('../pages/operations/OfflineSyncPage'),
  },
  {
    id: 'UI-011',
    name: '统计报表',
    path: '/reports/operations',
    smokePath: '/reports/operations',
    sourceFile: 'src/pages/reports/OperationReportPage.tsx',
    group: '治理',
    loadPage: () => import('../pages/reports/OperationReportPage'),
  },
  {
    id: 'UI-012',
    name: '系统配置',
    path: '/settings/system',
    smokePath: '/settings/system',
    sourceFile: 'src/pages/settings/SystemSettingsPage.tsx',
    group: '治理',
    loadPage: () => import('../pages/settings/SystemSettingsPage'),
  },
  {
    id: 'UI-013',
    name: '审计日志',
    path: '/governance/audit',
    smokePath: '/governance/audit',
    sourceFile: 'src/pages/governance/AuditLogPage.tsx',
    group: '治理',
    loadPage: () => import('../pages/governance/AuditLogPage'),
  },
] satisfies BaseRouteCatalogItem[];

export const routeCatalog: RouteCatalogItem[] = baseRouteCatalog.map((route) => ({
  ...route,
  requiredPermission: routeRequiredPermissions[route.id],
  allowedRoles: routePolicies[route.id],
}));
