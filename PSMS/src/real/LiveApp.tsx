import { useEffect, useState } from 'react';
import { Alert, Button, Space, Spin } from 'antd';
import { z } from 'zod';
import type { DemoRootState } from '../stores';
import type { DemoScenario } from '../mocks/fixtures';
import type { CommandResult } from '../commands';
import { setVerifiedLiveSession,loadCurrentDemoSession, type SessionContext } from '../auth';
import { createDemoRuntime, type DemoRuntime } from '../runtime';
import { liveApi as api, loadLivePlans as loadPlans, loadLiveOverview } from './client';
import { parsePlanEntryQuery } from '../features/plan-entry/query';
import App from '../app/App';

const userSchema = z.object({actorId:z.string(),username:z.string(),displayName:z.string(),roleCode:z.string(),dataScope:z.array(z.string()),
  readOnly:z.boolean(),superAdmin:z.boolean(),applicationAdmin:z.boolean().optional(),permissions:z.array(z.string())});
async function bootstrap(): Promise<DemoRuntime> {
  const me=userSchema.parse((await api('/auth/me')).data);
  const admin = me.superAdmin || me.applicationAdmin === true;
  const session:SessionContext={actorId:me.actorId,roleCode:me.readOnly?'AUDITOR':admin?'SYS_ADMIN':'DISPATCHER',
    dataScope:me.dataScope,online:true,demoTime:new Date().toISOString(),readOnly:me.readOnly,fullAdmin:admin,
    platformSuperAdmin:me.superAdmin,permissions:me.permissions};
  setVerifiedLiveSession(session);
  const plans=await loadPlans();
  const canReadOverview = admin || ['pps:api:overview:read','overview:view','pps:overview:view'].some(p=>me.permissions.includes(p));
  const readOverview = () => {
    const query = parsePlanEntryQuery(window.location.search);
    return canReadOverview ? loadLiveOverview(query.date, query.workArea) : Promise.resolve(undefined);
  };
  const liveOverview = await readOverview();
  const current:DemoScenario={id:'SCN-01',name:'当前业务',clock:session.demoTime,initialState:null,events:[],expected:null,
    resetPoint:'LIVE_DISABLED',seedRefs:[],fault:{type:'NONE',errorCode:null,delayMs:0,mutations:[]},
    reset:{restoreBaseObjects:false,restoreScenarioOverlay:false,resetTraceCounter:0,resetAuditCounter:0,clearFaultState:false}};
  const state:DemoRootState={liveOverview,session:{actorId:session.actorId,roleCode:session.roleCode,dataScope:[...session.dataScope],online:true,shiftId:'LIVE',scenarioId:current.id,demoTime:session.demoTime},
    scenario:{activeScenarioId:current.id,scenarios:[current],activeFault:current.fault,resetPoint:current.resetPoint},
    plan:{plans,waybills:[]},recommendation:{drafts:{}},workOrder:{workOrders:[],nodes:[]},resource:{tracks:[],materials:[],resources:[]},
    vehicle:{appointments:[]},exception:{exceptions:[]},interlock:{interlocks:[]},offline:{packets:[]},report:{reports:[]},
    systemConfig:{configVersions:[]},configAudit:{audit:[],userRoles:[],commandAudit:[]}};
  const liveFetch:typeof fetch=async(input,init)=>{
    const url=new URL(String(input),window.location.origin),method=(init?.method??'GET').toUpperCase();
    if(method==='GET'&&/\/mock\/(overview|plans)$/.test(url.pathname)){
      const [next, overview] = await Promise.all([loadPlans(), readOverview()]);
      runtime.store.replaceDomainState(s=>{s.plan.plans=next;s.liveOverview=overview;});
      return new Response(JSON.stringify({ok:true,data:{},traceId:'LIVE-READ',auditLogId:''}),{headers:{'Content-Type':'application/json'}});
    }
    throw new Error('此功能的业务接口适配尚未发布');
  };
  const runtime=createDemoRuntime(liveFetch,{initialState:state});
  const unavailable=async():Promise<CommandResult>=>({ok:false,commandId:'',traceId:'',auditLogId:'',errorCode:'TOS-AUTH-001',message:'此操作尚未完成业务接口适配'});
  runtime.commands={...runtime.commands,syncPlans:unavailable,supplementPlan:unavailable,resetScenario:unavailable,
    switchRole:()=>{throw new Error('业务模式禁止客户端切换身份');},
    confirmPlan:async(id,values)=>{
      try {
        await api(`/plans/${encodeURIComponent(id)}/confirm`,{method:'POST',body:JSON.stringify({supplements:values??{}})});
        const next=await loadPlans();runtime.store.replaceDomainState(s=>{s.plan.plans=next;});
        return{ok:true,commandId:'LIVE-'+id,traceId:'',auditLogId:''};
      }catch(e){return{ok:false,commandId:'LIVE-'+id,traceId:'',auditLogId:'',errorCode:'TOS-AUTH-001',message:e instanceof Error?e.message:'业务操作失败'};}
    }};
  return runtime;
}
export default function LiveApp(){
  const[runtime,setRuntime]=useState<DemoRuntime>();const[error,setError]=useState('');
  const[refreshError,setRefreshError]=useState('');
  useEffect(()=>{void bootstrap().then(setRuntime).catch(e=>setError(e instanceof Error?e.message:'业务会话恢复失败'));},[]);
  useEffect(()=>{
    if(!runtime)return;
    let pending=false,disposed=false;
    const timer=window.setInterval(async()=>{
      if(pending||document.visibilityState==='hidden')return;
      pending=true;
      try{
        const current=userSchema.parse((await api('/auth/me')).data),old=loadCurrentDemoSession();
        const signature=(permissions:readonly string[],scope:readonly string[],readOnly:boolean,admin:boolean)=>JSON.stringify([
          [...permissions].sort(),[...scope].sort(),readOnly,admin]);
        if(signature(current.permissions,current.dataScope,current.readOnly,current.superAdmin||current.applicationAdmin===true)
          !==signature(old.permissions??[],old.dataScope,old.readOnly===true,old.fullAdmin===true)){
          setVerifiedLiveSession({...old,permissions:[],readOnly:true,fullAdmin:false,dataScope:[]});
          window.location.reload();return;
        }
        await runtime.gateway.getOverview();if(!disposed)setRefreshError('');
      }
      catch(e){if(!disposed)setRefreshError(e instanceof Error?e.message:'业务刷新失败');}
      finally{pending=false;}
    },15000);
    return()=>{disposed=true;window.clearInterval(timer);};
  },[runtime]);
  if(error)return <Alert type="error" title="生产调度业务数据未能加载" description={error} action={<Button onClick={()=>window.location.reload()}>重试</Button>}/>;
  if(!runtime)return <Spin fullscreen tip="正在恢复业务会话与台账…"/>;
  return <>{refreshError&&<Alert type="warning" title="自动刷新失败，页面保留上次成功读取的数据" description={refreshError}/>}
    <Space style={{position:'fixed',right:24,bottom:16,zIndex:1001}}><Button onClick={async()=>{await api('/auth/sso/logout',{method:'POST',body:'{}'});window.location.replace('/sso/start?page=PSMS%3Aui-002');}}>退出平台</Button></Space><App runtime={runtime}/></>;
}
