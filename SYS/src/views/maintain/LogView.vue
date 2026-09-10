<script setup lang="ts">
import { ref, computed, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { useSysStore } from '@/stores/sys'
import { useAuthStore } from '@/stores/auth'
import { addLog } from '@/sys/mock-data'
import type { SysLog, LogKind } from '@/sys/types'

const sys = useSysStore()
const auth = useAuthStore()
const can = (code: string) => auth.isSuperAdmin || auth.permCodes.has(code)

onActivated(() => sys.reloadLogs())

const filters = ref({
  kind: '' as '' | LogKind,
  module: '',
  result: '',
  keyword: '',
  range: [] as string[],
})

const MODULE_LABEL: Record<string, string> = {
  auth: '登录认证', dict: '数据字典', config: '系统配置', log: '日志管理',
}
const ACTION_LABEL: Record<string, string> = {
  login: '登录', logout: '退出', add: '新增', edit: '编辑', delete: '删除', export: '导出',
}
const moduleOptions = Object.entries(MODULE_LABEL).map(([v, l]) => ({ v, l }))

const filteredLogs = computed(() => {
  return sys.logs.filter((l) => {
    if (filters.value.kind && l.kind !== filters.value.kind) return false
    if (filters.value.module && l.module !== filters.value.module) return false
    if (filters.value.result && l.result !== filters.value.result) return false
    if (filters.value.range && filters.value.range.length === 2) {
      const t = new Date(l.createdAt).getTime()
      if (t < new Date(filters.value.range[0]).getTime() || t > new Date(filters.value.range[1]).getTime() + 86400000) return false
    }
    if (filters.value.keyword) {
      const kw = filters.value.keyword.toLowerCase()
      const hay = `${l.username} ${l.target || ''} ${l.detail || ''}`.toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })
})

function resetFilter() {
  filters.value = { kind: '', module: '', result: '', keyword: '', range: [] }
}

function fmtTime(iso: string) {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 导出当前筛选结果为 CSV（带 BOM，Excel 中文不乱码） */
function exportCsv() {
  const rows = filteredLogs.value
  if (rows.length === 0) {
    ElMessage.warning('当前没有可导出的日志')
    return
  }
  const head = ['时间', '类别', '用户名', '模块', '动作', '对象', '详情', 'IP', '结果']
  const esc = (v: string) => `"${String(v ?? '').replace(/"/g, '""')}"`
  const lines = rows.map((l: SysLog) =>
    [
      fmtTime(l.createdAt),
      l.kind === 'login' ? '登录日志' : '操作日志',
      l.username,
      MODULE_LABEL[l.module] || l.module,
      ACTION_LABEL[l.action] || l.action,
      l.target || '',
      l.detail || '',
      l.ip || '',
      l.result === 'success' ? '成功' : '失败',
    ].map(esc).join(','),
  )
  const csv = '﻿' + [head.map(esc).join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `系统日志_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
  addLog({
    kind: 'operation',
    username: auth.currentUser?.username || 'anonymous',
    module: 'log', action: 'export', target: '操作日志',
    detail: `导出 ${rows.length} 条日志 CSV`, result: 'success',
  })
  sys.reloadLogs()
  ElMessage.success(`已导出 ${rows.length} 条日志`)
}
</script>

<template>
  <div class="log-page">
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" size="default">
        <el-form-item label="类别">
          <el-select v-model="filters.kind" placeholder="全部" clearable style="width:130px">
            <el-option label="登录日志" value="login" />
            <el-option label="操作日志" value="operation" />
          </el-select>
        </el-form-item>
        <el-form-item label="模块">
          <el-select v-model="filters.module" placeholder="全部" clearable style="width:130px">
            <el-option v-for="o in moduleOptions" :key="o.v" :label="o.l" :value="o.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="filters.result" placeholder="全部" clearable style="width:110px">
            <el-option label="成功" value="success" />
            <el-option label="失败" value="fail" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker
            v-model="filters.range"
            type="daterange"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DD"
            style="width:240px"
          />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" placeholder="用户/对象/详情" clearable style="width:170px" />
        </el-form-item>
        <el-form-item>
          <el-button :icon="'RefreshLeft'" @click="resetFilter">重置</el-button>
          <el-button
            v-if="can('sys:log:list:export')"
            type="primary"
            :icon="'Download'"
            @click="exportCsv"
          >导出 CSV</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <span class="panel-title">日志列表（{{ filteredLogs.length }} 条，最新在前）</span>
      </template>
      <el-table :data="filteredLogs" border stripe size="default" height="600">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="类别" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.kind === 'login' ? 'warning' : 'primary'">
              {{ row.kind === 'login' ? '登录' : '操作' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column label="模块" width="100">
          <template #default="{ row }">{{ MODULE_LABEL[row.module] || row.module }}</template>
        </el-table-column>
        <el-table-column label="动作" width="80">
          <template #default="{ row }">{{ ACTION_LABEL[row.action] || row.action }}</template>
        </el-table-column>
        <el-table-column prop="target" label="对象" min-width="150" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="结果" width="80" fixed="right">
          <template #default="{ row }">
            <el-tag size="small" :type="row.result === 'success' ? 'success' : 'danger'">
              {{ row.result === 'success' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的日志" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.log-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.filter-card :deep(.el-card__body) {
  padding-bottom: 2px;
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--iam-text-strong);
}
</style>
