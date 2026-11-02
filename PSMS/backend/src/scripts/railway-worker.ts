/** Server-only worker: independent of the web service and any user's computer. */
import { RailwayRuntime } from '../services/railway-runtime.service.js';
import { startRailwayWorker, railwayScheduleSettings } from '../services/railway-scheduler.service.js';
import { closePool, getPool } from '../db/openGaussClient.js';
import { logger } from '../lib/logger.js';
import type { PoolClient } from 'pg';

// The deployed legacy connectClient attaches another listener on every borrow.
// Keep one listener per pooled client in this independent long-running process.
const watched=new WeakSet<PoolClient>();
const runtime=new RailwayRuntime(async()=>{
  const client=await getPool().connect();
  if(!watched.has(client)){
    watched.add(client);client.on('error',error=>logger.error({err:error},'工单数据库连接异常'));
  }
  return client;
});
try {
  await runtime.initialize();
  const schedule=railwayScheduleSettings();
  const stop=startRailwayWorker(runtime,error=>logger.error({err:error},'服务器铁路工单任务失败'));
  logger.info({hour:schedule.PSMS_RAILWAY_HOUR,minute:schedule.PSMS_RAILWAY_MINUTE,timeZone:'Asia/Shanghai'},'服务器独立工单任务已启动');
  let closing=false;
  const shutdown=async()=>{
    if(closing)return;closing=true;stop();await closePool();process.exit(0);
  };
  process.on('SIGTERM',()=>void shutdown());process.on('SIGINT',()=>void shutdown());
} catch(error) {
  logger.error({err:error},'服务器独立工单任务启动失败');
  await closePool();process.exitCode=1;
}
