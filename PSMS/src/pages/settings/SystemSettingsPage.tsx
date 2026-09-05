import { useEffect, useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { authorize } from '../../auth';
import {
  deriveSystemSettingsChangePreview,
  parseSystemSettingsQuery,
  projectSystemSettings,
  SystemSettingsChangeSummary,
  SystemSettingsContextHeader,
  SystemSettingsFields,
  SystemSettingsGroupNav,
  SystemSettingsReadBanner,
  validateSystemSettingsDraft,
  type EditableConfigKey,
  type EditableConfigValues,
  type SystemSettingsGroupId,
} from '../../features/system-settings';
import {
  useDemoRuntime,
  useDemoSelector,
  useSystemSettingsWorkflow,
} from '../../runtime';
import { businessLabel } from '../../presentation/businessCopy';
import '../../features/system-settings/system-settings.css';

export default function SystemSettingsPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const runtime = useDemoRuntime();
  const state = useDemoSelector((root) => root);
  const workflow = useSystemSettingsWorkflow((snapshot) => snapshot);
  const query = useMemo(() => parseSystemSettingsQuery(location.search), [location.search]);
  const projection = useMemo(
    () => projectSystemSettings(state, query, { timezone: runtime.systemSettings.timezone }),
    [state, query, runtime.systemSettings.timezone],
  );
  const config = projection.config;

  useEffect(() => {
    if (runtime.systemSettings.workflow.getState().selectedGroup !== query.group) {
      runtime.systemSettings.workflow.selectGroup(query.group);
    }
  }, [query.group, runtime.systemSettings.workflow]);

  useEffect(() => {
    if (!config) return;
    let active = true;
    runtime.systemSettings.workflow.setReadPending();
    void runtime.systemSettings.gateway.listConfig({
      expectedScenarioId: state.scenario.activeScenarioId,
      expectedNow: state.session.demoTime,
      expectedConfigId: config.id,
    }).then((result) => {
      if (!active) return;
      if (result.ok && result.data.apiId === 'API-022') {
        runtime.systemSettings.workflow.recordReadSuccess(result);
        return;
      }
      if (!result.ok) {
        runtime.systemSettings.workflow.recordReadFailure(result.message, result);
        return;
      }
      runtime.systemSettings.workflow.recordReadFailure('系统配置响应契约不完整');
    }).catch((error: unknown) => {
      if (!active) return;
      const network = error instanceof TypeError && /network/i.test(error.message);
      runtime.systemSettings.workflow.recordReadFailure(
        network ? '系统配置读取暂不可用' : '系统配置响应契约不完整',
      );
    });
    return () => { active = false; };
  }, [
    config?.id,
    runtime.systemSettings.gateway,
    runtime.systemSettings.workflow,
    state.scenario.activeScenarioId,
    state.session.demoTime,
  ]);

  const editDecision = authorize({
    session: state.session,
    pageId: 'UI-012',
    permission: 'settings:edit',
  });
  const canEdit = editDecision.allow && config?.status === 'DRAFT';
  const editReason = editDecision.allow
    ? config?.status === 'DRAFT'
      ? undefined
      : `当前配置状态“${businessLabel(config?.status)}”不允许编辑。`
    : '当前角色或在线状态不允许编辑配置。';
  const preview = config && workflow.draft
    ? deriveSystemSettingsChangePreview(config, workflow.draft)
    : [];
  const readObservation = workflow.readObservation?.ok
    && workflow.readObservation.data.apiId === 'API-022'
    ? workflow.readObservation
    : undefined;

  const selectGroup = (group: SystemSettingsGroupId) => {
    runtime.systemSettings.workflow.selectGroup(group);
    const params = new URLSearchParams(location.search);
    params.set('group', group);
    void navigate({ pathname: location.pathname, search: `?${params.toString()}` }, { replace: true });
  };

  const changeField = <K extends EditableConfigKey>(
    key: K,
    value: EditableConfigValues[K],
  ) => runtime.systemSettings.workflow.updateDraftField(key, value);

  const save = async () => {
    if (!config || !workflow.draft) return;
    const validation = validateSystemSettingsDraft(config, workflow.draft);
    runtime.systemSettings.workflow.setValidationErrors({
      fieldErrors: validation.fieldErrors,
      formErrors: validation.formErrors,
    });
    if (!validation.valid) return;
    await runtime.systemSettings.commands.save({
      configId: config.id,
      expectedVersion: workflow.draft.baseVersion,
      changes: validation.changes,
      reason: validation.reason,
    });
  };

  if (projection.emptyReason === 'STORE') {
    return (
      <section className="system-settings-page system-settings-state">
        <span className="system-settings-id">UI-012</span>
        <h2>系统配置</h2>
        <p>当前状态快照中没有可见的 DO-015 配置</p>
        <p>演示系统配置视图，非生产配置中心</p>
      </section>
    );
  }
  if (projection.emptyReason === 'NOT_FOUND' || !config) {
    return (
      <section className="system-settings-page system-settings-state">
        <span className="system-settings-id">UI-012</span>
        <h2>系统配置</h2>
        <p>配置对象已变化或不存在</p>
        <p>演示系统配置视图，非生产配置中心</p>
      </section>
    );
  }

  return (
    <section className="system-settings-page">
      <SystemSettingsContextHeader
        config={config}
        context={projection.context}
        observation={readObservation}
      />
      <SystemSettingsReadBanner state={workflow} />
      <div className="system-settings-layout">
        <SystemSettingsGroupNav
          groups={projection.groups}
          selected={workflow.selectedGroup}
          onSelect={selectGroup}
        />
        <SystemSettingsFields
          config={config}
          context={projection.context}
          group={workflow.selectedGroup}
          draft={workflow.draft}
          disabled={workflow.pending}
          fieldErrors={workflow.validationErrors.fieldErrors}
          readObservation={readObservation}
          onChange={changeField}
        />
        <SystemSettingsChangeSummary
          draft={workflow.draft}
          preview={preview}
          validation={workflow.validationErrors}
          pending={workflow.pending}
          canEdit={canEdit}
          editReason={editReason}
          feedback={workflow.lastFeedback}
          onEdit={() => runtime.systemSettings.workflow.beginDraft(config)}
          onReason={(reason) => runtime.systemSettings.workflow.setDraftReason(reason)}
          onSave={() => { void save(); }}
          onDiscard={() => runtime.systemSettings.workflow.discardDraft()}
        />
      </div>
    </section>
  );
}
