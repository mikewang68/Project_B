import { z } from 'zod';
import { liveApi } from './client';

export const inboundLineSchema = z.object({
  lineId:z.string(),goodCode:z.string(),plannedQty:z.number().int().positive(),unit:z.string(),
  batchCode:z.string().optional(),supplierCode:z.string().optional(),
  receivedQty:z.number().nonnegative(),remainingQty:z.number().nonnegative(),
});
export const inboundSnapshotSchema = z.object({
  taskId:z.string(),eventId:z.string(),version:z.literal(1),workOrderId:z.string(),planId:z.string(),
  companyCode:z.string(),ownerCode:z.string(),expectedArrivalAt:z.string(),
  status:z.enum(['QUEUED','WAITING_WAREHOUSE','WAREHOUSE_CONFIRMED','PARTIALLY_RECEIVED','FULLY_RECEIVED','EXCEPTION']),
  warehouseCode:z.string().nullable(),wmsOrderCode:z.string().nullable(),lastSequence:z.number().int().nonnegative(),
  transferStatus:z.enum(['NOT_STARTED','IN_PROGRESS','COMPLETED']),verified:z.boolean(),recordVersion:z.string(),
  lines:z.array(inboundLineSchema),deliveryStatus:z.enum(['PENDING','DELIVERED','FAILED']),
  lastError:z.string().nullable(),updatedAt:z.string(),
});
export type WmsInboundSnapshot = z.infer<typeof inboundSnapshotSchema>;
export type WmsInboundConfig = { enabled:boolean; companyCode:string; ownerCode:string };
export type DispatchInbound = {
  recordVersion:string;reason:string;companyCode:string;ownerCode:string;expectedArrivalAt:string;
  lines:Array<{lineId:string;goodCode:string;plannedQty:number;unit:string;batchCode?:string;supplierCode?:string}>;
};
export type InboundAction = 'transfer-start'|'transfer-complete'|'verify';
const path=(id:string)=>'/business/work-orders/'+encodeURIComponent(id)+'/wms-inbound';
export async function loadWmsInbound(id:string,signal?:AbortSignal) {
  const result=await liveApi(path(id),{signal});
  return {config:z.object({enabled:z.boolean(),companyCode:z.string(),ownerCode:z.string()}).parse(result.config),
    data:inboundSnapshotSchema.nullable().parse(result.data)};
}
export async function dispatchWmsInbound(id:string,body:DispatchInbound) {
  return inboundSnapshotSchema.parse((await liveApi(path(id),{method:'POST',body:JSON.stringify(body)})).data);
}
export async function runInboundAction(id:string,action:InboundAction,reason:string,recordVersion:string) {
  return inboundSnapshotSchema.parse((await liveApi(path(id)+'/actions',{method:'POST',body:JSON.stringify({action,reason,recordVersion})})).data);
}

export function inboundActionAvailable(task:WmsInboundSnapshot, action:InboundAction, orderStatus:string) {
  if(orderStatus!=='IN_PROGRESS'||task.status==='EXCEPTION'||task.verified)return false;
  if(action==='transfer-start')return task.transferStatus==='NOT_STARTED'&&!!task.warehouseCode&&!!task.wmsOrderCode;
  if(action==='transfer-complete')return task.transferStatus==='IN_PROGRESS';
  return task.transferStatus==='COMPLETED'&&task.status==='FULLY_RECEIVED';
}
