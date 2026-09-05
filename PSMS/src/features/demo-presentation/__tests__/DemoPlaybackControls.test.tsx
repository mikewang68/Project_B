import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import DemoPlaybackControls from '../DemoPlaybackControls';
import { createDemoSequence } from '../demoSequence';
import { useDemoSequence } from '../useDemoSequence';

const sequence = createDemoSequence([
  { id: 'initial', durationMs: 500 },
  { id: 'scan', durationMs: 700 },
  { id: 'complete', durationMs: 1500 },
]);

function DemoHarness({
  autoplay = false,
  onReset = vi.fn(),
  onStep = vi.fn(),
  onComplete = vi.fn(),
}: {
  autoplay?: boolean;
  onReset?: () => void | Promise<void>;
  onStep?: (stepId: string) => void | Promise<void>;
  onComplete?: () => void | Promise<void>;
}) {
  const playback = useDemoSequence({
    sequence,
    autoplay,
    onReset,
    onStep: ({ id }) => onStep(id),
    onComplete,
  });

  return (
    <>
      <DemoPlaybackControls
        title="自动业务演示"
        boundary="样机演示 · 确定性 Mock"
        playback={playback}
      />
      <output aria-label="当前演示阶段">{playback.currentStepId ?? 'none'}</output>
    </>
  );
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('自动演示控制', () => {
  it('开始前复位，按序执行，并在最终结果停留', async () => {
    vi.useFakeTimers();
    const onReset = vi.fn();
    const onStep = vi.fn();
    const onComplete = vi.fn();
    render(<DemoHarness onReset={onReset} onStep={onStep} onComplete={onComplete} />);

    await act(async () => {
      screen.getByRole('button', { name: '开始演示' }).click();
      await Promise.resolve();
    });
    expect(onReset).toHaveBeenCalledTimes(1);
    expect(onStep).toHaveBeenNthCalledWith(1, 'initial');
    expect(screen.getByLabelText('当前演示阶段')).toHaveTextContent('initial');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(onStep).toHaveBeenNthCalledWith(2, 'scan');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2200);
    });
    expect(onStep).toHaveBeenNthCalledWith(3, 'complete');
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(screen.getByText('演示完成')).toBeVisible();
    expect(screen.getByLabelText('当前演示阶段')).toHaveTextContent('complete');
  });

  it('重新播放会取消旧序列、再次复位并从首阶段开始', async () => {
    vi.useFakeTimers();
    const onReset = vi.fn();
    const onStep = vi.fn();
    render(<DemoHarness onReset={onReset} onStep={onStep} />);

    await act(async () => {
      screen.getByRole('button', { name: '开始演示' }).click();
      await Promise.resolve();
    });
    await act(async () => {
      await vi.runAllTimersAsync();
    });
    await act(async () => {
      screen.getByRole('button', { name: '重新播放' }).click();
      await Promise.resolve();
    });

    expect(onReset).toHaveBeenCalledTimes(2);
    expect(onStep).toHaveBeenLastCalledWith('initial');
    expect(screen.getByLabelText('当前演示阶段')).toHaveTextContent('initial');
  });

  it('autoplay 只启动一次，并在卸载后清理计时器', async () => {
    vi.useFakeTimers();
    const onReset = vi.fn();
    const view = render(<DemoHarness autoplay onReset={onReset} />);

    await act(async () => {
      await Promise.resolve();
    });
    expect(onReset).toHaveBeenCalledTimes(1);
    view.rerender(<DemoHarness autoplay onReset={onReset} />);
    expect(onReset).toHaveBeenCalledTimes(1);

    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
