import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../runtime', () => ({ useDemoSelector: () => ({
  session: { dataScope: ['AREA-A'], demoTime: '2026-07-19T08:00:00+08:00' },
  plan: { plans: [{ id: 'PLAN-1', planBatchNo: 'BATCH-1', trainNo: 'T88001', cargoType: 'FLY_ASH', status: 'CONFIRMED', trackNo: 'T1', arrivalDepartureTime: '2026-07-19T09:00:00+08:00', missingFields: [], conflicts: [], workArea: 'AREA-A' }] },
  resource: { resources: [{ workArea: 'AREA-A', resourceType: 'EQUIPMENT', status: 'AVAILABLE' }, { workArea: 'AREA-B', resourceType: 'EQUIPMENT', status: 'UNAVAILABLE' }] },
  workOrder: { workOrders: [
    { id: 'ORDER-1', workOrderNo: 'WO-001', planId: 'PLAN-1', status: 'BLOCKED', type: 'UNLOAD', priority: 'HIGH', resourceId: 'PRIVATE-EQUIPMENT' },
    { id: 'ORDER-2', workOrderNo: 'WO-002', planId: 'PLAN-1', status: 'COMPLETED', type: 'TRANSFER', priority: 'LOW' },
  ], nodes: [
    { workOrderNo: 'WO-001', status: 'FAILED' }, { workOrderNo: 'WO-002', status: 'COMPLETED' },
  ] },
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
  it('finds the exact order on Enter, supports follow-ups, and clears chat on switching orders', async () => {
    const fetcher = vi.fn(async (url: string, options?: RequestInit) => new Response(JSON.stringify(
      url.endsWith('/status') ? { configured: true, workOrderConsultation: true }
        : { answer: JSON.parse(String(options?.body)).history ? '下一步是核对失败回执。' : '这张工单需要先核对阻断任务。' },
    )));
    vi.stubGlobal('fetch', fetcher);
    const user = userEvent.setup();
    render(<MemoryRouter><AiAssistant /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'AI 调度助手' }));
    await user.click(screen.getByText('工单咨询', { exact: true }));
    const picker = screen.getByRole('textbox', { name: '工单编号' });
    const choose = async (number: string) => {
      await user.clear(picker);
      await user.type(picker, number + '{Enter}');
      await screen.findByText('工单助手', { exact: true });
    };
    await choose(' wo-001 ');
    const question = () => screen.getByRole('textbox', { name: '工单咨询问题' });
    await user.type(question(), '这单进展怎么样？');
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    await user.keyboard('{Enter}');
    expect(await screen.findByText('这张工单需要先核对阻断任务。')).toBeVisible();
    const body = fetcher.mock.calls.find(([url]) => url.endsWith('/assist'))?.[1]?.body as string;
    expect(JSON.parse(body).context).toMatchObject({ kind: 'WORK_ORDER', status: 'BLOCKED', tasksTotal: 1, tasksBlocked: 1 });
    expect(body).not.toMatch(/WO-001|ORDER-1|PRIVATE-EQUIPMENT|WO-002/);
    await user.type(question(), '那接下来要看什么？{Enter}');
    expect(await screen.findByText('下一步是核对失败回执。')).toBeVisible();
    const followup = fetcher.mock.calls.filter(([url]) => url.endsWith('/assist')).at(-1)?.[1]?.body as string;
    expect(JSON.parse(followup).history).toEqual([{ question: '这单进展怎么样？', answer: '这张工单需要先核对阻断任务。' }]);
    await choose('WO-002');
    expect(screen.queryByText('这张工单需要先核对阻断任务。')).not.toBeInTheDocument();
    await user.type(question(), '已经结束了吗？');
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    await user.keyboard('{Enter}');
    await waitFor(() => {
      const newOrder = fetcher.mock.calls.filter(([url]) => url.endsWith('/assist')).at(-1)?.[1]?.body as string;
      expect(JSON.parse(newOrder)).toMatchObject({ question: '已经结束了吗？', context: { status: 'COMPLETED' } });
      expect(JSON.parse(newOrder)).not.toHaveProperty('history');
    });
  });
});
