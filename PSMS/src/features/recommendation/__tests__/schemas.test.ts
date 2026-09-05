import { describe, expect, it } from 'vitest';
import { recommendationDraftSchema } from '../schemas';

const candidate = {
  candidateId: 'PLAN-001:TRACK-001:2026-07-16T08:01:00+08:00',
  trackId: 'TRACK-001',
  trackNo: 'T1',
  trackVersion: 1,
  occupyStatus: 'FREE' as const,
  windowStart: '2026-07-16T08:01:00+08:00',
  windowEnd: '2026-07-16T10:01:00+08:00',
  score: 100,
  rank: 1,
  recommended: true,
  scoreBreakdown: {
    availability: 45,
    timing: 30,
    continuity: 15,
    authority: 10,
  },
  reasons: ['股道当前空闲', '保持原计划股道连续性'],
  sourceRefs: ['PLAN:PLAN-001', 'TRACK:TRACK-001'],
};

const alternative = {
  ...candidate,
  candidateId: 'PLAN-001:TRACK-003:2026-07-16T11:03:00+08:00',
  trackId: 'TRACK-003',
  trackNo: 'T3',
  occupyStatus: 'RELEASING' as const,
  windowStart: '2026-07-16T11:03:00+08:00',
  windowEnd: '2026-07-16T13:03:00+08:00',
  score: 62,
  rank: 2,
  recommended: false,
  scoreBreakdown: {
    availability: 35,
    timing: 12,
    continuity: 5,
    authority: 10,
  },
};

const calculatedDraft = {
  planId: 'PLAN-001',
  draftVersion: 1,
  status: 'CALCULATED' as const,
  inputPlanVersion: 2,
  ruleVersion: 'C05-DEMO-RULE-1.0' as const,
  generatedAt: '2026-07-16T09:00:00+08:00',
  candidates: [candidate, alternative],
  excluded: [
    {
      trackId: 'TRACK-004',
      trackNo: 'T4',
      exclusionCode: 'TRACK_BLOCKED' as const,
      reason: '股道处于封锁状态',
      sourceRefs: ['PLAN:PLAN-001', 'TRACK:TRACK-004'],
    },
  ],
};

describe('C05 strict recommendation schema', () => {
  it('accepts complete CALCULATED and CONFIRMED drafts and deep-freezes parsed values', () => {
    const calculated = recommendationDraftSchema.parse(calculatedDraft);
    const confirmed = recommendationDraftSchema.parse({
      ...calculatedDraft,
      draftVersion: 2,
      status: 'CONFIRMED',
      selectedCandidateId: alternative.candidateId,
      adjustment: {
        originalCandidateId: candidate.candidateId,
        finalCandidateId: alternative.candidateId,
        reason: '错峰释放 T1，采用 T3',
        affectedWorkOrderIds: ['WO-004', 'WO-007'],
        reviewerId: 'USER-001',
      },
      confirmation: {
        actorId: 'E2E-DISPATCHER',
        roleCode: 'DISPATCHER',
        confirmedAt: '2026-07-16T09:00:00+08:00',
        commandId: 'CMD-C05-001',
        traceId: 'TRACE-C05-001',
      },
    });

    expect(calculated).toMatchObject({ status: 'CALCULATED', draftVersion: 1 });
    expect(confirmed).toMatchObject({
      status: 'CONFIRMED',
      selectedCandidateId: alternative.candidateId,
      confirmation: { roleCode: 'DISPATCHER' },
    });
    expect(Object.isFrozen(confirmed)).toBe(true);
    expect(Object.isFrozen(confirmed.candidates)).toBe(true);
    expect(Object.isFrozen(confirmed.candidates[0]?.scoreBreakdown)).toBe(true);
    expect(Object.isFrozen(confirmed.adjustment?.affectedWorkOrderIds)).toBe(true);
  });

  it.each([
    ['extra property', { ...calculatedDraft, unexpected: true }],
    ['invalid status', { ...calculatedDraft, status: 'READY' }],
    [
      'invalid exclusion code',
      {
        ...calculatedDraft,
        excluded: [{ ...calculatedDraft.excluded[0], exclusionCode: 'PUBLIC-ERROR' }],
      },
    ],
    [
      'score above 100',
      { ...calculatedDraft, candidates: [{ ...candidate, score: 101 }, alternative] },
    ],
    [
      'selected candidate absent from candidates',
      { ...calculatedDraft, selectedCandidateId: 'PLAN-001:TRACK-999:missing' },
    ],
    [
      'CONFIRMED without confirmation',
      { ...calculatedDraft, status: 'CONFIRMED', selectedCandidateId: candidate.candidateId },
    ],
    [
      'CALCULATED with confirmation',
      {
        ...calculatedDraft,
        confirmation: {
          actorId: 'USER-001',
          roleCode: 'DISPATCHER',
          confirmedAt: '2026-07-16T09:00:00+08:00',
          commandId: 'CMD-C05-001',
          traceId: 'TRACE-C05-001',
        },
      },
    ],
    [
      'adjustment final candidate differs from selection',
      {
        ...calculatedDraft,
        selectedCandidateId: candidate.candidateId,
        adjustment: {
          originalCandidateId: candidate.candidateId,
          finalCandidateId: alternative.candidateId,
          reason: '调整',
          affectedWorkOrderIds: ['WO-004'],
        },
      },
    ],
  ])('rejects %s', (_label, value) => {
    expect(() => recommendationDraftSchema.parse(value)).toThrow();
  });

  it('rejects a candidate without trackVersion', () => {
    const { trackVersion: _trackVersion, ...withoutTrackVersion } = candidate;
    expect(() =>
      recommendationDraftSchema.parse({
        ...calculatedDraft,
        candidates: [withoutTrackVersion, alternative],
      }),
    ).toThrow();
  });
});
