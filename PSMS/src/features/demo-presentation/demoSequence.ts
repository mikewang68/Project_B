export type DemoSequenceStep<StepId extends string = string> = Readonly<{
  id: StepId;
  durationMs: number;
}>;

export type DemoSequence<StepId extends string = string> = Readonly<{
  steps: readonly DemoSequenceStep<StepId>[];
  totalDurationMs: number;
}>;

export function createDemoSequence<StepId extends string>(
  steps: readonly DemoSequenceStep<StepId>[],
): DemoSequence<StepId> {
  if (steps.length === 0) {
    throw new Error('演示序列至少包含一个阶段');
  }
  const ids = new Set<StepId>();
  let totalDurationMs = 0;
  for (const step of steps) {
    if (ids.has(step.id)) {
      throw new Error('演示阶段标识必须唯一');
    }
    if (!Number.isFinite(step.durationMs) || step.durationMs <= 0) {
      throw new Error('演示阶段时长必须大于 0');
    }
    ids.add(step.id);
    totalDurationMs += step.durationMs;
  }
  return Object.freeze({
    steps: Object.freeze(steps.map((step) => Object.freeze({ ...step }))),
    totalDurationMs,
  });
}

export function demoAutoplayEnabled(search: string): boolean {
  return new URLSearchParams(search).get('autoplay') === '1';
}
