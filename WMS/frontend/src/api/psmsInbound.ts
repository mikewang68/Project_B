import { apiFetch } from './http'

export interface PsmsInboundLine {
  lineId: string; goodCode: string; unit: string; plannedQty: number; receivedQty: number; remainingQty: number
  batchCode?: string; supplierCode?: string
}
export interface PsmsInboundTask {
  taskId: string; version: number; planId: string; workOrderId: string; companyCode: string; ownerCode: string
  expectedArrivalAt: string; status: string; warehouseCode?: string; wmsOrderCode?: string; wmsOrderId?: number
  lastSequence: number; lines: PsmsInboundLine[]; deliveryState: string; deliveryError?: string
}
const root = '/api/v1/stockin/psms-tasks'
export const listPsmsInboundTasks = () => apiFetch<PsmsInboundTask[]>(root)
async function post(taskId: string, action: string, data: unknown) {
  const csrf = await apiFetch<{ headerName: string; token: string }>('/api/v1/auth/csrf')
  return apiFetch<PsmsInboundTask>(`${root}/${encodeURIComponent(taskId)}/${action}`, {
    method: 'POST', data, headers: { [csrf.headerName]: csrf.token },
  })
}
export const confirmPsmsInbound = (taskId: string, warehouseCode: string, ownerCode: string) =>
  post(taskId, 'confirm', { warehouseCode, ownerCode })
export const retryPsmsInboundDelivery = (taskId: string) => post(taskId, 'retry-delivery', {})
