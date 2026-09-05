import { describe, expect, it } from 'vitest';
import { do001Schema, do003Schema, type Plan, type Track } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { calculateReceptionRecommendation } from '../ruleEngine';

function plan001(): Plan {
  const snapshot = createFixtureSnapshot();
  const plan = snapshot.objects['DO-001'].find(({ id }) => id === 'PLAN-001');
  return do001Schema.parse({ ...plan, status: 'CONFIRMED', version: 2 });
}

function frozenTracks(): Track[] {
  return do003Schema.array().parse(createFixtureSnapshot().objects['DO-003']);
}

describe('C05 deterministic recommendation rule engine', () => {
  it('calculates the frozen PLAN-001 scores, windows, ranking, and hard exclusion exactly', () => {
    const result = calculateReceptionRecommendation({
      plan: plan001(),
      tracks: frozenTracks(),
      generatedAt: '2026-07-16T09:00:00+08:00',
    });

    expect(result.candidates.map(({ trackNo, score, rank }) => [trackNo, score, rank])).toEqual([
      ['T1', 100, 1],
      ['T3', 62, 2],
      ['T2', 52, 3],
    ]);
    expect(result.excluded).toMatchObject([
      { trackNo: 'T4', exclusionCode: 'TRACK_BLOCKED' },
    ]);
    expect(result.candidates[0]).toMatchObject({
      candidateId: 'PLAN-001:TRACK-001:2026-07-16T08:01:00+08:00',
      windowStart: '2026-07-16T08:01:00+08:00',
      windowEnd: '2026-07-16T10:01:00+08:00',
      recommended: true,
      scoreBreakdown: { availability: 45, timing: 30, continuity: 15, authority: 10 },
    });
    expect(result.candidates[1]).toMatchObject({
      trackNo: 'T3',
      windowStart: '2026-07-16T11:03:00+08:00',
      windowEnd: '2026-07-16T13:03:00+08:00',
    });
    expect(
      Date.parse(result.candidates[1]!.windowEnd) - Date.parse(result.candidates[1]!.windowStart),
    ).toBe(2 * 60 * 60 * 1_000);
  });

  it('applies cargo incompatibility before unsupported occupancy and rejects invalid release time', () => {
    const base = frozenTracks()[0]!;
    const incompatibleUnsupported = {
      ...base,
      id: 'TRACK-X1',
      trackNo: 'TX1',
      occupyStatus: 'MAINTENANCE',
      compatibleCargoTypes: ['STEEL'],
    } as unknown as Track;
    const invalidRelease = do003Schema.parse({
      ...base,
      id: 'TRACK-X2',
      trackNo: 'TX2',
      occupyStatus: 'OCCUPIED',
      estimateReleaseTime: 'not-an-iso-time',
    });

    const result = calculateReceptionRecommendation({
      plan: plan001(),
      tracks: [incompatibleUnsupported, invalidRelease],
      generatedAt: '2026-07-16T09:00:00+08:00',
    });

    expect(result.candidates).toEqual([]);
    expect(result.excluded.map(({ trackNo, exclusionCode }) => [trackNo, exclusionCode])).toEqual([
      ['TX1', 'CARGO_INCOMPATIBLE'],
      ['TX2', 'INVALID_RELEASE_TIME'],
    ]);
  });

  it('orders equal scores by windowStart and then trackNo', () => {
    const free = frozenTracks()[0]!;
    const releasing = frozenTracks()[2]!;
    const tracks = [
      do003Schema.parse({ ...free, id: 'TRACK-T8', trackNo: 'T8' }),
      do003Schema.parse({ ...free, id: 'TRACK-T7', trackNo: 'T7' }),
      do003Schema.parse({
        ...releasing,
        id: 'TRACK-T5',
        trackNo: 'T5',
        estimateReleaseTime: '2026-07-16T08:06:00+08:00',
      }),
      do003Schema.parse({
        ...releasing,
        id: 'TRACK-T9',
        trackNo: 'T9',
        estimateReleaseTime: '2026-07-16T08:05:00+08:00',
      }),
    ];

    const result = calculateReceptionRecommendation({
      plan: plan001(),
      tracks,
      generatedAt: '2026-07-16T09:00:00+08:00',
    });

    expect(result.candidates.map(({ trackNo, score }) => [trackNo, score])).toEqual([
      ['T7', 90],
      ['T8', 90],
      ['T9', 80],
      ['T5', 80],
    ]);
  });

  it('does not mutate inputs and deep-freezes all returned objects and arrays', () => {
    const plan = plan001();
    const tracks = frozenTracks();
    const planBefore = structuredClone(plan);
    const tracksBefore = structuredClone(tracks);

    const result = calculateReceptionRecommendation({
      plan,
      tracks,
      generatedAt: '2026-07-16T09:00:00+08:00',
    });

    expect(plan).toEqual(planBefore);
    expect(tracks).toEqual(tracksBefore);
    expect(Object.isFrozen(result)).toBe(true);
    expect(Object.isFrozen(result.candidates)).toBe(true);
    expect(Object.isFrozen(result.candidates[0])).toBe(true);
    expect(Object.isFrozen(result.candidates[0]?.scoreBreakdown)).toBe(true);
    expect(Object.isFrozen(result.candidates[0]?.reasons)).toBe(true);
    expect(Object.isFrozen(result.excluded)).toBe(true);
  });

  it.each([
    'invalid/2026-07-16T10:01:00+08:00',
    '2026-07-16T10:01:00+08:00/invalid',
    '2026-07-16T10:01:00+08:00/2026-07-16T08:01:00+08:00',
  ])('rejects an invalid or non-positive plan interval: %s', (arrivalDepartureTime) => {
    const plan = do001Schema.parse({ ...plan001(), arrivalDepartureTime });
    expect(() =>
      calculateReceptionRecommendation({
        plan,
        tracks: frozenTracks(),
        generatedAt: '2026-07-16T09:00:00+08:00',
      }),
    ).toThrow();
  });
});
