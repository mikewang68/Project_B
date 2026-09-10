/**
 * 系统设置与维护（SYS）—— 初始 Mock 数据与 localStorage 持久化
 *
 * 当前为纯前端 mock：账号、字典、配置、日志均存 localStorage（前缀 b-sys-，
 * 与 IAM 的 b-iam- 区分，同一浏览器可并行运行）。后端就绪后替换为 /api/v1/sys/*
 * REST 调用，数据模型与 store 调用形态保持不变。
 */

import type { SysUser, SysRole, AuthSession, SysLog, DictType, DictItem, SysConfigItem } from './types'
import { collectAllPerms } from './sys-menu'

const KEYS = {
  users: 'b-sys-users',
  roles: 'b-sys-roles',
  session: 'b-sys-session',
  logs: 'b-sys-logs',
  dictTypes: 'b-sys-dict-types',
  dictItems: 'b-sys-dict-items',
  configs: 'b-sys-configs',
} as const

const now = () => new Date().toISOString()
const uid = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
/** 相对当前时间偏移分钟的 ISO 字符串，用于造初始日志 */
const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()

const ALL_PERMS = collectAllPerms().map((p) => p.code)

// ============================================================================
// 初始角色 / 用户（仅用于登录鉴权，人员维护在 IAM 统一完成）
// ============================================================================
function buildInitialRoles(): SysRole[] {
  return [
    {
      id: 'role-super-admin',
      name: '系统管理员',
      code: 'super_admin',
      description: '拥有系统设置与维护全部权限。',
      permCodes: [...ALL_PERMS],
      status: 'active',
    },
    {
      id: 'role-readonly',
      name: '运维只读',
      code: 'ops_readonly',
      description: '可查看字典/配置、查询与导出日志，不可修改。',
      permCodes: [
        'sys:dict:type:view',
        'sys:dict:item:view',
        'sys:log:list:view',
        'sys:log:list:export',
        'sys:config:list:view',
      ],
      status: 'active',
    },
  ]
}

function buildInitialUsers(): SysUser[] {
  return [
    { id: 'user-admin', username: 'admin', name: '系统管理员', password: 'Admin@123', roleIds: ['role-super-admin'], status: 'active' },
    { id: 'user-viewer', username: 'viewer', name: '运维观摩', password: 'Viewer@123', roleIds: ['role-readonly'], status: 'active' },
  ]
}

// ============================================================================
// 初始数据字典
// ============================================================================
function buildInitialDictTypes(): DictType[] {
  const t = now()
  const mk = (code: string, name: string, remark: string): DictType => ({
    id: `dt-${code}`, code, name, status: 'active', remark, createdAt: t, updatedAt: t,
  })
  return [
    mk('user_status', '用户状态', '账号启用/停用状态'),
    mk('gender', '性别', '人员性别'),
    mk('device_status', '设备状态', '现场设备运行状态'),
    mk('alert_level', '告警级别', '告警严重程度分级'),
    mk('yes_no', '是否', '通用布尔选项'),
  ]
}

function buildInitialDictItems(): DictItem[] {
  const t = now()
  type Row = [string, string, string, number, DictItem['tagType']]
  const rows: Row[] = [
    ['user_status', '启用', 'active', 1, 'success'],
    ['user_status', '停用', 'disabled', 2, 'info'],
    ['gender', '男', 'male', 1, 'primary'],
    ['gender', '女', 'female', 2, 'danger'],
    ['gender', '未知', 'unknown', 3, 'info'],
    ['device_status', '运行中', 'running', 1, 'success'],
    ['device_status', '待机', 'idle', 2, 'primary'],
    ['device_status', '维护中', 'maintenance', 3, 'warning'],
    ['device_status', '故障', 'fault', 4, 'danger'],
    ['alert_level', '低', 'low', 1, 'info'],
    ['alert_level', '中', 'medium', 2, 'primary'],
    ['alert_level', '高', 'high', 3, 'warning'],
    ['alert_level', '紧急', 'critical', 4, 'danger'],
    ['yes_no', '是', 'Y', 1, 'success'],
    ['yes_no', '否', 'N', 2, 'info'],
  ]
  return rows.map(([typeCode, label, value, sort, tagType]) => ({
    id: `di-${typeCode}-${value}`, typeCode, label, value, sort, tagType, status: 'active', createdAt: t, updatedAt: t,
  }))
}

// ============================================================================
// 初始系统配置
// ============================================================================
function buildInitialConfigs(): SysConfigItem[] {
  const t = now()
  const mk = (
    id: string, group: string, label: string, key: string, value: string,
    type: SysConfigItem['type'], extra: Partial<SysConfigItem> = {},
  ): SysConfigItem => ({ id, group, label, key, value, type, updatedAt: t, ...extra })
  return [
    mk('cfg-name', '基础设置', '系统名称', 'sys.name', 'B项目智慧管控平台', 'string', { remark: '登录页与浏览器标题展示' }),
    mk('cfg-version', '基础设置', '系统版本', 'sys.version', 'v1.0.0', 'string'),
    mk('cfg-footer', '基础设置', '页脚文案', 'sys.footer', 'B项目 数字孪生与智慧管控示范工程', 'string'),
    mk('cfg-pwdlen', '安全策略', '密码最小长度', 'security.pwdMinLen', '8', 'number', { unit: '位' }),
    mk('cfg-pwdcomplex', '安全策略', '密码复杂度', 'security.pwdComplex', '必须含大小写字母和数字', 'select', {
      options: ['不限制', '必须含字母和数字', '必须含大小写字母和数字'],
    }),
    mk('cfg-faillock', '安全策略', '登录失败锁定阈值', 'security.loginFailLock', '5', 'number', { unit: '次', remark: '连续失败达到该次数后临时锁定账号' }),
    mk('cfg-captcha', '安全策略', '登录验证码', 'security.captcha', 'false', 'boolean'),
    mk('cfg-timeout', '会话设置', '会话超时时长', 'session.timeout', '120', 'number', { unit: '分钟' }),
    mk('cfg-single', '会话设置', '单点登录', 'session.single', 'true', 'boolean', { remark: '同一账号仅允许一处在线' }),
    mk('cfg-remember', '会话设置', '允许记住登录', 'session.remember', 'true', 'boolean'),
  ]
}

