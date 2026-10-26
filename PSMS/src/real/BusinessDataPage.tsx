import { Alert, Button, Card, Descriptions, Drawer, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState,useRef } from 'react';
import { loadCurrentDemoSession, type SessionContext } from '../auth';
import PageIdentity from '../features/plan-entry/components/PageIdentity';
import { LiveApiError,liveApi } from './client';
import { useDemoSelector } from '../runtime';
import { canReadCollection, equipmentFreshness, liveBusinessPages, loadBusinessPage,
  businessActions,canRunBusinessAction,runBusinessAction,type BusinessAction,
  type BusinessPage, type BusinessRow, type LiveBusinessPageId, type LiveCollection } from './business';

const labels: Record<string, string> = {
  id:'记录编号', status:'业务状态', updated_at:'更新时间', created_at:'创建时间', plan_id:'计划', plan_batch_no:'计划批次',
  work_area:'作业区', equipment_id:'设备编号', equipment_name:'设备名称', assigned_operator:'执行人', order_type:'作业类型',
  priority:'优先级', description:'说明', actual_start_time:'实际开始', actual_end_time:'实际结束', work_order_id:'关联工单',
  task_no:'任务编号', name:'名称', order_no:'顺序', started_at:'开始时间', completed_at:'完成时间', type:'类型', model:'型号',
  last_heartbeat:'最近心跳', last_telemetry_at:'最近遥测', appointment_id:'预约编号', vehicle_plate:'车牌', vehicle_type:'车辆类型',
  company:'公司', cargo_type:'货种', planned_arrive_time:'计划到达', check_in_time:'签到时间', called_at:'叫号时间',
  enter_time:'入场时间', exit_time:'离场时间', queue_number:'排队号', gate_no:'闸口', parking_bay:'停车位',
  exception_id:'异常编号', severity:'等级', title:'标题', assigned_to:'处理人', acknowledged_by:'认领人',
  acknowledged_at:'认领时间', resolution:'处理结果', resolved_at:'解决时间', closed_at:'关闭时间',
  record_version:'记录版本',
};
const config: Record<LiveCollection, { title:string; columns:string[]; statuses:string[] }> = {
  'work-orders': {title:'作业工单',columns:['id','plan_id','work_area','equipment_id','status','order_type','priority','description'],
    statuses:['DRAFT','READY','ASSIGNED','ACCEPTED','IN_PROGRESS','PAUSED','COMPLETED','CANCELLED']},
  tasks:{title:'任务进度',columns:['task_no','name','work_order_id','work_area','equipment_id','status','order_no'],
    statuses:['PENDING','READY','IN_PROGRESS','PAUSED','COMPLETED','FAILED','CANCELLED']},
  equipment:{title:'设备资源',columns:['equipment_id','name','type','work_area','status','last_heartbeat','last_telemetry_at'],
    statuses:['ONLINE','OFFLINE','MAINTENANCE','FAULT']},
  appointments:{title:'车辆预约',columns:['appointment_id','vehicle_plate','company','planned_arrive_time','queue_number','status','gate_no'],
    statuses:['PENDING','APPROVED','CHECKED_IN','QUEUED','CALLED','ON_SITE','COMPLETED','CANCELLED','NO_SHOW']},
  exceptions:{title:'异常台账',columns:['exception_id','title','type','severity','work_area','status','assigned_to'],
    statuses:['OPEN','ACKNOWLEDGED','IN_PROGRESS','RESOLVED','CLOSED','DISMISSED']},
};
function display(value: unknown): string {
  if (value === null || value === undefined || value === '') return '未登记';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
function BusinessTable({ collection, session }: { collection:LiveCollection; session:SessionContext }) {
  const [page,setPage]=useState(1), [area,setArea]=useState<string>(), [status,setStatus]=useState<string>();
  const [reload,setReload]=useState(0), [data,setData]=useState<BusinessPage>(), [error,setError]=useState('');
  const [loading,setLoading]=useState(true), [selected,setSelected]=useState<BusinessRow>();
  const [command,setCommand]=useState<{row:BusinessRow;action:BusinessAction}>(),[submitting,setSubmitting]=useState(false);
  const [commandError,setCommandError]=useState('');
  const [form]=Form.useForm<{reason:string;equipmentId?:string}>();
  const [creating,setCreating]=useState(false),[createForm]=Form.useForm();
  const inFlight=useRef(false);
  const plans=useDemoSelector(state=>state.plan.plans);
  const allowed=canReadCollection(session,collection), global=session.dataScope.some(s=>['*','GLOBAL'].includes(s));
  const spec=config[collection];
  useEffect(()=>{
    if(!allowed){setData(undefined);setSelected(undefined);setError('当前账号无权查询此类业务数据');setLoading(false);return;}
    const controller=new AbortController();let active=true;setLoading(true);
    const query:Record<string,string>={page:String(page),pageSize:'50'};
    if(area)query.workArea=area;if(status)query.status=status;
    void loadBusinessPage(collection,query,controller.signal).then(result=>{if(active){setData(result);setError('');}})
      .catch(e=>{if(active){setError(e instanceof Error?e.message:'查询失败');
        if(e instanceof LiveApiError&&[401,403].includes(e.status)){setData(undefined);setSelected(undefined);setCommand(undefined);}}})
      .finally(()=>{if(active)setLoading(false);});
    return()=>{active=false;controller.abort();};
  },[collection,page,area,status,reload,allowed]);
  useEffect(()=>{const id=window.setInterval(()=>{if(document.visibilityState!=='hidden')setReload(v=>v+1);},15000);
    return()=>window.clearInterval(id);},[]);
  const columns=[...spec.columns.map(key=>({title:labels[key]??key,dataIndex:key,key,render:display})),
    ...(collection==='equipment'?[{title:'采集时效',key:'freshness',render:(_:unknown,row:BusinessRow)=>{
      const fresh=data?equipmentFreshness(row,data.generatedAt):'UNKNOWN';
      return <Tag color={fresh==='FRESH'?'green':'warning'}>{fresh==='FRESH'?'采集及时':fresh==='STALE'?'采集过期':'尚无采集时间'}</Tag>;
    }}]:[]),{title:'操作',key:'detail',render:(_:unknown,row:BusinessRow)=><Space wrap>
      <Button onClick={()=>setSelected(row)}>查看</Button>
      {businessActions(collection,row.status).map(action=><Button key={action.code} disabled={!canRunBusinessAction(session,action)||submitting}
        onClick={()=>{setCommandError('');form.resetFields();setCommand({row,action});}}>{action.label}</Button>)}
    </Space>}];
  return <Card title={spec.title} style={{marginBottom:20}} data-business-collection={collection}>
    <Space wrap style={{marginBottom:16}}>
      {collection!=='appointments'&&<Select aria-label={spec.title+'作业区'} value={area} allowClear placeholder="授权作业区"
        style={{width:170}} options={(global?['AREA-A','AREA-B','AREA-C']:session.dataScope.filter(s=>s.startsWith('AREA-')))
          .map(value=>({value,label:value}))} onChange={value=>{setArea(value);setPage(1);setData(undefined);}}/>}
      <Select aria-label={spec.title+'状态'} value={status} allowClear placeholder="全部业务状态" style={{width:200}}
        options={spec.statuses.map(value=>({value,label:value}))} onChange={value=>{setStatus(value);setPage(1);setData(undefined);}}/>
      <Button loading={loading} onClick={()=>setReload(v=>v+1)}>刷新</Button>
      {collection==='work-orders'&&<Button type="primary" disabled={!canRunBusinessAction(session,{code:'create',label:'新建工单',permission:'pps:api:work-orders:write'})}
        onClick={()=>{createForm.resetFields();setCreating(true);setCommandError('');}}>新建工单</Button>}
      {data&&<Typography.Text data-business-total={collection}>符合条件 {data.total} 条</Typography.Text>}
    </Space>
    {error&&<Alert showIcon type="error" title={error} description={data?'查询失败，以下保留上次成功结果；请重试。':undefined} style={{marginBottom:16}}/>}
    {commandError&&<Alert showIcon type="error" title={commandError} closable onClose={()=>setCommandError('')} style={{marginBottom:16}}/>}
    <Table<BusinessRow> rowKey="id" loading={loading} dataSource={data?.items??[]} columns={columns} scroll={{x:'max-content'}}
      locale={{emptyText:error?'查询未成功':loading?'查询中':'查询成功，当前条件下无记录'}}
      pagination={{current:page,pageSize:50,total:data?.total??0,showSizeChanger:false,onChange:setPage}}/>
    <Drawer title={spec.title+'详情'} open={!!selected} onClose={()=>setSelected(undefined)} size="large">
      {selected&&<Descriptions column={1} items={Object.entries(selected).map(([key,value])=>({key,label:labels[key]??key,children:display(value)}))}/>}
    </Drawer>
    <Modal title={command?.action.label} open={!!command} confirmLoading={submitting} destroyOnHidden
      onCancel={()=>{if(!submitting)setCommand(undefined);}} onOk={async()=>{
        if(!command||inFlight.current)return;inFlight.current=true;
        let values:{reason:string;equipmentId?:string};
        try{values=await form.validateFields();}catch{inFlight.current=false;return;}
        setSubmitting(true);
        try{await runBusinessAction(collection,command.row,command.action,values);setCommand(undefined);setSelected(undefined);setError('');setReload(v=>v+1);}
        catch(e){setCommand(undefined);setCommandError(e instanceof LiveApiError?e.message:'操作回执未取得，可能已经提交；请刷新记录和审计核对后再决定是否重试');
          setSelected(undefined);setReload(v=>v+1);}
        finally{inFlight.current=false;setSubmitting(false);}
      }}>
      <Typography.Paragraph>记录：{command?.row.id}。请核对当前状态并填写操作原因；提交后将写入业务记录和审计。</Typography.Paragraph>
      <Form form={form} layout="vertical" preserve={false}>
        {command?.action.equipment&&<Form.Item name="equipmentId" label="设备编号" rules={[{required:true,whitespace:true,max:160}]}>
          <Input maxLength={160} placeholder="选择同作业区且采集及时的设备"/></Form.Item>}
        <Form.Item name="reason" label="操作原因或处理结果" rules={[{required:true,whitespace:true,max:1000}]}>
          <Input.TextArea maxLength={1000} rows={3}/></Form.Item>
      </Form>
    </Modal>
    <Modal title="新建工单" open={creating} destroyOnHidden confirmLoading={submitting}
      onCancel={()=>{if(!submitting)setCreating(false);}} onOk={async()=>{
        if(inFlight.current)return;inFlight.current=true;let values:Record<string,string>;
        try{values=await createForm.validateFields();}catch{inFlight.current=false;return;}
        setSubmitting(true);
        try{await liveApi('/business/work-orders',{method:'POST',body:JSON.stringify(values)});setCreating(false);setReload(v=>v+1);}
        catch(e){setCreating(false);setCommandError(e instanceof LiveApiError?e.message:'创建回执未取得，请按计划和说明查询，避免重复创建');}
        finally{inFlight.current=false;setSubmitting(false);}
      }}>
      <Form form={createForm} layout="vertical" preserve={false} initialValues={{priority:'MEDIUM',orderType:'UNLOADING'}}>
        <Form.Item name="planId" label="关联计划" rules={[{required:true}]}><Select options={plans.filter(p=>p.status!=='CANCELLED')
          .map(p=>({value:p.id,label:p.planBatchNo+' · '+p.trainNo}))}/></Form.Item>
        <Form.Item name="orderType" label="作业类型" rules={[{required:true}]}><Select options={[
          {value:'LOADING',label:'装车'},{value:'UNLOADING',label:'卸车'},{value:'TRANSFER',label:'转运'},
          {value:'MAINTENANCE',label:'维护'},{value:'OTHER',label:'其他'}]}/></Form.Item>
        <Form.Item name="priority" label="优先级" rules={[{required:true}]}><Select options={[
          {value:'HIGH',label:'高'},{value:'MEDIUM',label:'中'},{value:'LOW',label:'低'}]}/></Form.Item>
        <Form.Item name="description" label="作业说明" rules={[{required:true,whitespace:true,max:1000}]}><Input.TextArea rows={3} maxLength={1000}/></Form.Item>
      </Form>
    </Modal>
  </Card>;
}
export default function BusinessDataPage({pageId}:{pageId:LiveBusinessPageId}) {
  const session=loadCurrentDemoSession(),spec=liveBusinessPages[pageId];
  return <main aria-label={pageId+' '+spec.title} style={{padding:24}}>
    <PageIdentity pageId={pageId} name={spec.title} path={window.location.pathname}/>
    <Alert type="info" showIcon title="业务库实时查询" style={{margin:'16px 0'}}
      description="每15秒更新当前查询。操作须经权限、记录版本和业务状态检查；设备采集过期时不能派工或开始作业。"/>
    {spec.collections.map(collection=><BusinessTable key={pageId+collection} collection={collection} session={session}/>)}</main>;
}
