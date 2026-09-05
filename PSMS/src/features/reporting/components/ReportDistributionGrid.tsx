import { Card, Tag, Typography } from 'antd';
import { useEffect, useRef } from 'react';

import type { ReportDistribution, ReportDistributions } from '../reportTypes';

type DistributionCardProps = {
  title: string;
  distributions: readonly ReportDistribution[];
  accent: string;
};

function DistributionCard({ title, distributions, accent }: DistributionCardProps) {
  const chartRef = useRef<HTMLDivElement>(null);
  const items = distributions.flatMap((distribution) => distribution.items.map((item) => ({
    ...item,
    label: distributions.length > 1 ? `${distribution.label.replace('分布', '')} · ${item.label}` : item.label,
  })));

  useEffect(() => {
    if (!chartRef.current || import.meta.env.MODE === 'test') return undefined;
    let disposed = false;
    let chart: { setOption(option: unknown): void; resize(): void; dispose(): void } | undefined;
    void import('../reportChartRuntime').then(({ createReportBarChart }) => {
      if (disposed || !chartRef.current) return;
      chart = createReportBarChart(chartRef.current, {
        animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
        grid: { top: 10, right: 8, bottom: 42, left: 30 },
        tooltip: { trigger: 'axis' },
        xAxis: {
          type: 'category',
          data: items.map(({ label }) => label),
          axisLabel: { color: '#61717a', fontSize: 10, interval: 0, rotate: items.length > 5 ? 25 : 0 },
          axisLine: { lineStyle: { color: '#d8e1e5' } },
        },
        yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#61717a' }, splitLine: { lineStyle: { color: '#edf2f4' } } },
        series: [{ type: 'bar', data: items.map(({ value }) => value), barMaxWidth: 24, itemStyle: { color: accent, borderRadius: [4, 4, 0, 0] } }],
      });
    });
    const resize = () => chart?.resize();
    window.addEventListener('resize', resize);
    return () => {
      disposed = true;
      window.removeEventListener('resize', resize);
      chart?.dispose();
    };
  }, [accent, items]);

  return (
    <Card className="report-distribution-card" size="small" title={title} aria-label={title}>
      <div ref={chartRef} className="report-chart" aria-label={`${title}图表`} />
      <div className="report-distribution-summary">
        {items.filter(({ value }) => value > 0).map(({ key, label, value }, index) => (
          <Tag key={`${key}-${index}`}>{label} {value}</Tag>
        ))}
        {items.every(({ value }) => value === 0) ? <Typography.Text type="secondary">暂无数据</Typography.Text> : null}
      </div>
    </Card>
  );
}

export default function ReportDistributionGrid({
  distributions,
}: {
  distributions: ReportDistributions;
}) {
  return (
    <section className="report-distribution-grid" aria-label="运行分布图表">
      <DistributionCard title="作业状态分布" distributions={[distributions.workOrderStatus]} accent="#147d92" />
      <DistributionCard title="异常分布" distributions={[distributions.exceptionLevel, distributions.exceptionType]} accent="#d97706" />
      <DistributionCard title="联锁动作等级分布" distributions={[distributions.interlockAction]} accent="#c24156" />
      <DistributionCard title="离线包状态分布" distributions={[distributions.offlineStatus]} accent="#5b5bd6" />
    </section>
  );
}
