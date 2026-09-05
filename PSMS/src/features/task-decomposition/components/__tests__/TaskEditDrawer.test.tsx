import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { TaskTreeNodeView } from '../../types';
import TaskEditDrawer from '../TaskEditDrawer';

const node = {
  workOrderId: 'C06-WO-PLAN-001-G001-02',
  workOrderNo: 'C06-WO-PLAN-001-G001-02',
  nodeId: 'C06-NODE-PLAN-001-G001-02',
  nodeNo: 'C06-N-PLAN-001-G001-02',
  sequence: 2,
  stage: 'UNLOAD',
  taskType: 'UNLOAD',
  title: '卸料准备',
  objectId: 'C06-OBJECT-PLAN-001-G001-02',
  dependencyIds: ['C06-WO-PLAN-001-G001-01'],
  ruleVersion: 'C06-DEMO-RULE-1.0',
  status: 'DRAFT',
  nodeStatus: 'WAITING',
  requiredResourceType: 'TIPPER',
  resourceCandidateIds: ['RESOURCE-001', 'RESOURCE-007'],
  sourceRefs: ['PLAN:PLAN-001'],
  workOrderVersion: 1,
  nodeVersion: 1,
} satisfies TaskTreeNodeView;

afterEach(() => cleanup());

describe('TaskEditDrawer', () => {
  it('shows target and version warning, and requires a nonempty split reason', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    function Harness() {
      const [reason, setReason] = useState('');
      return (
        <TaskEditDrawer
          open
          mode="SPLIT"
          targetNodeId={node.nodeId}
          selectedNodes={[node]}
          reason={reason}
          loading={false}
          onReasonChange={setReason}
          onClose={() => undefined}
          onSubmit={onSubmit}
        />
      );
    }
    render(<Harness />);

    const drawer = screen.getByRole('dialog');
    expect(within(drawer).getByText('拆分任务节点')).toBeVisible();
    expect(within(drawer).getAllByText(node.nodeId, { exact: false }).length).toBeGreaterThan(0);
    expect(within(drawer).getByText('工单 v1 / 节点 v1', { exact: false })).toBeVisible();
    expect(within(drawer).getByText('提交前将再次校验版本', { exact: false })).toBeVisible();
    expect(within(drawer).getByRole('button', { name: '提交编辑' })).toBeDisabled();

    await user.type(within(drawer).getByRole('textbox', { name: '调整原因' }), '按卸车波次拆分');
    expect(within(drawer).getByRole('button', { name: '提交编辑' })).toBeEnabled();
    await user.click(within(drawer).getByRole('button', { name: '提交编辑' }));
    expect(onSubmit).toHaveBeenCalledWith({
      mode: 'SPLIT',
      targetNodeId: node.nodeId,
      reason: '按卸车波次拆分',
    });
  });

  it('shows an exact merge-disabled reason without invoking the command', () => {
    const onSubmit = vi.fn();
    render(
      <TaskEditDrawer
        open
        mode="MERGE"
        selectedNodes={[node]}
        reason="合并原因"
        invalidReason="合并要求两个同类型、相邻且直接依赖的节点"
        loading={false}
        onReasonChange={() => undefined}
        onClose={() => undefined}
        onSubmit={onSubmit}
      />,
    );

    const drawer = screen.getByRole('dialog');
    expect(within(drawer).getByText('合并要求两个同类型、相邻且直接依赖的节点')).toBeVisible();
    expect(within(drawer).getByRole('button', { name: '提交编辑' })).toBeDisabled();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
