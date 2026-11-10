import { createHash } from 'node:crypto';
import { z } from 'zod';

const code = z.string().trim().min(1).max(160);
const optionalCode = z.preprocess(value => value === '' ? undefined : value, code.optional());
const quantity = z.number().min(0).max(1_000_000_000)
  .refine(value => /^\d+(?:\.\d{1,3})?$/.test(String(value)), '数量最多保留三位小数');
export const tonUnits = ['吨', 't', 'ton'];
export function quantityMillis(value: number): number { return Math.round(value * 1000); }
function validUnitQuantity(line: { unit: string }, values: number[]) {
  return tonUnits.includes(line.unit) || values.every(Number.isInteger);
}
export const inboundLine = z.object({
  lineId: code, goodCode: code, plannedQty: quantity.positive(), unit: code,
  batchCode: optionalCode, supplierCode: optionalCode,
}).strict().refine(line => validUnitQuantity(line, [line.plannedQty]), '按捆、按件等非吨库存单位只允许整数');
export const workOrderDispatchDetails = z.object({
  plannedWorkAt: z.iso.datetime({ offset: true }),
  operationLocation: code, channel: code,
  transferMode: z.enum(['DIRECT', 'SHUTTLE']), vehiclePlate: optionalCode, driverName: optionalCode,
  expectedArrivalAt: z.preprocess(value => value === '' ? undefined : value, z.iso.datetime({ offset: true }).optional()), sourceWarehouseCode: optionalCode,
  wagonNo: optionalCode, lines: z.array(inboundLine).min(1).max(100),
}).strict().superRefine((details, ctx) => {
  if (new Set(details.lines.map(line => line.lineId)).size !== details.lines.length)
    ctx.addIssue({ code: 'custom', message: '物料行号必须唯一' });
  if (details.transferMode === 'SHUTTLE' && (!details.vehiclePlate || !details.driverName))
    ctx.addIssue({ code: 'custom', message: '车辆短驳必须填写实际车牌和司机姓名' });
  if (details.transferMode === 'DIRECT' && (details.vehiclePlate || details.driverName))
    ctx.addIssue({ code: 'custom', message: '直装或直卸只填写作业安排，车辆短驳请切换转运方式' });
});
export type WorkOrderDispatchDetails = z.infer<typeof workOrderDispatchDetails>;
export function validateDispatchDirection(orderType: string, details: WorkOrderDispatchDetails) {
  if (orderType === 'UNLOADING' && !details.expectedArrivalAt) return '卸车入库须填写预计到达时间';
  if (orderType === 'LOADING' && !details.sourceWarehouseCode) return '装车出库须填写来源仓编码';
  return null;
}
export const dispatchInput = z.object({
  expectedVersion: code, companyCode: code.optional(), ownerCode: code.optional(),
  expectedArrivalAt: z.iso.datetime({ offset: true }), wagonNo: code.optional(),
  lines: z.array(inboundLine).min(1).max(100),
}).strict().refine(value => new Set(value.lines.map(line => line.lineId)).size === value.lines.length,
  '物料行号必须唯一');
const receiptLine = z.object({
  lineId: code, goodCode: code, unit: code,
  receivedDelta: quantity, receivedQty: quantity, remainingQty: quantity,
}).strict();
export const inboundEvent = z.object({
  schemaVersion: z.literal('1.0'), eventId: code, taskId: code, version: z.literal(1),
  sequence: z.number().int().positive().max(1_000_000),
  eventType: z.enum(['WAREHOUSE_CONFIRMED', 'PARTIALLY_RECEIVED', 'FULLY_RECEIVED', 'EXCEPTION']),
  occurredAt: z.iso.datetime({ offset: true }), warehouseCode: code.optional(), wmsOrderCode: code.optional(),
  receiptOperationCode: code.optional(), lines: z.array(receiptLine).min(1).max(100).optional(),
  reasonCode: code.optional(), reason: z.string().min(1).max(1000).optional(),
}).strict().superRefine((event, ctx) => {
  if (event.eventType === 'EXCEPTION') {
    if (!event.reason) ctx.addIssue({ code: 'custom', message: '差异回执必须说明原因' });
  } else if (!event.warehouseCode || !event.wmsOrderCode) {
    ctx.addIssue({ code: 'custom', message: '缺少仓库或 WMS 单号' });
  }
  if (['PARTIALLY_RECEIVED', 'FULLY_RECEIVED'].includes(event.eventType)
      && (!event.receiptOperationCode || !event.lines)) {
    ctx.addIssue({ code: 'custom', message: '收货回执缺少操作号或逐行数量' });
  }
  if (event.lines?.some(line => !validUnitQuantity(line, [line.receivedDelta, line.receivedQty, line.remainingQty])))
    ctx.addIssue({ code: 'custom', message: '非吨单位的收货数量只允许整数' });
});
export type InboundLine = z.infer<typeof inboundLine>;
export type InboundEvent = z.infer<typeof inboundEvent>;

function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object') {
    return '{' + Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
      .map(([key, item]) => JSON.stringify(key) + ':' + canonical(item)).join(',') + '}';
  }
  return JSON.stringify(value);
}
export function contentHash(value: unknown): string {
  return createHash('sha256').update(canonical(value)).digest('hex');
}
