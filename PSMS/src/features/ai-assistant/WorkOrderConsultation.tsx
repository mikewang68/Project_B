import { Alert, Button, Input, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { withAppBasePath } from '../../runtime/appBasePath';
import { liveApi } from '../../real/client';
import { BackendConnectionFields, useBackendConnection } from '../backend-connection/BackendConnection';
import { consultationStatus, consultationTopics, orderExplanation, type ConsultationOrder } from './workOrderSnapshot';
import './workOrderChat.css';

type AiStatus = { configured: boolean; demoSession?: boolean; sso?: boolean; workOrderConsultation?: boolean; liveWorkOrderConsultation?: boolean };
type Turn = { question: string; answer?: string; mode?: 'rules' | 'model'; error?: string };

function explainWithoutModel(question: string, order: ConsultationOrder) {
  if (/阻塞|风险|失败|卡住|卡在|暂停|异常|原因|缺少|缺项/.test(question))
    return orderExplanation(order.snapshot, 'blockers');
  if (/下一步|接下来|怎么做|建议|如何处理|要做|做什么|怎么办/.test(question))
    return orderExplanation(order.snapshot, 'next');
  if (/进度|状态|完成|哪一步|到哪|回执|阶段|任务/.test(question))
    return orderExplanation(order.snapshot, 'progress');
  return '当前只能根据工单记录解读进度、已知阻塞和下一步。这个问题需要大模型才能继续分析。\n'
    + orderExplanation(order.snapshot, 'progress');
}

export default function WorkOrderConsultation({ order }: { order: ConsultationOrder }) {
  const [status, setStatus] = useState<AiStatus>();
  const [checking, setChecking] = useState(true);
  const [detection, setDetection] = useState(0);
  const [draft, setDraft] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [pending, setPending] = useState(false);
  const request = useRef<AbortController | null>(null);
  const demoToken = useRef('');
  const transcript = useRef<HTMLDivElement>(null);
  const connection = useBackendConnection();
  const business = order.snapshot.source === 'BUSINESS';
  const modelEnabled = status?.configured === true && status.workOrderConsultation === true
    && (!business || status.liveWorkOrderConsultation === true);
  const needsLogin = modelEnabled && !status?.sso && (!status?.demoSession || business) && !connection.token;

  useEffect(() => {
    const controller = new AbortController();
    setChecking(true);
    setStatus(undefined);
    void fetch(withAppBasePath('/api/ai/status'), {
      cache: 'no-store', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]),
    }).then(async response => {
      if (!response.ok) return;
      const data = await response.json() as AiStatus;
      if (!controller.signal.aborted) setStatus(data);
    }).catch(() => { /* Use work-order records when the model service is unreachable. */ })
      .finally(() => { if (!controller.signal.aborted) setChecking(false); });
    return () => controller.abort();
  }, [detection]);
  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => {
    const element = transcript.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [turns, pending]);

  async function ask(text = draft) {
    const question = text.trim();
    if (!question || checking || request.current) return;
    setDraft('');
    if (!modelEnabled || !status || needsLogin) {
      setTurns(previous => [...previous, { question, answer: explainWithoutModel(question, order), mode: 'rules' }]);
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setPending(true);
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(75_000)]);
    const history = turns.flatMap(turn => turn.mode === 'model' && turn.answer
      ? [{ question: turn.question, answer: turn.answer.slice(0, 1500) }] : []).slice(-6);
    setTurns(previous => [...previous, { question }]);
    const finish = (reply: Omit<Turn, 'question'>) => {
      if (!controller.signal.aborted) setTurns(previous => [...previous.slice(0, -1), { question, ...reply }]);
    };
    try {
      const body = JSON.stringify({ question, context: order.snapshot, ...(history.length ? { history } : {}) });
      let result: { answer?: unknown; message?: string };
      if (status.sso) {
        result = await liveApi('/ai/assist', { method: 'POST', body, signal });
      } else {
        if (status.demoSession && !business && !demoToken.current) {
          const response = await fetch(withAppBasePath('/api/ai/session'), {
            method: 'POST', headers: { 'x-psms-local-demo': '1' }, signal,
          });
          const data = await response.json();
          if (!response.ok || typeof data.data?.accessToken !== 'string') throw new Error('AI 会话创建失败，请重试。');
          demoToken.current = data.data.accessToken;
        }
        const token = status.demoSession && !business ? demoToken.current : connection.token;
        const response = await fetch(withAppBasePath('/api/ai/assist'), {
          method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body, signal,
        });
        result = await response.json();
        if (response.status === 401) demoToken.current = '';
        if (!response.ok) throw new Error(result.message || 'AI 工单咨询失败，请重试。');
      }
      if (typeof result.answer !== 'string' || !result.answer.trim()) throw new Error('AI 未返回有效回答。');
      finish({ mode: 'model', answer: result.answer });
    } catch (cause) {
      finish({
        error: signal.aborted ? '大模型响应超时。' : cause instanceof Error ? cause.message : '大模型暂未返回回答。',
        mode: 'rules',
        answer: explainWithoutModel(question, order),
      });
    } finally {
      if (request.current === controller) {
        request.current = null;
        if (!controller.signal.aborted) setPending(false);
      }
    }
  }

  return <Space orientation="vertical" size="small" style={{ width: '100%' }}>
    <Space wrap>
      <Typography.Text strong>{order.label}</Typography.Text>
      <Tag>{consultationStatus(order.snapshot.status)}</Tag>
      <Tag color={business ? 'blue' : 'orange'}>{business ? '业务工单' : '模拟工单'}</Tag>
    </Space>
    {!checking && !modelEnabled && <Alert type="info" showIcon
      title={business && status && !status.liveWorkOrderConsultation ? '正式工单当前使用记录解读。' : '大模型暂不可用，当前使用记录解读。'}
      action={<Button size="small" onClick={() => setDetection(value => value + 1)}>重新检测</Button>} />}
    {needsLogin && <BackendConnectionFields connection={connection} />}
    <div className="work-order-chat-log" ref={transcript} role="log" aria-label="工单对话记录">
      <div className="work-order-chat-bubble">
        <strong>工单助手</strong>
        <div>你可以直接问这张工单的进度、阻塞或下一步。</div>
      </div>
      {turns.map((turn, index) => <div key={index} className="work-order-chat-turn">
        <div className="work-order-chat-bubble work-order-chat-user"><strong>你</strong><div>{turn.question}</div></div>
        <div className="work-order-chat-bubble">
          <strong>{turn.mode === 'rules' ? '工单记录解读' : '工单 AI 回答'}</strong>
          {turn.error && <div className="work-order-chat-note">{turn.error}</div>}
          <div>{turn.answer ?? '正在回答…'}</div>
        </div>
      </div>)}
    </div>
    <Space wrap>{consultationTopics.map(topic => <Button key={topic.id} size="small" disabled={pending || checking}
      onClick={() => void ask(topic.question)}>{topic.label}</Button>)}</Space>
    <Input.TextArea aria-label="工单咨询问题" placeholder="输入问题，例如：它还有哪些任务没完成？"
      value={draft} maxLength={1000} rows={2} onChange={event => setDraft(event.target.value)}
      onKeyDown={event => {
        if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing || event.keyCode === 229) return;
        event.preventDefault();
        if (!pending) void ask();
      }} />
    <div className="work-order-chat-composer">
      <Typography.Text type="secondary">Enter 发送，Shift + Enter 换行</Typography.Text>
      <Button aria-label="发送" type="primary" loading={pending} disabled={checking || !draft.trim()} onClick={() => void ask()}>
        发送
      </Button>
    </div>
  </Space>;
}
