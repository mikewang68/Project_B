import { Alert, Button, Card, Progress, Select, Space, Statistic, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';

import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import {
  createMonitorEvents,
  createOperationMonitorGateway,
  createTimeline,
  OperationTimelineChart,
  workAreaLabel,
  yardLocationLabel,
} from '../../features/operation-monitor';
import '../../features/operation-monitor/operation-monitor.css';
import { businessLabel, businessTone } from '../../presentation/businessCopy';
import { useDemoSelector } from '../../runtime';

const toneColor = {
  success: 'success', processing: 'processing', warning: 'warning', danger: 'error', neutral: 'default',
} as const;

const progressByStatus: Record<string, number> = {
  WAITING: 8, READY: 24, IN_PROGRESS: 68, COMPLETED: 100, SKIPPED: 100,
  BLOCKED: 46, FAILED: 38,
};

function formatEventTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date);
}

export default function OperationMonitorPage() {
  const state = useDemoSelector((current) => current);
  const planOptions = state.plan.plans.map((plan) => ({
    label: `${plan.id} · ${plan.trainNo}`, value: plan.id,
  }));
  const [planId, setPlanId] = useState(planOptions[0]?.value ?? 'PLAN-001');
  const [playing, setPlaying] = useState(false);
  const [clockStep, setClockStep] = useState(0);
  const [apiChecked, setApiChecked] = useState(false);
  const [notice, setNotice] = useState<{ type: 'success' | 'warning'; text: string }>();
  const gateway = useMemo(() => createOperationMonitorGateway(), []);
  const timeline = createTimeline(state, planId);
  const events = createMonitorEvents(state);
  const resources = state.resource.resources;

  useEffect(() => {
    let active = true;
    void gateway.listOperations(state.session.demoTime)
      .then((result) => {
        if (!active) return;
        setApiChecked(result.ok && result.scenarioId === state.scenario.activeScenarioId);
        if (!result.ok) setNotice({ type: 'warning', text: '作业监控接口暂不可用，当前展示本地固定快照。' });
      })
      .catch(() => {
        if (active) setNotice({ type: 'warning', text: '作业监控接口暂不可用，当前展示本地固定快照。' });
      });
    return () => { active = false; };
  }, [gateway, state.scenario.activeScenarioId, state.session.demoTime]);

  useEffect(() => {
    if (!playing) return undefined;
    const timer = window.setInterval(() => setClockStep((value) => (value + 1) % 12), 1_200);
    return () => window.clearInterval(timer);
  }, [playing]);

  const togglePlayback = async () => {
    if (playing) {
      setPlaying(false);
      setNotice({ type: 'success', text: '流程推演已暂停。' });
      return;
    }
    try {
      const result = await gateway.playScenario(state.scenario.activeScenarioId, 1);
      if (!result.ok) {
        setNotice({ type: 'warning', text: `流程推演未启动：${result.errorCode}` });
        return;
      }
      setPlaying(true);
      setNotice({ type: 'success', text: '流程推演已启动，监控游标将按固定节奏推进。' });
    } catch {
      setNotice({ type: 'warning', text: '流程推演请求未完成，请稍后重试。' });
    }
  };

  const activeOrders = state.workOrder.workOrders.filter(({ status }) =>
    ['DISPATCHED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PAUSED'].includes(status));
  const riskCount = state.workOrder.nodes.filter(({ status }) => ['BLOCKED', 'FAILED'].includes(status)).length;
  const resourceAvailable = resources.filter(({ status }) => status === 'AVAILABLE').length;
  const completed = state.workOrder.nodes.filter(({ status }) => status === 'COMPLETED').length;
  const selectedPlan = state.plan.plans.find(({ id }) => id === planId);

  return (
    <main className="operation-monitor-page" aria-label="UI-007 全流程监控页面" data-playing={playing}>
      <header className="operation-monitor-header">
        <PageIdentity pageId="UI-007" name="全流程监控" path="/monitor/operations" />
        <Space size={8} wrap>
          <Tag color={apiChecked ? 'success' : 'default'}>{apiChecked ? '监控接口已校验' : '本地快照就绪'}</Tag>
          <Tag color={playing ? 'processing' : 'default'}>{playing ? '正在推演' : '监控就绪'}</Tag>
          <Tag>{state.scenario.activeScenarioId}</Tag>
        </Space>
      </header>

      {notice ? <Alert showIcon type={notice.type} title={notice.text} closable onClose={() => setNotice(undefined)} /> : null}

      <section className="operation-toolbar" aria-label="监控条件">
        <div>
          <Typography.Text type="secondary">监控计划</Typography.Text>
          <Select aria-label="监控计划" value={planId} options={planOptions} onChange={setPlanId} />
        </div>
        <div className="operation-clock">
          <Typography.Text type="secondary">当前监控时点</Typography.Text>
          <strong>{formatEventTime(state.session.demoTime)}{playing ? ` + ${clockStep * 5} 分钟` : ''}</strong>
        </div>
        <Button type={playing ? 'default' : 'primary'} onClick={() => void togglePlayback()}>
          {playing ? '暂停推演' : '播放流程'}
        </Button>
      </section>

      <section className="operation-kpis" aria-label="全流程运行指标">
        <Card size="small"><Statistic title="执行中工单" value={activeOrders.length} suffix="项" /></Card>
        <Card size="small"><Statistic title="已完成工序" value={completed} suffix="项" /></Card>
        <Card size="small"><Statistic title="可用资源" value={resourceAvailable} suffix={`/ ${resources.length}`} /></Card>
        <Card size="small"><Statistic title="风险工序" value={riskCount} suffix="项" valueStyle={{ color: riskCount ? '#b83232' : '#4f8f5b' }} /></Card>
      </section>

      <section className="operation-main-grid">
        <Card
          title="工序计划甘特图"
          className="operation-timeline-card"
          extra={<Tag color="processing">{selectedPlan?.trainNo ?? planId}</Tag>}
        >
          <OperationTimelineChart items={timeline} />
          <div className="operation-chart-legend" aria-label="甘特图图例">
            {['等待处理', '就绪', '处理中', '已完成', '已阻断'].map((label) => <span key={label}>{label}</span>)}
          </div>
        </Card>

        <Card title="工序进度链" className="operation-chain-card">
          <div className="operation-chain" aria-label="工序进度链">
            {timeline.map((item, index) => (
              <div key={item.id} className="operation-chain-step">
                <div className="operation-chain-index">{index + 1}</div>
                <div>
                  <strong>{item.label}</strong>
                  <Typography.Text type="secondary">{item.resourceId || '资源待分配'}</Typography.Text>
                  <Progress
                    percent={progressByStatus[item.status] ?? 0}
                    size="small"
                    status={item.status === 'FAILED' ? 'exception' : undefined}
                    format={() => businessLabel(item.status)}
                  />
                </div>
              </div>
            ))}
          </div>
        </Card>

        <Card title="场区资源位置" className="operation-yard-card">
          <div className="operation-yard" aria-label="场区资源位置图">
            <span className="yard-zone zone-a">一作业区</span>
            <span className="yard-zone zone-b">二作业区</span>
            <span className="yard-zone zone-c">三作业区</span>
            {resources.map((resource, index) => (
              <button
                key={resource.id}
                type="button"
                className={`resource-dot tone-${businessTone(resource.status)}`}
                style={{ left: `${14 + (index % 4) * 22}%`, top: `${26 + Math.floor(index / 4) * 25}%` }}
                title={`${businessLabel(resource.resourceType)} · ${businessLabel(resource.status)}`}
              >
                <span>{resource.id.replace('RESOURCE-', '资源')}</span>
              </button>
            ))}
          </div>
        </Card>

        <Card title="资源运行状态" className="operation-resource-card">
          <div className="operation-resource-list" aria-label="资源运行状态">
            {resources.map((resource) => (
              <div key={resource.id}>
                <span>
                  <strong>{resource.id.replace('RESOURCE-', '资源')}</strong>
                  <small>{businessLabel(resource.resourceType)} · {yardLocationLabel(resource.location)}</small>
                </span>
                <span>
                  <Tag color={toneColor[businessTone(resource.status)]}>{businessLabel(resource.status)}</Tag>
                  <small>{workAreaLabel(resource.workArea)}</small>
                </span>
              </div>
            ))}
          </div>
        </Card>
      </section>

      <Card title="实时事件台账" extra={<Typography.Text type="secondary">最近 {events.length} 条</Typography.Text>}>
        <div className="operation-event-table-wrap">
          <table className="operation-event-table" aria-label="实时事件台账">
            <thead><tr><th>时间</th><th>级别</th><th>来源</th><th>事件</th><th>处置状态</th></tr></thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.id}>
                  <td>{formatEventTime(event.time)}</td>
                  <td><Tag color={event.level === '预警' ? 'error' : event.level === '提示' ? 'warning' : 'success'}>{event.level}</Tag></td>
                  <td>{event.source}</td>
                  <td>{event.content}</td>
                  <td>{event.level === '预警' ? '等待调度处置' : '已记录'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </main>
  );
}
