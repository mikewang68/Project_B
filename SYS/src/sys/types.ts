/**
 * 系统设置与维护（SYS）—— 数据模型
 *
 * 说明：账号/角色模型与 IAM 同构，当前为前端 mock；后端统一认证中心就绪后，
 * SYS 与 IAM 及各业务系统共用同一套账号与权限，本文件只保留 SYS 业务所需字段。
 */

/** 启用状态 */
export type Status = 'active' | 'disabled'

/** 登录用户（精简，人员与角色的维护在 IAM 完成，SYS 只做登录鉴权） */
export interface SysUser {
  id: string
  username: string
  name: string
  password: string
  roleIds: string[]
  status: Status
  lastLoginAt?: string
}

/** 角色 */
export interface SysRole {
  id: string
  name: string
  code: string
  description?: string
  permCodes: string[]
  status: Status
}

/** 登录会话（存入 localStorage，模拟 token） */
export interface AuthSession {
  token: string
  userId: string
  username: string
  name: string
  loginAt: string
  expireAt: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  success: boolean
  message?: string
  session?: AuthSession
  user?: SysUser
  permCodes?: string[]
}

// ============================================================================
// 数据字典
// ============================================================================

/** 字典分类（字典类型） */
export interface DictType {
  id: string
  /** 类型编码，唯一，如 user_status */
  code: string
  /** 类型名称，如 用户状态 */
  name: string
  status: Status
  remark?: string
  createdAt: string
  updatedAt: string
}

/** 字典项（某分类下的可选项） */
export interface DictItem {
  id: string
  /** 所属字典分类编码 */
  typeCode: string
  /** 显示文本 */
  label: string
  /** 实际值 */
  value: string
  /** 排序号，升序 */
  sort: number
  status: Status
  /** 标签样式类型，用于前端 el-tag 展示：primary/success/warning/danger/info */
  tagType?: 'primary' | 'success' | 'warning' | 'danger' | 'info'
  remark?: string
  createdAt: string
  updatedAt: string
}

// ============================================================================
// 操作 / 登录日志
// ============================================================================

export type LogKind = 'login' | 'operation'
export type LogResult = 'success' | 'fail'

export interface SysLog {
  id: string
  /** 日志类别：登录日志 / 操作日志 */
  kind: LogKind
  username: string
  /** 所属模块：dict / config / log / auth 等 */
  module: string
  /** 动作：login/logout/add/edit/delete/export/config 等 */
  action: string
  /** 操作对象 */
  target?: string
  detail?: string
  ip?: string
  result: LogResult
  createdAt: string
}

// ============================================================================
// 系统配置
// ============================================================================

export type ConfigType = 'string' | 'number' | 'boolean' | 'select'

/** 系统配置项（分组键值参数） */
export interface SysConfigItem {
  id: string
  /** 配置分组，如 基础设置 / 安全策略 / 会话设置 */
  group: string
  label: string
  /** 参数键，唯一 */
  key: string
  /** 参数值（统一以字符串存储，number/boolean 在界面层转换） */
  value: string
  type: ConfigType
  /** type=select 时的可选项 */
  options?: string[]
  unit?: string
  remark?: string
  updatedAt: string
}
