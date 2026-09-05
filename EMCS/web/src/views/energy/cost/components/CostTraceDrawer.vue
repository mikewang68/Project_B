<!-- 成本三步反查抽屉｜PRD §5.9 · REQ-054/055 -->
<template>
  <el-drawer
    :model-value="modelValue"
    size="min(860px, 94vw)"
    title="成本三步反查"
    destroy-on-close
    custom-class="cockpit-modal cost-trace-modal"
    @close="$emit('update:modelValue', false)"
  >
    <div v-loading="loading" class="trace-body">
      <template v-if="trace">
        <div class="trace-head">
          <div><span>成本版本</span><b class="mono">{{ trace.costRecord?.costVersion || '—' }}</b></div>
          <div><span>状态</span><b>{{ costStatusLabels[trace.costRecord?.status] || trace.costRecord?.status || '—' }}</b></div>
          <div><span>口径签名</span><b class="mono">{{ trace.costRecord?.signatureShort || trace.costRecord?.signature || '—' }}</b></div>
        </div>
        <div v-if="versions.length" class="version-picker">
          <span>切换历史版本</span>
          <el-select :model-value="trace.costRecord?.costVersion" @change="$emit('version-change', $event)">
            <el-option v-for="version in versions" :key="version" :label="`历史证据 ${version}`" :value="version" />
          </el-select>
        </div>
        <el-steps direction="vertical" :active="3" finish-status="success" class="trace-steps">
          <el-step title="用量证据">
            <template #description>
              <p>{{ trace.usageEvidence?.sourcePeriod?.start }} → {{ trace.usageEvidence?.sourcePeriod?.end }}</p>
              <p>总用量 {{ number(trace.usageEvidence?.usageQty) }} · 来源点位 {{ trace.usageEvidence?.sourcePointIds?.length || 0 }} 个</p>
              <div class="snapshot-label">原始统计快照</div>
              <pre>{{ text(trace.usageEvidence?.sourceStatSnapshot) }}</pre>
            </template>
          </el-step>
          <el-step title="单价版本">
            <template #description>
              <div v-for="formula in trace.tariffEvidence?.formulas || []" :key="formula.touPeriod || formula.period" class="evidence-row">
                {{ periodLabel(formula.touPeriod || formula.period) }} · {{ formula.expression || `${formula.quantity} × ${formula.price} = ${formula.cost}` }}
              </div>
              <div class="snapshot-label">单价冻结快照</div>
              <pre>{{ text(trace.tariffEvidence?.tariffSnapshot) }}</pre>
            </template>
          </el-step>
          <el-step title="分摊规则">
            <template #description>
              <el-alert
                v-if="trace.allocationEvidence?.allocationStatus === 'notApplied'"
                type="info"
                :closable="false"
                :title="trace.allocationEvidence?.message || '本对象未参与共享分摊'"
              />
              <div class="snapshot-label">分摊规则冻结快照</div>
              <pre>{{ text(trace.allocationEvidence?.allocRuleSnapshot) }}</pre>
              <div v-for="item in trace.allocationEvidence?.allocationDetails || []" :key="item.objectId" class="evidence-row">
                对象 {{ item.objectId }} · 比例 {{ item.ratio }} · 分摊成本 ¥{{ number(item.allocatedCost) }}
              </div>
            </template>
          </el-step>
        </el-steps>
        <section v-if="trace.recomputeChain?.length" class="trace-section">
          <b>重算版本链</b>
          <div v-for="item in trace.recomputeChain" :key="item.recomputeId" class="chain-row">
            旧版本 {{ item.oldCostVersion }} → 新版本 {{ item.newCostVersion }} · 复核 {{ costStatusLabels[item.reviewStatus] || item.reviewStatus }} · {{ item.triggerReason }}
          </div>
        </section>
        <section v-if="trace.relatedAlert" class="trace-section">
          <b>关联告警</b>
          <div class="chain-row">{{ trace.relatedAlert.ruleCode }} · {{ trace.relatedAlert.level }} · {{ trace.relatedAlert.note }}</div>
        </section>
        <div class="trace-actions">
          <el-button v-if="trace.relatedSuggestionId" type="primary" @click="$emit('suggestion', trace.relatedSuggestionId)">查看关联建议</el-button>
          <el-button
            v-else-if="canCreateSuggestion && trace.suggestionContext"
            type="primary"
            @click="$emit('create-suggestion', trace.suggestionContext)"
          >
            转为节能建议
          </el-button>
        </div>
      </template>
      <el-empty v-else-if="!loading" description="请选择成本对象以查看冻结证据" />
    </div>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'
