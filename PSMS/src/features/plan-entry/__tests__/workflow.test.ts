import { describe, expect, it, vi } from 'vitest';

import { createPlanEntryWorkflowStore } from '../workflow';

describe('plan entry workflow store', () => {
  it('opens the circuit exactly on retry three and caps the retry count', () => {
    const store = createPlanEntryWorkflowStore();

    store.recordRetry();
    store.recordRetry();
    expect(store.getState()).toMatchObject({ retryCount: 2, circuitOpen: false });

    store.recordRetry();
    expect(store.getState()).toMatchObject({ retryCount: 3, circuitOpen: true });

    store.recordRetry();
    expect(store.getState()).toMatchObject({ retryCount: 3, circuitOpen: true });
  });

  it('replaces a stable frozen snapshot and stores resolved field names only', () => {
    const store = createPlanEntryWorkflowStore();
    const initial = store.getState();
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    expect(store.getState()).toBe(initial);
    expect(Object.isFrozen(initial)).toBe(true);
    expect(Object.isFrozen(initial.resolvedFields)).toBe(true);

    store.selectPlan('PLAN-002');
    const selected = store.getState();
    expect(selected).not.toBe(initial);
    expect(selected.selectedPlanId).toBe('PLAN-002');

    store.resolveFields('PLAN-002', ['trackNo', 'trackNo']);
    const resolved = store.getState();
    expect(resolved).not.toBe(selected);
    expect(resolved.resolvedFields).toEqual({ 'PLAN-002': ['trackNo'] });
    expect(Object.isFrozen(resolved.resolvedFields['PLAN-002'])).toBe(true);
    expect(resolved.resolvedFields['PLAN-002']).toEqual(
      expect.arrayContaining([expect.any(String)]),
    );
    expect(listener).toHaveBeenCalledTimes(2);

    unsubscribe();
    store.recordSuccess('2026-07-16T10:05:00+08:00');
    expect(listener).toHaveBeenCalledTimes(2);
    expect(store.getState()).toMatchObject({
      retryCount: 0,
      circuitOpen: false,
      lastSuccessAt: '2026-07-16T10:05:00+08:00',
    });
  });

  it('reset clears selection, retry, circuit, success, and resolved fields', () => {
    const store = createPlanEntryWorkflowStore();
    store.selectPlan('PLAN-002');
    store.recordRetry();
    store.recordSuccess('2026-07-16T10:05:00+08:00');
    store.resolveFields('PLAN-002', ['trackNo']);

    store.reset();

    expect(store.getState()).toEqual({
      retryCount: 0,
      circuitOpen: false,
      resolvedFields: {},
    });
  });
});
