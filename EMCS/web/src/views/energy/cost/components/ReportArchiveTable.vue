<!-- 报表归档表｜PRD §5.9 · REQ-030/061/062 -->
<template>
  <section class="panel archive-panel">
    <header class="panel-head">
      <div class="head-title">
        <b>冻结归档</b>
        <small>预览与重下载只读 payloadSnapshot，不会重放当前查询条件</small>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </header>
    <el-table :data="items" v-loading="loading" class="dark-table">
      <el-table-column prop="archiveId" label="归档编号" width="90" />
      <el-table-column label="模板">
        <template #default="{row}">{{ reportTemplateLabels[row.templateCode] || row.templateCode }}</template>
      </el-table-column>
      <el-table-column label="覆盖周期">
        <template #default="{row}">{{ row.periodStart }} ～ {{ row.periodEnd }}</template>
      </el-table-column>
      <el-table-column prop="generatedAt" label="生成时钟" />
      <el-table-column label="口径签名">
        <template #default="{row}"><code>{{ row.fullSignature?.split(':').at(-1)?.slice(0,12) }}</code></template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{row}">
          <el-button link type="primary" @click="open(row.archiveId)">冻结预览</el-button>
          <el-button link @click="download(row.archiveId)">重下载</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-drawer
      v-model="visible"
      size="min(920px,95vw)"
      title="报表归档证据"
      destroy-on-close
      custom-class="cockpit-modal archive-preview-modal"
    >
      <ReportPreview :snapshot="detail" frozen />
    </el-drawer>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import downloadPlugin from '@/plugins/download'
import { getReportArchive, getReportArchives } from '@/api/reports'
import ReportPreview from './ReportPreview.vue'
import { reportTemplateLabels } from '../../shared/act5'

const items = ref([])
const loading = ref(false)
const visible = ref(false)
const detail = ref(null)

async function load() {
  loading.value = true
  try { items.value = (await getReportArchives()).data?.items || [] }
  finally { loading.value = false }
}
async function open(archiveId) {
  detail.value = (await getReportArchive(archiveId)).data
  visible.value = true
}
async function download(archiveId) {
  await downloadPlugin.getBlob(`/reports/archives/${archiveId}/export`, `archive-${archiveId}.xlsx`)
}
onMounted(load)
defineExpose({ refresh: load })
</script>

<style scoped>
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px;color:var(--ink)}
.panel-head{display:flex;align-items:center;gap:12px;border-bottom:1px dashed var(--line);padding-bottom:8px;margin-bottom:10px}
.head-title{margin-right:auto;display:flex;flex-direction:column}
.head-title b{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink)}
.head-title small{color:var(--ink-3);font:10px var(--mono);margin-top:4px}
code{color:var(--cyan);font-family:var(--mono);font-size:11px}
</style>
