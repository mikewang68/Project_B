<script setup lang="ts">
import { Close, WarningFilled } from '@element-plus/icons-vue'
import { ElDrawer } from 'element-plus'
import RiskBadge from '@/components/shared/RiskBadge.vue'
import AlertStatusBadge from './AlertStatusBadge.vue'
import AlertEvidence from './AlertEvidence.vue'
import AlertLinkageStatus from './AlertLinkageStatus.vue'
import AlertTimeline from './AlertTimeline.vue'
import AlertActionBar from './AlertActionBar.vue'
import type { AlertEvent } from '@/types/alert'

defineProps<{ modelValue: boolean; alert: AlertEvent | undefined; detailLoading?: boolean; actionPending?: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: []
  assign: []
  transfer: []
  start: []
  treat: []
  linkage: []
  review: []
  escalate: []
  'simulate-fail': []
  takeover: []
  'open-rule': [ruleId: string]
}>()

function duration(sec: number): string {
  if (!sec) return '瞬时'
  return sec >= 60 ? `${Math.floor(sec / 60)} 分 ${sec % 60} 秒` : `${sec} 秒`
}
</script>

<template>
  <ElDrawer :model-value="modelValue" size="680px" :with-header="false" class="alert-detail-drawer"
    @update:model-value="emit('update:modelValue', $event)">
    <template v-if="alert">
      <header class="alert-drawer-header">
        <div class="alert-drawer-header__main">
          <span>ALERT DETAIL</span>
          <h2>{{ alert.title }}</h2>
          <p>{{ alert.id }}<em v-if="alert.upgradedFrom"> · 由{{ alert.upgradedFrom }}升级</em></p>
        </div>
        <div class="alert-drawer-header__badges">
          <RiskBadge :risk="alert.risk" />
          <AlertStatusBadge :status="alert.status" />
          <button type="button" aria-label="关闭" @click="emit('update:modelValue', false)"><el-icon><Close /></el-icon></button>
        </div>
      </header>

      <div v-loading="detailLoading" class="alert-drawer-scroll">
        <dl class="alert-base-grid">
          <div><dt>发生时间</dt><dd>{{ alert.time }}</dd></div>
          <div><dt>区域</dt><dd>{{ alert.area }}</dd></div>
          <div><dt>对象</dt><dd>{{ alert.target }}</dd></div>
          <div><dt>来源</dt><dd>{{ alert.source }}</dd></div>
          <div><dt>责任人</dt><dd :class="{ unassigned: alert.assignee === '待分配' }">{{ alert.assignee }}</dd></div>
          <div><dt>持续时间</dt><dd>{{ duration(alert.durationSec) }}</dd></div>
          <div><dt>规则编号</dt><dd><button type="button" class="rule-link" @click="emit('open-rule', alert.ruleId)">{{ alert.ruleId }} ↗</button></dd></div>
          <div><dt>规则版本</dt><dd>{{ alert.ruleVersion }}</dd></div>
          <div v-if="alert.confirmUser" class="wide"><dt>确认人 / 时间</dt><dd>{{ alert.confirmUser }} · {{ alert.confirmTime }}</dd></div>
          <div v-if="alert.acceptTime" class="wide"><dt>接单时间</dt><dd>{{ alert.acceptTime }}</dd></div>
        </dl>

        <AlertEvidence :evidence="alert.evidence" />

        <template v-if="alert.linkageAvailable">
          <div v-if="!alert.linkageFinished && !alert.linkageFailed && alert.risk === '紧急'" class="linkage-demo-bar">
            <el-icon><WarningFilled /></el-icon>
            <span>联动演示：可走正常联动，或模拟 PLC 回执失败场景</span>
            <button type="button" @click="emit('simulate-fail')">模拟联动失败</button>
          </div>
          <AlertLinkageStatus :steps="alert.linkage" :failed="!!alert.linkageFailed"
            :finished="!!alert.linkageFinished" :takeover="!!alert.takeover" @takeover="emit('takeover')" />
        </template>

        <section v-if="alert.treatment" class="alert-treatment-record">
          <h4>处置结果</h4>
          <div class="alert-treatment-record__measures">
            <span v-for="m in alert.treatment.measures" :key="m">{{ m }}</span>
          </div>
          <div class="alert-treatment-record__row"><small>结果</small><b>{{ alert.treatment.result }}</b></div>
          <div class="alert-treatment-record__row"><small>附件</small><b>{{ alert.treatment.attachment }}</b></div>
          <div class="alert-treatment-record__row"><small>处置人 / 时间</small><b>{{ alert.treatment.handler }} · {{ alert.treatment.submitTime }}</b></div>
          <div v-if="alert.treatment.note" class="alert-treatment-record__row"><small>备注</small><b>{{ alert.treatment.note }}</b></div>
        </section>

        <AlertTimeline :nodes="alert.timeline" />
      </div>

      <footer class="alert-drawer-footer">
        <AlertActionBar :alert="alert" :action-pending="actionPending" @confirm="emit('confirm')" @assign="emit('assign')" @transfer="emit('transfer')"
          @start="emit('start')" @treat="emit('treat')" @linkage="emit('linkage')" @review="emit('review')" @escalate="emit('escalate')" />
      </footer>
    </template>
  </ElDrawer>
</template>
