import { Alert,Button,Card,Descriptions,Form,Input,InputNumber,Modal,Space,Table,Tag,Typography } from 'antd';
import { useCallback,useEffect,useRef,useState } from 'react';
import type { SessionContext } from '../auth';
import { canReadCollection,type BusinessRow } from './business';
import { canLiveMenuAction } from '../auth/liveActionPolicy';
import { LiveApiError } from './client';
import { dispatchWmsInbound,inboundActionAvailable,loadWmsInbound,runInboundAction,
  type DispatchInbound,type InboundAction,type WmsInboundConfig,type WmsInboundSnapshot } from './wmsInbound';

const statusText:Record<string,string>={QUEUED:'待发送',WAITING_WAREHOUSE:'等待仓库确认',WAREHOUSE_CONFIRMED:'已确认仓库',PARTIALLY_RECEIVED:'部分收货',FULLY_RECEIVED:'已收齐',EXCEPTION:'需处理'};
const transferText:Record<string,string>={NOT_STARTED:'未开始',IN_PROGRESS:'转运中',COMPLETED:'转运完成'};
const actionText:Record<InboundAction,string>={'transfer-start':'开始转运','transfer-complete':'完成转运',verify:'完成入库复核'};
type Draft={expectedArrivalAt:string;reason:string;lines:DispatchInbound['lines']};

