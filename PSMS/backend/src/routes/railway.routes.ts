import { Router } from 'express';
import { RailwayRuntime, generateRailwayTicket } from '../services/railway-runtime.service.js';
import { railwayScheduleSettings } from '../services/railway-scheduler.service.js';
export function railwayRouter(runtime: RailwayRuntime) {
  const router=Router();
  router.use((req,res,next)=>{
    if(req.user && !req.user.dataScope?.some(scope=>['AREA-A','*','GLOBAL'].includes(scope))){
      res.status(403).json({ok:false,message:'当前账号没有模拟工单所在作业区的权限'});return;
    }
    next();
  });
  router.get('/',async(_req,res,next)=>{try{res.json({ok:true,data:{...await runtime.overview(),
    simulated:true,intervalSeconds:runtime.intervalSeconds,schedule:railwayScheduleSettings()}});}catch(e){next(e);}});
  router.post('/generate',async(_req,res,next)=>{try{const ticket=generateRailwayTicket();await runtime.ensureTicket(ticket);res.json({ok:true,data:ticket});}catch(e){next(e);}});
  router.post('/import',async(req,res)=>{
    try{const run=await runtime.importTicket(req.body,req.user?.actorId??'LOCAL-DEMO-USER');res.status(201).json({ok:true,data:run});}
    catch{res.status(400).json({ok:false,message:'工单格式错误或相同编号内容冲突，请导入本系统生成的模拟工单。'});}
  });
  return router;
}
