import { describe, expect, it } from 'vitest';
import { createMockRuntime } from '../scenarios';

describe('C02 场景 runtime', () => {
  it('冻结七套场景的种子、故障、错误码与延迟', () => {
    const runtime = createMockRuntime();

    expect(
      runtime.getScenarioCatalog().map((scenario) => ({
        id: scenario.id,
        seedRefs: scenario.seedRefs,
        fault: scenario.fault.type,
        errorCode: scenario.fault.errorCode,
        delayMs: scenario.fault.delayMs,
      })),
    ).toEqual([
      { id: 'SCN-01', seedRefs: ['PLAN-001'], fault: 'NONE', errorCode: null, delayMs: 300 },
      {
        id: 'SCN-02',
        seedRefs: ['PLAN-002'],
        fault: 'VALIDATION_MISSING_FIELD',
        errorCode: 'TOS-EXT-002',
        delayMs: 300,
      },
      {
        id: 'SCN-03',
        seedRefs: ['PLAN-003'],
        fault: 'INTERFACE_TIMEOUT',
        errorCode: 'TOS-EXT-001',
        delayMs: 1500,
      },
      {
        id: 'SCN-04',
        seedRefs: ['WO-005'],
        fault: 'DEVICE_OFFLINE',
        errorCode: 'TOS-WO-001',
        delayMs: 800,
      },
      {
        id: 'SCN-05',
        seedRefs: ['WO-006', 'IL-001'],
        fault: 'INTERLOCK_FORCE_STOP',
        errorCode: 'TOS-IL-001',
        delayMs: 500,
      },
      {
        id: 'SCN-06',
        seedRefs: ['OFF-001'],
        fault: 'OFFLINE_VERSION_CONFLICT',
        errorCode: 'TOS-OFF-001',
        delayMs: 700,
      },
      {
        id: 'SCN-07',
        seedRefs: ['APT-006'],
        fault: 'SOURCE_DATA_CONFLICT',
        errorCode: 'TOS-EXT-003',
        delayMs: 600,
      },
    ]);
  });

  it('故障只应用于投影视图，SCN-02 不污染合法基础 Plan', () => {
    const runtime = createMockRuntime();
    runtime.reset('SCN-02');

    const basePlan = runtime.getSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-002');
    const projectedPlan = runtime
      .getProjectedSnapshot()
      .objects['DO-001'].find(({ id }) => id === 'PLAN-002');

    expect(basePlan).toHaveProperty('trackNo');
    expect(projectedPlan).not.toHaveProperty('trackNo');
    expect(runtime.getSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-002')).toEqual(basePlan);
  });

  it('按对象记录已解决字段并在 reset 时清空，所有读取保持深拷贝', () => {
    const runtime = createMockRuntime();
    runtime.reset('SCN-02');

    expect(runtime.getResolvedFaultObjects()).toEqual({});

    runtime.resolveFaultFields('PLAN-002', ['trackNo']);

    expect(runtime.hasResolvedFaultFields('PLAN-002', ['trackNo'])).toBe(true);
    expect(runtime.hasResolvedFaultFields('PLAN-001', ['trackNo'])).toBe(false);

    const returned = runtime.getResolvedFaultObjects() as Record<string, string[]>;
    returned['PLAN-002']?.push('callerMutation');
    returned['PLAN-001'] = ['trackNo'];

    expect(runtime.getResolvedFaultObjects()).toEqual({ 'PLAN-002': ['trackNo'] });

    runtime.reset('SCN-02');
    expect(runtime.getResolvedFaultObjects()).toEqual({});
  });

  it('只为显式请求的同一已解决对象恢复投影字段', () => {
    const runtime = createMockRuntime();
    runtime.reset('SCN-02');

    const defaultProjection = runtime.getProjectedSnapshot();
    expect(defaultProjection.objects['DO-001'].find(({ id }) => id === 'PLAN-002')).not.toHaveProperty(
      'trackNo',
    );

    runtime.resolveFaultFields('PLAN-002', ['containerNo']);
    const unresolvedProjection = runtime.getProjectedSnapshot({ restoreResolvedObjectId: 'PLAN-002' });
    expect(unresolvedProjection.objects['DO-001'].find(({ id }) => id === 'PLAN-002')).not.toHaveProperty(
      'trackNo',
    );

    runtime.resolveFaultFields('PLAN-002', ['trackNo']);
    const restoredProjection = runtime.getProjectedSnapshot({ restoreResolvedObjectId: 'PLAN-002' });
    const wrongObjectProjection = runtime.getProjectedSnapshot({ restoreResolvedObjectId: 'PLAN-001' });

    expect(restoredProjection.objects['DO-001'].find(({ id }) => id === 'PLAN-002')).toHaveProperty(
      'trackNo',
      'T2',
    );
    expect(wrongObjectProjection.objects['DO-001'].find(({ id }) => id === 'PLAN-002')).not.toHaveProperty(
      'trackNo',
    );
    expect(restoredProjection.objects['DO-001'].find(({ id }) => id === 'PLAN-001')).toEqual(
      runtime.getSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-001'),
    );
  });

  it('runtime 变更事务失败时保持原值，reset 原子恢复全部确定性状态', () => {
    const runtime = createMockRuntime();
    const initial = runtime.getSnapshot();

    runtime.updateObject('PLAN-001', { trackNo: 'TRACK-TEMP' });
    expect(runtime.getObject('PLAN-001')).toMatchObject({ trackNo: 'TRACK-TEMP' });
    expect(() => runtime.updateObject('PLAN-001', { cargoType: 'INVALID' })).toThrow();
    expect(runtime.getObject('PLAN-001')).toMatchObject({ trackNo: 'TRACK-TEMP' });

    expect(runtime.nextEnvelopeIds()).toEqual({ traceId: 'TRACE-0001', auditLogId: 'AUD-0001' });
    runtime.forceNextFailure({
      status: 403,
      errorCode: 'TOS-AUTH-001',
      message: 'forbidden',
    });

    runtime.reset('SCN-01');

    expect(runtime.getSnapshot()).toEqual(initial);
    expect(runtime.now()).toBe('2026-07-16T09:00:00+08:00');
    expect(runtime.nextEnvelopeIds()).toEqual({ traceId: 'TRACE-0001', auditLogId: 'AUD-0001' });
    expect(runtime.consumeForcedFailure()).toBeUndefined();
  });

  it('所有外部读取均返回深拷贝', () => {
    const runtime = createMockRuntime();
    const snapshot = runtime.getSnapshot();
    const scenario = runtime.getActiveScenario();

    snapshot.objects['DO-001'][0].trackNo = 'CALLER-MUTATION';
    scenario.seedRefs.push('CALLER-MUTATION');

    expect(runtime.getSnapshot().objects['DO-001'][0].trackNo).not.toBe('CALLER-MUTATION');
    expect(runtime.getActiveScenario().seedRefs).toEqual(['PLAN-001']);
  });
});
