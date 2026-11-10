import { Alert, Button, Card, Collapse, Descriptions, Drawer, Space, Spin, Table, Tag, Timeline, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { z } from 'zod';
import { railwayApi } from './railwayClient';
import { businessCopyMap } from '../presentation/businessCopy';
import WorkOrderConsultation from '../features/ai-assistant/WorkOrderConsultation';
import { railwayConsultation } from '../features/ai-assistant/workOrderSnapshot';

type Fields = Record<string, unknown>;
const fields = z.record(z.string(), z.unknown());
const detailSchema = z.object({
  source: z.object({ kind: z.enum(['LOCAL_DEMO', 'SERVER_DATABASE']), database: z.string() }),
  ticket: z.object({ id: z.string(), business_date: z.string(), created_at: z.string(), payload: fields }),
  businessDetails: fields.nullable().optional(),
  detailsProvenance: z.object({source:z.enum(['SIMULATION_SUPPLEMENT','ORIGINAL_TICKET']),recordedAt:z.string(),actorId:z.string().nullable()}).optional(),
  run: fields.nullable(), plan: fields.nullable(), workOrder: fields.nullable(), equipment: fields.nullable(),
  tasks: z.array(fields), events: z.array(z.object({ id: z.string(), step: z.number(), stage: z.string(), detail: fields, occurred_at: z.string() })),
  auditLogs: z.array(fields), stages: z.array(z.string()),
});
type Detail = z.infer<typeof detailSchema>;
const labels: Record<string, string> = {
  id: '记录编号', version: '工单格式版本', simulated: '模拟来源', date: '业务日期', trainNo: '车次', cargoType: '货物类别',
  weight: '计划总重量（吨）', workArea: '作业区', trackNo: '股道', sourceStation: '始发站', arrivalTime: '计划到达时间', departureTime: '计划发出时间',
  companyCode: '公司编码', companyName: '公司名称', ownerCode: '货主编码', ownerName: '货主名称', destinationStation: '目的站',
  cargoName: '货物名称', cargoSpecification: '货物说明', priority: '优先级', plannedWorkAt: '计划作业时间', operationLocation: '具体作业点',
  channel: '现场通道', transferMode: '转运方式', vehiclePlate: '车辆编号或车牌', driverName: '司机', expectedArrivalAt: '预计到达时间',
  sourceWarehouseCode: '来源仓', wagonNo: '车皮编号', equipmentId: '计划设备编号', equipmentName: '计划设备名称',
  crewCode: '计划班组编码', crewName: '计划班组名称', operatorCode: '计划执行人编码', operatorName: '计划执行人名称',
  plan_id: '关联计划', plan_batch_no: '计划批次', train_no: '车次', cargo_type: '货物类别', estimated_weight: '计划重量', weight_unit: '重量单位',
  source_station: '始发站', destination_station: '目的站', arrive_time: '计划到达时间', track_no: '股道', work_area: '作业区',
  status: '状态', confirmed_by: '确认人', confirmed_at: '确认时间', supplements: '计划补充资料', created_at: '创建时间', updated_at: '最近更新时间',
  order_type: '作业类型', description: '作业说明', instructions: '作业指令说明', equipment_id: '实际设备编号', equipment_name: '实际设备名称',
  assigned_operator: '分配执行人', accepted_by: '接单人', accepted_at: '接单时间', actual_start_time: '实际开始时间', actual_end_time: '实际结束时间',
  dispatch_details: '已保存派遣资料', feedback_comment: '作业反馈', feedback_by: '反馈人', feedback_at: '反馈时间',
  name: '名称', type: '类型', model: '型号', specs: '设备规格与来源', last_heartbeat: '最近心跳', last_telemetry_at: '最近采集时间',
  task_no: '任务编号', order_no: '执行顺序', started_at: '开始时间', completed_at: '完成时间', actor_id: '触发人或后台任务',
  actor_role: '操作角色', action: '动作', object_type: '对象类型', object_id: '对象编号', after_state: '记录结果', reason: '操作原因',
  trace_id: '追踪编号', occurred_at: '记录时间', step: '阶段序号', stage: '阶段', progress: '进度（%）', unit: '单位',
  sourceSystem: '来源系统', planId: '计划编号', workOrderId: '作业工单编号', runId: '运行编号', actorId: '触发人',
  next_at: '下阶段计划执行时间', error: '错误说明', lineId: '物料行号', goodCode: '物料编码', plannedQty: '计划数量',
  batchCode: '批次', supplierCode: '供应商编码', wagonType: '车皮类型', plannedWeight: '计划重量（吨）',
};
function record(value: unknown): Fields | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Fields : undefined;
}
function items(value: unknown): Fields[] {
  return Array.isArray(value) ? value.flatMap(item => record(item) ? [record(item)!] : []) : [];
}
function valueText(key: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '未记录';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'string') {
    if (/^\d{4}-\d\d-\d\dT/.test(value) && Number.isFinite(Date.parse(value)))
      return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
    const copy: Record<string, string> = { DIRECT: '直卸', SHUTTLE: '车辆短驳', UNLOADING: '卸车', LOADING: '装车',
      DAILY_RAILWAY_SCHEDULER: '每日铁路工单任务', 'DAILY-RAILWAY-SCHEDULER': '每日铁路工单任务', SIMULATOR: '模拟执行器' };
    return copy[value] ?? businessCopyMap[value] ?? value;
  }
  return String(value);
}
function DataFields({ data }: { data: Fields | null | undefined }) {
  if (!data || !Object.keys(data).length) return <Typography.Text type="secondary">尚无落库记录</Typography.Text>;
  return <Descriptions bordered size="small" column={1} items={Object.entries(data).map(([key, value]) => ({
    key, label: labels[key] ?? key,
    children: record(value) ? <DataFields data={record(value)} /> : Array.isArray(value) ?
      <Space orientation="vertical">{value.map((item, index) => <div key={index}>{record(item) ? <DataFields data={record(item)} /> : valueText(key, item)}</div>)}</Space>
      : valueText(key, value),
  }))} />;
}
function without(data: Fields, excluded: string[]): Fields {
  return Object.fromEntries(Object.entries(data).filter(([key]) => !excluded.includes(key)));
}

