/**
 * 系统设置与维护业务 Store：数据字典、系统配置、日志读取。
 * 所有写操作同步持久化到 localStorage，并写入一条操作日志。
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DictType, DictItem, SysConfigItem, SysLog } from '@/sys/types'
import {
  getDictTypes, saveDictTypes,
  getDictItems, saveDictItems,
  getConfigs, saveConfigs,
  getLogs, addLog, uid, now,
} from '@/sys/mock-data'
import { useAuthStore } from './auth'

export const useSysStore = defineStore('sys', () => {
  const auth = useAuthStore()
  const dictTypes = ref<DictType[]>(getDictTypes())
  const dictItems = ref<DictItem[]>(getDictItems())
  const configs = ref<SysConfigItem[]>(getConfigs())
  const logs = ref<SysLog[]>(getLogs())

  const me = () => auth.currentUser?.username || 'anonymous'

  function log(action: string, target: string, detail: string) {
    addLog({ kind: 'operation', username: me(), module: 'dict', action, target, detail, result: 'success' })
  }

  function reloadLogs() {
    logs.value = getLogs()
  }

  // ---------------- 字典分类 ----------------
  function persistTypes() {
    dictTypes.value = [...dictTypes.value]
    saveDictTypes(dictTypes.value)
  }
  function addType(data: Omit<DictType, 'id' | 'createdAt' | 'updatedAt'>) {
    const t = now()
    dictTypes.value.push({ ...data, id: uid(), createdAt: t, updatedAt: t })
    persistTypes()
    log('add', data.code, `新增字典分类「${data.name}」`)
  }
  function updateType(id: string, data: Partial<DictType>) {
    const idx = dictTypes.value.findIndex((x) => x.id === id)
    if (idx === -1) return
    dictTypes.value[idx] = { ...dictTypes.value[idx], ...data, updatedAt: now() }
    persistTypes()
    log('edit', dictTypes.value[idx].code, `编辑字典分类「${dictTypes.value[idx].name}」`)
  }
  /** 删除分类，同时删除其下全部字典项 */
  function removeType(id: string) {
    const target = dictTypes.value.find((x) => x.id === id)
    if (!target) return
    dictTypes.value = dictTypes.value.filter((x) => x.id !== id)
    dictItems.value = dictItems.value.filter((x) => x.typeCode !== target.code)
    persistTypes()
    saveDictItems(dictItems.value)
    log('delete', target.code, `删除字典分类「${target.name}」及其字典项`)
  }

  // ---------------- 字典项 ----------------
  function persistItems() {
    dictItems.value = [...dictItems.value]
    saveDictItems(dictItems.value)
  }
  function itemsOf(typeCode: string) {
    return dictItems.value
      .filter((x) => x.typeCode === typeCode)
      .sort((a, b) => a.sort - b.sort)
  }
  function addItem(data: Omit<DictItem, 'id' | 'createdAt' | 'updatedAt'>) {
    const t = now()
    dictItems.value.push({ ...data, id: uid(), createdAt: t, updatedAt: t })
    persistItems()
    log('add', `${data.typeCode}.${data.value}`, `新增字典项「${data.label}」`)
  }
  function updateItem(id: string, data: Partial<DictItem>) {
    const idx = dictItems.value.findIndex((x) => x.id === id)
    if (idx === -1) return
    dictItems.value[idx] = { ...dictItems.value[idx], ...data, updatedAt: now() }
    persistItems()
    log('edit', dictItems.value[idx].value, `编辑字典项「${dictItems.value[idx].label}」`)
  }
  function removeItem(id: string) {
    const target = dictItems.value.find((x) => x.id === id)
    if (!target) return
    dictItems.value = dictItems.value.filter((x) => x.id !== id)
    persistItems()
    log('delete', `${target.typeCode}.${target.value}`, `删除字典项「${target.label}」`)
  }

  // ---------------- 系统配置 ----------------
  /** 批量保存配置（一次编辑一组），返回更新条数 */
  function saveConfigGroup(entries: Array<{ key: string; value: string }>) {
    let n = 0
    for (const e of entries) {
      const idx = configs.value.findIndex((c) => c.key === e.key)
      if (idx !== -1 && configs.value[idx].value !== e.value) {
        configs.value[idx] = { ...configs.value[idx], value: e.value, updatedAt: now() }
        n += 1
      }
    }
    configs.value = [...configs.value]
    saveConfigs(configs.value)
    if (n > 0) {
      addLog({ kind: 'operation', username: me(), module: 'config', action: 'edit', target: '系统配置', detail: `保存系统配置，更新 ${n} 项参数`, result: 'success' })
    }
    return n
  }

  return {
    dictTypes, dictItems, configs, logs,
    reloadLogs,
    addType, updateType, removeType,
    addItem, updateItem, removeItem, itemsOf,
    saveConfigGroup,
  }
})
