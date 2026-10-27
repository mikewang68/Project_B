import {Alert,Button,Card,Descriptions,Drawer,Form,Input,Modal,Select,Space,Table,Tag,Typography} from 'antd';
import {useEffect,useRef,useState} from 'react';
import {loadCurrentDemoSession} from '../auth';
import {liveApi,LiveApiError} from './client';
const governanceApi=(path:string,init?:RequestInit)=>liveApi('/governance'+path,init);

export const governancePages={'UI-009':{title:'安全联锁',resource:'interlocks'},
 'UI-010':{title:'离线同步',resource:'offline'},'UI-011':{title:'统计报表',resource:'reports'},
 'UI-012':{title:'系统配置',resource:'settings'},'UI-013':{title:'审计日志',resource:'audit-logs'}} as const;
export type GovernancePageId=keyof typeof governancePages;
type Row=Record<string,unknown>&{id:string;status?:string;version?:number;server_version?:number};
const labels:Record<string,string>={id:'编号',name:'名称',work_area:'作业区',status:'状态',version:'版本',server_version:'服务端版本',
 terminal_id:'终端',packet_id:'数据包',sync_attempts:'补传次数',last_sync_at:'最近补传',display_name:'配置名称',
 dispatch_strategy:'调度策略',report_period:'报表周期',audit_retention_days:'审计保留天数',updated_at:'更新时间',
 actor_id:'操作者',action:'动作',object_type:'对象类型',object_id:'对象编号',reason:'原因',trace_id:'追踪编号',
 occurred_at:'时间',chain_state:'上链状态',writelog_state:'WriteLog 状态',command:'申请动作',requested_by:'申请人',
 approved_by:'审批人',interlock_id:'联锁编号'};
