/**
 * 统一身份与权限管理 —— 初始 Mock 数据与 localStorage 持久化
 *
 * 当前阶段：纯前端 mock，数据存入 localStorage，模拟后端接口。
 * 后续接入：替换为 REST 调用（/api/v1/iam/users、/roles 等），数据模型不变。
 * 区块链字段：blockchainId / blockchainAddress / blockchainTxHash 已预留，当前不触发链上交易。
 */

import type { User, Role, OperationLog } from './types'
import { collectAllPerms } from './menu-tree'
import { resolveOrgPath } from './org-tree'

const STORAGE_KEYS = {
  users: 'b-iam-users',
  roles: 'b-iam-roles',
  session: 'b-iam-session',
  logs: 'b-iam-logs',
} as const

const now = () => new Date().toISOString()
const uid = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`

// 收集全部权限编码
const ALL_PERMS = collectAllPerms().map((p) => p.code)

// 按系统前缀筛选权限
function permsBySystem(prefix: string): string[] {
  return ALL_PERMS.filter((c) => c.startsWith(`${prefix}:`))
}

// 按系统前缀 + 操作类型筛选
function permsBySystemAndOp(prefix: string, ops: string[]): string[] {
  return ALL_PERMS.filter((c) => {
    if (!c.startsWith(`${prefix}:`)) return false
    return ops.some((op) => c.endsWith(`:${op}`))
  })
}

// ============================================================================
// 初始角色
// ============================================================================
function buildInitialRoles(): Role[] {
  const t = now()
  return [
    {
      id: 'role-super-admin',
      name: '系统管理员',
      code: 'super_admin',
      description: '拥有全部六系统与统一身份管理的所有权限，负责用户授权与系统配置。',
      permCodes: [...ALL_PERMS],
      status: 'active',
      createdAt: t,
      updatedAt: t,
    },
    {
      id: 'role-dispatcher',
      name: '调度管理员',
      code: 'dispatcher',
      description: '负责生产调度全流程、作业任务派发、异常处置，可查看数字孪生与设备健康；不进入用户权限后台。',
      permCodes: [
        ...permsBySystem('pps'),
        ...permsBySystemAndOp('dt', ['view', 'execute']),
        ...permsBySystemAndOp('ehealth', ['view', 'execute']),
        ...permsBySystemAndOp('safety', ['view', 'execute']),
        // 业务角色不授予 iam 权限，统一身份后台仅系统管理员可见
      ],
      status: 'active',
      createdAt: t,
      updatedAt: t,
    },
    {
      id: 'role-operator',
      name: '运维工程师',
      code: 'operator',
      description: '负责设备健康管理、告警处置、三维模型校准、仓储运维；不进入用户权限后台。',
      permCodes: [
        ...permsBySystem('ehealth'),
        ...permsBySystem('ems'),
        ...permsBySystemAndOp('dt', ['view', 'edit', 'execute']),
        ...permsBySystemAndOp('wms', ['view', 'execute']),
        ...permsBySystemAndOp('safety', ['view', 'edit', 'execute']),
      ],
      status: 'active',
      createdAt: t,
      updatedAt: t,
    },
    {
      id: 'role-field',
      name: '现场操作员',
      code: 'field_worker',
      description: '现场作业执行，可查看作业台、执行出入库与作业任务，只读大屏。',
      permCodes: [
        ...permsBySystemAndOp('dt', ['view', 'execute']),
        ...permsBySystemAndOp('pps', ['view', 'execute']),
        ...permsBySystemAndOp('wms', ['view', 'add', 'execute']),
        ...permsBySystemAndOp('safety', ['view']),
      ],
      status: 'active',
      createdAt: t,
      updatedAt: t,
    },
    {
      id: 'role-viewer',
      name: '大屏访客',
      code: 'viewer',
      description: '只读所有业务展示模块，无任何写/执行操作，不可访问用户权限管理后台，用于领导观摩与答辩演示。',
      permCodes: [
        ...permsBySystemAndOp('dt', ['view']),
        ...permsBySystemAndOp('pps', ['view']),
        ...permsBySystemAndOp('wms', ['view']),
        ...permsBySystemAndOp('ehealth', ['view']),
        ...permsBySystemAndOp('ems', ['view']),
        ...permsBySystemAndOp('safety', ['view']),
        // 注意：viewer 不包含 iam 系统权限，因此看不到用户/角色/菜单管理菜单
      ],
      status: 'active',
      createdAt: t,
      updatedAt: t,
    },
  ]
}

// ============================================================================
// 初始用户
// ============================================================================
function buildInitialUsers(): User[] {
  const t = now()
  return [
    {
      id: 'user-admin',
      username: 'admin',
      name: '系统管理员',
      phone: '13800000001',
      email: 'admin@bproject.local',
      password: 'Admin@123',
      roleIds: ['role-super-admin'],
      orgCodes: ['z-ne', 'c-dl-port', 'd-it', 'g-it-1'],
      ...resolveOrgPath(['z-ne', 'c-dl-port', 'd-it', 'g-it-1']),
      status: 'active',
      // 预留：区块链身份ID（当前为占位，后续对接链上 DID 后填充）
      blockchainId: 'did:bproject:admin:001',
      blockchainAddress: '0x0000000000000000000000000000000000000001',
      createdAt: t,
      updatedAt: t,
    },
    {
      id: 'user-dispatcher',
      username: 'dispatcher01',
      name: '张调度',
      phone: '13800000002',
      email: 'dispatcher01@bproject.local',
      password: 'Dispatch@123',
      roleIds: ['role-dispatcher'],
      orgCodes: ['z-ne', 'c-dl-port', 'd-pps', 'g-pps-1'],
      ...resolveOrgPath(['z-ne', 'c-dl-port', 'd-pps', 'g-pps-1']),
      status: 'active',
      blockchainId: 'did:bproject:dispatcher:001',
      createdAt: t,
      updatedAt: t,
    },
    {
      id: 'user-operator',
      username: 'operator01',
      name: '李运维',
      phone: '13800000003',
      email: 'operator01@bproject.local',
      password: 'Operate@123',
      roleIds: ['role-operator'],
      orgCodes: ['z-ne', 'c-dl-port', 'd-eh', 'g-eh-1'],
      ...resolveOrgPath(['z-ne', 'c-dl-port', 'd-eh', 'g-eh-1']),
      status: 'active',
      blockchainId: 'did:bproject:operator:001',
      createdAt: t,
      updatedAt: t,
    },
    {
      id: 'user-field',
      username: 'field01',
      name: '王现场',
      phone: '13800000004',
      password: 'Field@123',
      roleIds: ['role-field'],
      orgCodes: ['z-ne', 'c-dl-steel', 'ds-wms', 'gs-wms-2'],
      ...resolveOrgPath(['z-ne', 'c-dl-steel', 'ds-wms', 'gs-wms-2']),
      status: 'active',
      createdAt: t,
      updatedAt: t,
    },
    {
      id: 'user-viewer',
      username: 'viewer',
      name: '观摩访客',
      password: 'Viewer@123',
      roleIds: ['role-viewer'],
      orgCodes: ['z-sw', 'c-my', 'm-it', 'gm-it-1'],
      ...resolveOrgPath(['z-sw', 'c-my', 'm-it', 'gm-it-1']),
      status: 'active',
      createdAt: t,
      updatedAt: t,
    },
  ]
}

// ============================================================================
// localStorage 持久化工具
// ============================================================================
function load<T>(key: string, fallback: () => T): T {
  try {
    const raw = localStorage.getItem(key)
    if (raw) return JSON.parse(raw) as T
  } catch {
    // ignore
  }
  const data = fallback()
  try {
    localStorage.setItem(key, JSON.stringify(data))
  } catch {
    // ignore
  }
  return data
}

function save<T>(key: string, data: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(data))
  } catch {
    // ignore
  }
}

export function getUsers(): User[] {
  return load<User[]>(STORAGE_KEYS.users, buildInitialUsers)
}
export function saveUsers(users: User[]): void {
  save(STORAGE_KEYS.users, users)
}

export function getRoles(): Role[] {
  return load<Role[]>(STORAGE_KEYS.roles, buildInitialRoles)
}
export function saveRoles(roles: Role[]): void {
  save(STORAGE_KEYS.roles, roles)
}

export function getSession(): import('./types').AuthSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.session)
    if (raw) {
      const s = JSON.parse(raw) as import('./types').AuthSession
      // 检查是否过期
      if (new Date(s.expireAt).getTime() > Date.now()) return s
      localStorage.removeItem(STORAGE_KEYS.session)
    }
  } catch {
    // ignore
  }
  return null
}
export function saveSession(session: import('./types').AuthSession): void {
  save(STORAGE_KEYS.session, session)
}
export function clearSession(): void {
  localStorage.removeItem(STORAGE_KEYS.session)
}

export function getLogs(): OperationLog[] {
  return load<OperationLog[]>(STORAGE_KEYS.logs, () => [])
}
export function addLog(log: Omit<OperationLog, 'id' | 'createdAt'>): void {
  const logs = getLogs()
  logs.unshift({ ...log, id: uid(), createdAt: now() })
  // 最多保留 500 条
  save(STORAGE_KEYS.logs, logs.slice(0, 500))
}

/** 重置所有 mock 数据为初始状态（开发调试用） */
export function resetAllMockData(): void {
  localStorage.removeItem(STORAGE_KEYS.users)
  localStorage.removeItem(STORAGE_KEYS.roles)
  localStorage.removeItem(STORAGE_KEYS.session)
  localStorage.removeItem(STORAGE_KEYS.logs)
}

export { uid, now }
