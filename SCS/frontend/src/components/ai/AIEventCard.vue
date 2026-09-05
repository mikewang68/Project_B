<script setup lang="ts">
import { Timer, VideoCamera } from '@element-plus/icons-vue'
import AIDetectionImage from './AIDetectionImage.vue'
import AIEventStatusBadge from './AIEventStatusBadge.vue'
import ConfidenceBadge from './ConfidenceBadge.vue'
import CameraHealthBadge from './CameraHealthBadge.vue'
import type { AiEvent } from '@/types/ai'

defineProps<{ event: AiEvent }>()
defineEmits<{ open: [event: AiEvent] }>()
</script>

<template>
  <article class="ai-event-card" :class="{ 'is-fresh': event.fresh }" @click="$emit('open', event)">
    <div class="ai-event-card__shot">
      <AIDetectionImage :scene="event.scene" :boxes="event.boxes" :health="event.health" :camera="event.camera" :time="event.time" compact />
      <span class="ai-event-card__risk" :data-risk="event.risk">{{ event.risk }}风险</span>
    </div>
    <div class="ai-event-card__body">
      <header>
        <h3>{{ event.type }}</h3>
        <AIEventStatusBadge :status="event.status" />
      </header>
      <p class="ai-event-card__loc"><el-icon><VideoCamera /></el-icon>{{ event.camera }} · {{ event.cameraName }} · {{ event.area }}</p>
      <dl class="ai-event-card__meta">
        <div><dt>置信度</dt><dd><ConfidenceBadge :confidence="event.confidence" :threshold="event.threshold" /></dd></div>
        <div><dt>持续</dt><dd>{{ event.durationSec ? event.durationSec.toFixed(1) + ' s' : '--' }}</dd></div>
        <div><dt>模型</dt><dd>{{ event.model }}</dd></div>
        <div><dt>时间</dt><dd>{{ event.time }}</dd></div>
      </dl>
      <footer>
        <CameraHealthBadge :health="event.health" />
        <span class="ai-event-card__duration"><el-icon><Timer /></el-icon>{{ event.id.slice(-3) }} 号事件</span>
      </footer>
      <div v-if="event.assignee" class="ai-event-card__assignee">已派单 · {{ event.assignee }} · {{ event.processStatus }}</div>
    </div>
  </article>
</template>
