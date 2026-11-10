import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button, Form } from 'antd';
import DispatchDetailsFields from './DispatchDetailsFields';

afterEach(cleanup);
const details = { plannedWorkAt: '2026-11-08T16:00:00+08:00', operationLocation: 'T1', channel: '通道1',
  expectedArrivalAt: '2026-11-08T15:00:00+08:00', transferMode: 'DIRECT',
  lines: [{ lineId: '1', goodCode: 'ASH-1', unit: 't', plannedQty: 1.025 }] };
function renderForm(values: Record<string, unknown>, orderType = 'UNLOADING') {
  const submit = vi.fn();
  render(<Form initialValues={{ dispatchDetails: values }} onFinish={submit}>
    <DispatchDetailsFields orderType={orderType} /><Button htmlType="submit">提交测试工单</Button>
  </Form>);
  return submit;
}
describe('dispatch work order fields', () => {
  it('retains three-decimal tons in the submitted order', async () => {
    const submit = renderForm(details);
    await userEvent.click(screen.getByRole('button', { name: '提交测试工单' }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0][0].dispatchDetails.lines[0]).toMatchObject({ plannedQty: 1.025, unit: 't' });
  });
  it('rejects fractional bundles instead of rounding them to an integer', async () => {
    const submit = renderForm({ ...details, lines: [{ ...details.lines[0], unit: '捆' }] });
    await userEvent.click(screen.getByRole('button', { name: '提交测试工单' }));
    await waitFor(() => expect(screen.getByText('捆、件等非吨库存单位只允许整数')).toBeVisible());
    expect(submit).not.toHaveBeenCalled();
  });
  it('shows the source warehouse for loading and requires plate and driver for a shuttle', async () => {
    renderForm({ ...details, transferMode: 'SHUTTLE', sourceWarehouseCode: 'WH1' }, 'LOADING');
    expect(screen.getByLabelText('来源仓编码')).toBeVisible();
    expect(screen.queryByLabelText('预计到达时间（含时区）')).not.toBeInTheDocument();
    expect(screen.getByLabelText('实际车牌')).toBeVisible();
    expect(screen.getByLabelText('司机姓名')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '提交测试工单' }));
    await waitFor(() => expect(screen.getByLabelText('实际车牌')).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.getByLabelText('司机姓名')).toHaveAttribute('aria-invalid', 'true');
  });
});
