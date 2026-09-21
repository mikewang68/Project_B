export const DICTIONARY_KEYS = ['areas', 'teams', 'assignees'] as const
export type DictionaryKey = (typeof DICTIONARY_KEYS)[number]

/** GET /api/v1/meta/dictionaries 中区域 / 班组字典项的原始结构（稳定 code + 规范中文名）。 */
export interface BackendCodeName {
  code: string
  name: string
}

/** GET /api/v1/meta/dictionaries 中责任人的原始结构（由后端 Demo User Master 生成）。 */
export interface BackendAssignee {
  id: string
  name: string
  teamCode: string
  teamName: string
  /** true 表示该用户的班组关系未经权威人员主数据核验（Demo 数据）。 */
  demoUnverified?: boolean
}

/** GET /api/v1/meta/dictionaries 的真实响应结构。 */
export interface BackendDictionaryResponse {
  areas?: BackendCodeName[]
  teams?: BackendCodeName[]
  assignees?: BackendAssignee[]
}

/**
 * 前端统一消费的下拉选项。
 * 责任人 value 使用后端用户 ID（USR-xxx），label 使用姓名；
 * 区域 / 班组当前 value 暂用规范中文名（现有筛选 REST 按中文名精确匹配），
 * 稳定 code 随行携带；待后端筛选接口支持 code 后切换为 value=code。
 */
export interface DictionaryOption {
  value: string
  label: string
  /** 后端稳定编码；责任人与 id 相同，区域/班组为独立 code。 */
  code: string
  /** 责任人所属班组名称，区域/班组字典无该字段。 */
  team?: string
  /** 责任人所属班组稳定 code。 */
  teamCode?: string
  /** 责任人班组关系是否为 Demo 待核验。 */
  demoUnverified?: boolean
  disabled?: boolean
}

export type DictionaryOptionMap = Record<DictionaryKey, DictionaryOption[]>
