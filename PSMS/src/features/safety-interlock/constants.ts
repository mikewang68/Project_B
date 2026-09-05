export const SAFETY_INTERLOCK_FEATURE = 'C09' as const;
export const C09_AUDIT_PREFIX = 'SI' as const;
export const c09ProgressStates = ['LOCKED', 'RESETTING', 'RESTORED', 'OVERRIDE'] as const;

export const INTERLOCK_SOURCE_DISCLOSURE = '演示来源上下文，非生产外键' as const;
export const FORCE_STOP_WARNING =
  '强制停机仅演示安全流程；演示恢复记录不代表真实设备已复位。' as const;