const text=(value:unknown)=>value==null?'未登记':typeof value==='object'?JSON.stringify(value):String(value);
const chinaToday=()=>new Date(Date.now()+8*3600_000).toISOString().slice(0,10);
export default function GovernancePage({pageId}:{pageId:GovernancePageId}){
 const spec=governancePages[pageId],session=loadCurrentDemoSession();
 const [workArea,setWorkArea]=useState<string>(),[startDate,setStartDate]=useState(chinaToday),[endDate,setEndDate]=useState(chinaToday);
 const [page,setPage]=useState(1),[rows,setRows]=useState<Row[]>([]),[requests,setRequests]=useState<Row[]>([]),[total,setTotal]=useState(0);
 const [report,setReport]=useState<Record<string,unknown>>(),[error,setError]=useState(''),[loading,setLoading]=useState(true),[revision,setRevision]=useState(0);
 const [selected,setSelected]=useState<Row>(),[operation,setOperation]=useState<{action:string;row?:Row}>(),[commandError,setCommandError]=useState('');
 const [form]=Form.useForm(),busy=useRef(false);
 const all=session.dataScope.some(s=>['GLOBAL','*'].includes(s));
 const allowed=(operationName:string,resource=spec.resource)=>!session.readOnly&&(session.fullAdmin||session.permissions?.includes(`pps:api:${resource}:${operationName}`));
 const filters={...(workArea?{workArea}:{}),...(spec.resource==='reports'||spec.resource==='audit-logs'?{startDate,endDate}:{})};
 useEffect(()=>{
  const controller=new AbortController();let current=true;setLoading(true);setError('');setSelected(undefined);
  const query=new URLSearchParams(spec.resource==='reports'?filters:{...filters,page:String(page),pageSize:'50'});
  void governanceApi('/'+spec.resource+'?'+query,{signal:controller.signal}).then(async result=>{
   if(!current)return;
   if(spec.resource==='reports'){setReport(result.data);setRows([]);setTotal(0);}
   else{if(!Array.isArray(result.data.items)||typeof result.data.total!=='number')throw new Error('列表响应格式无效');
    setRows(result.data.items);setTotal(result.data.total);setReport(undefined);}
   if(spec.resource==='interlocks'){
    const result=await governanceApi('/interlock-requests?'+new URLSearchParams({...filters,pageSize:'100'}),{signal:controller.signal});
    if(current)setRequests(result.data.items);
   }
  }).catch(e=>{if(current){setError(e instanceof Error?e.message:'查询失败');
   setRows([]);setReport(undefined);setRequests([]);
   if(e instanceof LiveApiError&&[401,403].includes(e.status))setOperation(undefined);}})
   .finally(()=>{if(current)setLoading(false);});
  return()=>{current=false;controller.abort();};
 },[pageId,page,workArea,startDate,endDate,revision]);
 useEffect(()=>{setPage(1);setRows([]);setReport(undefined);setRequests([]);setOperation(undefined);},[pageId]);
 const open=(action:string,row?:Row)=>{setCommandError('');form.resetFields();form.setFieldsValue({workArea:row?.work_area??workArea,
  terminalId:row?.terminal_id,version:row?.server_version??1,payload:JSON.stringify(row?.payload??{},null,2),
  display_name:row?.display_name,dispatch_strategy:row?.dispatch_strategy??'BALANCED',report_period:row?.report_period??'DAILY'});setOperation({action,row});};
 const command=async()=>{
  if(busy.current||!operation)return;busy.current=true;setCommandError('');
  try{
   const v=await form.validateFields(),{action,row}=operation;let path='/'+spec.resource+(row?'/'+encodeURIComponent(row.id):'');
   let body:Record<string,unknown>={action,reason:v.reason};
   if(spec.resource==='settings'){
    const changes={display_name:v.display_name,dispatch_strategy:v.dispatch_strategy,report_period:v.report_period};
    if(action==='create')body={workArea:v.workArea,changes,reason:v.reason};
    else body={action,reason:v.reason,expectedVersion:row?.version,...(action==='edit'?{changes}:{})};
   }else if(spec.resource==='offline'){
    path='/offline/'+encodeURIComponent(row?.id??v.id);
    if(action==='sync')body={action,reason:v.reason,terminalId:v.terminalId,workArea:v.workArea,
     version:Number(v.version),payload:JSON.parse(v.payload)};
    else body={action,reason:v.reason,expectedVersion:row?.server_version,resolution:v.resolution,
     ...(v.resolution==='MANUAL_MERGE'?{payload:JSON.parse(v.payload)}:{})};
   }else if(action==='approve'||action==='cancel'){
    path='/interlock-requests/'+encodeURIComponent(row!.id);body.expectedVersion=row?.version;
   }
   await governanceApi(path,{method:'POST',body:JSON.stringify(body)});setOperation(undefined);setRevision(v=>v+1);
  }catch(e){setCommandError(e instanceof Error?e.message:'请核对必填项');}finally{busy.current=false;}
 };
 const actionButtons=(row:Row,request=false)=>{
  const actions=request?row.status==='REQUESTED'?['approve','cancel']:row.status==='APPROVED'?['cancel']:[]
   :spec.resource==='settings'?row.status==='DRAFT'?['edit','submit']:row.status==='SUBMITTED'?['approve']
    :row.status==='APPROVED'?['publish']:row.status==='PUBLISHED'?['rollback']:row.status==='ROLLED_BACK'?['reopen']:[]
   :spec.resource==='offline'?row.status==='CONFLICT'?['resolve']:['sync']
   :spec.resource==='interlocks'&&row.status==='TRIGGERED'?['requestOverride','requestReset']:[];
  return <Space wrap><Button onClick={()=>setSelected(row)}>详情</Button>{actions.map(a=><Button key={a}
   disabled={!allowed(['approve','resolve'].includes(a)?'approve':['publish','rollback'].includes(a)?'publish':'write')}
   onClick={()=>open(a,row)}>{({edit:'编辑',submit:'提交审批',approve:'审批',publish:'发布登记',rollback:'回退登记',reopen:'重新编辑',
    sync:'补传',resolve:'处理冲突',requestOverride:'申请覆盖',requestReset:'申请复位',cancel:'撤销申请'} as Record<string,string>)[a]}</Button>)}</Space>;
 };
 const columns=(request=false)=>{
  const fields=request?['interlock_id','command','status','requested_by','approved_by','version']
   :spec.resource==='settings'?['display_name','work_area','status','version','updated_at']
   :spec.resource==='offline'?['packet_id','terminal_id','work_area','status','server_version','sync_attempts','last_sync_at']
   :spec.resource==='audit-logs'?['occurred_at','actor_id','action','object_id','work_area','reason','writelog_state','chain_state']
   :['interlock_id','name','work_area','status','updated_at'];
  return [...fields.map(key=>({title:labels[key]??key,dataIndex:key,key,render:text})),
   {title:'操作',key:'operations',render:(_:unknown,row:Row)=>actionButtons(row,request)}];
 };
 const exportReport=async()=>{
  const purpose=window.prompt('请输入报表导出用途');if(!purpose?.trim())return;
  try{const result=await governanceApi('/reports/export',{method:'POST',body:JSON.stringify({...filters,purpose})});
   const url=URL.createObjectURL(new Blob([JSON.stringify(result.data,null,2)],{type:'application/json'}));
   const link=document.createElement('a');link.href=url;link.download='operations-report.json';link.click();URL.revokeObjectURL(url);
  }catch(e){setError(e instanceof Error?e.message:'导出失败');}
 };
 return <Space orientation="vertical" size="large" style={{width:'100%'}}>
  <Typography.Title level={2}>{spec.title}</Typography.Title>
  {spec.resource==='interlocks'&&<Alert type="warning" showIcon title="软件申请与审批不会解除实际联锁"
   description="设备停机、复位及覆盖结果须由设备确认；未取得回执时仍保持原联锁状态和执行限制。"/>}
  {spec.resource==='settings'&&<Alert type="info" title="配置版本独立审批，保留完整历史"
   description="保存、提交、审批和发布分别登记。页面不触发设备控制或算法执行；历史归属不明的配置仅平台管理员可见。"/>}
  <Space wrap><Select aria-label="作业区" allowClear value={workArea} placeholder="授权作业区" style={{minWidth:160}}
   options={(all?['AREA-A','AREA-B','AREA-C']:session.dataScope.filter(s=>s.startsWith('AREA-'))).map(value=>({value,label:value}))}
   onChange={value=>{setPage(1);setWorkArea(value);}}/>
   {['reports','audit-logs'].includes(spec.resource)&&<><Input aria-label="起始日期" type="date" value={startDate} onChange={e=>setStartDate(e.target.value)}/>
    <Input aria-label="结束日期" type="date" value={endDate} onChange={e=>setEndDate(e.target.value)}/><Tag>北京时间</Tag></>}
   <Button onClick={()=>setRevision(v=>v+1)} loading={loading}>刷新</Button>
   {spec.resource==='settings'&&<Button disabled={!allowed('write')} onClick={()=>open('create')}>新建配置草稿</Button>}
   {spec.resource==='offline'&&<Button disabled={!allowed('write')} onClick={()=>open('sync')}>接收离线包</Button>}
   {spec.resource==='reports'&&<Button onClick={()=>void exportReport()} disabled={session.readOnly||
    !(session.permissions?.includes('pps:export')||session.platformSuperAdmin)}>导出报表</Button>}
  </Space>
  {error&&<Alert type="error" showIcon title={error} description="查询失败，未以零值或演示数据代替。"/>}
  {spec.resource==='reports'?report&&<Card title="业务库期间统计">
   <Typography.Paragraph>{text(report.period)}</Typography.Paragraph>
   {(['plans','workOrders','equipments','exceptions'] as const).map(key=><Card key={key} title={
    ({plans:'计划',workOrders:'工单',equipments:'设备当前状态（不按创建日期）',exceptions:'异常'})[key]} style={{marginBottom:12}}>
    <Table rowKey="_id" dataSource={report[key] as {_id:string;count:number}[]} pagination={false}
     columns={[{title:'状态/分类',dataIndex:'_id'},{title:'数量',dataIndex:'count'}]}/></Card>)}
  </Card>:<Table rowKey="id" loading={loading} dataSource={rows} columns={columns()} scroll={{x:1100}}
   pagination={{current:page,pageSize:50,total,showSizeChanger:false,onChange:setPage}}/>}
  {spec.resource==='interlocks'&&<Card title="软件解除申请与审批记录"><Table rowKey="id" dataSource={requests}
   columns={columns(true)} pagination={false} scroll={{x:1100}}/></Card>}
  <Drawer open={!!selected} onClose={()=>setSelected(undefined)} title="业务详情" width={720}>
   <Descriptions column={1} bordered items={Object.entries(selected??{}).map(([key,value])=>({key,label:labels[key]??key,children:text(value)}))}/>
  </Drawer>
  <Modal open={!!operation} title="业务操作" onCancel={()=>setOperation(undefined)} onOk={()=>void command()} destroyOnHidden>
   {commandError&&<Alert type="error" title={commandError}/>}
   <Form form={form} layout="vertical">
    {(spec.resource==='settings'&&['edit','create'].includes(operation?.action??''))&&<>
     {operation?.action==='create'&&<Form.Item name="workArea" label="作业区" rules={[{required:true}]}><Input/></Form.Item>}
     <Form.Item name="display_name" label="配置名称" rules={[{required:true}]}><Input maxLength={100}/></Form.Item>
     <Form.Item name="dispatch_strategy" label="调度策略"><Select options={['BALANCED','PRIORITY_FIRST','RESOURCE_FIRST','OPTIMAL'].map(value=>({value,label:value}))}/></Form.Item>
     <Form.Item name="report_period" label="报表周期"><Select options={['SHIFT','DAILY','MONTHLY'].map(value=>({value,label:value}))}/></Form.Item></>}
    {spec.resource==='offline'&&operation?.action==='sync'&&<>
     {!operation.row&&<Form.Item name="id" label="数据包编号" rules={[{required:true}]}><Input maxLength={160}/></Form.Item>}
     <Form.Item name="terminalId" label="终端编号" rules={[{required:true}]}><Input disabled={!!operation.row}/></Form.Item>
     <Form.Item name="workArea" label="作业区" rules={[{required:true}]}><Input disabled={!!operation.row}/></Form.Item>
     <Form.Item name="version" label="终端版本" rules={[{required:true}]}><Input type="number" min={1}/></Form.Item>
     <Form.Item name="payload" label="离线业务数据（JSON）" rules={[{required:true}]}><Input.TextArea rows={6}/></Form.Item></>}
    {operation?.action==='resolve'&&<><Form.Item name="resolution" label="冲突处理" rules={[{required:true}]}>
     <Select options={['ACCEPT_LOCAL','ACCEPT_SERVER','MANUAL_MERGE','DISCARD'].map(value=>({value,label:value}))}/></Form.Item>
     <Form.Item noStyle shouldUpdate={(a,b)=>a.resolution!==b.resolution}>{({getFieldValue})=>getFieldValue('resolution')==='MANUAL_MERGE'
      ?<Form.Item name="payload" label="合并后完整数据（JSON）" rules={[{required:true}]}><Input.TextArea rows={6}/></Form.Item>:null}</Form.Item></>}
    <Form.Item name="reason" label="操作原因" rules={[{required:true,whitespace:true}]}><Input.TextArea maxLength={1000}/></Form.Item>
   </Form>
  </Modal>
 </Space>;
}
