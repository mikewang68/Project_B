import { describe, expect, it } from 'vitest';

import { do015Schema, type ConfigVersion } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import {
  createSystemSettingsDraft,
  deriveConfigChanges,
  deriveSystemSettingsChangePreview,
  editableConfigKeys,
  setSystemSettingsDraftReason,
  updateSystemSettingsDraft,
  validateSystemSettingsDraft,
} from '../index';

function config(): ConfigVersion {
  return do015Schema.parse(createFixtureSnapshot().objects['DO-015'][0]);
}

describe('C13 system settings draft', () => {
  it('copies exactly the eight editable values and the Store base version', () => {
    const source = config();
    const draft = createSystemSettingsDraft(source);

    expect(Object.keys(draft.values)).toEqual(editableConfigKeys);
    expect(draft.baseVersion).toBe(source.version);
    expect(draft.reason).toBe('');
    expect(draft.values).not.toHaveProperty('id');
    expect(draft.values).not.toHaveProperty('status');
    expect(Object.isFrozen(draft)).toBe(true);
    expect(Object.isFrozen(draft.values)).toBe(true);
  });

  it('updates only allowlisted fields and keeps source drafts immutable', () => {
    const source = createSystemSettingsDraft(config());
    const updated = updateSystemSettingsDraft(source, 'displayName', '新的 Demo 名称');

    expect(source.values.displayName).not.toBe('新的 Demo 名称');
    expect(updated.values.displayName).toBe('新的 Demo 名称');
    expect(() => updateSystemSettingsDraft(
      source,
      'updatedBy' as never,
      'forbidden' as never,
    )).toThrow('Unsupported editable config field: updatedBy');
  });

  it('derives strict changes and a deterministic Chinese preview in whitelist order', () => {
    const source = config();
    let draft = createSystemSettingsDraft(source);
    draft = updateSystemSettingsDraft(draft, 'displayName', '新的 Demo 名称');
    draft = updateSystemSettingsDraft(draft, 'recommendationEnabled', false);
    draft = updateSystemSettingsDraft(draft, 'reportPeriod', 'DAILY');

    expect(deriveConfigChanges(source, draft)).toEqual({
      displayName: '新的 Demo 名称',
      recommendationEnabled: false,
      reportPeriod: 'DAILY',
    });
    expect(deriveSystemSettingsChangePreview(source, draft)).toEqual([
      {
        field: 'displayName', label: '配置名称',
        before: 'B项目生产调度演示', after: '新的演示名称',
      },
      {
        field: 'recommendationEnabled', label: '启用智能推荐',
        before: '已启用', after: '已停用',
      },
      {
        field: 'reportPeriod', label: '默认报表周期',
        before: '班报', after: '日报',
      },
    ]);
  });

  it('rejects field violations, empty reasons, and unchanged submissions', () => {
    const source = config();
    let draft = createSystemSettingsDraft(source);
    let result = validateSystemSettingsDraft(source, draft);
    expect(result.valid).toBe(false);
    expect(result.formErrors).toEqual(['至少修改一个配置字段。', '请填写变更说明。']);
    expect(deriveConfigChanges(source, draft)).toEqual({});

    draft = updateSystemSettingsDraft(draft, 'displayName', '   ');
    draft = updateSystemSettingsDraft(draft, 'ruleVersion', 'x'.repeat(33));
    draft = updateSystemSettingsDraft(draft, 'auditRetentionDays', 0);
    draft = setSystemSettingsDraftReason(draft, '   ');
    result = validateSystemSettingsDraft(source, draft);
    expect(result.valid).toBe(false);
    expect(result.fieldErrors).toMatchObject({
      displayName: expect.any(String),
      ruleVersion: expect.any(String),
      auditRetentionDays: expect.any(String),
    });

    const runtimeInvalid = updateSystemSettingsDraft(
      createSystemSettingsDraft(source),
      'dispatchStrategy',
      'RANDOM' as never,
    );
    expect(validateSystemSettingsDraft(source, runtimeInvalid).fieldErrors)
      .toHaveProperty('dispatchStrategy');
  });

  it('accepts a trimmed reason and valid typed changes', () => {
    const source = config();
    let draft = createSystemSettingsDraft(source);
    draft = updateSystemSettingsDraft(draft, 'auditRetentionDays', 730);
    draft = setSystemSettingsDraftReason(draft, '  调整 Demo 审计保留天数  ');

    const result = validateSystemSettingsDraft(source, draft);
    expect(result).toMatchObject({
      valid: true,
      fieldErrors: {},
      formErrors: [],
      changes: { auditRetentionDays: 730 },
      reason: '调整 Demo 审计保留天数',
    });
  });
});
