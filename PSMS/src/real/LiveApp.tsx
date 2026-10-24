import { useEffect, useState } from 'react';
import { Alert, Button, Space, Spin } from 'antd';
import { z } from 'zod';
import type { DemoRootState } from '../stores';
import type { DemoScenario } from '../mocks/fixtures';
import type { CommandResult } from '../commands';
import { setVerifiedLiveSession, type SessionContext } from '../auth';
import { createDemoRuntime, type DemoRuntime } from '../runtime';
import { liveApi as api, loadLivePlans as loadPlans } from './client';
import App from '../app/App';

const userSchema = z.object({actorId:z.string(),username:z.string(),displayName:z.string(),roleCode:z.string(),dataScope:z.array(z.string()),
  readOnly:z.boolean(),superAdmin:z.boolean(),permissions:z.array(z.string())});
async function bootstrap(): Promise<DemoRuntime> {
  const me=userSchema.parse((await api('/auth/me')).data);
  const session:SessionContext={actorId:me.actorId,roleCode:me.readOnly?'AUDITOR':me.superAdmin?'SYS_ADMIN':'DISPATCHER',
    dataScope:me.dataScope,online:true,demoTime:new Date().toISOString(),readOnly:me.readOnly,fullAdmin:me.superAdmin,permissions:me.permissions};
  setVerifiedLiveSession(session);
  const plans=await loadPlans();
  const current:DemoScenario={id:'SCN-01',name:'当前业务',clock:session.demoTime,initialState:null,events:[],expected:null,
    resetPoint:'LIVE_DISABLED',seedRefs:[],fault:{type:'NONE',errorCode:null,delayMs:0,mutations:[]},
    reset:{restoreBaseObjects:false,restoreScenarioOverlay:false,resetTraceCounter:0,resetAuditCounter:0,clearFaultState:false}};
  const state:DemoRootState={session:{actorId:session.actorId,roleCode:session.roleCode,dataScope:[...session.dataScope],online:true,shiftId:'LIVE',scenarioId:current.id,demoTime:session.demoTime},
    scenario:{activeScenarioId:current.id,scenarios:[current],activeFault:current.fault,resetPoint:current.resetPoint},
    plan:{plans,waybills:[]},recommendation:{drafts:{}},workOrder:{workOrders:[],nodes:[]},resource:{tracks:[],materials:[],resources:[]},
    vehicle:{appointments:[]},exception:{exceptions:[]},interlock:{interlocks:[]},offline:{packets:[]},report:{reports:[]},
    systemConfig:{configVersions:[]},configAudit:{audit:[],userRoles:[],commandAudit:[]}};
  const liveFetch:typeof fetch=async(input,init)=>{
    const url=new URL(String(input),window.location.origin),method=(init?.method??'GET').toUpperCase();
    if(method==='GET'&&/\/mock\/(overview|plans)$/.test(url.pathname)){
      const next=await loadPlans();runtime.store.replaceDomainState(s=>{s.plan.plans=next;});
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
  useEffect(()=>{void bootstrap().then(setRuntime).catch(e=>setError(e instanceof Error?e.message:'业务会话恢复失败'));},[]);
  if(error)return <Alert type="error" title="生产调度业务数据未能加载" description={error} action={<Button onClick={()=>window.location.reload()}>重试</Button>}/>;
  if(!runtime)return <Spin fullscreen tip="正在恢复业务会话与台账…"/>;
  return <><Space style={{position:'fixed',right:24,bottom:16,zIndex:1001}}><Button onClick={async()=>{await api('/auth/sso/logout',{method:'POST',body:'{}'});window.location.replace('/sso/start?page=PSMS%3Aui-002');}}>退出平台</Button></Space><App runtime={runtime}/></>;
}
