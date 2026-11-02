import { Alert, Button, Card, Progress, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { railwayApi, RailwayApiError } from './railwayClient';
import { BackendConnectionFields,useBackendConnection } from '../features/backend-connection/BackendConnection';
import { withAppBasePath } from '../runtime/appBasePath';
import { businessLabel } from '../presentation/businessCopy';
import { z } from 'zod';

type Order={id:string;business_date:string;train_no:string;cargo_type:string;estimated_weight:number;status:string;updated_at:string};
type Snapshot={source:{kind:string;database:string};orders:Order[];runs:{id:string;step:number}[]};
const snapshotSchema=z.object({source:z.object({kind:z.enum(['LOCAL_DEMO','SERVER_DATABASE']),database:z.string().min(1)}),
  orders:z.array(z.object({id:z.string(),business_date:z.string(),train_no:z.string(),cargo_type:z.string(),estimated_weight:z.coerce.number(),status:z.string(),updated_at:z.string()})),
  runs:z.array(z.object({id:z.string(),step:z.number().int().min(0).max(16)}))});
export default function LatestRailwayOrders(){
  const[data,setData]=useState<Snapshot>();const[error,setError]=useState('');const[login,setLogin]=useState(false);
  const connection=useBackendConnection();
  useEffect(()=>{let pending=false,disposed=false;
    const refresh=async()=>{if(pending)return;pending=true;
      try{const result=await railwayApi();const snapshot=snapshotSchema.parse(result.data);if(!disposed){setData(snapshot);setError('');setLogin(false);}}
      catch(e){if(!disposed){setError(e instanceof Error?e.message:'数据库请求失败');setLogin(e instanceof RailwayApiError&&e.status===401);}}
      finally{pending=false;}};
    void refresh();const timer=setInterval(()=>void refresh(),5000);return()=>{disposed=true;clearInterval(timer);};
  },[connection.token]);
  return <Card title="最新铁路工单 · 数据库实时读取" style={{marginBottom:20}}
    extra={<Button href={withAppBasePath('/?railway=1')}>查看执行流水</Button>}>
    {error&&<Alert type="warning" title="最新工单暂未能刷新" description={error} style={{marginBottom:12}}/>}
    {login&&<BackendConnectionFields connection={connection}/>}
    {data&&<Space style={{marginBottom:12}} wrap>
      <Tag color={data.source.kind==='SERVER_DATABASE'?'green':'orange'}>{data.source.kind==='SERVER_DATABASE'?'服务器业务数据库':'本机验证数据库'}</Tag>
      <Typography.Text>数据库：{data.source.database}</Typography.Text>
      {data.orders[0]&&<Typography.Text strong>最新工单业务日期：{data.orders[0].business_date}</Typography.Text>}
      {error&&<Tag color="warning">保留上次成功读取的记录</Tag>}
    </Space>}
    <Table rowKey="id" dataSource={data?.orders??[]} pagination={{pageSize:5}} scroll={{x:900}} locale={{emptyText:error?'连接恢复后显示工单':'暂无已生成的作业工单'}} columns={[
      {title:'工单号',dataIndex:'id'},{title:'业务日期',dataIndex:'business_date'},{title:'车次',dataIndex:'train_no'},
      {title:'货类',render:(_,o:Order)=>businessLabel(o.cargo_type)},
      {title:'重量（吨）',dataIndex:'estimated_weight'},{title:'状态',render:(_,o:Order)=>businessLabel(o.status)},
      {title:'流程进度',render:(_,o:Order)=><Progress size="small" percent={Math.round((data?.runs.find(r=>o.id===r.id+'-WO')?.step??0)/16*100)}/>},
      {title:'数据库更新时间',render:(_,o:Order)=>new Date(o.updated_at).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})},
    ]}/>
  </Card>;
}
