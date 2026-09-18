/**
 * 系统设置与维护业务 Store：数据字典、系统配置、日志读取。
 * 已接入 SYS 后端 REST API（/api/v1/sys/*）；操作日志由后端统一落库。
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DictType, DictItem, SysConfigItem, SysLog } from '@/sys/types'
import { sysApi } from '@/api/sys'

export const useSysStore = defineStore('sys', () => {
  const dictTypes = ref<DictType[]>([])
  const dictItems = ref<DictItem[]>([])
  const configs = ref<SysConfigItem[]>([])
  const logs = ref<SysLog[]>([])
  const loading = ref(false)

  /** 首次进入页面时加载字典与配置（日志在日志页激活时单独加载） */
  async function bootstrap() {
    if (dictTypes.value.length === 0) await reloadDict()
    if (configs.value.length === 0) await reloadConfigs()
  }

  async function reloadDict() {
    loading.value = true
    try {
      const [types, items] = await Promise.all([
        sysApi.listDictTypes(),
        sysApi.listDictItems(),
      ])
      dictTypes.value = types
      dictItems.value = items
    } finally {
      loading.value = false
    }
  }

  async function reloadConfigs() {
    configs.value = await sysApi.listConfigs()
  }

  async function reloadLogs() {
    const page = await sysApi.listLogs()
    logs.value = page.list
  }

  // ---------------- 字典分类 ----------------
  async function addType(data: Omit<DictType, 'id' | 'createdAt' | 'updatedAt'>) {
    const created = await sysApi.createDictType(data)
    dictTypes.value.push(created)
    return created
  }

  async function updateType(id: string, data: Partial<DictType>) {
    const existing = dictTypes.value.find((x) => x.id === id)
    const updated = await sysApi.updateDictType(id, {
      name: data.name ?? existing?.name ?? '',
      status: data.status ?? existing?.status ?? 'active',
      remark: data.remark ?? existing?.remark ?? '',
    })
    const idx = dictTypes.value.findIndex((x) => x.id === id)
    if (idx >= 0) dictTypes.value[idx] = updated
    return updated
  }

  /** 删除分类（后端事务内同时逻辑删除其下字典项） */
  async function removeType(id: string) {
    const target = dictTypes.value.find((x) => x.id === id)
    await sysApi.deleteDictType(id)
    dictTypes.value = dictTypes.value.filter((x) => x.id !== id)
    if (target) dictItems.value = dictItems.value.filter((x) => x.typeCode !== target.code)
  }

  // ---------------- 字典项 ----------------
  function itemsOf(typeCode: string) {
    return dictItems.value
      .filter((x) => x.typeCode === typeCode)
      .slice()
      .sort((a, b) => a.sort - b.sort)
  }

  async function addItem(data: Omit<DictItem, 'id' | 'createdAt' | 'updatedAt'>) {
    const created = await sysApi.createDictItem({
      typeCode: data.typeCode,
      label: data.label,
      value: data.value,
      sort: data.sort,
      status: data.status,
      tagType: data.tagType ?? '',
      remark: data.remark,
    })
    dictItems.value.push(created)
    return created
  }

  async function updateItem(id: string, data: Partial<DictItem>) {
    const existing = dictItems.value.find((x) => x.id === id)
    const updated = await sysApi.updateDictItem(id, {
      label: data.label ?? existing?.label ?? '',
      value: existing?.value ?? '',
      sort: data.sort ?? existing?.sort ?? 0,
      status: data.status ?? existing?.status ?? 'active',
      tagType: (data.tagType ?? existing?.tagType ?? '') as DictItem['tagType'] | '',
      remark: data.remark ?? existing?.remark ?? '',
    })
    const idx = dictItems.value.findIndex((x) => x.id === id)
    if (idx >= 0) dictItems.value[idx] = updated
    return updated
  }

  async function removeItem(id: string) {
    await sysApi.deleteDictItem(id)
    dictItems.value = dictItems.value.filter((x) => x.id !== id)
  }

  // ---------------- 系统配置 ----------------
  /** 批量保存配置（一次编辑一组），返回更新条数 */
  async function saveConfigGroup(entries: Array<{ key: string; value: string }>) {
    const group = configs.value.find((c) => c.key === entries[0]?.key)?.group
    if (!group) return 0
    const result = await sysApi.saveConfigGroup(group, entries)
    if (result.updated > 0) await reloadConfigs()
    return result.updated
  }

  return {
    dictTypes, dictItems, configs, logs, loading,
    bootstrap, reloadDict, reloadConfigs, reloadLogs,
    addType, updateType, removeType,
    addItem, updateItem, removeItem, itemsOf,
    saveConfigGroup,
  }
})
