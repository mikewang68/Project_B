import type {
  BackendCodeName,
  BackendDictionaryResponse,
  DictionaryKey,
  DictionaryOption,
  DictionaryOptionMap,
} from '@/types/dictionary'

/**
 * 区域 / 班组：后端返回 {code,name}。
 * 过渡阶段 option.value 仍使用规范中文名（现有筛选 REST 按中文名精确匹配），
 * 稳定 code 放入 option.code；责任人 value 直接使用用户 ID。
 */
function codeNameOptions(values: readonly BackendCodeName[] | undefined): DictionaryOption[] {
  return (values ?? []).map((item) => ({ value: item.name, label: item.name, code: item.code }))
}

/** 将后端字典 DTO 转为前端统一选项；不改变后端 key、编码和顺序。 */
export function adaptDictionaries(response: BackendDictionaryResponse): DictionaryOptionMap {
  return {
    areas: codeNameOptions(response.areas),
    teams: codeNameOptions(response.teams),
    assignees: (response.assignees ?? []).map((person) => ({
      value: person.id,
      label: person.name,
      code: person.id,
      team: person.teamName,
      teamCode: person.teamCode,
      demoUnverified: person.demoUnverified ?? false,
    })),
  }
}

export function emptyDictionaries(): DictionaryOptionMap {
  return {
    areas: [],
    teams: [],
    assignees: [],
  }
}

export function isDictionaryKey(value: string): value is DictionaryKey {
  return value === 'areas' || value === 'teams' || value === 'assignees'
}
