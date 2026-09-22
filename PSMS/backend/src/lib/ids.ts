/**
 * 业务标识生成器。
 *
 * 审计主键与链路追踪标识统一在这里生成，保证命名规则一致、
 * 且在同一毫秒内并发调用也不会重复（追加进程内自增序列）。
 */

let sequence = 0;

function nextSequence(): number {
  sequence = (sequence + 1) % 100000;
  return sequence;
}

/** 形如 AUD-20260921153012345-0007 */
export function newAuditId(): string {
  const ts = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 17);
  return `AUD-${ts}-${String(nextSequence()).padStart(4, '0')}`;
}

/** 形如 TRACE-20260921153012345-0007 */
export function newTraceId(): string {
  const ts = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 17);
  return `TRACE-${ts}-${String(nextSequence()).padStart(4, '0')}`;
}
