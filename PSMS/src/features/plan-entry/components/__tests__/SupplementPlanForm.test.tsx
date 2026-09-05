import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createFixtureSnapshot } from '../../../../mocks/fixtures';
import { createDemoStore } from '../../../../stores';
import { selectVisibleYardObjects } from '../../selectors';
import type { PlanEntryQuery } from '../../types';
import SupplementPlanForm, {
  SEPARATION_OF_DUTIES_MESSAGE,
  type SupplementPlanResult,
  type SupplementPlanValues,
} from '../SupplementPlanForm';

const query: PlanEntryQuery = {
  date: '2026-07-16',
  workArea: 'AREA-A',
  scenarioId: 'SCN-02',
  planBatchNo: '',
  trainNo: '',
  statuses: [],
  exceptionTypes: [],
  page: 1,
  pageSize: 20,
  sort: 'updatedAt:desc',
};

const state = createDemoStore(createFixtureSnapshot(), {
  actorId: 'E2E-DISPATCHER',
  roleCode: 'DISPATCHER',
  dataScope: ['AREA-A'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-02',
}).getState();
const tracks = selectVisibleYardObjects(state, query).tracks;
const defaultReviewerOptions = [{ value: 'USER-001', label: 'USER-001' }] as const;
const enteredValues: SupplementPlanValues = {
  trackNo: 'T1',
  reviewerId: 'USER-001',
  effectiveUntil: '2026-07-16T12:30',
  reason: '补齐场景缺失股道',
};

afterEach(cleanup);

function renderForm({
  actorId = 'E2E-DISPATCHER',
  reviewerOptions = defaultReviewerOptions,
  onSubmit = async (): Promise<SupplementPlanResult> => ({ ok: true }),
}: {
  actorId?: string;
  reviewerOptions?: readonly Readonly<{ value: string; label: string }>[];
  onSubmit?: (values: SupplementPlanValues) => Promise<SupplementPlanResult>;
} = {}) {
  render(
    <SupplementPlanForm
      actorId={actorId}
      tracks={tracks}
      reviewerOptions={reviewerOptions}
      onSubmit={onSubmit}
    />,
  );
}

async function chooseOption(name: string, label: string): Promise<void> {
  const user = userEvent.setup();
  await user.click(screen.getByRole('combobox', { name }));
  await user.click(
    await screen.findByText(label, { selector: '.ant-select-item-option-content' }),
  );
}

async function fillForm(values: SupplementPlanValues = enteredValues): Promise<void> {
  const user = userEvent.setup();
  await chooseOption('补录股道', values.trackNo);
  await chooseOption('异人复核员', values.reviewerId);
  await user.type(screen.getByLabelText('有效期至'), values.effectiveUntil);
  await user.type(screen.getByRole('textbox', { name: '补录原因' }), values.reason);
}

function expectValuesRetained(values: SupplementPlanValues = enteredValues): void {
  const form = screen.getByLabelText('计划字段补录');
  expect(within(form).getByTitle(values.trackNo)).toBeVisible();
  expect(within(form).getByTitle(values.reviewerId)).toBeVisible();
  expect(within(form).getByLabelText('有效期至')).toHaveValue(values.effectiveUntil);
  expect(within(form).getByRole('textbox', { name: '补录原因' })).toHaveValue(values.reason);
}

describe('SupplementPlanForm', () => {
  it('requires exactly trackNo, reviewerId, effectiveUntil, and reason', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    renderForm({ onSubmit });

    await user.click(screen.getByRole('button', { name: '提交补录' }));

    for (const message of ['请选择股道', '请选择异人复核员', '请输入有效期', '请输入补录原因']) {
      expect(await screen.findByText(message)).toBeInTheDocument();
    }
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('renders track selector projection and UI-ready reviewer options without raw roles', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole('combobox', { name: '补录股道' }));
    for (const track of tracks) {
      expect(
        await screen.findByText(track.trackNo, { selector: '.ant-select-item-option-content' }),
      ).toBeInTheDocument();
    }
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('combobox', { name: '异人复核员' }));
    expect(
      await screen.findByText('USER-001', { selector: '.ant-select-item-option-content' }),
    ).toBeInTheDocument();
  });

  it('rejects a same-actor reviewer with the exact C03 separation wording', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    renderForm({
      actorId: 'E2E-DISPATCHER',
      reviewerOptions: [{ value: 'E2E-DISPATCHER', label: 'E2E-DISPATCHER' }],
      onSubmit,
    });
    await fillForm({ ...enteredValues, reviewerId: 'E2E-DISPATCHER' });

    await user.click(screen.getByRole('button', { name: '提交补录' }));

    expect(await screen.findByText(SEPARATION_OF_DUTIES_MESSAGE)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
    expectValuesRetained({ ...enteredValues, reviewerId: 'E2E-DISPATCHER' });
  });

  it('shows a structured API/version/business failure and retains every value', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(async (): Promise<SupplementPlanResult> => ({
      ok: false,
      errorCode: 'TOS-EXT-002',
      message: '版本已变化，请刷新后重试。',
    }));
    renderForm({ onSubmit });
    await fillForm();

    await user.click(screen.getByRole('button', { name: '提交补录' }));

    expect(await screen.findByText('版本已变化，请刷新后重试。')).toBeVisible();
    expect(screen.getByText('TOS-EXT-002')).toBeVisible();
    expect(onSubmit).toHaveBeenCalledWith(enteredValues);
    expectValuesRetained();
  });

  it('shows a network failure and retains every value', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(async (): Promise<SupplementPlanResult> => {
      throw new Error('offline');
    });
    renderForm({ onSubmit });
    await fillForm();

    await user.click(screen.getByRole('button', { name: '提交补录' }));

    expect(await screen.findByText('网络请求失败，已保留当前输入，请稍后重试。')).toBeVisible();
    expectValuesRetained();
  });

  it('returns success only through the callback boundary without faking domain success', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(async (): Promise<SupplementPlanResult> => ({ ok: true }));
    renderForm({ onSubmit });
    await fillForm();

    await user.click(screen.getByRole('button', { name: '提交补录' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(enteredValues));
    expect(screen.queryByText(/补录成功/)).not.toBeInTheDocument();
    expectValuesRetained();
  });
});
