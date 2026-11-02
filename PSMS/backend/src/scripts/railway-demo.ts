// Persistent, isolated local PostgreSQL engine. No access to the configured shared database.
import { PGlite } from '@electric-sql/pglite';
import express from 'express';
import path from 'node:path';
import { BACKEND_ROOT } from '../config/env.js';
import { SCHEMA_STATEMENTS } from '../db/schema.js';
import { RailwayRuntime } from '../services/railway-runtime.service.js';
import { startRailwayWorker } from '../services/railway-scheduler.service.js';
import { railwayRouter } from '../routes/railway.routes.js';
import type { PoolClient } from 'pg';
import { mkdir } from 'node:fs/promises';

await mkdir(path.join(BACKEND_ROOT,'.data'),{recursive:true});
const db=await PGlite.create(path.join(BACKEND_ROOT,'.data','railway-demo'));
// The embedded connection supports a single transaction. Queue callers until release.
let queue=Promise.resolve();
const connect=async()=>{
  const previous=queue;let release!:()=>void;
  queue=new Promise<void>(resolve=>{release=resolve;});await previous;
  return {query:async(sql:string,values?:unknown[])=>db.query(sql,values),release} as unknown as Pick<PoolClient,'query'|'release'>;
};
for(const sql of SCHEMA_STATEMENTS) {
  const add=sql.match(/^ALTER TABLE (\w+) ADD COLUMN (\w+)/);
  if(add && (await db.query('SELECT 1 FROM information_schema.columns WHERE table_schema=$1 AND table_name=$2 AND column_name=$3',['public',add[1],add[2]])).rows.length) continue;
  await db.exec(sql);
}
const runtime=new RailwayRuntime(connect,15,true);await runtime.initialize();
const app=express();app.use(express.json({limit:'64kb'}));
app.use((req,res,next)=>{
  const origin=req.get('origin');
  if(!['localhost','127.0.0.1'].includes(req.hostname) || (origin&&!['http://localhost:5173','http://127.0.0.1:5173'].includes(origin)) ||
    (req.method!=='GET'&&req.get('X-PSMS-Local-Runtime')!=='1')) {res.status(403).json({ok:false,message:'仅允许本机运行台访问'});return;}
  next();
});
app.use('/api/railway',railwayRouter(runtime));
app.use((_req,res)=>{res.status(404).json({ok:false,message:'接口不存在'});});
app.use((error:unknown,_req:express.Request,res:express.Response,_next:express.NextFunction)=>{console.error(error);res.status(500).json({ok:false,message:'本地数据库请求失败，请查看运行日志'});});
const stop=startRailwayWorker(runtime,error=>console.error('铁路运行后台任务失败',error));
const server=app.listen(3101,'127.0.0.1',()=>console.log('铁路模拟运行台 API http://127.0.0.1:3101/api/railway；数据持久化于 backend/.data/railway-demo'));
async function shutdown(){stop();server.close();await queue;await db.close();process.exit(0);}
process.on('SIGINT',()=>void shutdown());process.on('SIGTERM',()=>void shutdown());
