<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchDashboardSummary, type DashboardSummary } from '@/api/dashboard'

const loading = ref(true)
const error = ref('')
const summary = ref<DashboardSummary>()

async function load() {
  loading.value = true
  error.value = ''
  try {
    summary.value = await fetchDashboardSummary()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '工作台加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <section class="page-heading">
      <div><p class="eyebrow">WAREHOUSE OVERVIEW</p><h1>仓储工作台</h1><p>按当前公司、仓库和货主汇总仓储业务。</p></div>
      <el-button type="primary" :loading="loading" @click="load">刷新数据</el-button>
    </section>
    <el-skeleton v-if="loading" :rows="5" animated />
    <el-result v-else-if="error" icon="error" title="数据加载失败" :sub-title="error">
      <template #extra><el-button type="primary" @click="load">重试</el-button></template>
    </el-result>
    <template v-else-if="summary">
      <section class="metric-grid">
        <article><span>仓库数量</span><strong>{{ summary.warehouseCount }}</strong><small>已启用的仓库基础资料</small></article>
        <article><span>待处理入库</span><strong>{{ summary.stockInPending }}</strong><small>草稿与收货中入库单</small></article>
        <article><span>待处理出库</span><strong>{{ summary.stockOutPending }}</strong><small>草稿与作业中出库单</small></article>
        <article class="warning"><span>库存预警</span><strong>{{ summary.inventoryAlertCount }}</strong><small>按货品上下限实时计算</small></article>
      </section>
      <section class="panel empty-panel">
        <div class="empty-symbol">仓储</div>
        <div><h2>仓储经营模块已连接</h2><p>当前数据源：{{ summary.dataSource }}。基础资料、库存、入出库、财务统计与数据交换已使用同一租户上下文。</p></div>
      </section>
    </template>
  </div>
</template>
