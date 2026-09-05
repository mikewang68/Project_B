import { act, cleanup, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import {
  renderOfflineSyncPage,
  waitForOfflineSyncPage,
} from './offlineSyncTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-010 offline sync render', () => {
  it('shows identity, seven KPIs, four strict packet rows, detail, actions, and feedback', async () => {
    renderOfflineSyncPage();
    await waitForOfflineSyncPage();

    expect(screen.getByText('UI-010')).toBeVisible();
    expect(screen.getByText('当前路由：/operations/offline-sync')).toBeVisible();
    expect(screen.getByText('演示文本上下文，非生产外键')).toBeVisible();
    const kpis = screen.getByLabelText('离线同步指标');
    for (const label of ['缓存', '待上传', '校验中', '已合并', '冲突', '已驳回', '重试']) {
      expect(within(kpis).getByText(label, { exact: true })).toBeVisible();
    }
    const ledger = screen.getByLabelText('离线包台账');
    for (const packet of ['OFF-PKG-001', 'OFF-PKG-002', 'OFF-PKG-003', 'OFF-PKG-004']) {
      expect(within(ledger).getByText(packet)).toBeVisible();
    }
    expect(screen.getByLabelText('离线包详情')).toHaveTextContent('OFF-PKG-001');
    expect(screen.getByLabelText('离线包详情')).toHaveTextContent('PDA-01');
    expect(screen.getByLabelText('离线包详情')).toHaveTextContent('WO-006');
    expect(screen.getByLabelText('离线包状态流')).toHaveTextContent('版本冲突');
    expect(screen.getByLabelText('离线包处置面板')).toBeVisible();
    expect(screen.getByLabelText('C10 命令反馈')).toBeVisible();
    expect(screen.getByLabelText('离线缓存与冲突合并演示')).toBeVisible();
    expect(screen.getByLabelText('离线同步演示阶段')).toBeVisible();
  });

  it('hydrates all six display-only query labels and explains SCN-06 conflict recovery', async () => {
    renderOfflineSyncPage({
      scenarioId: 'SCN-06',
      search: '?packetId=OFF-001&terminalId=PDA-01&workOrderNo=WO-006'
        + '&mergeStatus=CONFLICT&scenarioId=SCN-06&from=exception-handling',
    });
    await waitForOfflineSyncPage();

    for (const label of [
      '来源模块：异常处置',
      '场景：SCN-06',
      '离线包：OFF-001',
      '终端：PDA-01',
      '作业单文本：WO-006',
      '状态：版本冲突',
    ]) {
      expect(screen.getByText(label)).toBeVisible();
    }
    expect(screen.getByText(
      'SCN-06 仅用于冲突识别与恢复引导；请重置到 SCN-01 后继续标准离线包处理闭环。',
    )).toBeVisible();
    expect(screen.getByLabelText('离线包详情')).toHaveTextContent('版本冲突');
    expect(screen.getByLabelText('离线包详情')).toHaveTextContent('包版本 2');
    expect(screen.getByLabelText('离线包详情')).toHaveTextContent('服务端版本 1');
    expect(within(screen.getByLabelText('离线包台账')).queryByText('OFF-PKG-002'))
      .not.toBeInTheDocument();
  });

  it('reactively renders UPLOADING, VALIDATING, MERGED, REJECTED, and RETRY states', async () => {
    const fixture = renderOfflineSyncPage();
    await waitForOfflineSyncPage();

    const setStatus = (
      status: 'PENDING_UPLOAD' | 'VALIDATING' | 'MERGED' | 'REJECTED' | 'RETRY',
    ) => {
      act(() => fixture.runtime.store.replaceDomainState((candidate) => {
        candidate.offline.packets.find(({ id }) => id === 'OFF-001')!.mergeStatus = status;
      }));
    };

    setStatus('PENDING_UPLOAD');
    expect(screen.getAllByText('上传中', { exact: true })[0]).toBeVisible();
    for (const [status, label] of [
      ['VALIDATING', '校验中'],
      ['MERGED', '已合并'],
      ['REJECTED', '已拒绝'],
      ['RETRY', '等待重试'],
    ] as const) {
      setStatus(status);
      expect(screen.getAllByText(label, { exact: true })[0]).toBeVisible();
    }
    expect(screen.getByLabelText('UI-010 离线同步页面')).toHaveClass('offline-sync-page');
  });
});
