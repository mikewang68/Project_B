import { describe, expect, it } from 'vitest';

import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import {
  editableConfigKeys,
  projectSystemSettings,
  systemSettingsGroupIds,
} from '../index';

const session: DemoSessionSeed = {
  actorId: 'USER-SYS_ADMIN',
  roleCode: 'SYS_ADMIN',
  dataScope: ['GLOBAL'],
  shiftId: 'SHIFT-001',
  online: true,
  scenarioId: 'SCN-01',
};

function createState() {
  const snapshot = createFixtureSnapshot();
  return {
    snapshot,
    store: createDemoStore(snapshot, session),
  };
}

describe('C13 system settings projection', () => {
  it('projects the sole DO-015 into five stable groups without inventing config fields', () => {
    const { snapshot, store } = createState();
    const projection = projectSystemSettings(
      store.getState(),
      { group: 'overview', configId: 'CFG-001', scenarioId: 'SCN-01' },
      { timezone: snapshot.timezone },
    );

    expect(projection.config).toEqual(snapshot.objects['DO-015'][0]);
    expect(projection.config?.id).toBe('CFG-001');
    expect(projection.groups.map(({ id }) => id)).toEqual(systemSettingsGroupIds);
    expect(projection.groups.flatMap(({ editableKeys }) => editableKeys)).toEqual(editableConfigKeys);
    expect(projection.groups.flatMap(({ readOnlyKeys }) => readOnlyKeys)).toEqual([
      'id', 'configVersion', 'status', 'version', 'createdAt', 'updatedAt', 'updatedBy',
      'api022', 'api023', 'externalSystems',
      'roleCode', 'dataScope', 'shiftId', 'online', 'timezone', 'demoTime', 'activeScenarioId',
    ]);
    for (const forbidden of [
      'areaCode', 'roleCode', 'dataScope', 'shiftId', 'online', 'timezone', 'demoTime',
      'activeScenarioId', 'api022', 'api023',
    ]) {
      expect(projection.config).not.toHaveProperty(forbidden);
    }
    expect(projection.context).toEqual({
      roleCode: 'SYS_ADMIN',
      dataScope: ['GLOBAL'],
      shiftId: 'SHIFT-001',
      online: true,
      timezone: 'Asia/Shanghai',
      demoTime: '2026-07-16T09:00:00+08:00',
      activeScenarioId: 'SCN-01',
    });
    expect(Object.isFrozen(projection)).toBe(true);
    expect(Object.isFrozen(projection.config)).toBe(true);
    expect(Object.isFrozen(projection.context.dataScope)).toBe(true);
  });

  it('does not fall back when an explicit config id is unknown', () => {
    const { snapshot, store } = createState();
    const projection = projectSystemSettings(
      store.getState(),
      { group: 'dispatch', configId: 'CFG-404' },
      { timezone: snapshot.timezone },
    );

    expect(projection.selectedGroup).toBe('dispatch');
    expect(projection.config).toBeUndefined();
    expect(projection.emptyReason).toBe('NOT_FOUND');
  });

  it('reports a genuine empty Store without creating a replacement config', () => {
    const { snapshot, store } = createState();
    store.replaceDomainState((candidate) => {
      candidate.systemConfig.configVersions = [];
    });

    const projection = projectSystemSettings(
      store.getState(),
      { group: 'overview', configId: 'CFG-001' },
      { timezone: snapshot.timezone },
    );
    expect(projection.config).toBeUndefined();
    expect(projection.emptyReason).toBe('STORE');
    expect(store.getState().systemConfig.configVersions).toEqual([]);
  });
});
