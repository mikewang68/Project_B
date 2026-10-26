import { apiFetch } from './http'

export interface StockoutLine { id:number;lineNo:number;goodCode:string;goodName:string;barcode:string;plannedQty:number;allocatedQty:number;pickedQty:number;shippedQty:number;unallocatedQty:number;unpickedQty:number;unshippedQty:number;supplierCode:string;qualityType:string;batchCode:string;unitPrice:number;remark?:string }
export interface StockoutOrderSummary { id:number;orderCode:string;externalOrderCode?:string;outboundType:string;source:string;state:string;allocationState:string;pickState:string;shipState:string;partnerCode?:string;partnerName?:string;plannedDate?:string;plannedQty:number;allocatedQty:number;pickedQty:number;shippedQty:number;totalAmount:number;createdAt:string;completedAt?:string }
export interface StockoutOrderDetail extends StockoutOrderSummary { relatedOrderCode?:string;receiverName?:string;receiverPhone?:string;receiverAddress?:string;carrierCode?:string;trackingCode?:string;targetWarehouseCode?:string;targetOwnerCode?:string;transferInOrderCode?:string;remark?:string;lines:StockoutLine[] }
export interface StockoutAllocation { id:number;lineNo:number;goodCode:string;goodName:string;locationCode:string;batchCode:string;qualityType:string;supplierCode:string;lpn:string;allocatedQty:number;pickedQty:number;shippedQty:number;state:string;createdAt:string }
export interface StockoutEvent { id:number;eventType:string;operationCode?:string;lineNo:number;goodCode?:string;goodName?:string;locationCode?:string;quantity:number;operatorName:string;remark?:string;createdAt:string }
export interface PackageLine { id:number;orderLineId:number;lineNo:number;goodCode:string;goodName:string;quantity:number }
export interface StockoutPackage { id:number;packageCode:string;carrierCode?:string;trackingCode?:string;state:string;shippedAt?:string;remark?:string;createdAt:string;lines:PackageLine[] }
export interface StockoutWave { id:number;waveCode:string;state:string;orderCount:number;remark?:string;createdAt:string;orderCodes:string[] }
export interface OperationResult { operationCode:string;order:StockoutOrderDetail;quantity:number }

async function mutate<T>(path:string,data:unknown):Promise<T>{const csrf=await apiFetch<{headerName:string;token:string}>('/api/v1/auth/csrf');return apiFetch(path,{method:'POST',data,headers:{[csrf.headerName]:csrf.token}})}
export const listStockoutOrders=():Promise<StockoutOrderSummary[]>=>apiFetch('/api/v1/stockout')
export const getStockoutOrder=(id:number):Promise<StockoutOrderDetail>=>apiFetch(`/api/v1/stockout/${id}`)
export const listStockoutAllocations=(id:number):Promise<StockoutAllocation[]>=>apiFetch(`/api/v1/stockout/${id}/allocations`)
export const listStockoutEvents=(id:number):Promise<StockoutEvent[]>=>apiFetch(`/api/v1/stockout/${id}/events`)
export const listStockoutPackages=(id:number):Promise<StockoutPackage[]>=>apiFetch(`/api/v1/stockout/${id}/packages`)
export const createStockoutOrder=(data:unknown):Promise<StockoutOrderDetail>=>mutate('/api/v1/stockout',data)
export const allocateStockout=(id:number,data:unknown):Promise<OperationResult>=>mutate(`/api/v1/stockout/${id}/allocate`,data)
export const pickStockout=(id:number,data:unknown):Promise<OperationResult>=>mutate(`/api/v1/stockout/${id}/pick`,data)
export const pickAllStockout=(id:number,data:unknown):Promise<OperationResult>=>mutate(`/api/v1/stockout/${id}/pick-all`,data)
export const packStockout=(id:number,data:unknown):Promise<StockoutPackage>=>mutate(`/api/v1/stockout/${id}/packages`,data)
export const shipStockout=(id:number,data:unknown):Promise<OperationResult>=>mutate(`/api/v1/stockout/${id}/ship`,data)
export const cancelAllocation=(id:number,data:unknown):Promise<StockoutOrderDetail>=>mutate(`/api/v1/stockout/${id}/cancel-allocation`,data)
export const cancelStockout=(id:number,data:unknown):Promise<StockoutOrderDetail>=>mutate(`/api/v1/stockout/${id}/cancel`,data)
export const completeStockout=(id:number,data:unknown):Promise<StockoutOrderDetail>=>mutate(`/api/v1/stockout/${id}/complete`,data)
export const reverseStockout=(id:number,data:unknown):Promise<string>=>mutate(`/api/v1/stockout/${id}/reverse`,data)
export const listStockoutWaves=():Promise<StockoutWave[]>=>apiFetch('/api/v1/stockout/waves')
export const createStockoutWave=(data:unknown):Promise<StockoutWave>=>mutate('/api/v1/stockout/waves',data)
export const allocateStockoutWave=(id:number,data:unknown):Promise<StockoutWave>=>mutate(`/api/v1/stockout/waves/${id}/allocate`,data)
export const pickStockoutWave=(id:number,data:unknown):Promise<StockoutWave>=>mutate(`/api/v1/stockout/waves/${id}/pick`,data)
