<script setup lang="ts">
import { Close } from '@element-plus/icons-vue'
import { ElDrawer } from 'element-plus'
import AIDetectionImage from './AIDetectionImage.vue'
import AIEventStatusBadge from './AIEventStatusBadge.vue'
import ConfidenceBadge from './ConfidenceBadge.vue'
import CameraHealthBadge from './CameraHealthBadge.vue'
import AITimeline from './AITimeline.vue'
import ReviewActionBar from './ReviewActionBar.vue'
import type { AiEvent } from '@/types/ai'

withDefaults(defineProps<{ modelValue: boolean; event: AiEvent | undefined; loading?: boolean }>(), {
  loading: false,
})
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: []
  falseReport: []
  uncertain: []
  assign: []
  processing: []
  close: []
}>()
</script>

<template>
  <ElDrawer :model-value="modelValue" size="560px" :with-header="false" class="ai-event-drawer"
    @update:model-value="emit('update:modelValue', $event)">
    <div v-if="loading" class="ai-drawer-scroll ai-drawer-loading">
      <span>正在加载事件详情…</span>
    </div>
    <template v-else-if="event">
      <header class="ai-drawer-header">
        <div>
          <span>AI REVIEW CENTER</span>
          <h2>{{ event.type }}</h2>
          <p>{{ event.id }}</p>
        </div>
        <button type="button" aria-label="关闭详情" @click="emit('update:modelValue', false)"><el-icon><Close /></el-icon></button>
      </header>

      <div class="ai-drawer-scroll">
        <div class="ai-drawer-status">
          <div><small>复核状态</small><AIEventStatusBadge :status="event.status" /></div>
          <div><small>风险等级</small><span class="ai-risk-tag" :data-risk="event.risk">{{ event.risk }}风险</span></div>
          <div><small>摄像头</small><CameraHealthBadge :health="event.health" /></div>
        </div>

        <AIDetectionImage class="ai-drawer-shot" :scene="event.scene" :boxes="event.boxes" :health="event.health"
          :camera="event.camera" :time="event.time" />

        <dl class="ai-drawer-grid">
          <div><dt>事件类型</dt><dd>{{ event.type }}</dd></div>
          <div><dt>风险等级</dt><dd>{{ event.risk }}风险</dd></div>
          <div><dt>摄像头</dt><dd>{{ event.camera }} · {{ event.cameraName }}</dd></div>
          <div><dt>所在区域</dt><dd>{{ event.area }}</dd></div>
          <div><dt>触发时间</dt><dd>{{ event.time }}</dd></div>
          <div><dt>持续时间</dt><dd>{{ event.durationSec ? event.durationSec.toFixed(1) + ' 秒' : '--' }}</dd></div>
          <div><dt>模型版本</dt><dd>{{ event.model }}</dd></div>
          <div><dt>置信度</dt><dd><ConfidenceBadge :confidence="event.confidence" :threshold="event.threshold" /></dd></div>
          <div class="wide"><dt>区域规则</dt><dd>{{ event.rule }}<em v-if="event.threshold">（阈值 {{ event.threshold }}%）</em></dd></div>
          <div><dt>关联人员</dt><dd>{{ event.relatedPerson }}</dd></div>
          <div><dt>关联设备</dt><dd>{{ event.relatedDevice }}</dd></div>
          <div v-if="event.linkedAlertId" class="wide"><dt>关联告警</dt>
            <dd><RouterLink class="ai-linked-alert" to="/alarms">{{ event.linkedAlertId }}（进入告警主链处置）</RouterLink></dd>
          </div>
        </dl>

        <section class="ai-judge-panel">
          <h4>AI 判定</h4>
          <p>{{ event.judgeText }}</p>
          <div class="ai-judge-metrics">
            <span><small>置信度</small><b :class="{ low: event.confidence && event.confidence < event.threshold }">{{ event.confidence ? event.confidence.toFixed(1) + '%' : '--' }}</b></span>
            <span><small>持续时间</small><b>{{ event.durationSec ? event.durationSec.toFixed(1) + ' 秒' : '--' }}</b></span>
            <span><small>规则阈值</small><b>{{ event.threshold ? event.threshold + '%' : '--' }}</b></span>
            <span class="wide"><small>检测模型</small><b>{{ event.model }}</b></span>
          </div>
        </section>

        <section v-if="event.reviewer || event.falseReason || event.assignee" class="ai-review-result">
          <h4>人工复核记录</h4>
          <div v-if="event.reviewer" class="ai-review-result__row"><span>复核人</span><b>{{ event.reviewer }} · {{ event.reviewTime }}</b></div>
          <div v-if="event.falseReason" class="ai-review-result__row"><span>误报原因</span><b>{{ event.falseReason }}</b></div>
          <div v-if="event.assignee" class="ai-review-result__row"><span>派单信息</span><b>{{ event.assignee }} · {{ event.assignmentPriority }}优先级 · {{ event.processStatus }}</b></div>
          <div v-if="event.assignmentNote" class="ai-review-result__row"><span>处置备注</span><b>{{ event.assignmentNote }}</b></div>
        </section>

        <AITimeline :nodes="event.timeline" />
      </div>

      <footer class="ai-drawer-footer">
        <ReviewActionBar :event="event" @confirm="emit('confirm')" @false-report="emit('falseReport')"
          @uncertain="emit('uncertain')" @assign="emit('assign')" @processing="emit('processing')" @close="emit('close')" />
      </footer>
    </template>
  </ElDrawer>
</template>
