/**
 * SYS 后端 REST API 封装。
 * - 登录/登出走 IAM 认证中心（/api/v1/iam/auth/*），token 由 IAM 签发
 * - 会话恢复与业务接口走 SYS（/api/v1/sys/*），SYS 本地验签并跨库读取 IAM 权限
 */
import { createClient } from './request'
import type { DictItem, DictType, SysConfigItem, SysLog } from '@/sys/types'

export const TOKEN_KEY = 'b-sys-token'
const client = createClient({ tokenKey: TOKEN_KEY })

export interface LoginResult {
  token: string
  user: { id: string; username: string; name: string }
  roles: Array<{ id: string; name: string; code: string }>
  permissions: string[]
}

export interface MeResult {
  user: { id: string; username: string; name: string }
  roles: Array<{ id: string; name: string; code: string }>
  permissions: string[]
  superAdmin: boolean
}

export interface PageResult<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}

export interface LogQuery {
  kind?: string
  module?: string
  result?: string
  keyword?: string
  begin?: string
  end?: string
}

export interface DictTypePayload {
  code?: string
  name: string
  status: DictType['status']
  remark?: string
}

export interface DictItemPayload {
  typeCode?: string
  label: string
  value: string
  sort: number
  status: DictItem['status']
  tagType?: DictItem['tagType'] | ''
  remark?: string
}

export const sysApi = {
  token: client,

  // ---------------- 认证（IAM 签发，SYS 恢复） ----------------
  login: (username: string, password: string) =>
    client.request<LoginResult>('/iam/auth/login', { method: 'POST', body: { username, password } }),
  logout: () => client.request<void>('/iam/auth/logout', { method: 'POST' }),
  me: () => client.request<MeResult>('/sys/auth/me'),

  // ---------------- 数据字典：分类 ----------------
  listDictTypes: () => client.request<DictType[]>('/sys/dict/types'),
  createDictType: (payload: DictTypePayload) =>
    client.request<DictType>('/sys/dict/types', { method: 'POST', body: payload }),
  updateDictType: (id: string, payload: DictTypePayload) =>
    client.request<DictType>(`/sys/dict/types/${id}`, { method: 'PUT', body: payload }),
  deleteDictType: (id: string) =>
    client.request<void>(`/sys/dict/types/${id}`, { method: 'DELETE' }),

  // ---------------- 数据字典：字典项 ----------------
  listDictItems: (typeCode?: string) =>
    client.request<DictItem[]>('/sys/dict/items', { query: { typeCode } }),
  createDictItem: (payload: DictItemPayload) =>
    client.request<DictItem>('/sys/dict/items', { method: 'POST', body: payload }),
  updateDictItem: (id: string, payload: DictItemPayload) =>
    client.request<DictItem>(`/sys/dict/items/${id}`, { method: 'PUT', body: payload }),
  deleteDictItem: (id: string) =>
    client.request<void>(`/sys/dict/items/${id}`, { method: 'DELETE' }),

  // ---------------- 日志 ----------------
  listLogs: (query: LogQuery = {}) =>
    client.request<PageResult<SysLog>>('/sys/logs', { query: { ...query, pageSize: 10000 } }),
  /** 导出当前筛选日志 CSV（后端生成，带 UTF-8 BOM），返回原始 Response 供前端落盘 */
  exportLogs: (query: LogQuery = {}) =>
    client.request<Response>('/sys/logs/export', { query: { ...query }, raw: true }),

  // ---------------- 系统配置 ----------------
  listConfigs: () => client.request<SysConfigItem[]>('/sys/configs'),
  saveConfigGroup: (group: string, entries: Array<{ key: string; value: string }>) =>
    client.request<{ updated: number }>(`/sys/configs/group/${encodeURIComponent(group)}`, {
      method: 'PUT',
      body: { entries },
    }),
}
