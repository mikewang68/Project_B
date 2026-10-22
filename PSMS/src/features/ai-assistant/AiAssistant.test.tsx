import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../runtime', () => ({ useDemoSelector: () => ({
  session: { dataScope: ['AREA-A'], demoTime: '2026-07-19T08:00:00+08:00' },
  plan: { plans: [{ id: 'PLAN-1', planBatchNo: 'BATCH-1', trainNo: 'T88001', cargoType: 'FLY_ASH', status: 'CONFIRMED', trackNo: 'T1', arrivalDepartureTime: '2026-07-19T09:00:00+08:00', missingFields: [], conflicts: [] }] },
  resource: { resources: [{ workArea: 'AREA-A', resourceType: 'EQUIPMENT', status: 'AVAILABLE' }, { workArea: 'AREA-B', resourceType: 'EQUIPMENT', status: 'UNAVAILABLE' }] },
  workOrder: { workOrders: [] },
}) }));
vi.mock('../backend-connection/BackendConnection', () => ({ useBackendConnection: () => ({ token: 'session-token' }), BackendConnectionFields: () => <div>业务服务账号</div> }));
import AiAssistant from './AiAssistant';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
describe('AI assistant integration', () => {
  it('automatically logs into the local demo service and hides account fields', async () => {
    const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/status') ? { configured: true, demoSession: true } : { data: { accessToken: 'ai-only-token' } })));
    vi.stubGlobal('fetch', fetcher);
    const user = userEvent.setup();
    render(<MemoryRouter><AiAssistant /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'AI 调度助手' }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/ai/session', expect.objectContaining({ method: 'POST' })));
    await waitFor(() => expect(screen.getByRole('button', { name: '分析当前计划' })).toBeEnabled());
    expect(screen.queryByText('业务服务账号')).not.toBeInTheDocument();
  });
  it('uses the configured model and displays an unbranded result with mock context sent only in the request', async () => {
    const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/status') ? { configured: true, model: 'qwen3.8-27b' } : { answer: '先确认股道。', model: 'qwen3.8-27b' })));
    vi.stubGlobal('fetch', fetcher);
    const user = userEvent.setup();
    render(<MemoryRouter><AiAssistant /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'AI 调度助手' }));
    await waitFor(() => expect(screen.getByRole('combobox', { name: '分析方式' }).closest('.ant-select')).toHaveTextContent('大模型分析'));
    await user.click(screen.getByRole('button', { name: '分析当前计划' }));
    expect(await screen.findByText('先确认股道。')).toBeVisible();
    expect(screen.getByText('AI 分析结果')).toBeVisible();
    expect(screen.queryByText(/通义千问|模拟数据|qwen3\.8/)).not.toBeInTheDocument();
    const call = fetcher.mock.calls.find(([url]) => url.endsWith('/assist')) as unknown as [string, RequestInit];
    const input = JSON.parse(call[1].body as string);
    expect(input.context).toMatchObject({ dataSource: 'MOCK', snapshotAt: '2026-07-19T08:00:00+08:00' });
    expect(input.context.resources).toEqual([{ type: 'EQUIPMENT', status: 'AVAILABLE' }]);
  });
  it('keeps rules available when the backend status check fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
    const user = userEvent.setup();
    render(<MemoryRouter><AiAssistant /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'AI 调度助手' }));
    await user.click(screen.getByRole('button', { name: '分析当前计划' }));
    expect(await screen.findByText('本地规则分析', { selector: '.ant-card-head-title' })).toBeVisible();
    expect(await screen.findByText('AI 服务暂未启动或无法连接，请启动后重试。')).toBeVisible();
  });
  it('rechecks the status after the AI service becomes available', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('{}', { status: 502 })).mockResolvedValueOnce(new Response(JSON.stringify({ configured: true })));
    vi.stubGlobal('fetch', fetcher);
    const user = userEvent.setup();
    render(<MemoryRouter><AiAssistant /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'AI 调度助手' }));
    await user.click(await screen.findByRole('button', { name: '重新检测' }));
    await waitFor(() => expect(screen.getByRole('combobox', { name: '分析方式' }).closest('.ant-select')).toHaveTextContent('大模型分析'));
  });
});
