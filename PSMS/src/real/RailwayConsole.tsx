import { useEffect, useState } from 'react';
import { Alert, Button, Card, Space, Table, Typography, Upload, Tag, Progress, Segmented } from 'antd';
import { RailwayApiError, railwayApi as api } from './railwayClient';
import { BackendConnectionFields,useBackendConnection } from '../features/backend-connection/BackendConnection';
import { withAppBasePath } from '../runtime/appBasePath';
import RailwayDataSource, { type RailwaySource } from './RailwayDataSource';
import RailwayOrderDetails from './RailwayOrderDetails';
import RailwayIntake from './RailwayIntake';
import { businessLabel } from '../presentation/businessCopy';
type Ticket={id:string;business_date:string;payload:Record<string,unknown>;scenario_name?:string|null;work_summary?:string|null;
  owner_name?:string|null;operation_location?:string|null};
type Run={id:string;status:string;step:number;created_at:string;updated_at:string;error?:string};
type Event={id:string;run_id:string;stage:string;occurred_at:string;detail:{progress:number;weight:number;simulated:boolean}};
type Snapshot={source?:RailwaySource;tickets:Ticket[];runs:Run[];events:Event[];schedule:{PSMS_RAILWAY_AUTO_IMPORT:string;PSMS_RAILWAY_HOUR:number;PSMS_RAILWAY_MINUTE:number}};
function time(value:string){return new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});}
export default function RailwayConsole({readOnly = false}:{readOnly?:boolean}){
  const[data,setData]=useState<Snapshot>();const[error,setError]=useState('');const[busy,setBusy]=useState(false);
  const[selectedId,setSelectedId]=useState<string>();
  const [mode, setMode] = useState<'simulation' | 'mail' | 'file'>('simulation');
  const connection=useBackendConnection();
  const [needsLogin, setNeedsLogin] = useState(false);
  useEffect(()=>{let disposed=false,pending=false;
    const refresh=async()=>{if(pending)return;pending=true;try{const result=await api();if(!disposed){setData(result.data);setError('');setNeedsLogin(false);}}catch(e){if(!disposed){setError(e instanceof Error?e.message:'加载失败');setNeedsLogin(e instanceof RailwayApiError&&e.status===401);}}finally{pending=false;}};
    if (!readOnly && mode !== 'simulation') return;
    void refresh();const timer=setInterval(()=>void refresh(),3000);return()=>{disposed=true;clearInterval(timer);};},[connection.token, mode]);
  async function execute(action:()=>Promise<unknown>){if(readOnly)return;setBusy(true);try{await action();setData((await api()).data);setError('');}catch(e){setError(e instanceof Error?e.message:'操作失败');}finally{setBusy(false);}}
  return <main style={{maxWidth:1280,margin:'24px auto',padding:24}}>
    <Typography.Title level={2}>铁路工单运行台</Typography.Title>
    <Space wrap style={{ marginBottom: 16 }}>
      <Segmented value={mode} onChange={setMode} options={[
        { label: '系统模拟工单', value: 'simulation' },
        { label: '邮件接收工单', value: 'mail', disabled: readOnly },
        { label: '上传工单文件', value: 'file', disabled: readOnly },
      ]} />
      <Button href={withAppBasePath('/')}>返回原系统</Button>
    </Space>
    {!readOnly && mode !== 'simulation' ? <RailwayIntake key={mode} mode={mode} /> : <>
    <RailwayDataSource source={data?.source}/>
    <Alert type="info" showIcon title="持久化模拟运行" description="每一步由后台定时推进并写入对应数据库，工单、模拟设备状态及执行流水可按编号核对。"/>
    {error&&<Alert style={{marginTop:16}} type="error" title="运行服务请求失败" description={error}/>}
    {(needsLogin || error&&import.meta.env.VITE_PSMS_REAL_API!=='true')&&<BackendConnectionFields connection={connection}/>}
    <Space style={{margin:'20px 0'}} wrap>
      <Button type="primary" disabled={readOnly} loading={busy} onClick={()=>void execute(async()=>{const result=await api('/generate',{});const blob=new Blob([JSON.stringify(result.data,null,2)],{type:'application/json'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=result.data.id+'.json';a.click();URL.revokeObjectURL(url);})}>生成并下载今日工单</Button>
      <Upload disabled={readOnly} accept=".json" showUploadList={false} beforeUpload={file=>{if(readOnly)return false;void execute(async()=>{if(file.size>65536)throw new Error('工单文件不能超过64KB');await api('/import',JSON.parse(await file.text()));});return false;}}><Button disabled={readOnly} loading={busy}>导入模拟工单并启动流程</Button></Upload>
      <Button disabled={readOnly} loading={busy} onClick={()=>void execute(async()=>{const result=await api('/generate',{});await api('/import',result.data);})}>生成并运行今日模拟工单</Button>
      {data?.source&&<Tag color={data.source.kind==='SERVER_DATABASE'?'green':'orange'}>
        {data.source.kind==='SERVER_DATABASE'?'服务器定时任务':'仅本机后台运行期间'}
        {data.schedule?.PSMS_RAILWAY_AUTO_IMPORT==='true'?` · 北京时间 ${String(data.schedule.PSMS_RAILWAY_HOUR).padStart(2,'0')}:${String(data.schedule.PSMS_RAILWAY_MINUTE).padStart(2,'0')} 导入`:' · 自动导入已关闭'}
      </Tag>}
    </Space>
    <Card title={data?.source?.kind==='LOCAL_DEMO'?'本机验证工单':'每日自动导入工单'} style={{marginBottom:20}}><Table rowKey="id" dataSource={data?.tickets??[]} pagination={{pageSize:5}} scroll={{x:1400}} columns={[
      {title:'工单编号',dataIndex:'id'},{title:'日期（北京时间）',dataIndex:'business_date'},
      {title:'本工单业务内容',width:420,render:(_,t:Ticket)=><Space orientation="vertical">
        <Typography.Text strong>{t.scenario_name??`${t.payload.trainNo} / ${businessLabel(String(t.payload.cargoType))} / ${t.payload.weight}吨`}</Typography.Text>
        {t.work_summary&&<Typography.Text type="secondary">{t.work_summary}</Typography.Text>}
      </Space>},
      {title:'货主',dataIndex:'owner_name',render:(value:string|null)=>value||'原票据未记录'},
      {title:'作业点',dataIndex:'operation_location',render:(value:string|null)=>value||'原票据未记录'},
      {title:'导入状态',render:(_,t:Ticket)=>data?.runs.some(r=>r.id===t.id)?'已导入':'尚未导入'},
      {title:'操作',render:(_,t:Ticket)=><Space wrap><Button onClick={()=>setSelectedId(t.id)}>查看完整工单</Button>
        <Button disabled={readOnly||busy||data?.runs.some(r=>r.id===t.id)} onClick={()=>void execute(()=>api('/import',t.payload))}>导入并运行</Button></Space>},
    ]}/></Card>
    <Card title="执行进度（每15秒推进一个阶段）" style={{marginBottom:20}}><Table rowKey="id" dataSource={data?.runs??[]} pagination={{pageSize:5}} columns={[
      {title:'工单',dataIndex:'id'},{title:'状态',render:(_,r:Run)=><Tag color={r.status==='ERROR'?'red':r.status==='COMPLETED'?'green':'blue'}>{r.status==='COMPLETED'?'已完成':r.status==='ERROR'?'执行失败':'运行中'}</Tag>},
      {title:'进度',render:(_,r:Run)=><Progress percent={Math.round(r.step/16*100)} status={r.status==='ERROR'?'exception':undefined}/>},
      {title:'最近更新',render:(_,r:Run)=>time(r.updated_at)},{title:'失败原因',dataIndex:'error'},
      {title:'操作',render:(_,r:Run)=><Button onClick={()=>setSelectedId(r.id)}>查看执行详情</Button>},
    ]}/></Card>
    <Card title="数据库执行流水（每3秒刷新）"><Table rowKey="id" dataSource={data?.events??[]} pagination={{pageSize:12}} columns={[
      {title:'工单',dataIndex:'run_id'},{title:'阶段',dataIndex:'stage'},{title:'记录时间',render:(_,e:Event)=>time(e.occurred_at)},
      {title:'模拟作业数据',render:(_,e:Event)=>`${e.detail.progress}% · ${e.detail.weight}吨 · 模拟来源`},
      {title:'操作',render:(_,e:Event)=><Button onClick={()=>setSelectedId(e.run_id)}>查看工单与阶段</Button>},
    ]}/></Card>
    {selectedId&&<RailwayOrderDetails key={selectedId} id={selectedId} onClose={()=>setSelectedId(undefined)}/>}
    </>}
  </main>;
}
