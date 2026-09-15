<!-- 报表预览｜PRD §5.9 · REQ-030/059/061/062 -->
<template>
  <div v-if="normalized" class="report-preview">
    <header class="preview-head">
      <div class="head-left">
        <el-tag v-if="frozen" class="frozen-tag">冻结归档</el-tag>
        <b>{{ reportTemplateLabels[meta.templateCode] || meta.templateCode }} · 模板版本 {{ meta.templateVersion }}</b>
        <small>{{ periodLabel }} · 生成时钟 {{ meta.generatedAt }}</small>
      </div>
      <div class="signature">
        <span>口径签名短码</span>
        <b>{{ shortSignature }}</b>
      </div>
    </header>
    <el-alert
      v-if="meta.period?.state === 'inProgress'"
      type="warning"
      :closable="false"
      :title="`进行中周期 · 截至 ${meta.period.asOf}`"
    />
    <section class="version-strip">
      <b>实际参与版本快照</b>
      <small>由服务端 versionSnapshots 提供，页面不补写</small>
      <code>{{ display(meta.versionSnapshots || {}) }}</code>
    </section>
    <section v-for="key in visibleSections" :key="key" class="report-section">
      <header>
        <b>{{ reportSectionLabels[key] || key }}</b>
      </header>
      <dl v-if="Object.keys(sectionMeta(key)).length" class="section-meta">
        <div v-for="(value, field) in sectionMeta(key)" :key="field">
          <dt>{{ reportSectionFieldLabel(field) }}</dt>
          <dd>{{ cell(value) }}</dd>
        </div>
      </dl>
      <el-table
        v-if="sectionItems(key).length"
        :data="sectionItems(key)"
        size="small"
        max-height="360"
        class="dark-table"
      >
        <el-table-column
          v-for="column in columns(sectionItems(key))"
          :key="column"
          :prop="column"
          :label="reportSectionFieldLabel(column)"
          min-width="130"
          show-overflow-tooltip
        >
          <template #default="{row}">{{ cell(row[column]) }}</template>
        </el-table-column>
      </el-table>
      <template v-if="sectionDiffRows(key).length">
        <h4 class="diff-heading">对象级重算差异 diffSummary</h4>
        <el-table :data="sectionDiffRows(key)" size="small" max-height="360" class="dark-table">
          <el-table-column
            v-for="column in columns(sectionDiffRows(key))"
            :key="column"
            :prop="column"
            :label="diffSummaryFieldLabel(column)"
            min-width="120"
            show-overflow-tooltip
          >
            <template #default="{row}">{{ cell(row[column]) }}</template>
          </el-table-column>
        </el-table>
      </template>
      <pre v-else-if="!sectionItems(key).length && !Object.keys(sectionMeta(key)).length">{{ display(sections[key]) }}</pre>
    </section>
  </div>
  <el-empty v-else description="请选择模板并生成预览" />
</template>

<script setup>
import { computed } from 'vue'
import { diffSummaryFieldLabel, reportDiffRows, reportSectionFieldLabel, reportSectionLabels, reportSectionMeta, reportTemplateLabels } from '../../shared/act5'

const props = defineProps({ snapshot: Object, frozen: Boolean })
const sectionOrder = ['usageSection', 'costSection', 'alertSection', 'suggestionSection', 'qualitySection']
const normalized = computed(() => props.frozen ? props.snapshot?.payloadSnapshot : props.snapshot)
const meta = computed(() => props.frozen ? { ...normalized.value, versionSnapshots: normalized.value?.versionSnapshots } : normalized.value?.reportMeta || {})
const sections = computed(() => normalized.value?.sections || {})
const visibleSections = computed(() => sectionOrder.filter((key) => Object.hasOwn(sections.value, key)))
const signature = computed(() => props.frozen ? props.snapshot?.fullSignature : props.snapshot?.signature)
const shortSignature = computed(() => signature.value?.split(':').at(-1)?.slice(0, 12) || '—')
const periodLabel = computed(() => meta.value?.period?.label || `${meta.value?.period?.start || ''} ～ ${meta.value?.period?.end || ''}`)
const sectionItems = (key) => Array.isArray(sections.value[key]?.items) ? sections.value[key].items : []
const sectionMeta = (key) => reportSectionMeta(sections.value[key])
const sectionDiffRows = (key) => reportDiffRows(sections.value[key])
const columns = (items) => [...new Set(items.flatMap((item) => Object.keys(item || {})))].filter((key) => !['diffSummary'].includes(key))
const cell = (value) => typeof value === 'object' ? display(value) : (value ?? '—')
const display = (value) => JSON.stringify(value, null, 2)
</script>

<style scoped>
.report-preview{display:grid;gap:12px}
.preview-head{display:flex;justify-content:space-between;align-items:center;padding:16px;background:var(--bg);border:1px solid var(--line);color:var(--ink)}
.head-left{display:flex;flex-direction:column;gap:4px}
.head-left b{font-family:var(--serif);font-size:15px;letter-spacing:.06em}
.head-left small{color:var(--ink-3);font:12px var(--mono)}
.frozen-tag{background:var(--lime-tint)!important;border-color:var(--lime)!important;color:var(--lime)!important;width:fit-content}
.signature{text-align:right;display:flex;flex-direction:column;gap:4px}
.signature span{color:var(--ink-3);font:12px var(--mono)}
.signature b{color:var(--cyan);font-family:var(--mono);font-size:14px;letter-spacing:.05em}
.version-strip,.report-section{padding:14px 16px;background:var(--panel);border:1px solid var(--line);color:var(--ink)}
.version-strip{display:flex;flex-direction:column;gap:4px}
.version-strip b{font-family:var(--serif);font-size:13px;letter-spacing:.06em}
.version-strip small{color:var(--ink-3);font:12px var(--mono)}
.version-strip code{display:block;margin-top:8px;white-space:pre-wrap;color:var(--ink-2);background:var(--panel-2);border:1px solid var(--line);padding:10px;font-family:var(--mono);font-size:11px}
.report-section>header{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;border-bottom:1px solid var(--line);padding-bottom:8px}
.report-section>header b{font-family:var(--serif);font-size:14px;letter-spacing:.06em;color:var(--ink)}
.section-meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;margin:0 0 12px}
.section-meta div{background:var(--panel-2);padding:10px;border:1px solid var(--line)}
.section-meta dt{color:var(--ink-3);font:12px var(--mono)}
.section-meta dd{margin:5px 0 0;color:var(--ink);white-space:pre-wrap;word-break:break-word;font-family:var(--mono);font-size:12px}
.diff-heading{margin:12px 0 6px;font-family:var(--serif);font-size:12px;color:var(--ink-2);letter-spacing:.05em}
.report-section pre{white-space:pre-wrap;max-height:360px;overflow:auto;background:var(--panel-2);color:var(--ink-2);border:1px solid var(--line);padding:10px;font-family:var(--mono);font-size:11px}
</style>
