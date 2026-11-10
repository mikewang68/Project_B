import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Space, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { z } from 'zod';
import { liveApi } from './client';
import { canRunBusinessAction, loadBusinessPage, type BusinessRow } from './business';
import type { SessionContext } from '../auth';

const lineSchema = z.object({ lineId: z.string(), goodCode: z.string(), plannedQty: z.number(), unit: z.string(),
  batchCode: z.string().optional(), supplierCode: z.string().optional() });
const detailSchema = z.object({ enabled: z.boolean(), companyCode:z.string().optional(),ownerCode:z.string().optional(),
  pendingSequences: z.array(z.number()), task: z.object({
  task_id: z.string(), state: z.string(), warehouse_code: z.string().nullable(), wms_order_code: z.string().nullable(),
  last_sequence: z.number(), error: z.string().nullable(),
  reconcile_error: z.string().nullable(), remote_snapshot: z.object({ lastSequence: z.number() }).nullable(),
  payload: z.object({ companyCode: z.string(), ownerCode: z.string(), expectedArrivalAt: z.string(),
    wagonNo: z.string().optional(), lines: z.array(lineSchema) }),
  progress: z.array(z.object({ lineId: z.string(), receivedQty: z.number(), remainingQty: z.number() })),
}).nullable() });
type Detail = z.infer<typeof detailSchema>;
const states: Record<string, string> = { PENDING_SEND: '待发送', WAITING_WAREHOUSE: '等待仓库确认',
  WAREHOUSE_CONFIRMED: '已确认仓库', PARTIALLY_RECEIVED: '部分收货', FULLY_RECEIVED: '已收齐', EXCEPTION: '需处理' };

