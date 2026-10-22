import { Navigate } from 'react-router-dom';
import { useDemoSelector } from '../../runtime';
import PlanPicker, { chooseDefaultPlan } from '../../features/plan-entry/components/PlanPicker';

export default function PlanEntryPage({ mode }: { mode: 'tasks' | 'recommendation' }) {
  const state = useDemoSelector((current) => current);
  const plans = state.session.dataScope.some((area) => ['AREA-A', '*', 'GLOBAL'].includes(area)) ? state.plan.plans : [];
  const plan = chooseDefaultPlan(plans, mode);
  return plan ? <Navigate replace to={`/dispatch/plans/${encodeURIComponent(plan.id)}/${mode}`} /> : <PlanPicker mode={mode} />;
}
