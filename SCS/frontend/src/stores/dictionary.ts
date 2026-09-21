import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getDictionaries } from '@/api/meta'
import { adaptDictionaries, emptyDictionaries } from '@/adapters/dictionary'
import type { DictionaryKey, DictionaryOption, DictionaryOptionMap } from '@/types/dictionary'

export const useDictionaryStore = defineStore('dictionary', () => {
  const dictionaries = ref<DictionaryOptionMap>(emptyDictionaries())
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const lastLoadedAt = ref<string | null>(null)
  let inflight: Promise<DictionaryOptionMap | null> | null = null

  const optionsByKey = computed(() => dictionaries.value)

  function setDictionaries(next: DictionaryOptionMap): void {
    dictionaries.value = next
    loaded.value = true
    error.value = null
    lastLoadedAt.value = new Date().toISOString()
  }

  async function ensureLoaded(force = false): Promise<DictionaryOptionMap | null> {
    if (!force && loaded.value) return dictionaries.value
    if (inflight) return inflight

    loading.value = true
    error.value = null
    inflight = getDictionaries()
      .then((response) => {
        const next = adaptDictionaries(response)
        setDictionaries(next)
        return next
      })
      .catch((err: unknown) => {
        error.value = err instanceof Error ? err.message : '字典加载失败'
        return null
      })
      .finally(() => {
        loading.value = false
        inflight = null
      })

    return inflight
  }

  function refresh(): Promise<DictionaryOptionMap | null> {
    return ensureLoaded(true)
  }

  function getOptions(key: DictionaryKey): DictionaryOption[] {
    return [...dictionaries.value[key]]
  }

  function getOption(key: DictionaryKey, value: string): DictionaryOption | undefined {
    return dictionaries.value[key].find(
      (option) => option.value === value || option.code === value || option.label === value,
    )
  }

  function getLabel(key: DictionaryKey, value: string): string {
    const option = getOption(key, value)
    if (!option) {
      // 未知 code/名称：不猜测、不静默替换为相似中文，暴露主数据缺口。
      console.warn(`[dictionary] 未知 ${key} 字典值：${value}`)
      return value
    }
    return option.label
  }

  function hasDictionary(key: DictionaryKey): boolean {
    return dictionaries.value[key].length > 0
  }

  return {
    dictionaries: optionsByKey,
    loaded,
    loading,
    error,
    lastLoadedAt,
    ensureLoaded,
    refresh,
    getOptions,
    getOption,
    getLabel,
    hasDictionary,
    setDictionaries,
  }
})
