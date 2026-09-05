import { apiFetch } from './http'

export type MasterResource = 'warehouses'|'owners'|'areas'|'work-areas'|'locations'|'partners'|'categories'|'goods'|'components'
export interface MasterItem {
  id:number; code:string; name:string; type?:string; status:string; parentCode?:string; secondaryCode?:string
  barcode?:string; specification?:string; unit?:string; contact?:string; telephone?:string; address?:string
  remark?:string; quantity?:number; price?:number
}
export interface SaveMasterItem extends Omit<MasterItem,'id'> {}

export const listMasterData=(resource:MasterResource):Promise<MasterItem[]>=>apiFetch(`/api/v1/master-data/${resource}`)
export async function saveMasterData(resource:MasterResource,id:number|null,body:SaveMasterItem):Promise<MasterItem>{
  const csrf=await apiFetch<{headerName:string;token:string}>('/api/v1/auth/csrf')
  return apiFetch(id?`/api/v1/master-data/${resource}/${id}`:`/api/v1/master-data/${resource}`,{method:id?'PUT':'POST',data:body,headers:{[csrf.headerName]:csrf.token}})
}
