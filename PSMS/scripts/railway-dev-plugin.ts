import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync, mkdirSync, openSync, closeSync } from 'node:fs';
import { connect } from 'node:net';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import type { Plugin } from 'vite';

function portOpen(port: number): Promise<boolean> {
  return new Promise(resolve=>{
    const socket=connect(port,'127.0.0.1');let done=false;
    const finish=(value:boolean)=>{if(done)return;done=true;socket.destroy();resolve(value);};
    socket.setTimeout(700);socket.once('connect',()=>finish(true));socket.once('error',()=>finish(false));socket.once('timeout',()=>finish(false));
  });
}

/** Start and supervise local backends alongside the Vite development server. */
function localRuntimePlugin(enabled: boolean, options: {
  name: string; label: string; port: number; script: string; env?: NodeJS.ProcessEnv;
}): Plugin {
  return {name: options.name, apply:'serve',configureServer(server){
    if(!enabled||process.env.VITEST)return;
    const backend=fileURLToPath(new URL('../backend/',import.meta.url));
    const loader=path.join(backend,'node_modules/tsx/dist/loader.mjs');
    if(!existsSync(loader)){
      server.config.logger.error(`${options.label}依赖缺失，请先安装 PSMS/backend 依赖`);return;
    }
    let child:ChildProcess|undefined,closed=false,checking=false,nextStart=0,failures=0;
    const logDir=path.join(backend,'.data');mkdirSync(logDir,{recursive:true});
    const logPath=path.join(logDir,options.name + '.log');
    const ensure=async()=>{
      if(closed||child||checking||Date.now()<nextStart)return;checking=true;
      try{
        if(await portOpen(options.port)||closed)return;
        const log=openSync(logPath,'a');
        try{
          child=spawn(process.execPath,['--import',pathToFileURL(loader).href,path.join(backend,'src/scripts',options.script)],{
            cwd:backend,windowsHide:true,stdio:['ignore',log,log],env:{...process.env,NODE_ENV:'development',...options.env},
          });
        }finally{closeSync(log);}
        server.config.logger.info(`已自动启动${options.label}，日志：backend/.data/${options.name}.log`);
        child.once('error',error=>server.config.logger.error(`${options.label}启动失败：${error.message}`));
        child.once('close',()=>{
          child=undefined;failures++;
          nextStart=Date.now()+Math.min(30000,1000*2**Math.min(failures,5));
          if(!closed)server.config.logger.warn(`${options.label}已退出，将自动重试；详情见 backend/.data/${options.name}.log`);
        });
      }finally{checking=false;}
    };
    void ensure();const timer=setInterval(()=>void ensure(),3000);
    server.httpServer?.once('close',()=>{closed=true;clearInterval(timer);child?.kill('SIGTERM');});
  }};
}

export function railwayDevPlugin(enabled: boolean) {
  return localRuntimePlugin(enabled, { name: 'railway-runtime', label: '铁路工单后台', port: 3101, script: 'railway-demo.ts' });
}

export const localAiPort = 3102;
export function aiDevPlugin(enabled: boolean) {
  return localRuntimePlugin(enabled, { name: 'ai-runtime', label: 'AI 调度助手后台', port: localAiPort,
    script: 'ai-dev.ts', env: { PORT: String(localAiPort), PSMS_SSO_ENABLED: 'false' } });
}
