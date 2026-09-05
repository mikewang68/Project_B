import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { createFixtureSnapshot, validateFixtureSnapshot } from '../fixtures';

function readSourceFixtures(): Record<string, unknown> {
  return JSON.parse(
    readFileSync(join(process.cwd(), 'docs', 'baseline', 'demo-fixtures.json'), 'utf8'),
  ) as Record<string, unknown>;
}

describe('C02 fixture 装载', () => {
  it('只从冻结 JSON 建立等值深拷贝', () => {
    const source = readSourceFixtures();
    const first = createFixtureSnapshot();
    const second = createFixtureSnapshot();

    expect(first).toEqual(source);
    expect(second).toEqual(source);
    expect(first).not.toBe(second);
    expect(first.objects).not.toBe(second.objects);

    first.objects['DO-001'][0].trackNo = 'MUTATED-IN-CALLER';
    expect(second.objects['DO-001'][0].trackNo).not.toBe('MUTATED-IN-CALLER');
    expect(createFixtureSnapshot()).toEqual(source);
  });

  it('逐类校验 15 个对象集合与冻结数量', () => {
    const snapshot = validateFixtureSnapshot(createFixtureSnapshot());
    const expectedCounts = [3, 8, 4, 8, 12, 12, 10, 6, 5, 4, 4, 3, 9, 13, 1];

    expect(Object.keys(snapshot.objects)).toEqual(
      Array.from({ length: 15 }, (_, index) => `DO-${String(index + 1).padStart(3, '0')}`),
    );
    expect(Object.values(snapshot.objects).map((records) => records.length)).toEqual(expectedCounts);
  });

  it('拒绝被污染的基础对象，不把故障变体写回合法种子', () => {
    const snapshot = createFixtureSnapshot();
    snapshot.objects['DO-001'][0].unknownField = true;

    expect(() => validateFixtureSnapshot(snapshot)).toThrow();
    expect(validateFixtureSnapshot(createFixtureSnapshot()).objects['DO-001'][0]).not.toHaveProperty(
      'unknownField',
    );
  });
});
