import { apiFetch } from './http'
export interface AsyncTask { id:number;taskCode:string;name:string;taskType:string;resourceType:string;state:string;originalFileName?:string;processedRows:number;successRows:number;failureRows:number;errorMessage?:string;completedAt?:string;createdAt:string }
export interface QimenConfig { id:number;customerId:string;appKey:string;callbackUrl?:string;payloadFormat:string;enabled:boolean;secretMasked:string;updatedAt:string }
export interface QimenLog { id:number;direction:string;methodName:string;requestId?:string;relatedOrderCode?:string;success:boolean;errorMessage?:string;createdAt:string }
async function csrf(){return apiFetch<{headerName:string;token:string}>('/api/v1/auth/csrf')}
export const listTasks=():Promise<AsyncTask[]>=>apiFetch('/api/v1/integration/tasks')
export async function createExport(resource:string):Promise<AsyncTask>{const c=await csrf();return apiFetch(`/api/v1/integration/tasks/export/${resource}`,{method:'POST',headers:{[c.headerName]:c.token}})}
export async function createImport(resource:string,file:File):Promise<AsyncTask>{const c=await csrf();const data=new FormData();data.append('file',file);return apiFetch(`/api/v1/integration/tasks/import/${resource}`,{method:'POST',data,headers:{[c.headerName]:c.token}})}
export const getQimenConfig=():Promise<QimenConfig|undefined>=>apiFetch('/api/v1/integration/qimen/config')
export async function saveQimenConfig(data:unknown):Promise<QimenConfig>{const c=await csrf();return apiFetch('/api/v1/integration/qimen/config',{method:'PUT',data,headers:{[c.headerName]:c.token}})}
export const listQimenLogs=():Promise<QimenLog[]>=>apiFetch('/api/v1/integration/qimen/logs')
