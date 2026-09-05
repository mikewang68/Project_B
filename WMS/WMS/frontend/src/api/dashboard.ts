import { apiFetch } from './http'

export interface DashboardSummary {
  warehouseCount: number
  stockInPending: number
  stockOutPending: number
  inventoryAlertCount: number
  generatedAt: string
  dataSource: 'SKELETON' | 'DATABASE'
}

export const fetchDashboardSummary = () => apiFetch<DashboardSummary>('/api/v1/dashboard/summary')
