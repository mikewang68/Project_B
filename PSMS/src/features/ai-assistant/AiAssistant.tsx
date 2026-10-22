import { Alert, Button, Card, Drawer, Input, Select, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useDemoSelector } from '../../runtime';
import { businessLabel } from '../../presentation/businessCopy';
import { BackendConnectionFields, useBackendConnection } from '../backend-connection/BackendConnection';

export default function AiAssistant() {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<'rules' | 'model'>('rules');
  const [configured, setConfigured] = useState(false);
  const [localDemo, setLocalDemo] = useState(false);
  const [demoToken, setDemoToken] = useState('');
  const [checking, setChecking] = useState(false);
  const [statusError, setStatusError] = useState('');
  const [statusAttempt, setStatusAttempt] = useState(0);
  const modeChosen = useRef(false);
  const [question, setQuestion] = useState('分析当前计划的生产准备和调度风险');
  const [answer, setAnswer] = useState('');
  const [answerSource, setAnswerSource] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const state = useDemoSelector((current) => current);
  const location = useLocation();
  const connection = useBackendConnection();
  const visible = state.session.dataScope.some((scope) => ['AREA-A', '*', 'GLOBAL'].includes(scope));
  const id = location.pathname.match(/\/plans\/([^/]+)/)?.[1] ?? new URLSearchParams(location.search).get('planId');
  const plan = visible ? state.plan.plans.find((item) => item.id === (id ? decodeURIComponent(id) : undefined)) ?? state.plan.plans.find((item) => item.status === 'CONFIRMED') ?? state.plan.plans[0] : undefined;
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setChecking(true); setStatusError('');
    void fetch('/api/ai/status', { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]) }).then((response) => {
      if (!response.ok) throw new Error('AI 服务暂未启动或无法连接，请启动后重试。');
      return response.json();
    }).then(async (data) => {
      if (controller.signal.aborted) return;
      setLocalDemo(data.demoSession === true);
      if (data.demoSession === true && data.configured === true) {
        const response = await fetch('/api/ai/session', { method: 'POST', headers: { 'x-psms-local-demo': '1' }, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]) });
        const result = await response.json();
        if (!response.ok || typeof result.data?.accessToken !== 'string') throw new Error('AI 服务自动登录失败，请重新检测。');
        if (!controller.signal.aborted) setDemoToken(result.data.accessToken);
      }
      if (controller.signal.aborted) return;
      setConfigured(data.configured === true);
      if (data.configured !== true) setStatusError('AI 服务未配置密钥，请配置后重启服务。');
      if (data.configured === true && !modeChosen.current) setMode('model');
    }).catch((cause) => {
      if (!controller.signal.aborted) { setConfigured(false); setStatusError(cause instanceof Error && cause.message.includes('AI 服务') ? cause.message : '无法连接 AI 服务，请启动后重试。'); }
    }).finally(() => { if (!controller.signal.aborted) setChecking(false); });
    return () => controller.abort();
  }, [open, statusAttempt]);
  useEffect(() => { setAnswer(''); setAnswerSource(''); setError(''); }, [location.pathname, location.search]);
  const analyze = async () => {
    if (!plan || !question.trim()) return;
    const resources = state.resource.resources.filter((item) => item.workArea === 'AREA-A');
    const orders = state.workOrder.workOrders.filter((item) => item.planId === plan.id);
    const unavailable = resources.filter((item) => item.status !== 'AVAILABLE');
    const summary = {
      dataSource: 'MOCK', snapshotAt: state.session.demoTime,
      plan: { planBatchNo: plan.planBatchNo, trainNo: plan.trainNo, cargoType: plan.cargoType, status: plan.status, trackNo: plan.trackNo, arrivalDepartureTime: plan.arrivalDepartureTime },
      workOrders: orders.map((item) => ({ status: item.status, type: item.type })),
      resources: resources.map((item) => ({ type: item.resourceType, status: item.status })),
      missingFields: plan.missingFields,
      conflicts: plan.conflicts.length,
    };
    setLoading(true); setError(''); setAnswer('');
    try {
      if (mode === 'model') {
        const response = await fetch('/api/ai/assist', { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${localDemo ? demoToken : connection.token}` }, body: JSON.stringify({ question: question.trim(), context: summary }), signal: AbortSignal.timeout(35_000) });
        const result = await response.json();
        if (!response.ok || typeof result.answer !== 'string') throw new Error(result.message ?? 'AI 服务暂不可用');
        setAnswer(result.answer); setAnswerSource('AI 分析结果');
      } else {
        const next = plan.status === 'PENDING_CONFIRM' || plan.status === 'RECEIVED' || plan.status === 'ADJUSTED'
          ? '先在外部到发信息台账确认计划，再生成并确认生产准备建议。'
          : plan.status === 'CONFIRMED' ? '检查股道建议并确认，然后进入任务拆解；确认任务定义后进入派工看板。'
          : plan.status === 'DECOMPOSED' ? '进入派工看板，核对可用资源和班组后派工。' : '先处理计划阻断或取消状态，再推进调度流程。';
        setAnswer(`计划：${plan.planBatchNo} / ${plan.trainNo}\n货类：${businessLabel(plan.cargoType)}，状态：${businessLabel(plan.status)}，股道：${plan.trackNo}\n\n准备建议：${next}\n\n风险核对：\n${plan.missingFields.length ? `• 待补字段：${plan.missingFields.join('、')}` : '• 必填字段完整'}\n• 来源数据冲突：${plan.conflicts.length} 项\n• A 区资源：${resources.length} 项，当前不可用 ${unavailable.length} 项\n• 当前计划工单：${orders.length} 项，其中阻断或失败 ${orders.filter((item) => ['BLOCKED', 'FAILED'].includes(item.status)).length} 项\n\n以上根据当前界面快照和固定业务规则生成，设备状态需在执行前复核。`);
        setAnswerSource('本地规则分析');
      }
    } catch (cause) { setError(cause instanceof Error ? cause.message : '分析失败'); }
    finally { setLoading(false); }
  };
  return <>
    <Button type="primary" onClick={() => setOpen(true)}>AI 调度助手</Button>
    <Drawer title="AI 调度助手" open={open} onClose={() => setOpen(false)} size={560}>
      <Space orientation="vertical" style={{ width: '100%' }} size={16}>
        <Alert type="info" title="基于当前生产计划分析准备条件和调度风险" description="分析结果供调度员参考；确认、派工和联锁操作仍通过业务页面完成。" />
        <Space wrap><Tag>{plan?.trainNo ?? '无可见计划'}</Tag><Select aria-label="分析方式" value={mode} onChange={(value) => { modeChosen.current = true; setMode(value); }} options={[
          { value: 'rules', label: '本地规则分析' }, { value: 'model', label: '大模型分析', disabled: !configured },
        ]} /></Space>
        {checking ? <Typography.Text type="secondary">正在检查 AI 服务…</Typography.Text> : statusError ? <Alert type="warning" title={statusError} action={<Button size="small" onClick={() => setStatusAttempt((value) => value + 1)}>重新检测</Button>} /> : <Typography.Text type="secondary">大模型分析会结合当前计划及资源状态摘要生成建议。</Typography.Text>}
        <Input.TextArea aria-label="向 AI 调度助手提问" value={question} maxLength={1000} rows={3} onChange={(event) => setQuestion(event.target.value)} />
        {mode === 'model' && !localDemo ? <BackendConnectionFields connection={connection} /> : null}
        <Button type="primary" loading={loading} disabled={!plan || !question.trim() || (mode === 'model' && (checking || !configured || !(localDemo ? demoToken : connection.token)))} onClick={() => void analyze()}>分析当前计划</Button>
        {error ? <Alert type="error" title={error} /> : null}
        {answer ? <Card size="small" title={answerSource}><Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{answer}</Typography.Paragraph></Card> : null}
      </Space>
    </Drawer>
  </>;
}
