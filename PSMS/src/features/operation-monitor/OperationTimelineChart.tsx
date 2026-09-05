import { useEffect, useRef } from 'react';

import type { TimelineItem } from './model';
import { createOperationTimelineChart } from './operationChartRuntime';

export default function OperationTimelineChart({ items }: { items: readonly TimelineItem[] }) {
  const elementRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!elementRef.current) return undefined;
    const chart = createOperationTimelineChart(elementRef.current, items);
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(elementRef.current);
    return () => {
      observer.disconnect();
      chart.dispose();
    };
  }, [items]);

  return <div ref={elementRef} className="operation-timeline-chart" role="img" aria-label="工序计划甘特图" />;
}
