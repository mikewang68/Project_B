import { describe, expect, it, vi } from 'vitest';

vi.mock('../src/config/env.js', () => ({
  isDev: false,
  env: {
    AI_BASE_URL: '', AI_MODEL: '', AI_API_KEY: '', DASHSCOPE_API_KEY: '',
    AI_CONFIG_SECRET: 'test-secret', AI_CONFIG_CACHE_TTL_MS: 0,
    JWT_SECRET: 'test-secret-1234567890', LOG_LEVEL: 'silent',
  },
}));

import { mergeDispatchDetails, workOrderMissingFields } from '../src/services/workOrderAi.service.js';

const base = {
  plannedWorkAt: '2026-11-10T14:00:00+08:00',
  operationLocation: 'T4',
  channel: '通道4',
  transferMode: 'DIRECT',
  expectedArrivalAt: '2026-11-10T13:30:00+08:00',
  vehiclePlate: '京A12345',
  lines: [{ lineId: '1', goodCode: 'WMS-1', unit: '吨', plannedQty: 1.025 }],
  draftSource: 'AI' as const,
};

describe('工单 AI 草稿合并与缺项', () => {
  it('dispatchDetails 按字段合并，未传字段保留，lines 整组替换', () => {
    const merged = mergeDispatchDetails(base, {
      channel: '通道5',
      lines: [{ lineId: '1', goodCode: 'WMS-2', unit: '吨', plannedQty: 2 }],
    });
    expect(merged.operationLocation).toBe('T4');
    expect(merged.channel).toBe('通道5');
    expect(merged.lines).toEqual([{ lineId: '1', goodCode: 'WMS-2', unit: '吨', plannedQty: 2 }]);
    expect(merged.draftSource).toBe('AI');
  });

  it('null 清空可选字段，undefined 不修改', () => {
    const merged = mergeDispatchDetails(base, { vehiclePlate: null, operationLocation: undefined });
    expect(Object.hasOwn(merged, 'vehiclePlate')).toBe(false);
    expect(merged.operationLocation).toBe('T4');
  });

  it('缺项路径使用点号和数组下标，补齐后为空', () => {
    const missing = workOrderMissingFields({
      description: '卸车', priority: 'MEDIUM', orderType: 'UNLOADING',
      dispatchDetails: { ...base, expectedArrivalAt: undefined, lines: [{ lineId: '1', goodCode: '', unit: '吨', plannedQty: 1 }] },
    });
    expect(missing).toContain('dispatchDetails.expectedArrivalAt');
    expect(missing).toContain('dispatchDetails.lines[0].goodCode');
    expect(workOrderMissingFields({ description: '卸车', priority: 'MEDIUM', orderType: 'UNLOADING', dispatchDetails: base })).toEqual([]);
  });
});
