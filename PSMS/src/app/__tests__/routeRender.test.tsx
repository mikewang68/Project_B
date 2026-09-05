import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { cleanup, render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { DEMO_SESSION_STORAGE_KEY } from '../../auth';

async function loadModule<T>(path: string): Promise<T> {
  return import(/* @vite-ignore */ path) as Promise<T>;
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('业务路由渲染', () => {
  it('13 个路由均显示对应编号、中文名称和当前路径', async () => {
    expect(existsSync(join(process.cwd(), 'src', 'app', 'router.tsx'))).toBe(true);
    const [{ default: App }, { appRoutes }, { routeCatalog }] = await Promise.all([
      loadModule<typeof import('../App')>('../App.tsx'),
      loadModule<typeof import('../router')>('../router.tsx'),
      loadModule<typeof import('../routeCatalog')>('../routeCatalog.ts'),
    ]);
    expect(routeCatalog).toHaveLength(13);

    for (const route of routeCatalog) {
      localStorage.setItem(
        DEMO_SESSION_STORAGE_KEY,
        JSON.stringify({
          actorId: `E2E-${route.allowedRoles[0]}`,
          roleCode: route.allowedRoles[0],
          dataScope: ['AREA-A'],
          online: true,
        }),
      );
      const router = createMemoryRouter(appRoutes, { initialEntries: [route.smokePath] });
      const view = render(<App router={router} />);

      expect(await screen.findByText(route.id, undefined, { timeout: 5_000 })).toBeVisible();
      expect(screen.getByRole('heading', { name: route.name })).toBeVisible();
      expect(screen.getByText(`当前路由：${route.smokePath}`)).toBeVisible();
      if (route.id === 'UI-004') {
        expect(screen.getByText('对象已变化或不存在', { exact: false })).toBeVisible();
        expect(screen.queryByText(/当前 C01 仅提供可验证的工程骨架/)).not.toBeInTheDocument();
      }
      view.unmount();
      localStorage.clear();
    }
  });

  it('未知路由显示 404 和明确的返回入口', async () => {
    expect(existsSync(join(process.cwd(), 'src', 'app', 'router.tsx'))).toBe(true);
    const [{ default: App }, { appRoutes }] = await Promise.all([
      loadModule<typeof import('../App')>('../App.tsx'),
      loadModule<typeof import('../router')>('../router.tsx'),
    ]);
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/not-a-real-route'] });

    render(<App router={router} />);

    expect(await screen.findByText('404')).toBeVisible();
    const returnLink = screen.getByRole('link', { name: '返回调度总览' });
    expect(returnLink).toHaveAttribute(
      'href',
      '/dispatch/overview',
    );
    expect(returnLink.closest('button')).toBeNull();
  });

  it('懒加载路由异常进入错误页并提供语义正确的返回入口', async () => {
    const [{ default: App }, { appRoutes }] = await Promise.all([
      loadModule<typeof import('../App')>('../App.tsx'),
      loadModule<typeof import('../router')>('../router.tsx'),
    ]);
    const rootRoute = appRoutes[0];
    const router = createMemoryRouter(
      [
        {
          ...rootRoute,
          children: [
            ...(rootRoute.children ?? []),
            {
              path: '__test-route-error',
              lazy: async () => {
                throw new Error('C01 路由异常测试');
              },
            },
          ],
        } as (typeof appRoutes)[number],
      ],
      { initialEntries: ['/__test-route-error'] },
    );

    render(<App router={router} />);

    expect(await screen.findByText('路由加载失败')).toBeVisible();
    const returnLink = screen.getByRole('link', { name: '返回调度总览' });
    expect(returnLink).toHaveAttribute('href', '/dispatch/overview');
    expect(returnLink.closest('button')).toBeNull();
  });
});
