import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { SessionContext } from '../../../../auth/types';
import LiveCsvImportPanel from '../LiveCsvImportPanel';

const state = vi.hoisted(() => ({ session: {} as SessionContext }));
vi.mock('../../../../auth', () => ({ loadCurrentDemoSession: () => state.session }));
vi.mock('../../../../runtime', () => ({
  useDemoRuntime: () => ({ store: { replaceDomainState: vi.fn() } }),
  useDemoSelector: (selector: (value: unknown) => unknown) => selector({ plan: { plans: [] } }),
}));
vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }));
vi.mock('../../../../real/client', () => ({ liveApi: vi.fn(), loadLivePlans: vi.fn() }));
afterEach(cleanup);

beforeEach(() => {
  state.session = {
    actorId: 'TEST-OPERATOR', roleCode: 'DISPATCHER', dataScope: ['AREA-A'], online: true,
    demoTime: '2026-11-04T00:00:00Z', readOnly: false,
    permissions: ['pps:api:plans:write'], menuOperations: { 'PSMS:ui-002': ['VIEW', 'ADD'] },
  };
});

describe('Live CSV import follows the actual UPLOAD action', () => {
  it('does not treat native write capacity or central ADD as UPLOAD', () => {
    render(<LiveCsvImportPanel />);
    expect(screen.getByLabelText('选择 CSV 文件')).toBeDisabled();
  });
  it('enables import for an explicitly authorized UPLOAD operator', () => {
    state.session.menuOperations = { 'PSMS:ui-002': ['VIEW', 'UPLOAD'] };
    render(<LiveCsvImportPanel />);
    expect(screen.getByLabelText('选择 CSV 文件')).toBeEnabled();
  });
  it('keeps readonly identities unable to upload even with an UPLOAD grant', () => {
    state.session.readOnly = true;
    state.session.menuOperations = { 'PSMS:ui-002': ['VIEW', 'UPLOAD'] };
    render(<LiveCsvImportPanel />);
    expect(screen.getByLabelText('选择 CSV 文件')).toBeDisabled();
  });
  it('preserves the existing native write rule for a legacy session', () => {
    state.session.menuOperations = undefined;
    render(<LiveCsvImportPanel />);
    expect(screen.getByLabelText('选择 CSV 文件')).toBeEnabled();
  });
  it('does not let a central UPLOAD grant exceed native plan-write capacity', () => {
    state.session.permissions = [];
    state.session.menuOperations = { 'PSMS:ui-002': ['VIEW', 'UPLOAD'] };
    render(<LiveCsvImportPanel />);
    expect(screen.getByLabelText('选择 CSV 文件')).toBeDisabled();
  });
});
