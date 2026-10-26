import { apiFetch } from './http'

export interface InventoryBalance {
  id:number; goodCode:string; goodName:string; barcode:string; locationCode:string; batchCode:string
  qualityType:string; supplierCode:string; productDate?:string; expireDate?:string; lpn:string
  availableQty:number; allocatedQty:number; frozenQty:number; totalQty:number; version:number
}
export interface InventoryTransaction {
  id:number; transactionType:string; operationCode:string; goodCode:string; locationCode:string
  quantityBefore:number; quantityChange:number; quantityAfter:number; relatedLocationCode?:string
  remark?:string; operatorName:string; createdAt:string
}
export interface InventoryWarning { level:'LOW'|'HIGH'; goodCode:string; goodName:string; currentQty:number; thresholdQty:number }
export interface InventorySerial {
  id:number; serialCode:string; goodCode:string; goodName:string; locationCode:string; quantity:number
  weightKg:number; source:string; state:string; printed:boolean; createdAt:string
}
export interface ReplenishmentRule {
  id:number; goodCode:string; goodName:string; locationCode:string; minQty:number; maxQty:number
  currentQty:number; suggestedQty:number; status:string; remark?:string
}
export interface ReplenishmentResult { operationCode:string; ruleCount:number; lineCount:number; movedQuantity:number }
export interface OperationResult { operationCode:string; balance:InventoryBalance }
export interface MoveResult { operationCode:string; source:InventoryBalance; destination:InventoryBalance }

async function mutate<T>(path:string,data:unknown):Promise<T>{
  const csrf=await apiFetch<{headerName:string;token:string}>('/api/v1/auth/csrf')
  return apiFetch(path,{method:'POST',data,headers:{[csrf.headerName]:csrf.token}})
}

export const listBalances=():Promise<InventoryBalance[]>=>apiFetch('/api/v1/inventory/balances')
export const listTransactions=():Promise<InventoryTransaction[]>=>apiFetch('/api/v1/inventory/transactions')
export const listWarnings=():Promise<InventoryWarning[]>=>apiFetch('/api/v1/inventory/warnings')
export const listSerials=():Promise<InventorySerial[]>=>apiFetch('/api/v1/inventory/serials')
export const listReplenishmentRules=():Promise<ReplenishmentRule[]>=>apiFetch('/api/v1/inventory/replenishment-rules')
export const adjustInventory=(data:unknown):Promise<OperationResult>=>mutate('/api/v1/inventory/adjustments',data)
export const moveInventory=(data:unknown):Promise<MoveResult>=>mutate('/api/v1/inventory/moves',data)
export const freezeInventory=(data:unknown):Promise<OperationResult>=>mutate('/api/v1/inventory/freeze',data)
export const unfreezeInventory=(data:unknown):Promise<OperationResult>=>mutate('/api/v1/inventory/unfreeze',data)
export const countInventory=(data:unknown):Promise<OperationResult>=>mutate('/api/v1/inventory/counts',data)
export const createInventorySerial=(data:unknown):Promise<InventorySerial>=>mutate('/api/v1/inventory/serials',data)
export const generateReplenishment=(data:unknown):Promise<ReplenishmentResult>=>mutate('/api/v1/inventory/replenishments/generate',data)
export async function saveReplenishmentRule(id:number|null,data:unknown):Promise<ReplenishmentRule>{
  const csrf=await apiFetch<{headerName:string;token:string}>('/api/v1/auth/csrf')
  return apiFetch(id?`/api/v1/inventory/replenishment-rules/${id}`:'/api/v1/inventory/replenishment-rules',{method:id?'PUT':'POST',data,headers:{[csrf.headerName]:csrf.token}})
}
