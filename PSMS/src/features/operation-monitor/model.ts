import type { DemoRootState } from '../../stores';

export type TimelineItem = Readonly<{
  id: string;
  label: string;
  startHour: number;
  durationHour: number;
  status: string;
  planId: string;
  resourceId: string;
}>;

export type MonitorEvent = Readonly<{
  id: string;
  time: string;
  level: '正常' | '提示' | '预警';
  source: string;
  content: string;
}>;

export function createTimeline(state: DemoRootState, planId?: string): TimelineItem[] {
  return state.workOrder.nodes
    .map((node) => {
      const order = state.workOrder.workOrders.find(({ workOrderNo }) => workOrderNo === node.workOrderNo);
      if (!order || (planId && order.planId !== planId)) return undefined;
      const start = new Date(node.plannedStartTime);
      const finish = new Date(node.plannedFinishTime);
      const startHour = Number.isNaN(start.getTime()) ? 8 + node.sequence * .2 : start.getHours() + start.getMinutes() / 60;
      const durationHour = Number.isNaN(finish.getTime())
        ? 1
        : Math.max(.25, (finish.getTime() - start.getTime()) / 3_600_000);
      return {
        id: node.id,
        label: `${node.nodeNo} ${order.title.replace('演示', '')}`,
        startHour,
        durationHour,
        status: node.status,
        planId: order.planId,
        resourceId: order.resourceId,
      };
    })
    .filter((item): item is TimelineItem => item !== undefined)
    .sort((left, right) => left.startHour - right.startHour);
}

export function createMonitorEvents(state: DemoRootState): MonitorEvent[] {
  return state.workOrder.nodes.slice(0, 6).map((node, index) => {
    const order = state.workOrder.workOrders.find(({ workOrderNo }) => workOrderNo === node.workOrderNo);
    const resource = state.resource.resources.find(({ id }) => id === order?.resourceId);
    const level = node.status === 'BLOCKED' || node.status === 'FAILED'
      ? '预警' as const
      : node.status === 'WAITING'
        ? '提示' as const
        : '正常' as const;
    return {
      id: `事件-${String(index + 1).padStart(2, '0')}`,
      time: node.updatedAt,
      level,
      source: resource?.location ? resource.location.replace('YARD-', '场区') : '调度中心',
      content: `${node.nodeNo} ${order?.title.replace('演示', '') ?? '作业任务'}状态更新`,
    };
  });
}

export function workAreaLabel(value: string): string {
  return ({ 'AREA-A': '一作业区', 'AREA-B': '二作业区', 'AREA-C': '三作业区' } as Record<string, string>)[value]
    ?? value;
}

export function yardLocationLabel(value: string): string {
  return value.replace('YARD-', '场区');
}
