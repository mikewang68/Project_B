/**
 * 统一身份与权限管理 —— 数据模型
 *
 * 区块链字段说明：blockchainId / blockchainAddress / blockchainTxHash
 * 为后续"操作上链溯源"预留，当前阶段仅存储、不触发链上交易。
 * 待框架稳定后，可在用户创建、角色授权、权限变更等关键操作时写入区块链。
 */

/** 用户状态 */
export type UserStatus = 'active' | 'disabled'

/** 角色状态 */
export type RoleStatus = 'active' | 'disabled'

/** 系统用户 */
export interface User {
  id: string
  /** 登录用户名（唯一） */
  username: string
  /** 姓名/显示名 */
  name: string
  /** 手机号 */
  phone?: string
  /** 邮箱 */
  email?: string
  /** 密码（mock 阶段明文存储；接入后端时应为 BCrypt 哈希，前端不持有） */
  password: string
  /** 关联角色 ID 列表（可多角色） */
  roleIds: string[]
  /** 所属组织级联编码路径（el-cascader 回显用），如 ['z-ne','c-dl-port','d-pps','g-pps-1'] */
  orgCodes?: string[]
  /** 所属区域 Zone（由 orgCodes 解析，列表选择，不手输） */
  zone?: string
  /** 所属公司 Company */
  company?: string
  /** 所属部门 Dept */
  dept?: string
  /** 所属组 Group（班组） */
  group?: string
  /** 组织完整路径 "区域 / 公司 / 部门 / 组"，便于展示与检索 */
  orgPath?: string
  status: UserStatus
  /** 预留：区块链身份 ID（DID / 链上用户编号），后续溯源用 */
  blockchainId?: string
  /** 预留：区块链钱包地址，用于操作签名与上链 */
  blockchainAddress?: string
  /** 预留：最近一次权限变更的链上交易哈希 */
  blockchainTxHash?: string
  createdAt: string
  updatedAt: string
  lastLoginAt?: string
}

/** 角色 */
export interface Role {
  id: string
  /** 角色名称 */
  name: string
  /** 角色编码（唯一，如 super_admin） */
  code: string
  description?: string
  /** 权限编码列表（对应 menu-tree.ts 中各 perms 的值） */
  permCodes: string[]
  status: RoleStatus
  /** 预留：角色变更上链交易哈希 */
  blockchainTxHash?: string
  createdAt: string
  updatedAt: string
}

/** 登录会话（存入 localStorage，模拟 token） */
export interface AuthSession {
  /** 模拟 JWT token（mock 阶段为随机串） */
  token: string
  userId: string
  username: string
  name: string
  /** 登录时间 */
  loginAt: string
  /** 过期时间（mock 阶段 24 小时） */
  expireAt: string
}

/** 登录请求 */
export interface LoginRequest {
  username: string
  password: string
}

/** 登录响应 */
export interface LoginResponse {
  success: boolean
  message?: string
  session?: AuthSession
  user?: User
  permCodes?: string[]
}

/** 操作日志（预留，当前记录到 localStorage） */
export interface OperationLog {
  id: string
  userId: string
  username: string
  module: string
  action: string
  target?: string
  detail?: string
  /** 预留：上链交易哈希 */
  blockchainTxHash?: string
  ip?: string
  createdAt: string
}
