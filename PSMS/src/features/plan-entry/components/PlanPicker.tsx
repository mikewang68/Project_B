import { Card, Empty, Select, Space, Tag } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useDemoRuntime, useDemoSelector } from '../../../runtime';
import { businessLabel } from '../../../presentation/businessCopy';

export function chooseDefaultPlan(plans: readonly { id: string; status: string }[], mode: string) {
  return (mode === 'tasks'
    ? plans.find((plan) => plan.status === 'CONFIRMED')
    : plans.find((plan) => plan.status === 'CONFIRMED')) ?? plans.find((plan) => !['CANCELLED', 'BLOCKED', 'DECOMPOSED'].includes(plan.status)) ?? plans[0];
}

export default function PlanPicker({ mode, planId }: { mode: 'tasks' | 'recommendation'; planId?: string }) {
  const navigate = useNavigate();
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const plans = state.session.dataScope.some((area) => ['AREA-A', '*', 'GLOBAL'].includes(area)) ? state.plan.plans : [];
  return <Card size="small" style={{ marginBlock: 16 }}>
    <Space wrap>
      <label htmlFor={`plan-picker-${mode}`}>选择生产计划</label>
      <Select id={`plan-picker-${mode}`} aria-label="选择生产计划" showSearch optionFilterProp="label"
        style={{ minWidth: 320 }} value={plans.some((plan) => plan.id === planId) ? planId : undefined}
        placeholder="选择车次或计划" options={plans.map((plan) => ({ value: plan.id, label: `${plan.trainNo} · ${plan.planBatchNo} · ${businessLabel(plan.status)}` }))}
        onChange={(id: string) => {
          runtime.recommendation.workflow.reset();
          runtime.taskDecomposition.workflow.reset();
          void navigate(`/dispatch/plans/${encodeURIComponent(id)}/${mode}`);
        }} />
      {mode === 'tasks' ? <Tag>默认优先选择已确认、尚未拆解的计划</Tag> : null}
    </Space>
    {!plans.length ? <Empty description="当前作业区没有可选计划，请先在台账导入计划" /> : null}
  </Card>;
}
