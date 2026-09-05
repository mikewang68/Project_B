import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { init, use, type EChartsCoreOption } from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';

use([BarChart, GridComponent, TooltipComponent, SVGRenderer]);

export function createReportBarChart(element: HTMLElement, option: EChartsCoreOption) {
  const chart = init(element, undefined, { renderer: 'svg' });
  chart.setOption(option);
  return chart;
}
