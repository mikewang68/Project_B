import { Button, Select, Space, Typography } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { useDemoSelector } from '../runtime';
import { loadCurrentDemoSession } from '../auth';
import { parsePlanEntryQuery } from '../features/plan-entry/query';

export default function LiveQueryFilters(){
  const location=useLocation(),navigate=useNavigate();
  const state=useDemoSelector(s=>s);
  if(import.meta.env.VITE_PSMS_REAL_API!=='true')return null;
  const query=parsePlanEntryQuery(location.search),session=loadCurrentDemoSession();
  const global=session.dataScope.some(s=>s==='*'||s==='GLOBAL');
  const areas=[...new Set([...state.plan.plans.map(p=>p.workArea).filter((p):p is string=>!!p),
    ...session.dataScope.filter(s=>s!=='*'&&s!=='GLOBAL'),query.workArea])]
    .filter(area=>global||session.dataScope.includes(area)).sort();
  const change=(key:string,value:string)=>{
    const search=new URLSearchParams(location.search);search.set(key,value);search.set('page','1');
    void navigate({pathname:location.pathname,search:search.toString()});
  };
  const latest=state.liveOverview?.workArea===query.workArea?state.liveOverview.planRange.latest:null;
  return <Space wrap style={{margin:'12px 0'}} aria-label="业务查询范围">
    <label>业务日期 <input type="date" aria-label="业务日期" value={query.date}
      onChange={e=>{if(e.target.value)change('date',e.target.value)}}/></label>
    <Typography.Text>作业区</Typography.Text>
    <Select aria-label="业务作业区" value={query.workArea} options={areas.map(value=>({value,label:value}))}
      style={{minWidth:140}} onChange={value=>change('workArea',value)}/>
    {latest&&latest!==query.date&&<Button onClick={()=>change('date',latest)}>查看最近有计划日期（{latest}）</Button>}
  </Space>;
}
