import { cleanup, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  DEMO_SESSION_STORAGE_KEY,
  type SessionContext,
} from '../../auth';
import App from '../App';
import { createC03Element, routeCatalog, type RouteCatalogItem } from '../routeCatalog';
import { appRoutes, createProtectedRouteElement } from '../router';

const dispatcher: SessionContext = {
  actorId: 'USER-001',
  roleCode: 'DISPATCHER',
  dataScope: ['AREA-A'],
  online: true,
  demoTime: '2026-07-19T08:00:00+08:00',
};

const admin: SessionContext = {
  ...dispatcher,
  actorId: 'ADMIN-001',
  roleCode: 'SYS_ADMIN',
};

function seedSession(session: SessionContext): void {
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({
      actorId: session.actorId,
      roleCode: session.roleCode,
      dataScope: session.dataScope,
      online: session.online,
    }),
  );
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('C03 route permissions', () => {
  it('filters navigation from the current session policy catalog', async () => {
    seedSession(dispatcher);
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/dispatch/overview'] });

    render(createC03Element(App, { router }));

    expect(await screen.findByRole('link', { name: 'UI-001 调度总览' })).toBeVisible();
    expect(screen.queryByRole('link', { name: 'UI-012 系统配置' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'UI-013 审计日志' })).not.toBeInTheDocument();
  });

  it('shows navigation granted to a different frozen role', async () => {
    seedSession(admin);
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/settings/system'] });

    render(createC03Element(App, { router }));

    expect(await screen.findByRole('link', { name: 'UI-012 系统配置' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'UI-013 审计日志' })).toBeVisible();
    expect(screen.queryByRole('link', { name: 'UI-001 调度总览' })).not.toBeInTheDocument();
  });

  it('returns a safe direct-access 403 with permission and scope but no page object', async () => {
    seedSession(dispatcher);
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/settings/system'] });

    render(createC03Element(App, { router }));

    expect(await screen.findByRole('heading', { name: /403/ })).toBeVisible();
    expect(screen.getByText(/settings:view/)).toBeVisible();
    expect(screen.getByText(/AREA-A/)).toBeVisible();
    expect(screen.queryByRole('heading', { name: '系统配置' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '前往首个可访问页面' })).toHaveAttribute(
      'href',
      '/dispatch/overview',
    );
  });

  it('does not call or render an unauthorized page module before the boundary', async () => {
    const loadPage = vi.fn(async () => ({
      default: () => createC03Element('div', null, '敏感业务对象 PLAN-SECRET'),
    }));
    const protectedRoute: RouteCatalogItem = {
      ...routeCatalog.find(({ id }) => id === 'UI-012')!,
      loadPage,
    };
    const router = createMemoryRouter(
      [{ path: '/settings/system', element: createProtectedRouteElement(protectedRoute, dispatcher) }],
      { initialEntries: ['/settings/system'] },
    );

    render(createC03Element(RouterProvider, { router }));

    expect(await screen.findByRole('heading', { name: /403/ })).toBeVisible();
    expect(screen.queryByText('敏感业务对象 PLAN-SECRET')).not.toBeInTheDocument();
    expect(loadPage).not.toHaveBeenCalled();
  });
});
