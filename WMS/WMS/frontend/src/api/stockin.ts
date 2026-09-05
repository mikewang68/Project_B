import { apiFetch } from './http'

export interface StockinLine {
  id:number; lineNo:number; goodCode:string; goodName:string; barcode:string; plannedQty:number; receivedQty:number
  remainingQty:number; preferredLocationCode?:string; supplierCode:string; qualityType:string; productDate?:string
  expireDate?:string; batchCode:string; unitPrice:number; remark?:string
}
export interface StockinOrderSummary {
  id:number; orderCode:string; externalOrderCode?:string; inboundType:string; source:string; state:string
  partnerCode?:string; partnerName?:string; plannedDate?:string; plannedQty:number; receivedQty:number
  totalAmount:number; createdAt:string; finishedAt?:string
}
export interface StockinOrderDetail extends StockinOrderSummary { relatedOrderCode?:string; remark?:string; lines:StockinLine[] }
export interface StockinReceipt {
  id:number; operationCode:string; lineNo:number; goodCode:string; goodName:string; locationCode:string
  quantity:number; batchCode:string; qualityType:string; lpn:string; serialCount:number; operatorName:string
  remark?:string; createdAt:string
}
export interface ReceiveResult { operationCode:string; order:StockinOrderDetail; receiptCount:number; receivedQuantity:number }

async function mutate<T>(path:string,data:unknown):Promise<T>{
  const csrf=await apiFetch<{headerName:string;token:string}>('/api/v1/auth/csrf')
  return apiFetch(path,{method:'POST',data,headers:{[csrf.headerName]:csrf.token}})
}
export const listStockinOrders=():Promise<StockinOrderSummary[]>=>apiFetch('/api/v1/stockin')
export const getStockinOrder=(id:number):Promise<StockinOrderDetail>=>apiFetch(`/api/v1/stockin/${id}`)
export const listStockinReceipts=(id:number):Promise<StockinReceipt[]>=>apiFetch(`/api/v1/stockin/${id}/receipts`)
export const createStockinOrder=(data:unknown):Promise<StockinOrderDetail>=>mutate('/api/v1/stockin',data)
export const receiveStockin=(id:number,data:unknown):Promise<ReceiveResult>=>mutate(`/api/v1/stockin/${id}/receipts`,data)
export const completeStockin=(id:number,data:unknown):Promise<StockinOrderDetail>=>mutate(`/api/v1/stockin/${id}/complete`,data)
export const cancelStockin=(id:number,data:unknown):Promise<StockinOrderDetail>=>mutate(`/api/v1/stockin/${id}/cancel`,data)
export const quickStockin=(data:unknown):Promise<ReceiveResult>=>mutate('/api/v1/stockin/quick',data)
