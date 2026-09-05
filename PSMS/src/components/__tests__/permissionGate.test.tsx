import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import type { PolicyContext } from '../../auth';
import { createC03Element } from '../../app/routeCatalog';
import PermissionGate from '../PermissionGate';

const allowedContext: PolicyContext = {
  session: {
    actorId: 'USER-001',
    roleCode: 'DISPATCHER',
    dataScope: ['AREA-A'],
    online: true,
    demoTime: '2026-07-19T08:00:00+08:00',
  },
  pageId: 'UI-003',
  permission: 'plan:recommend',
  objectScope: { type: 'AREA', value: 'AREA-A' },
};

const deniedContext: PolicyContext = {
  ...allowedContext,
  session: { ...allowedContext.session, roleCode: 'AUDITOR', actorId: 'AUDITOR-001' },
};

afterEach(() => cleanup());

describe('PermissionGate', () => {
  it('renders an allowed child unchanged', () => {
    render(
      createC03Element(
        PermissionGate,
        { permission: 'plan:recommend', context: allowedContext, mode: 'hide' },
        createC03Element('button', { type: 'button' }, '生成推荐'),
      ),
    );

    expect(screen.getByRole('button', { name: '生成推荐' })).toBeEnabled();
  });

  it('does not render a denied child in hide mode', () => {
    render(
      createC03Element(
        PermissionGate,
        { permission: 'plan:recommend', context: deniedContext, mode: 'hide' },
        createC03Element('button', { type: 'button' }, '生成推荐'),
      ),
    );

    expect(screen.queryByRole('button', { name: '生成推荐' })).not.toBeInTheDocument();
  });

  it('clones a denied control as disabled with an accessible reason', () => {
    render(
      createC03Element(
        PermissionGate,
        { permission: 'plan:recommend', context: deniedContext, mode: 'disable' },
        createC03Element('button', { type: 'button' }, '生成推荐'),
      ),
    );

    const button = screen.getByRole('button', { name: '生成推荐' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveAttribute('title', expect.stringContaining('AUDITOR'));
  });

  it('keeps the denied action visible and displays the policy reason in explain mode', () => {
    render(
      createC03Element(
        PermissionGate,
        { permission: 'plan:recommend', context: deniedContext, mode: 'explain' },
        createC03Element('button', { type: 'button' }, '生成推荐'),
      ),
    );

    expect(screen.getByRole('button', { name: '生成推荐' })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('note')).toHaveTextContent('AUDITOR');
  });
});
