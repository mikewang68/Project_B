import { businessLabel } from '../../presentation/businessCopy';
import { z } from 'zod';

export type ConsultationSnapshot = {
  kind: 'WORK_ORDER';
  source: 'SIMULATION' | 'BUSINESS';
  workArea: string;
  status: string;
  orderType?: string;
  priority?: 'HIGH' | 'MEDIUM' | 'LOW' | 'URGENT' | 'NORMAL';
  resourceAssigned?: boolean;
  stagesCompleted?: number;
  stagesTotal?: number;
  lastStage?: string;
  tasksCompleted?: number;
  tasksTotal?: number;
  tasksBlocked?: number;
};

export type ConsultationOrder = { id: string; label: string; snapshot: ConsultationSnapshot };

const token = (value: unknown) => typeof value === 'string' && /^[A-Z_]{1,40}$/.test(value) ? value : undefined;
const priority = (value: unknown): ConsultationSnapshot['priority'] => {
  switch (value) {
    case 'HIGH': case 'MEDIUM': case 'LOW': case 'URGENT': case 'NORMAL': return value;
    default: return undefined;
  }
};

export function orderSnapshot(input: {
  source: ConsultationSnapshot['source']; workArea: string; status: unknown; orderType?: unknown;
  priority?: unknown; resourceAssigned?: boolean; tasks?: readonly unknown[];
}): ConsultationSnapshot {
  const { source, workArea, status, orderType, resourceAssigned, tasks } = input;
  const sample = tasks?.slice(0, 1000);
  return {
    kind: 'WORK_ORDER', source, workArea, status: token(status) ?? 'UNKNOWN',
    orderType: token(orderType), priority: priority(input.priority), resourceAssigned,
    ...(sample ? {
      tasksTotal: sample.length,
      tasksCompleted: sample.filter(state => state === 'COMPLETED').length,
      tasksBlocked: sample.filter(state => state === 'FAILED' || state === 'BLOCKED' || state === 'ERROR').length,
    } : {}),
  };
}

export function businessConsultation(row: { id: string; status: string; [key: string]: unknown }): ConsultationOrder {
  const orderType = row.order_type;
  return {
    id: row.id,
    label: row.id + ' · ' + businessLabel(String(orderType ?? row.status)),
    snapshot: orderSnapshot({
      source: row.id.startsWith('SIM-RAIL-') ? 'SIMULATION' : 'BUSINESS',
      workArea: typeof row.work_area === 'string' ? row.work_area : '',
      status: row.status, orderType, priority: row.priority,
      resourceAssigned: Boolean(row.equipment_id || row.assigned_operator),
    }),
  };
}

const railwayConsultationSchema = z.object({
  ticket: z.object({ id: z.string() }),
  run: z.object({ status: z.unknown().optional() }).nullable(),
  workOrder: z.object({
    order_type: z.unknown().optional(), priority: z.unknown().optional(),
    equipment_id: z.unknown().optional(), assigned_operator: z.unknown().optional(),
  }).nullable(),
  events: z.array(z.object({ stage: z.string() })),
  stages: z.array(z.string()),
  tasks: z.array(z.object({ status: z.unknown().optional() })),
});

export function railwayConsultation(input: unknown, id: string): ConsultationOrder {
  const data = railwayConsultationSchema.parse(input);
  if (data.ticket.id !== id) throw new Error('查询结果与工单编号不一致。');
  const lastStage = data.events.at(-1)?.stage;
  return { id, label: id, snapshot: {
    ...orderSnapshot({
      source: 'SIMULATION', workArea: 'AREA-A', status: data.run?.status ?? 'NOT_IMPORTED',
      orderType: data.workOrder?.order_type ?? 'UNLOADING', priority: data.workOrder?.priority,
      resourceAssigned: data.workOrder ? Boolean(data.workOrder.equipment_id || data.workOrder.assigned_operator) : undefined,
      tasks: data.tasks.map(task => task.status),
    }),
    stagesCompleted: data.events.length, stagesTotal: data.stages.length,
    lastStage: lastStage && /^[\u4e00-\u9fff]{1,20}$/.test(lastStage) ? lastStage : undefined,
  } };
}

