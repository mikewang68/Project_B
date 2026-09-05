import { describe, expect, it } from 'vitest';

import {
  businessCode,
  businessCopyMap,
  businessLabel,
  businessTone,
  industryTermLabel,
} from '../businessCopy';

const requiredCopy = {
  RECEIVED: '已接收',
  READY: '就绪',
  WAITING: '等待处理',
  PENDING_CONFIRM: '待确认',
  BLOCKED: '已阻断',
  AVAILABLE: '可用',
  BUSY: '忙碌',
  ASSIGNED: '已分配',
  READY_QUEUE: '待派工',
  DISPATCHED: '已派工',
  EXECUTING: '执行中',
  IN_PROGRESS: '处理中',
  ACKNOWLEDGED: '已确认',
  PENDING_REVIEW: '待复核',
  HANDLING: '处置中',
  CLOSED: '已关闭',
  LOCKED: '联锁锁定',
  OVERRIDE_PENDING: '解锁审批中',
  RESET_REQUESTED: '恢复申请中',
  RESTORED: '已恢复',
  PENDING_UPLOAD: '待上传',
  VALIDATING: '校验中',
  CONFLICT: '版本冲突',
  MERGED: '已合并',
  SUCCESS: '成功',
  FAILED: '失败',
  RECOGNITION: '识别',
  INSPECT: '检查',
  UNLOAD: '卸载',
  TRANSFER: '转运',
  STORAGE: '入库',
  LOAD: '装载',
  DEVICE_OFFLINE: '设备离线',
  DATA_CONFLICT: '数据冲突',
  INTERLOCK: '安全联锁',
  TIMEOUT: '任务超时',
  INFO: '提示',
  MINOR: '一般',
  MAJOR: '重大',
  CRITICAL: '紧急',
} as const;

describe('业务展示文案映射', () => {
  it.each(Object.entries(requiredCopy))('%s 默认显示为 %s', (source, expected) => {
    expect(businessLabel(source)).toBe(expected);
  });

  it('覆盖需求规定的全部 40 项基础映射', () => {
    expect(Object.keys(requiredCopy)).toHaveLength(40);
    expect(Object.keys(requiredCopy).every((key) => key in businessCopyMap)).toBe(true);
  });

  it('不在主要展示位置直接暴露未知的大写枚举', () => {
    expect(businessLabel('A_NEW_INTERNAL_ENUM')).toBe('未配置展示文案');
    expect(businessLabel('A_NEW_INTERNAL_ENUM')).not.toMatch(/[A-Z][A-Z0-9_]{2,}/);
  });

  it('允许在次要技术详情中保留原始编码', () => {
    expect(businessCode('PENDING_CONFIRM')).toBe('PENDING_CONFIRM');
    expect(businessCode(undefined)).toBeUndefined();
  });

  it('按业务语义返回克制的视觉语气', () => {
    expect(businessTone('SUCCESS')).toBe('success');
    expect(businessTone('PENDING_REVIEW')).toBe('warning');
    expect(businessTone('LOCKED')).toBe('danger');
    expect(businessTone('EXECUTING')).toBe('processing');
    expect(businessTone('A_NEW_INTERNAL_ENUM')).toBe('neutral');
  });

  it('为行业缩写提供首次出现时的中文解释', () => {
    expect(industryTermLabel('TOS')).toBe('生产调度管理系统（TOS）');
    expect(industryTermLabel('PLC')).toBe('可编程逻辑控制器（PLC）');
    expect(industryTermLabel('ECS')).toBe('设备控制系统（ECS）');
    expect(industryTermLabel('AGV')).toBe('自动导引运输车（AGV）');
    expect(industryTermLabel('UWB')).toBe('超宽带定位（UWB）');
  });

  it.each([
    ['C03', '演示会话与权限'],
    ['C04', '外部到发信息台账'],
    ['C05', '生产准备建议'],
    ['C06', '任务拆解'],
    ['C07', '派工与执行'],
    ['C08', '异常处置'],
    ['C09', '安全联锁'],
    ['C10', '离线同步'],
    ['C11', '统计报表'],
  ])('审计来源 %s 显示为 %s', (source, expected) => {
    expect(businessLabel(source)).toBe(expected);
  });
});