export default function WmsInboundPanel({row,session,onChanged}:{row:BusinessRow;session:SessionContext;onChanged:()=>void}) {
  const [data,setData]=useState<WmsInboundSnapshot|null>(null),[config,setConfig]=useState<WmsInboundConfig>();
  const [loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[loaded,setLoaded]=useState(false);
  const [error,setError]=useState(''),[uncertain,setUncertain]=useState(''),[dispatchOpen,setDispatchOpen]=useState(false);
  const [action,setAction]=useState<InboundAction>(),[form]=Form.useForm<Draft>(),[actionForm]=Form.useForm<{reason:string}>();
  const generation=useRef(0),inFlight=useRef(false),pendingDispatch=useRef<DispatchInbound|null>(null);
  const canRead=canReadCollection(session,'work-orders');
  const authScope=JSON.stringify([session.actorId,session.dataScope,session.permissions,session.menuOperations,session.readOnly]);
  const identity=useRef('');identity.current=JSON.stringify([row.id,authScope]);
  const canDispatch=canLiveMenuAction(session,'PSMS:ui-005','ADD','pps:api:work-orders:write');
  const canExecute=(value:InboundAction)=>canLiveMenuAction(session,'PSMS:ui-005',value==='verify'?'CONFIRM':'EDIT','pps:api:work-orders:execute');
  const refresh=useCallback(async()=>{
    const turn=++generation.current;setLoading(true);
    try{const result=await loadWmsInbound(row.id);if(turn!==generation.current)return;
      setData(result.data);setConfig(result.config);setLoaded(true);setError('');
      if(result.data){pendingDispatch.current=null;setUncertain('');setDispatchOpen(false);}
    }catch(e){if(turn!==generation.current)return;setError(e instanceof Error?e.message:'仓储进度查询失败');setLoaded(false);
      if(e instanceof LiveApiError&&[401,403].includes(e.status)){setData(null);setConfig(undefined);}}
    finally{if(turn===generation.current)setLoading(false);}
  },[row.id]);
  useEffect(()=>{
    setData(null);setConfig(undefined);setLoaded(false);setDispatchOpen(false);setAction(undefined);setUncertain('');pendingDispatch.current=null;
    if(canRead)void refresh();else setLoading(false);
    const timer=window.setInterval(()=>{if(canRead&&document.visibilityState!=='hidden'&&!inFlight.current)void refresh();},15000);
    return()=>{window.clearInterval(timer);generation.current++;};
  },[row.id,canRead,authScope,refresh]);
  async function sendDispatch(){
    if(inFlight.current||!loaded||!canDispatch||!config?.enabled)return;
    const requestedIdentity=identity.current;
    let body=pendingDispatch.current;
    if(!body){
      let values:Draft;try{values=await form.validateFields();}catch{return;}
      body={...values,expectedArrivalAt:new Date(values.expectedArrivalAt).toISOString(),recordVersion:row.record_version,
        companyCode:config.companyCode,ownerCode:config.ownerCode,
        lines:values.lines.map((line,i)=>({lineId:String(i+1),goodCode:line.goodCode.trim(),unit:line.unit.trim(),plannedQty:line.plannedQty,
          ...(line.batchCode?.trim()?{batchCode:line.batchCode.trim()}:{}),...(line.supplierCode?.trim()?{supplierCode:line.supplierCode.trim()}:{}),}))};
    }
    if(identity.current!==requestedIdentity)return;
    pendingDispatch.current=body;inFlight.current=true;setSaving(true);setError('');
    try{const result=await dispatchWmsInbound(row.id,body);if(identity.current!==requestedIdentity)return;setData(result);pendingDispatch.current=null;setUncertain('');setDispatchOpen(false);onChanged();}
    catch(e){
      if(identity.current!==requestedIdentity)return;
      if(e instanceof LiveApiError&&e.status>=400&&e.status<500){pendingDispatch.current=null;setError(e.message);onChanged();}
      else {setUncertain('派发结果未取得。请保留此页，先刷新进度核对；未显示任务时用原报文重试，不要改工单重复派发。');}
    }finally{inFlight.current=false;setSaving(false);}
  }
  async function runAction(){
    if(!action||!data||!loaded||inFlight.current||!canExecute(action)||!inboundActionAvailable(data,action,row.status))return;
    const requestedIdentity=identity.current;
    let values:{reason:string};try{values=await actionForm.validateFields();}catch{return;}
    if(identity.current!==requestedIdentity)return;
    inFlight.current=true;setSaving(true);
    try{const result=await runInboundAction(row.id,action,values.reason,data.recordVersion);if(identity.current!==requestedIdentity)return;setData(result);setAction(undefined);setError('');onChanged();}
    catch(e){if(identity.current!==requestedIdentity)return;setAction(undefined);await refresh();if(identity.current!==requestedIdentity)return;setError(e instanceof Error?e.message:'操作结果未取得，请核对最新进度后再操作');onChanged();}
    finally{inFlight.current=false;setSaving(false);}
  }
  if(!canRead)return <Alert type="warning" title="当前账号无权查询仓储联动进度"/>;
  return <Card title="WMS 入库联动" style={{marginTop:20}} extra={<Button onClick={()=>void refresh()} loading={loading}>刷新仓储进度</Button>}>
    <Typography.Paragraph type="secondary">手工卸车工单派发到仓储后，由 WMS 选择目的仓并确认实际入库。设备分配、接单与开始作业仍按原流程执行；铁路自动演示工单不参与这条链路。已有未完成任务时请另建手工联调工单。</Typography.Paragraph>
    {error&&<Alert type="error" title={error} showIcon style={{marginBottom:12}}/>}
    {uncertain&&<Alert type="warning" title={uncertain} showIcon style={{marginBottom:12}}/>}
    {config&&!config.enabled&&<Alert type="info" title="服务器暂未启用 WMS 入库联动" showIcon/>}
    {data?<>
      <Descriptions column={1} size="small" items={[
        {key:'task',label:'调度任务号',children:data.taskId},{key:'status',label:'仓储进度',children:<Tag color={data.status==='FULLY_RECEIVED'?'green':data.status==='EXCEPTION'?'red':'blue'}>{statusText[data.status]}</Tag>},
        {key:'warehouse',label:'WMS 目的仓',children:data.warehouseCode||'等待仓储确认'},{key:'order',label:'WMS 入库单',children:data.wmsOrderCode||'尚未建单'},
        {key:'scope',label:'公司 / 货主',children:data.companyCode+' / '+data.ownerCode},
        {key:'transfer',label:'转运进度',children:transferText[data.transferStatus]},{key:'verify',label:'入库复核',children:data.verified?'已复核':'未复核'},
        {key:'seq',label:'已应用回执序号',children:data.lastSequence},
        {key:'delivery',label:'派发状态',children:({PENDING:'等待发送',DELIVERED:'WMS 已受理',FAILED:'发送需处理'} as Record<string,string>)[data.deliveryStatus]},
      ]}/>
      {data.lastError&&<Alert type="warning" title={data.lastError} showIcon style={{margin:'12px 0'}}/>}
      <Table pagination={false} rowKey="lineId" size="small" dataSource={data.lines} scroll={{x:650}} columns={[
        {title:'物料编码',dataIndex:'goodCode'},{title:'批次',dataIndex:'batchCode'},{title:'单位',dataIndex:'unit'},
        {title:'计划',dataIndex:'plannedQty'},{title:'累计实收',dataIndex:'receivedQty'},{title:'剩余',dataIndex:'remainingQty'},
      ]}/>
      <Space wrap style={{marginTop:16}}>{(['transfer-start','transfer-complete','verify'] as const).map(value=><Button key={value}
        disabled={!loaded||!config?.enabled||!canExecute(value)||saving||!inboundActionAvailable(data,value,row.status)}
        onClick={()=>{actionForm.resetFields();setAction(value);}}>{actionText[value]}</Button>)}</Space>
      <Typography.Paragraph type="secondary" style={{marginTop:12}}>仓库确认后才可转运。WMS 实收全部物料且转运完成后才可复核；复核后，使用原“完成工单”操作结束作业。</Typography.Paragraph>
    </>:loaded&&<>
      <Typography.Paragraph>本工单尚未派发入库任务。公司与货主由服务器配置固定：{config?.companyCode||'未配置'} / {config?.ownerCode||'未配置'}。</Typography.Paragraph>
      <Button type="primary" disabled={!config?.enabled||!canDispatch||saving||row.order_type!=='UNLOADING'||['COMPLETED','CANCELLED'].includes(row.status)}
        onClick={()=>{if(!pendingDispatch.current)form.resetFields();setDispatchOpen(true);}}>填写物料并派发入库</Button>
    </>}
    <Modal title="派发 WMS 入库任务" open={dispatchOpen} width={880} confirmLoading={saving} okText={pendingDispatch.current?'原报文重试':'确认派发'}
      okButtonProps={{disabled:!loaded||!canDispatch||!config?.enabled}} onOk={()=>void sendDispatch()} onCancel={()=>{if(!saving)setDispatchOpen(false);}}>
      <Alert type="info" showIcon title="派发只发送到货需求，不增加库存。物料编码和单位须与 WMS 主数据完全一致。" style={{marginBottom:16}}/>
      {uncertain&&<Alert type="warning" title={uncertain} showIcon style={{marginBottom:16}}/>}
      <Form form={form} layout="vertical" disabled={saving||!!pendingDispatch.current} initialValues={{lines:[{goodCode:'',unit:'',plannedQty:100}]}}>
        <Form.Item name="expectedArrivalAt" label="预计到达时间（本机时区）" rules={[{required:true},{validator:(_,v)=>typeof v==='string'&&Number.isFinite(Date.parse(v))?Promise.resolve():Promise.reject(new Error('请选择有效的到达时间'))}]}>
          <Input type="datetime-local"/>
        </Form.Item>
        <Form.List name="lines" rules={[{validator:(_,lines)=>lines?.length>=1&&lines.length<=100?Promise.resolve():Promise.reject(new Error('需要 1 至 100 条物料明细'))}]}>{(fields,{add,remove},{errors})=><>
          {fields.map(({key,name,...rest})=><Space key={key} align="start" wrap style={{display:'flex'}}>
            <Form.Item {...rest} name={[name,'goodCode']} label={`第 ${name+1} 行物料编码`} rules={[{required:true,whitespace:true,max:64}]}><Input style={{width:170}}/></Form.Item>
            <Form.Item {...rest} name={[name,'plannedQty']} label="计划数量" rules={[{required:true,type:'number',min:1,max:999999999},{validator:(_,v)=>Number.isInteger(v)?Promise.resolve():Promise.reject(new Error('首期按整数计件'))}]}><InputNumber min={1} max={999999999} precision={0}/></Form.Item>
            <Form.Item {...rest} name={[name,'unit']} label="WMS 库存单位" rules={[{required:true,whitespace:true,max:32}]}><Input style={{width:105}} placeholder="实际单位"/></Form.Item>
            <Form.Item {...rest} name={[name,'batchCode']} label="批次" rules={[{max:64}]}><Input style={{width:135}}/></Form.Item>
            <Form.Item {...rest} name={[name,'supplierCode']} label="供应商编码" rules={[{max:64}]}><Input style={{width:135}}/></Form.Item>
            <Button danger disabled={fields.length===1} style={{marginTop:30}} onClick={()=>remove(name)}>删除</Button>
          </Space>)}
          <Button disabled={fields.length>=100} onClick={()=>add({goodCode:'',unit:'',plannedQty:1})}>添加物料行</Button><Form.ErrorList errors={errors}/>
        </>}</Form.List>
        <Form.Item name="reason" label="派发原因" rules={[{required:true,whitespace:true,max:1000}]} style={{marginTop:16}}><Input.TextArea rows={2} maxLength={1000}/></Form.Item>
      </Form>
    </Modal>
    <Modal title={action? actionText[action]:''} open={!!action} confirmLoading={saving} onOk={()=>void runAction()} onCancel={()=>{if(!saving)setAction(undefined);}} okButtonProps={{disabled:!loaded||!action||!canExecute(action)}}>
      <Typography.Paragraph>目标仓库：{data?.warehouseCode||'未确认'} · 入库单：{data?.wmsOrderCode||'未生成'}。此操作记录实际转运或复核结果，不代替 WMS 收货。</Typography.Paragraph>
      <Form form={actionForm} layout="vertical"><Form.Item name="reason" label="操作原因或结果" rules={[{required:true,whitespace:true,max:1000}]}><Input.TextArea rows={3} maxLength={1000}/></Form.Item></Form>
    </Modal>
  </Card>;
}