// ============================================================================
// 初始日志
// ============================================================================
function buildInitialLogs(): SysLog[] {
  const rows: Array<Omit<SysLog, 'id'>> = [
    { kind: 'login', username: 'admin', module: 'auth', action: 'login', detail: '管理员登录成功', ip: '192.168.11.21', result: 'success', createdAt: ago(18) },
    { kind: 'operation', username: 'admin', module: 'dict', action: 'edit', target: 'device_status', detail: '修改字典分类「设备状态」备注', ip: '192.168.11.21', result: 'success', createdAt: ago(52) },
    { kind: 'operation', username: 'admin', module: 'dict', action: 'add', target: 'alert_level.critical', detail: '新增字典项「紧急」', ip: '192.168.11.21', result: 'success', createdAt: ago(95) },
    { kind: 'login', username: 'viewer', module: 'auth', action: 'login', detail: '运维观摩登录成功', ip: '192.168.11.33', result: 'success', createdAt: ago(140) },
    { kind: 'operation', username: 'admin', module: 'config', action: 'edit', target: 'session.timeout', detail: '会话超时由 60 调整为 120 分钟', ip: '192.168.11.21', result: 'success', createdAt: ago(200) },
    { kind: 'operation', username: 'admin', module: 'log', action: 'export', target: '操作日志', detail: '导出近 7 天操作日志 CSV', ip: '192.168.11.21', result: 'success', createdAt: ago(260) },
    { kind: 'login', username: 'unknown', module: 'auth', action: 'login', detail: '用户名不存在', ip: '10.12.3.9', result: 'fail', createdAt: ago(330) },
    { kind: 'operation', username: 'admin', module: 'dict', action: 'delete', target: 'gender.other', detail: '删除字典项「其他」', ip: '192.168.11.21', result: 'success', createdAt: ago(420) },
  ]
  return rows.map((r, i) => ({ ...r, id: `log-init-${i}` }))
}

// ============================================================================
// 通用持久化
// ============================================================================
function load<T>(key: string, fallback: () => T): T {
  try {
    const raw = localStorage.getItem(key)
    if (raw) return JSON.parse(raw) as T
  } catch {
    // ignore
  }
  const data = fallback()
  save(key, data)
  return data
}

function save<T>(key: string, data: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(data))
  } catch {
    // ignore
  }
}

// ---- 账号 / 角色 / 会话 ----
export const getUsers = (): SysUser[] => load(KEYS.users, buildInitialUsers)
export const saveUsers = (u: SysUser[]) => save(KEYS.users, u)
export const getRoles = (): SysRole[] => load(KEYS.roles, buildInitialRoles)

export function getSession(): AuthSession | null {
  try {
    const raw = localStorage.getItem(KEYS.session)
    if (raw) {
      const s = JSON.parse(raw) as AuthSession
      if (new Date(s.expireAt).getTime() > Date.now()) return s
      localStorage.removeItem(KEYS.session)
    }
  } catch {
    // ignore
  }
  return null
}
export const saveSession = (s: AuthSession) => save(KEYS.session, s)
export const clearSession = () => localStorage.removeItem(KEYS.session)

// ---- 日志 ----
export const getLogs = (): SysLog[] => load(KEYS.logs, buildInitialLogs)
export function addLog(log: Omit<SysLog, 'id' | 'createdAt'> & { createdAt?: string }): void {
  const logs = getLogs()
  logs.unshift({ ...log, id: uid(), createdAt: log.createdAt ?? now() })
  save(KEYS.logs, logs.slice(0, 1000))
}

// ---- 数据字典 ----
export const getDictTypes = (): DictType[] => load(KEYS.dictTypes, buildInitialDictTypes)
export const saveDictTypes = (d: DictType[]) => save(KEYS.dictTypes, d)
export const getDictItems = (): DictItem[] => load(KEYS.dictItems, buildInitialDictItems)
export const saveDictItems = (d: DictItem[]) => save(KEYS.dictItems, d)

// ---- 系统配置 ----
export const getConfigs = (): SysConfigItem[] => load(KEYS.configs, buildInitialConfigs)
export const saveConfigs = (c: SysConfigItem[]) => save(KEYS.configs, c)

/** 重置全部 mock 数据（开发调试） */
export function resetAllMockData(): void {
  Object.values(KEYS).forEach((k) => localStorage.removeItem(k))
}

export { uid, now }