export default function RailwayOrderDetails({ id, onClose }: { id: string; onClose: () => void }) {
  const [consulting, setConsulting] = useState(false);
  const [data, setData] = useState<Detail>(), [error, setError] = useState('');
  useEffect(() => {
    let active = true, pending = false;
    setData(undefined); setError('');
    const refresh = async () => {
      if (pending) return; pending = true;
      try {
        const result = detailSchema.parse((await railwayApi('/' + encodeURIComponent(id))).data);
        if (result.ticket.id !== id) throw new Error('工单详情与当前选择不一致');
        if (active) { setData(result); setError(''); }
      } catch (e) { if (active) setError(e instanceof Error ? e.message : '工单详情读取失败'); }
      finally { pending = false; }
    };
    void refresh(); const timer = window.setInterval(() => void refresh(), 3000);
    return () => { active = false; window.clearInterval(timer); };
  }, [id]);
  const details = record(data?.businessDetails) ?? record(data?.ticket.payload.details), dispatch = record(details?.dispatch);
  return <Drawer title={'铁路工单详情 · ' + id} open onClose={onClose} size="large" destroyOnHidden>
    {error && <Alert type="error" showIcon title={error} description={data ? '当前保留上次成功读取的记录。' : undefined} style={{ marginBottom: 16 }} />}
    {!data && !error && <Spin tip="正在读取完整工单"><div style={{ minHeight: 120 }} /></Spin>}
    {data && <Space orientation="vertical" style={{ width: '100%' }} size="middle">
      <Space wrap><Tag color="orange">模拟工单</Tag><Tag>{data.source.kind === 'SERVER_DATABASE' ? '服务器业务数据库' : '本机验证数据库'}</Tag>
        <Tag color={data.run?.status === 'ERROR' ? 'red' : data.run?.status === 'COMPLETED' ? 'green' : 'blue'}>{valueText('status', data.run?.status ?? '尚未导入')}</Tag>
        <Typography.Text>{data.source.database} · 时间均为北京时间</Typography.Text></Space>
      <Button disabled={Boolean(error)} onClick={() => setConsulting(value => !value)}>
        {consulting ? '收起工单咨询' : '咨询这张工单'}
      </Button>
      {consulting && !error && <Card size="small" title="铁路工单咨询">
        <WorkOrderConsultation key={id} order={railwayConsultation(data, id)} />
      </Card>}
      {data.run?.status === 'ERROR' && <Alert type="error" showIcon title="该工单执行已停止"
        description={valueText('error', data.run.error)} />}
      {!!details?.workSummary&&<Card title={String(details.scenarioName??'本工单作业内容')} size="small">
        <Typography.Paragraph style={{marginBottom:0}}>{String(details.workSummary)}</Typography.Paragraph>
        {!!details.scenarioCode&&<Typography.Text type="secondary">业务方案号 {String(details.scenarioCode)}</Typography.Text>}
      </Card>}
      {data.detailsProvenance?.source==='SIMULATION_SUPPLEMENT'&&<Alert type="warning" showIcon
        title="本工单的专属模拟资料为后来补录"
        description={'补录时间 '+valueText('recordedAt',data.detailsProvenance.recordedAt)+'。原始执行时间和阶段回执可在下方核对。'}/>}
      <Card title="工单基础信息" size="small"><DataFields data={{ ...without(data.ticket.payload, ['details']), created_at: data.ticket.created_at }} /></Card>
      {details ? <>
        <Alert type="info" title="以下公司、物料、车皮及计划资源为持久化的模拟资料" />
        <Card title="公司、货主与货物" size="small"><DataFields data={without(details, ['dispatch', 'wagons', 'resources', 'checkpoints','materialDescriptions','scenarioName','scenarioCode','workSummary'])} /></Card>
        <Card title="作业安排" size="small"><DataFields data={dispatch ? without(dispatch, ['lines']) : undefined} />
          <Typography.Paragraph style={{ marginTop: 12 }}>目的仓与库位待 WMS 确认。</Typography.Paragraph></Card>
        <Card title="物料与批次明细" size="small"><Table<Fields> rowKey={line => String(line.lineId)} size="small" pagination={false}
          dataSource={items(dispatch?.lines).map(line=>({...line,...items(details.materialDescriptions).find(description=>description.lineId===line.lineId)}))} scroll={{ x: 1200 }} columns={[
            ['lineId', '行号'], ['goodName','物料名称'], ['goodCode', '模拟物料编码'], ['specification','规格说明'],
            ['plannedQty', '计划数量'], ['unit', '单位'], ['batchCode', '批次'], ['packaging','包装与计量口径'],['supplierName','模拟供应商'],['supplierCode', '模拟供应商编码'],
          ].map(([key, title]) => ({ key, title, dataIndex: key, render: value => valueText(key, value) }))} /></Card>
        <Card title={'车皮明细 · ' + items(details.wagons).length + ' 辆'} size="small"><Table<Fields> rowKey={wagon => String(wagon.wagonNo)}
          size="small" dataSource={items(details.wagons)} pagination={{ pageSize: 10, showSizeChanger: false }} scroll={{ x: 600 }} columns={[
            ['wagonNo', '模拟车皮编号'], ['wagonType', '车皮类型'], ['plannedWeight', '计划重量（吨）'], ['unit', '单位'],
          ].map(([key, title]) => ({ key, title, dataIndex: key, render: value => valueText(key, value) }))} /></Card>
        <Card title="计划人员与设备" size="small"><DataFields data={record(details.resources)} />
          <Typography.Paragraph style={{ marginTop: 12 }}>计划核验要求</Typography.Paragraph>
          {Array.isArray(details.checkpoints) && <ul>{details.checkpoints.map((item, index) => <li key={index}>{String(item)}</li>)}</ul>}</Card>
      </> : <Alert type="info" title="历史工单的原始票据未记录扩展明细" description="下方展示这张工单已有的计划、作业工单、任务及执行流水。" />}
      <Collapse items={[
        { key: 'plan', label: '已落库计划', children: <DataFields data={data.plan} /> },
        { key: 'order', label: '已落库作业工单', children: <DataFields data={data.workOrder} /> },
        { key: 'device', label: '实际分配的模拟设备', children: <DataFields data={data.equipment} /> },
        { key: 'run', label: '后台运行记录', children: <DataFields data={data.run} /> },
      ]} />
      <Card title="任务执行明细" size="small"><Table<Fields> rowKey={task => String(task.id)} size="small" pagination={false}
        dataSource={data.tasks} scroll={{ x: 650 }} expandable={{ expandedRowRender: row => <DataFields data={row} /> }} columns={[
          ['name', '任务'], ['status', '状态'], ['started_at', '开始时间'], ['completed_at', '完成时间'],
        ].map(([key, title]) => ({ key, title, dataIndex: key, render: value => valueText(key, value) }))} /></Card>
      <Card title="完整执行阶段与回执" size="small"><Timeline items={data.stages.map((stage, step) => {
        const event = data.events.find(item => item.step === step);
        return { key: step, color: event ? 'green' : data.run?.status === 'ERROR' && data.run.step === step ? 'red' : 'gray',
          content: <><Typography.Text strong>{step + 1}. {stage}</Typography.Text>
            <div>{event ? valueText('occurred_at', event.occurred_at) : '尚无执行回执'}</div>
            {event && <Collapse size="small" items={[{ key: event.id, label: '查看阶段详细记录',
              children: <DataFields data={{ id: event.id, ...event.detail }} /> }]} />}</> };
      })} /></Card>
      <Card title="审计与追踪明细" size="small"><Table<Fields> rowKey={row => String(row.id)} size="small" dataSource={data.auditLogs}
        pagination={{ pageSize: 5, showSizeChanger: false }} scroll={{ x: 650 }} expandable={{ expandedRowRender: row => <DataFields data={row} /> }}
        columns={[
          ['occurred_at', '记录时间'], ['actor_id', '触发人或任务'], ['reason', '记录原因'], ['trace_id', '追踪编号'],
        ].map(([key, title]) => ({ key, title, dataIndex: key, render: value => valueText(key, value) }))} /></Card>
    </Space>}
  </Drawer>;
}
