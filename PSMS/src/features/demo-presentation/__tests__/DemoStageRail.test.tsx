import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import DemoStageRail from '../DemoStageRail';

afterEach(cleanup);

describe('DemoStageRail', () => {
  it('marks completed, current, and pending stages without exposing enum-like step ids', () => {
    render(
      <DemoStageRail
        ariaLabel="任务拆解演示阶段"
        currentStepIndex={1}
        stages={[
          { id: 'source', label: '读取计划' },
          { id: 'route', label: '生成路线' },
          { id: 'ready', label: '形成工单' },
        ]}
      />,
    );

    expect(screen.getByText('读取计划').closest('li')).toHaveAttribute('data-state', 'completed');
    expect(screen.getByText('生成路线').closest('li')).toHaveAttribute('aria-current', 'step');
    expect(screen.getByText('形成工单').closest('li')).toHaveAttribute('data-state', 'pending');
    expect(screen.queryByText('source')).not.toBeInTheDocument();
  });
});
