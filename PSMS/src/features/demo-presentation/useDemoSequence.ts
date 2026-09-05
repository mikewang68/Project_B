import { useCallback, useEffect, useRef, useState } from 'react';

import type { DemoSequence, DemoSequenceStep } from './demoSequence';

type MaybePromise = void | Promise<void>;

export type DemoPlaybackStatus = 'idle' | 'running' | 'completed' | 'error';

export type UseDemoSequenceOptions<StepId extends string> = Readonly<{
  sequence: DemoSequence<StepId>;
  autoplay?: boolean;
  onReset: () => MaybePromise;
  onStep: (step: DemoSequenceStep<StepId>, index: number) => MaybePromise;
  onComplete?: () => MaybePromise;
}>;

export type DemoSequencePlayback<StepId extends string = string> = Readonly<{
  status: DemoPlaybackStatus;
  currentStepId?: StepId;
  currentStepIndex: number;
  totalDurationMs: number;
  errorMessage?: string;
  start: () => Promise<void>;
  replay: () => Promise<void>;
  reset: () => Promise<void>;
}>;

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export function useDemoSequence<StepId extends string>({
  sequence,
  autoplay = false,
  onReset,
  onStep,
  onComplete,
}: UseDemoSequenceOptions<StepId>): DemoSequencePlayback<StepId> {
  const [status, setStatus] = useState<DemoPlaybackStatus>('idle');
  const [currentStepId, setCurrentStepId] = useState<StepId>();
  const [currentStepIndex, setCurrentStepIndex] = useState(-1);
  const [errorMessage, setErrorMessage] = useState<string>();
  const runTokenRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();
  const timerResolveRef = useRef<(() => void)>();
  const autoplayStartedRef = useRef(false);

  const cancelPendingWait = useCallback(() => {
    if (timerRef.current !== undefined) {
      clearTimeout(timerRef.current);
      timerRef.current = undefined;
    }
    timerResolveRef.current?.();
    timerResolveRef.current = undefined;
  }, []);

  const cancelRun = useCallback(() => {
    runTokenRef.current += 1;
    cancelPendingWait();
  }, [cancelPendingWait]);

  const waitForStep = useCallback((durationMs: number): Promise<void> => (
    new Promise((resolve) => {
      const effectiveDuration = prefersReducedMotion()
        ? Math.min(durationMs, 40)
        : durationMs;
      timerResolveRef.current = resolve;
      timerRef.current = setTimeout(() => {
        timerRef.current = undefined;
        timerResolveRef.current = undefined;
        resolve();
      }, effectiveDuration);
    })
  ), []);

  const start = useCallback(async (): Promise<void> => {
    cancelRun();
    const runToken = runTokenRef.current;
    setStatus('running');
    setCurrentStepId(undefined);
    setCurrentStepIndex(-1);
    setErrorMessage(undefined);
    try {
      await onReset();
      if (runToken !== runTokenRef.current) return;
      for (let index = 0; index < sequence.steps.length; index += 1) {
        const step = sequence.steps[index];
        if (!step || runToken !== runTokenRef.current) return;
        setCurrentStepId(step.id);
        setCurrentStepIndex(index);
        await onStep(step, index);
        if (runToken !== runTokenRef.current) return;
        await waitForStep(step.durationMs);
      }
      if (runToken !== runTokenRef.current) return;
      setStatus('completed');
      await onComplete?.();
    } catch (error) {
      if (runToken !== runTokenRef.current) return;
      setStatus('error');
      setErrorMessage(error instanceof Error ? error.message : '演示执行失败');
    }
  }, [cancelRun, onComplete, onReset, onStep, sequence.steps, waitForStep]);

  const replay = useCallback(() => start(), [start]);

  const reset = useCallback(async (): Promise<void> => {
    cancelRun();
    setStatus('idle');
    setCurrentStepId(undefined);
    setCurrentStepIndex(-1);
    setErrorMessage(undefined);
    await onReset();
  }, [cancelRun, onReset]);

  useEffect(() => {
    if (!autoplay || autoplayStartedRef.current) return;
    autoplayStartedRef.current = true;
    void start();
  }, [autoplay, start]);

  useEffect(() => () => {
    cancelRun();
  }, [cancelRun]);

  return {
    status,
    currentStepId,
    currentStepIndex,
    totalDurationMs: sequence.totalDurationMs,
    errorMessage,
    start,
    replay,
    reset,
  };
}
