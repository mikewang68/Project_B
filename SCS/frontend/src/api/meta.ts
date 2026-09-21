import { apiRequest } from './http'
import type { BackendDictionaryResponse, DictionaryKey } from '@/types/dictionary'

/** 获取基础字典；不传 keys 时后端返回 areas、teams、assignees 全量字典。 */
export function getDictionaries(keys?: readonly DictionaryKey[]): Promise<BackendDictionaryResponse> {
  const query = keys && keys.length > 0 ? `?keys=${encodeURIComponent(keys.join(','))}` : ''
  return apiRequest<BackendDictionaryResponse>(`/meta/dictionaries${query}`)
}
