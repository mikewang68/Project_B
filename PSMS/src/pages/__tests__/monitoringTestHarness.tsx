import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { vi } from 'vitest';

import { DEMO_SESSION_STORAGE_KEY } from '../../auth';
import type { RoleCode } from '../../auth/types';
import { createFixtureSnapshot } from '../../mocks';
import RoadAppointmentPage from '../yard/RoadAppointmentPage';
import OperationMonitorPage from '../monitor/OperationMonitorPage';
import { createDemoRuntime, DemoRuntimeProvider } from '../../runtime';

type MonitoringPage = 'appointments' | 'operations';

function envelope(data: Record<string, unknown>) {
  return new Response(JSON.stringify({
    ok: true,
    data,
    auditLogId: 'AUD-MONITOR-TEST',
    traceId: 'TRACE-MONITOR-TEST',
  }), { status: 200, headers: { 'content-type': 'application/json' } });
}

export function createMonitoringFetcher() {
  const snapshot = createFixtureSnapshot();
  return async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname === '/mock/appointments') {
      return envelope({
        apiId: 'API-010', operationId: 'GET_mock_appointments', now: snapshot.scenarios[0].clock,
        scenarioId: 'SCN-01', items: snapshot.objects['DO-008'],
      });
    }
    const appointmentMatch = url.pathname.match(/^\/mock\/appointments\/(APT-\d+)\/transition$/);
    if (appointmentMatch && init?.method === 'POST') {
      return envelope({
        apiId: 'API-011', operationId: 'POST_mock_appointments_id_transition',
        now: snapshot.scenarios[0].clock, scenarioId: 'SCN-01',
        items: snapshot.objects['DO-008'].filter(({ id }) => id === appointmentMatch[1]),
      });
    }
    if (url.pathname === '/mock/operations') {
      return envelope({
        apiId: 'API-012', operationId: 'GET_mock_operations', now: snapshot.scenarios[0].clock,
        scenarioId: 'SCN-01',
        items: [
          ...snapshot.objects['DO-005'],
          ...snapshot.objects['DO-006'],
          ...snapshot.objects['DO-007'],
        ],
      });
    }
    const scenarioMatch = url.pathname.match(/^\/mock\/scenarios\/(SCN-\d+)\/play$/);
    if (scenarioMatch && init?.method === 'POST') {
      const scenario = snapshot.scenarios.find(({ id }) => id === scenarioMatch[1]);
      return envelope({
        apiId: 'API-013', operationId: 'POST_mock_scenarios_id_play',
        now: scenario?.clock ?? snapshot.scenarios[0].clock, scenario,
      });
    }
    throw new TypeError(`未处理的测试请求：${url.pathname}`);
  };
}

export function renderMonitoringPage(
  page: MonitoringPage,
  options: { roleCode?: RoleCode; online?: boolean } = {},
) {
  const roleCode = options.roleCode ?? 'DISPATCHER';
  localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
    actorId: `TEST-${roleCode}`,
    roleCode,
    dataScope: ['AREA-A'],
    online: options.online ?? true,
  }));
  const fetcher = createMonitoringFetcher() as typeof fetch;
  vi.stubGlobal('fetch', fetcher);
  const runtime = createDemoRuntime(fetcher);
  const element: ReactElement = page === 'appointments'
    ? <RoadAppointmentPage />
    : <OperationMonitorPage />;
  return {
    runtime,
    ...render(<DemoRuntimeProvider runtime={runtime}>{element}</DemoRuntimeProvider>),
  };
}
