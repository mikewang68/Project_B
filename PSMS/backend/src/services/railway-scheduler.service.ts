import { z } from 'zod';
import { RailwayRuntime, shanghaiDate } from './railway-runtime.service.js';
import { assertLegacyBackground } from './background-scope-policy.js';

export function railwayScheduleSettings(source: NodeJS.ProcessEnv = process.env) {
  return z.object({
    PSMS_RAILWAY_AUTO_IMPORT:z.enum(['true','false']).default('true'),
    PSMS_RAILWAY_HOUR:z.coerce.number().int().min(0).max(23).default(12),
    PSMS_RAILWAY_MINUTE:z.coerce.number().int().min(0).max(59).default(30),
  }).parse(source);
}
export async function dailyRailwayJob(runtime: RailwayRuntime, now = new Date(), settings=railwayScheduleSettings()) {
  if(settings.PSMS_RAILWAY_AUTO_IMPORT!=='true')return;
  const parts=new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(now);
  const hour=Number(parts.find(p=>p.type==='hour')!.value),minute=Number(parts.find(p=>p.type==='minute')!.value);
  if(hour*60+minute<settings.PSMS_RAILWAY_HOUR*60+settings.PSMS_RAILWAY_MINUTE) return;
  return runtime.importTicket(await runtime.generateTicket(shanghaiDate(now)),'DAILY-RAILWAY-SCHEDULER');
}
export function startRailwayWorker(runtime: RailwayRuntime, report: (error:unknown)=>void) {
  assertLegacyBackground('RAILWAY'); // Fail before registering the timer or executing a tick.
  let busy=false,lastDaily=0,closed=false;
  const execute=async()=>{
    if(busy||closed)return; busy=true;
    try {await runtime.tick();} catch(error) {report(error);}
    try {if(!closed&&Date.now()-lastDaily>=60000){lastDaily=Date.now();await dailyRailwayJob(runtime);}} catch(error) {report(error);}
    finally {busy=false;}
  };
  const timer=setInterval(()=>void execute(),1000);
  void execute();
  return ()=>{closed=true;clearInterval(timer);};
}