import { costStatusLabels } from '../../shared/act5'
const props = defineProps({ modelValue: Boolean, trace: Object, loading: Boolean, canCreateSuggestion: Boolean })
defineEmits(['update:modelValue', 'version-change', 'create-suggestion', 'suggestion'])
const versions = computed(() => {
  const chain = props.trace?.recomputeChain || []
  return [...new Set([props.trace?.costRecord?.costVersion, ...chain.flatMap((item) => [item.oldCostVersion, item.newCostVersion])].filter(Boolean))]
})
const number = (value) => Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const text = (value) => value && Object.keys(value).length ? JSON.stringify(value, null, 2) : '无适用快照'
const periodLabel = (value) => ({ peak: '峰段', flat: '平段', valley: '谷段', flatOnly: '单一计价' }[value] || value)
</script>

<style>
/* 抽屉/对话框脱 scoped 必需；色板由 cockpit-modal 全局皮提供，此处只补步骤条与内容层特化 */
.cost-trace-modal .trace-body{min-height:300px}
.cost-trace-modal .trace-head{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:14px}
.cost-trace-modal .trace-head div{padding:12px;background:var(--panel-2);border:1px solid var(--line)}
.cost-trace-modal .trace-head span{display:block;color:var(--ink-3);font:10px var(--mono)}
.cost-trace-modal .trace-head b{display:block;color:var(--ink);margin-top:6px;font-family:var(--serif);font-size:15px;letter-spacing:.05em}
.cost-trace-modal .mono{font-family:var(--mono)!important;word-break:break-all}
.cost-trace-modal .version-picker{display:flex;align-items:center;gap:12px;margin-bottom:16px;color:var(--ink-2);font:11px var(--mono)}
.cost-trace-modal .version-picker .el-select{width:220px}
.cost-trace-modal .trace-steps{margin-top:16px}
.cost-trace-modal .trace-steps .el-step__title,
.cost-trace-modal .trace-steps .el-step__title.is-process,
.cost-trace-modal .trace-steps .el-step__title.is-success{color:var(--ink);font-family:var(--serif);letter-spacing:.05em}
.cost-trace-modal .trace-steps .el-step__description,
.cost-trace-modal .trace-steps .el-step__description.is-process,
.cost-trace-modal .trace-steps .el-step__description.is-success{color:var(--ink-2)}
.cost-trace-modal .trace-steps .el-step__head.is-success,
.cost-trace-modal .trace-steps .el-step__head.is-process{color:var(--cyan);border-color:var(--cyan)}
.cost-trace-modal .trace-steps .el-step__line{background:var(--line)}
.cost-trace-modal .snapshot-label{color:var(--ink-3);font:9px var(--mono);letter-spacing:.08em;margin-top:8px}
.cost-trace-modal .trace-steps pre{white-space:pre-wrap;background:var(--panel-2);color:var(--ink-2);border:1px solid var(--line);padding:10px;margin-top:4px;max-height:200px;overflow:auto;font-family:var(--mono);font-size:11px}
.cost-trace-modal .evidence-row{padding:8px 0;color:var(--ink-2);font-family:var(--mono);font-size:11px;border-bottom:1px dashed var(--line)}
.cost-trace-modal .trace-section{margin-top:14px;padding-top:12px;border-top:1px solid var(--line)}
.cost-trace-modal .trace-section>b{display:block;color:var(--ink);font-family:var(--serif);letter-spacing:.06em;margin-bottom:8px}
.cost-trace-modal .chain-row{color:var(--ink-2);font:11px var(--mono);padding:6px 0}
.cost-trace-modal .trace-actions{display:flex;justify-content:flex-end;margin-top:18px;gap:8px}
</style>
