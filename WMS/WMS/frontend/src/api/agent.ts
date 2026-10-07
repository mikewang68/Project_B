import { apiFetch } from './http'

export type AgentRow = Record<string, unknown>
export interface AgentStatus { modelConfigured: boolean; model: string; mailConfigured: boolean; timezone: string; version: string }
export interface AgentEvent { tool: string; label: string; arguments: AgentRow; result: unknown }
export interface ChatResult { id: number; answer: string; state: string; events: AgentEvent[]; model: string }
export interface Schedule {
  id?: number; name: string; enabled: boolean; frequency: string; intervalMinutes: number; dailyTime: string;
  emails: string; lowStock: boolean; replenishment: boolean; frozenStock: boolean; capacity: boolean;
  capacityPercent: number; cooldownHours: number; nextRunAt?: string; lastRunAt?: string; lastError?: string;
}
export interface Utilization {
  total_locations: number; occupied_locations: number; occupancy_percent: number | null; known_weight_kg: number;
  missing_weight_rows: number; full_warehouse: boolean; note: string; locations: AgentRow[]; history: AgentRow[];
}
async function mutate<T>(path: string, data: unknown, method = 'POST'): Promise<T> {
  const csrf = await apiFetch<{ headerName: string; token: string }>('/api/v1/auth/csrf')
  return apiFetch(path, { method, data, headers: { [csrf.headerName]: csrf.token }, timeout: 180_000 })
}
const root = '/api/v1/agent'
export const agentStatus = (): Promise<AgentStatus> => apiFetch(root + '/status')
export const agentChat = (question: string, previousQuestions: string[]): Promise<ChatResult> => mutate(root + '/chat', { question, previousQuestions })
export const agentHistory = (): Promise<AgentRow[]> => apiFetch(root + '/history')
export const agentUtilization = (): Promise<Utilization> => apiFetch(root + '/utilization')
export const agentAlerts = (): Promise<AgentRow[]> => apiFetch(root + '/alerts')
export const readAgentAlert = (id: number): Promise<void> => mutate(root + `/alerts/${id}/read`, {})
export const agentSchedules = (): Promise<Schedule[]> => apiFetch(root + '/schedules')
export const saveAgentSchedule = (data: Schedule): Promise<void> => mutate(root + '/schedules' + (data.id ? '/' + data.id : ''), data, data.id ? 'PUT' : 'POST')
export const runAgentSchedule = (id: number): Promise<{ findings: number; newNotifications: number; emailState: string }> => mutate(root + `/schedules/${id}/run`, {})
export const agentMails = (): Promise<AgentRow[]> => apiFetch(root + '/mails')
export const retryAgentMail = (id: number): Promise<void> => mutate(root + `/mails/${id}/retry`, {})
export const agentWeights = (): Promise<AgentRow[]> => apiFetch(root + '/weights')
export const saveAgentWeight = (id: number, weightKg: number): Promise<void> => mutate(root + `/weights/${id}`, { weightKg }, 'PUT')
