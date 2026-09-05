import { describe, expect, it, vi } from 'vitest';
import { createRecommendationWorkflowStore } from '../workflow';

describe('recommendation workflow store', () => {
  it('starts with only selection-independent drawer and error state', () => {
    const store = createRecommendationWorkflowStore();
    expect(store.getState()).toEqual({
      adjustmentDrawerOpen: false,
      ruleDrawerOpen: false,
    });
    expect(Object.isFrozen(store.getState())).toBe(true);
  });

  it('replaces immutable snapshots, notifies listeners, and keeps selection after an error', () => {
    const store = createRecommendationWorkflowStore();
    const initial = store.getState();
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    store.selectCandidate('PLAN-001:TRACK-003:window');
    const selected = store.getState();
    store.setAdjustmentDrawerOpen(true);
    store.setRuleDrawerOpen(true);
    store.recordCommandError({ errorCode: 'DEMO-VERSION-001', message: 'version conflict' });
    const failed = store.getState();

    expect(selected).not.toBe(initial);
    expect(failed).not.toBe(selected);
    expect(failed).toEqual({
      selectedCandidateId: 'PLAN-001:TRACK-003:window',
      adjustmentDrawerOpen: true,
      ruleDrawerOpen: true,
      lastCommandError: { errorCode: 'DEMO-VERSION-001', message: 'version conflict' },
    });
    expect(Object.isFrozen(failed)).toBe(true);
    expect(Object.isFrozen(failed.lastCommandError)).toBe(true);
    expect(listener).toHaveBeenCalledTimes(4);

    unsubscribe();
    store.recordCommandError(undefined);
    expect(listener).toHaveBeenCalledTimes(4);
    expect(store.getState().selectedCandidateId).toBe('PLAN-001:TRACK-003:window');
  });

  it('reset clears selection, drawers, and command error exactly', () => {
    const store = createRecommendationWorkflowStore();
    store.selectCandidate('candidate');
    store.setAdjustmentDrawerOpen(true);
    store.setRuleDrawerOpen(true);
    store.recordCommandError({ errorCode: 'TOS-AUTH-001', message: 'denied' });

    store.reset();

    expect(store.getState()).toEqual({
      adjustmentDrawerOpen: false,
      ruleDrawerOpen: false,
    });
  });
});
