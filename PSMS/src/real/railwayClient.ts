import { liveApi } from './client';
import { withAppBasePath } from '../runtime/appBasePath';

export class RailwayApiError extends Error {
  constructor(message:string,public status:number){super(message);}
}
export async function railwayApi(path='',body?:unknown) {
  if(import.meta.env.VITE_PSMS_REAL_API==='true')return liveApi('/railway'+path,body===undefined?{}:{method:'POST',body:JSON.stringify(body)});
  const token=sessionStorage.getItem('psms:backend-access-token');
  const response=await fetch(withAppBasePath('/api/railway'+path),{method:body===undefined?'GET':'POST',cache:'no-store',credentials:'same-origin',
    headers:{'Content-Type':'application/json','X-PSMS-Local-Runtime':'1',...(token?{Authorization:`Bearer ${token}`}:{})},
    ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)});
  const type=response.headers.get('content-type')??'';
  if(!type.includes('application/json'))throw new RailwayApiError(
    import.meta.env.DEV&&[502,503,504].includes(response.status)
      ?'工单后台暂未就绪，请稍后重试；本地后台会随系统启动并自动恢复'
      :'工单接口尚未部署或代理未接通',response.status);
  const result=await response.json();
  if(!response.ok||result.ok!==true)throw new RailwayApiError(result.message??'工单数据库请求失败',response.status);
  return result;
}
