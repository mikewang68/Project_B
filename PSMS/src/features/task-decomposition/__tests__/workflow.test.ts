import { describe, expect, it, vi } from 'vitest';
import { createTaskDecompositionWorkflowStore } from '../workflow';

describe('task-decomposition workflow store', () => {
  it('starts with the exact page-only state and immutable selection', () => {
    const store = createTaskDecompositionWorkflowStore();

    expect(store.getState()).toEqual({
      selectedNodeIds: [],
      editDrawerOpen: false,
      rulePanelOpen: false,
      reason: '',
    });
    expect(Object.isFrozen(store.getState())).toBe(true);
    expect(Object.isFrozen(store.getState().selectedNodeIds)).toBe(true);
  });

  it('notifies immutable snapshots and preserves the editor after a command failure', () => {
    const store = createTaskDecompositionWorkflowStore();
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    store.selectNodes(['C06-NODE-PLAN-001-G001-02']);
    store.openEditor('SPLIT', 'C06-NODE-PLAN-001-G001-02');
    store.setReason('按演示卸车波次拆分');
    store.setRulePanelOpen(true);
    store.recordCommandError({ errorCode: 'DEMO-VERSION-001', message: 'version conflict' });

    expect(store.getState()).toEqual({
      selectedNodeIds: ['C06-NODE-PLAN-001-G001-02'],
      editDrawerOpen: true,
      rulePanelOpen: true,
      mode: 'SPLIT',
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      reason: '按演示卸车波次拆分',
      lastCommandError: { errorCode: 'DEMO-VERSION-001', message: 'version conflict' },
    });
    expect(Object.isFrozen(store.getState().lastCommandError)).toBe(true);
    expect(listener).toHaveBeenCalledTimes(5);

    unsubscribe();
    store.recordCommandError(undefined);
    expect(listener).toHaveBeenCalledTimes(5);
    expect(store.getState().reason).toBe('按演示卸车波次拆分');
  });

  it('closeEditor clears success-sensitive state while reset restores the exact initial state', () => {
    const store = createTaskDecompositionWorkflowStore();
    store.selectNodes(['NODE-A', 'NODE-B']);
    store.openEditor('MERGE');
    store.setReason('恢复连续卸车阶段');
    store.recordCommandError({ errorCode: 'DEMO-SCENARIO-001', message: 'failed' });

    store.closeEditor();

    expect(store.getState()).toEqual({
      selectedNodeIds: [],
      editDrawerOpen: false,
      rulePanelOpen: false,
      reason: '',
    });

    store.openEditor('REGENERATE');
    store.setRulePanelOpen(true);
    store.reset();
    expect(store.getState()).toEqual({
      selectedNodeIds: [],
      editDrawerOpen: false,
      rulePanelOpen: false,
      reason: '',
    });
  });
});
