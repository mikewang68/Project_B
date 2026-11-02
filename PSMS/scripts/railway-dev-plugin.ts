import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync, mkdirSync, openSync, closeSync } from 'node:fs';
import { connect } from 'node:net';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import type { Plugin } from 'vite';

function portOpen(): Promise<boolean> {
  return new Promise(resolve=>{
    const socket=connect(3101,'127.0.0.1');let done=false;
    const finish=(value:boolean)=>{if(done)return;done=true;socket.destroy();resolve(value);};
    socket.setTimeout(700);socket.once('connect',()=>finish(true));socket.once('error',()=>finish(false));socket.once('timeout',()=>finish(false));
  });
}

/** Start and supervise the isolated local runtime whenever Vite dev is running. */
export function railwayDevPlugin(enabled:boolean): Plugin {
  return {name:'psms-local-railway-runtime',apply:'serve',configureServer(server){
    if(!enabled||process.env.VITEST)return;
    const backend=fileURLToPath(new URL('../backend/',import.meta.url));
    const loader=path.join(backend,'node_modules/tsx/dist/loader.mjs');
    if(!existsSync(loader)){
      server.config.logger.error('工单后台依赖缺失，请先在 PSMS/backend 执行 pnpm --ignore-workspace install --frozen-lockfile');return;
    }
    let child:ChildProcess|undefined,closed=false,checking=false,nextStart=0,failures=0;
    const logDir=path.join(backend,'.data');mkdirSync(logDir,{recursive:true});
    const logPath=path.join(logDir,'railway-runtime.log');
    const ensure=async()=>{
      if(closed||child||checking||Date.now()<nextStart)return;checking=true;
      try{
        if(await portOpen()||closed)return;
        const log=openSync(logPath,'a');
        try{
          child=spawn(process.execPath,['--import',pathToFileURL(loader).href,path.join(backend,'src/scripts/railway-demo.ts')],{
            cwd:backend,windowsHide:true,stdio:['ignore',log,log],env:{...process.env,NODE_ENV:'development'},
          });
        }finally{closeSync(log);}
        server.config.logger.info('已自动启动铁路工单后台，日志：backend/.data/railway-runtime.log');
        child.once('error',error=>server.config.logger.error(`工单后台启动失败：${error.message}`));
        child.once('close',()=>{
          child=undefined;failures++;
          nextStart=Date.now()+Math.min(30000,1000*2**Math.min(failures,5));
          if(!closed)server.config.logger.warn('工单后台已退出，将自动重试；详情见 backend/.data/railway-runtime.log');
        });
      }finally{checking=false;}
    };
    void ensure();const timer=setInterval(()=>void ensure(),3000);
    server.httpServer?.once('close',()=>{closed=true;clearInterval(timer);child?.kill('SIGTERM');});
  }};
}
