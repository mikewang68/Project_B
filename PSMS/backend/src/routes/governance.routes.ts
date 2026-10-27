import {Router,type Request,type Response} from 'express';
import {authenticate} from '../middleware/auth.js';
import {env} from '../config/env.js';
import {AppError} from '../middleware/errorHandler.js';
import {beijingToday} from '../services/report-period.js';
import {generateReport} from '../services/report.service.js';
import {readGovernance,submitOffline,resolveOffline,createConfig,updateGovernanceConfig,interlockRequest,
 governanceArea,recordGovernanceExport,type GovernanceActor} from '../services/governance.service.js';

/** Real-mode adapters precede legacy demo contracts. Every path has a current SSO permission and area policy. */
export const governanceRouter=Router();
governanceRouter.use((req,res,next)=>{
 if(!env.PSMS_SSO_ENABLED)return next('router');
 if(!/^\/(interlocks|interlock-requests|offline|settings|reports|audit-logs)(\/|$)/.test(req.path))return next('router');
 authenticate(req,res,next);
});
function actor(req:Request,res:Response):GovernanceActor{
 if(!req.user||typeof res.locals.platformSubject!=='string')throw new AppError(401,'UNAUTHORIZED','需要当前统一身份');
 return {subject:res.locals.platformSubject,roleCode:req.user.roleCode,dataScope:req.user.dataScope};
}
for(const resource of ['interlocks','interlock-requests','offline','settings','audit-logs'] as const){
 governanceRouter.get('/'+resource,async(req,res,next)=>{
  try{res.setHeader('Cache-Control','no-store');res.json({ok:true,data:await readGovernance(resource,actor(req,res),req.query)});}
  catch(error){next(error);}
 });
 governanceRouter.get('/'+resource+'/:id',async(req,res,next)=>{
  try{const data=await readGovernance(resource,actor(req,res),{id:String(req.params.id),pageSize:1});
   if(!data.items.length)throw new AppError(404,'OBJECT_NOT_FOUND','记录不存在或不在授权范围');
   res.json({ok:true,data});}catch(error){next(error);}
 });
}
governanceRouter.post('/offline/:id',async(req,res,next)=>{
 try{const a=actor(req,res);res.json({ok:true,data:req.body?.action==='sync'
  ?await submitOffline(String(req.params.id),req.body,a):await resolveOffline(String(req.params.id),req.body,a)});}catch(error){next(error);}
});
governanceRouter.post('/settings',async(req,res,next)=>{
 try{res.status(201).json({ok:true,data:await createConfig(req.body,actor(req,res))});}catch(error){next(error);}
});
governanceRouter.post('/settings/:id',async(req,res,next)=>{
 try{res.json({ok:true,data:await updateGovernanceConfig(String(req.params.id),req.body,actor(req,res))});}catch(error){next(error);}
});
for(const path of ['/interlocks/:id','/interlock-requests/:id'])governanceRouter.post(path,async(req,res,next)=>{
 try{res.json({ok:true,data:await interlockRequest(String(req.params.id),req.body,actor(req,res))});}catch(error){next(error);}
});
function reportInput(req:Request,res:Response,source:Record<string,unknown>){
 const allowed=new Set(['startDate','endDate','workArea','type','purpose']);
 if(Object.keys(source).some(k=>!allowed.has(k))||Object.values(source).some(v=>typeof v!=='string'))
  throw new AppError(400,'INVALID_FILTER','报表筛选字段无效');
 const workArea=source.workArea as string|undefined;
 const areas=governanceArea(actor(req,res),workArea);
 return {startDate:(source.startDate as string|undefined)??beijingToday(),
  endDate:(source.endDate as string|undefined)??beijingToday(),workArea,workAreas:areas,
  type:(source.type as string|undefined)??'daily'};
}
governanceRouter.get('/reports',async(req,res,next)=>{
 try{res.setHeader('Cache-Control','no-store');res.json({ok:true,data:await generateReport(reportInput(req,res,req.query))});}catch(error){next(error);}
});
governanceRouter.post('/reports/export',async(req,res,next)=>{
 try{if(typeof req.body?.purpose!=='string'||!req.body.purpose.trim()||req.body.purpose.length>500)
   throw new AppError(400,'PURPOSE_REQUIRED','报表导出须填写用途');
  const filters=reportInput(req,res,req.body);
  const report=await generateReport(filters);
  const audit=await recordGovernanceExport(actor(req,res),filters,req.body.purpose);
  res.setHeader('Content-Disposition','attachment; filename="operations-report.json"');
  res.setHeader('Cache-Control','no-store');res.json({ok:true,data:{purpose:req.body.purpose,report,...audit}});
 }catch(error){next(error);}
});
