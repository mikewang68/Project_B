import { BarChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { init, use, type EChartsCoreOption } from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';

import { businessLabel } from '../../presentation/businessCopy';
import type { TimelineItem } from './model';

use([BarChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer]);

const statusColor: Record<string, string> = {
  WAITING: '#9aaab1',
  READY: '#3b82a0',
  IN_PROGRESS: '#178c84',
  COMPLETED: '#4f8f5b',
  SKIPPED: '#b98932',
  BLOCKED: '#d05b45',
  FAILED: '#b83232',
};

export function createOperationTimelineChart(element: HTMLElement, items: readonly TimelineItem[]) {
  const chart = init(element, undefined, { renderer: 'svg' });
  const option: EChartsCoreOption = {
    animationDuration: 450,
    grid: { left: 126, right: 26, top: 30, bottom: 38 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (raw: unknown) => {
        const params = raw as Array<{ dataIndex: number }>;
        const item = items[params[0]?.dataIndex ?? 0];
        return item
          ? `${item.label}<br/>计划：${item.planId}<br/>状态：${businessLabel(item.status)}<br/>时段：${item.startHour.toFixed(2)} - ${(item.startHour + item.durationHour).toFixed(2)}`
          : '';
      },
    },
    xAxis: {
      type: 'value', min: 8, max: 12,
      axisLabel: { formatter: (value: number) => `${Math.floor(value)}时` },
      splitLine: { lineStyle: { color: '#e8eef0' } },
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: items.map(({ label }) => label),
      axisLabel: { width: 112, overflow: 'truncate', color: '#465b64' },
    },
    series: [
      {
        name: '起始时间', type: 'bar', stack: '时间', silent: true,
        itemStyle: { color: 'transparent' }, emphasis: { itemStyle: { color: 'transparent' } },
        data: items.map(({ startHour }) => startHour),
      },
      {
        name: '计划工序', type: 'bar', stack: '时间', barWidth: 15,
        data: items.map(({ durationHour, status }) => ({
          value: durationHour,
          itemStyle: { color: statusColor[status] ?? '#607d86', borderRadius: 2 },
        })),
      },
    ],
  };
  chart.setOption(option);
  return chart;
}
