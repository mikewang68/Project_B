import { describe, expect, it, vi } from 'vitest';

import { do015Schema } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import {
  createSystemSettingsWorkflowStore,
  type SystemSettingsCommandFeedback,
} from '../index';

const config = do015Schema.parse(createFixtureSnapshot().objects['DO-015'][0]);

function feedback(overrides: Partial<SystemSettingsCommandFeedback> = {}): SystemSettingsCommandFeedback {
  return {
    ok: true,
    commandId: 'CMD-C13-001',
    traceId: 'TRACE-C13-001',
    transportAuditLogId: 'AUD-TRANSPORT-023',
    domainAuditLogId: 'AUD-C13-001',
    message: '系统配置已保存',
    idempotent: false,
    version: 2,
    ...overrides,
  };
}

describe('C13 system settings workflow store', () => {
  it('starts from a frozen overview/idle state', () => {
    const workflow = createSystemSettingsWorkflowStore();
    expect(workflow.getState()).toEqual({
      selectedGroup: 'overview',
      dirtyFields: [],
      validationErrors: { fieldErrors: {}, formErrors: [] },
      pending: false,
      readState: 'idle',
    });
    expect(Object.isFrozen(workflow.getState())).toBe(true);
    expect(Object.isFrozen(workflow.getState().dirtyFields)).toBe(true);
  });

  it('tracks selection, read observations, and immutable draft changes', () => {
    const workflow = createSystemSettingsWorkflowStore();
    const listener = vi.fn();
    workflow.subscribe(listener);
    workflow.selectGroup('dispatch');
    workflow.setReadPending();
    workflow.recordReadSuccess({
      ok: true,
      data: {
        apiId: 'API-022', operationId: 'GET_mock_config',
        now: '2026-07-16T09:00:00+08:00', scenarioId: 'SCN-01', items: [config],
      },
      auditLogId: 'AUD-022', traceId: 'TRACE-022',
    });
    workflow.beginDraft(config);
    workflow.updateDraftField('displayName', '新的 Demo 名称');
    workflow.setDraftReason('配置变更');
    workflow.setValidationErrors({
      fieldErrors: { displayName: '名称错误' },
      formErrors: ['表单错误'],
    });

    expect(workflow.getState()).toMatchObject({
      selectedGroup: 'dispatch',
      readState: 'success',
      draft: {
        values: { displayName: '新的 Demo 名称' },
        reason: '配置变更',
        baseVersion: 1,
      },
      dirtyFields: ['displayName'],
      validationErrors: {
        fieldErrors: { displayName: '名称错误' }, formErrors: ['表单错误'],
      },
    });
    expect(workflow.getState().readObservation).not.toBe(config);
    expect(Object.isFrozen(workflow.getState().draft)).toBe(true);
    expect(listener).toHaveBeenCalledTimes(7);
  });

  it('retains a draft on failure, clears it on success/discard, and fully resets', () => {
    const workflow = createSystemSettingsWorkflowStore();
    workflow.beginDraft(config);
    workflow.updateDraftField('displayName', '新的 Demo 名称');
    workflow.setPending(true);
    workflow.recordFeedback(feedback({
      ok: false,
      message: '保存失败',
      errorCode: 'DEMO-SCENARIO-001',
      domainAuditLogId: 'AUD-C13-001',
      version: undefined,
    }));
    expect(workflow.getState()).toMatchObject({ pending: false, draft: expect.any(Object) });

    workflow.recordFeedback(feedback());
    expect(workflow.getState().draft).toBeUndefined();
    expect(workflow.getState().dirtyFields).toEqual([]);

    workflow.beginDraft(config);
    workflow.updateDraftField('ruleVersion', 'RULE-2.0');
    workflow.discardDraft();
    expect(workflow.getState().draft).toBeUndefined();

    workflow.recordReadFailure('网络不可用');
    workflow.selectGroup('access');
    workflow.reset();
    expect(workflow.getState()).toEqual({
      selectedGroup: 'overview',
      dirtyFields: [],
      validationErrors: { fieldErrors: {}, formErrors: [] },
      pending: false,
      readState: 'idle',
    });
  });
});
