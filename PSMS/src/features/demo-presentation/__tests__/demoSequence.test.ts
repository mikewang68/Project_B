import { describe, expect, it } from 'vitest';

import { createDemoSequence, demoAutoplayEnabled } from '../demoSequence';

describe('演示序列定义', () => {
  it('保留阶段顺序并计算总时长', () => {
    const sequence = createDemoSequence([
      { id: 'initial', durationMs: 500 },
      { id: 'scan', durationMs: 900 },
      { id: 'complete', durationMs: 1500 },
    ]);

    expect(sequence.steps.map(({ id }) => id)).toEqual(['initial', 'scan', 'complete']);
    expect(sequence.totalDurationMs).toBe(2900);
  });

  it('拒绝空序列、重复阶段和非正时长', () => {
    expect(() => createDemoSequence([])).toThrow('至少包含一个阶段');
    expect(() => createDemoSequence([
      { id: 'scan', durationMs: 500 },
      { id: 'scan', durationMs: 700 },
    ])).toThrow('阶段标识必须唯一');
    expect(() => createDemoSequence([{ id: 'scan', durationMs: 0 }]))
      .toThrow('阶段时长必须大于 0');
  });

  it('仅在查询参数明确为 autoplay=1 时自动播放', () => {
    expect(demoAutoplayEnabled('?autoplay=1')).toBe(true);
    expect(demoAutoplayEnabled('?scenarioId=SCN-01&autoplay=1')).toBe(true);
    expect(demoAutoplayEnabled('?autoplay=0')).toBe(false);
    expect(demoAutoplayEnabled('')).toBe(false);
  });
});
