import { Button, Select, Space, Tag, Typography } from 'antd';

import type { PolicyContext, RoleCode } from '../../../auth';
import { routePolicies } from '../../../auth';
import { createC03Element } from '../../../app/routeCatalog';
import PermissionGate from '../../../components/PermissionGate';
import type { DemoScenario } from '../../../mocks/fixtures';
import { businessLabel } from '../../../presentation/businessCopy';

export type DemoScenarioBarProps = {
  date: string;
  workArea: string;
  role: RoleCode;
  scenarioId: DemoScenario['id'];
  scenarios: readonly DemoScenario[];
  demoTime: string;
  policyContext: PolicyContext;
  scenarioLoading?: boolean;
  resetLoading?: boolean;
  onScenarioChange?: (scenarioId: DemoScenario['id']) => void;
  onRoleChange?: (role: RoleCode) => void;
  onReset?: () => void;
};

function displayDemoTime(value: string): string {
  return value.replace('T', ' ').slice(0, 19);
}

export default function DemoScenarioBar({
  date,
  workArea,
  role,
  scenarioId,
  scenarios,
  demoTime,
  policyContext,
  scenarioLoading = false,
  resetLoading = false,
  onScenarioChange,
  onRoleChange,
  onReset,
}: DemoScenarioBarProps) {
  const resetButton = createC03Element(
    PermissionGate,
    {
      permission: 'demo:reset',
      context: { ...policyContext, permission: 'demo:reset' },
      mode: 'disable',
    },
    <Button
      type="default"
      loading={resetLoading}
      disabled={resetLoading || !onReset}
      onClick={onReset}
    >
      重置演示
    </Button>,
  );

  return (
    <section className="demo-scenario-bar" aria-label="演示上下文">
      <div className="demo-context-facts">
        <div className="demo-context-item">
          <Typography.Text type="secondary">业务日期</Typography.Text>
          <Typography.Text strong>{date}</Typography.Text>
        </div>
        <div className="demo-context-item">
          <Typography.Text type="secondary">作业区</Typography.Text>
          <Tag title={businessLabel(workArea)}>{businessLabel(workArea)}</Tag>
        </div>
        <div className="demo-context-item demo-context-control">
          <Typography.Text type="secondary">当前角色</Typography.Text>
          <Select<RoleCode>
            aria-label="当前角色"
            value={role}
            disabled={!onRoleChange}
            onChange={(value: RoleCode) => onRoleChange?.(value)}
            options={routePolicies['UI-001'].map((roleCode) => ({
              value: roleCode,
              label: businessLabel(roleCode),
            }))}
          />
        </div>
        <div className="demo-context-item demo-context-control demo-context-scenario">
          <Typography.Text type="secondary">当前场景</Typography.Text>
          <Select<DemoScenario['id']>
            className="demo-scenario-select"
            aria-label="演示场景"
            value={scenarioId}
            loading={scenarioLoading}
            disabled={scenarioLoading || !onScenarioChange}
            onChange={(value: DemoScenario['id']) => onScenarioChange?.(value)}
            options={scenarios.map(({ id, name }) => ({
              value: id,
              label: `${id} · ${name}`,
            }))}
          />
        </div>
        <div className="demo-context-item">
          <Typography.Text type="secondary">演示时间</Typography.Text>
          <Typography.Text>{displayDemoTime(demoTime)}</Typography.Text>
        </div>
      </div>
      <Space>{resetButton}</Space>
    </section>
  );
}