export default function WmsJointInboundPanel({ row, session }: { row: BusinessRow; session: SessionContext }) {
  const [detail, setDetail] = useState<Detail>();
  const [tasks, setTasks] = useState<BusinessRow[]>([]);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0), [busy, setBusy] = useState(false);
  const [form] = Form.useForm();
  useEffect(()=>{if(row.dispatch_details)form.setFieldsValue(row.dispatch_details);},[form,row.dispatch_details]);
  const canDispatch = canRunBusinessAction(session, { code: 'dispatch', label: '派发入库任务', permission: 'pps:api:work-orders:write' });
  const canExecute = canRunBusinessAction(session, { code: 'execute', label: '执行任务', permission: 'pps:api:work-orders:execute' });
  const path = '/business/work-orders/' + encodeURIComponent(row.id) + '/inbound';
  useEffect(() => {
    let active = true;
    void liveApi(path).then(result => {
      if (active) { setDetail(detailSchema.parse(result.data)); setError(''); }
    }).catch(e => { if (active) setError(e instanceof Error ? e.message : '入库进度查询失败'); });
    return () => { active = false; };
  }, [path, reload]);
  useEffect(() => {
    const timer = window.setInterval(() => setReload(value => value + 1), 10000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    let active = true;
    if (detail?.task) void loadBusinessPage('tasks', { planId: String(row.plan_id), pageSize: '200' })
      .then(result => { if (active) setTasks(result.items.filter(task => task.work_order_id === row.id)); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : '前置任务查询失败'); });
    return () => { active = false; };
  }, [detail, row.id, row.plan_id]);
  const task = detail?.task;
  async function dispatch(values: Record<string, unknown>) {
    setBusy(true);
    try {
      const lines = (values.lines as Record<string, unknown>[]).map(line => Object.fromEntries(Object.entries(line).filter(([, value]) => value !== '' && value !== undefined)));
      await liveApi(path + '/dispatch', { method: 'POST', body: JSON.stringify({ lines, expectedArrivalAt: values.expectedArrivalAt,
        ...(values.wagonNo ? { wagonNo: values.wagonNo } : {}), expectedVersion: row.record_version }) });
      setReload(value => value + 1);
    } catch (e) { setError(e instanceof Error ? e.message : '派发回执未取得，请刷新核对原任务'); }
    finally { setBusy(false); }
  }
  async function runStep(step: BusinessRow, action: string) {
    setBusy(true);
    try {
      await liveApi(path + '/tasks', { method: 'POST', body: JSON.stringify({ taskId: step.id, action, reason: '服务器入库联调人工确认' }) });
      setReload(value => value + 1);
    } catch (e) { setError(e instanceof Error ? e.message : '任务回执未取得，请刷新核对'); }
    finally { setBusy(false); }
  }
  return <Card title="WMS 入库联动" style={{ marginTop: 20 }}>
    {error && <Alert type="error" showIcon title={error} />}
    {!detail ? <Button onClick={() => setReload(value => value + 1)}>重试查询</Button> : !detail.enabled ?
      <Alert type="info" title="当前环境尚未启用 WMS 联调" /> : task ? <Space orientation="vertical" style={{ width: '100%' }}>
        <Tag>{task.error ? '需处理' : states[task.state] ?? task.state}</Tag>
        <Descriptions column={1} items={[
          { key: 'task', label: '入库任务号', children: task.task_id },
          { key: 'warehouse', label: 'WMS 目的仓', children: task.warehouse_code ?? '等待仓储人员确认' },
          { key: 'order', label: 'WMS 入库单号', children: task.wms_order_code ?? '待建单' },
          { key: 'company', label: '公司 / 货主', children: task.payload.companyCode + ' / ' + task.payload.ownerCode },
          { key: 'arrival', label: '预计到货', children: task.payload.expectedArrivalAt },
        ]} />
        {task.error && <Alert type="warning" title={task.error} />}
        {task.state === 'PENDING_SEND' && task.error && <Button loading={busy} disabled={!canDispatch} onClick={async () => {
          setBusy(true);
          try { await liveApi(path + '/retry', { method: 'POST' }); setReload(value => value + 1); }
          catch (e) { setError(e instanceof Error ? e.message : '重试未取得回执，请刷新核对'); }
          finally { setBusy(false); }
        }}>按原任务重新发送</Button>}
        {task.reconcile_error && <Alert type="warning" title={task.reconcile_error} />}
        {task.remote_snapshot && task.remote_snapshot.lastSequence > task.last_sequence && <Alert type="warning"
          title={'WMS 已生成至回执 ' + task.remote_snapshot.lastSequence + '，本系统已应用至 ' + task.last_sequence}
          description="等待 WMS 补齐回执后再继续复核。" />}
        {detail.pendingSequences.length > 0 && <Alert type="warning" title={'回执缺号，已暂存序号 ' + detail.pendingSequences.join('、')}
          description={'已应用至 ' + task.last_sequence + '，等待 WMS 补发，补齐前继续保留当前进度。'} />}
        <Table pagination={false} rowKey="lineId" dataSource={task.payload.lines.map(line => ({ ...line,
          ...task.progress.find(progress => progress.lineId === line.lineId) }))} columns={[
          { title: '物料', dataIndex: 'goodCode' }, { title: '单位', dataIndex: 'unit' },
          { title: '计划量', dataIndex: 'plannedQty' }, { title: '累计实收', dataIndex: 'receivedQty' },
          { title: '剩余', dataIndex: 'remainingQty' },
        ]} />
        <Table rowKey="id" pagination={false} dataSource={tasks} columns={[
          { title: '前置任务', dataIndex: 'name' }, { title: '状态', dataIndex: 'status' },
          { title: '操作', render: (_, step: BusinessRow) => <Space>
            <Button disabled={busy || !canExecute || !['PENDING', 'READY', 'PAUSED'].includes(step.status)} onClick={() => void runStep(step, 'start')}>开始</Button>
            <Button disabled={busy || !canExecute || step.status !== 'IN_PROGRESS'} onClick={() => void runStep(step, 'complete')}>完成</Button>
          </Space> },
        ]} />
        <Button onClick={() => setReload(value => value + 1)}>刷新仓储进度</Button>
      </Space> : row.dispatch_details ? <Space orientation="vertical" style={{width:'100%'}}>
        <Alert type="info" title="派发使用上方已保存的派遣工单资料" description="公司与货主由服务器联动配置确定。修改物料或到达时间后，请先保存再派发。"/>
        <Button type="primary" loading={busy} disabled={!canDispatch||row.id.startsWith('SIM-RAIL-')||['CANCELLED','COMPLETED'].includes(row.status)}
          onClick={()=>void dispatch(row.dispatch_details as Record<string,unknown>)}>派发已保存的入库任务</Button>
      </Space> : <Form form={form} layout="vertical" initialValues={{ lines: [{ lineId: '1', plannedQty: 100 }] }} onFinish={dispatch}>
        <Alert type="info" title="请选择双方已建档的物料和库存单位" description="派发后由 WMS 确认目的仓。变更或取消需双方人工协调。" style={{ marginBottom: 16 }} />
        <Descriptions column={1} items={[
          {key:'company',label:'配置公司',children:detail.companyCode||'等待后台联动配置'},
          {key:'owner',label:'配置货主',children:detail.ownerCode||'等待后台联动配置'},
        ]}/>
        {!!row.dispatch_details&&<Alert type="info" title="派发使用上方已保存的派遣物料和预计到达时间" style={{marginBottom:12}}/>}
        {[
          ['expectedArrivalAt', '预计到货时间（含时区）'],
        ].map(([name, label]) => <Form.Item key={name} name={name} label={label} rules={[{ required: true, whitespace: true, max: 160 }]}>
          <Input placeholder={name === 'expectedArrivalAt' ? '2026-11-04T16:00:00+08:00' : undefined} />
        </Form.Item>)}
        <Form.Item name="wagonNo" label="车皮编号"><Input maxLength={160} /></Form.Item>
        <Form.List name="lines">{(fields, { add, remove }) => <>
          {fields.map(field => <Card key={field.key} title={'入库物料 ' + (field.name + 1)} size="small" style={{ marginBottom: 12 }}>
            {[
              ['lineId', '行号'], ['goodCode', '物料编码'], ['unit', '库存单位编码'],
            ].map(([name, label]) => <Form.Item key={name} name={[field.name, name]} label={label} rules={[{ required: true, whitespace: true, max: 160 }]}><Input /></Form.Item>)}
            <Form.Item name={[field.name, 'plannedQty']} label="计划数量（按物料库存单位）" rules={[{ required: true }]}><InputNumber min={0.001} max={1000000000} step={0.001} /></Form.Item>
            <Form.Item name={[field.name, 'batchCode']} label="批次编码"><Input maxLength={160} /></Form.Item>
            <Form.Item name={[field.name, 'supplierCode']} label="供应商编码"><Input maxLength={160} /></Form.Item>
            <Button disabled={fields.length === 1} onClick={() => remove(field.name)}>删除此行</Button>
          </Card>)}
          <Button disabled={fields.length >= 100} onClick={() => add({})}>增加物料行</Button>
        </>}</Form.List>
        <Button type="primary" htmlType="submit" loading={busy} disabled={!canDispatch || row.id.startsWith('SIM-RAIL-') || ['CANCELLED', 'COMPLETED'].includes(row.status)} style={{ marginTop: 16 }}>派发入库任务</Button>
      </Form>}
  </Card>;
}