export const consultationTopics = [
  { id: 'progress', label: '当前进度', question: '这张工单目前进展到哪一步？' },
  { id: 'blockers', label: '阻塞原因', question: '这张工单有已知阻塞吗？还缺哪些信息？' },
  { id: 'next', label: '下一步建议', question: '这张工单下一步需要核对或办理什么？' },
] as const;
export type ConsultationTopic = typeof consultationTopics[number]['id'];

export const consultationStatus = (status: string) => ({
  UNKNOWN: '未记录', NOT_IMPORTED: '尚未导入', ERROR: '执行异常', ACCEPTED: '已接单',
} as Record<string, string>)[status] ?? businessLabel(status);

export function orderExplanation(snapshot: ConsultationSnapshot, topic: ConsultationTopic) {
  const { status, resourceAssigned, stagesCompleted, stagesTotal, lastStage, tasksTotal, tasksCompleted, tasksBlocked } = snapshot;
  if (topic === 'progress') {
    return [
      '当前状态：' + consultationStatus(status) + '。',
      stagesTotal === undefined ? undefined : `已有 ${stagesCompleted ?? 0} / ${stagesTotal} 个阶段回执。`,
      lastStage && '最近阶段回执：' + lastStage + '。',
      tasksTotal === undefined ? undefined : `当前已记录任务 ${tasksTotal} 项，其中完成 ${tasksCompleted ?? 0} 项。`,
      stagesTotal === undefined && tasksTotal === undefined ? '这份记录没有阶段回执或任务统计，无法判断完成比例。' : undefined,
    ].filter(Boolean).join('\n');
  }
  if (topic === 'blockers') {
    const findings: string[] = [];
    if (['ERROR', 'FAILED', 'BLOCKED', 'PAUSED'].includes(status)) findings.push('记录显示：' + consultationStatus(status) + '。请在原工单详情核对原因。');
    if (tasksBlocked) findings.push(`已记录 ${tasksBlocked} 项失败或阻断任务。`);
    if (resourceAssigned === false && !['COMPLETED', 'CANCELLED'].includes(status)) findings.push('工单尚未记录设备或执行人的分配。');
    return findings.length ? findings.join('\n') : '本摘要未记录明确阻塞。请核对工单反馈、任务回执及现场联锁状态。';
  }
  const next: Record<string, string> = {
    NOT_IMPORTED: '先核对模拟工单资料，再从运行台导入。', DRAFT: '核对计划和派遣资料，按当前流程完成确认或派工。',
    READY: '核对执行人、设备和现场条件后，由授权人员派工。', ASSIGNED: '核对分配的设备和执行人，由执行人按流程接单。',
    ACCEPTED: '检查作业准备和安全条件后，由授权人员开始作业。', RUNNING: '继续查看阶段回执，核对最新已完成的步骤。',
    EXECUTING: '核对进行中的任务和反馈，确认是否具备进入下一阶段的条件。',
    IN_PROGRESS: '核对进行中的任务和反馈，确认是否具备进入下一阶段的条件。',
    PAUSED: '先核查暂停原因，再按审批和恢复流程处理。', BLOCKED: '先核查阻断原因，再按审批和恢复流程处理。',
    ERROR: '查看最近成功的回执和失败原因，再交由负责人处理。', FAILED: '查看失败任务与工单反馈，再交由负责人处理。',
    COMPLETED: '核对已保存的阶段回执、反馈及归档记录。', CANCELLED: '核对取消原因和审计记录。',
  };
  return next[status] ?? '打开原工单核对最新状态及反馈，再由调度员判断下一步。';
}
