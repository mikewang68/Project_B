import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

async function loadModule<T>(path: string): Promise<T> {
  return import(/* @vite-ignore */ path) as Promise<T>;
}

describe('应用空壳渲染', () => {
  it('显示应用标题、默认角色授权导航和默认页面', async () => {
    expect(existsSync(join(process.cwd(), 'src', 'app', 'App.tsx'))).toBe(true);
    expect(existsSync(join(process.cwd(), 'src', 'app', 'router.tsx'))).toBe(true);
    const [{ default: App }, { appRoutes }] = await Promise.all([
      loadModule<typeof import('../App')>('../App.tsx'),
      loadModule<typeof import('../router')>('../router.tsx'),
    ]);
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/'] });

    render(<App router={router} />);

    expect(await screen.findByRole('heading', { name: 'B项目生产调度管理模块' })).toBeVisible();
    expect(screen.getByRole('navigation', { name: '页面导航' })).toBeVisible();
    expect(screen.getAllByRole('link', { name: /UI-\d{3}/ })).toHaveLength(11);
    expect(await screen.findByText('UI-001', undefined, { timeout: 5_000 })).toBeVisible();
    expect(screen.getByRole('heading', { name: '调度总览' })).toBeVisible();
  });
});
