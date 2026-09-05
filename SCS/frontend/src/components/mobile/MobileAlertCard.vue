<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useIncidentStore } from '@/stores/incident'
import type { MobileIncident } from '@/types/incident'

const props = defineProps<{ incident: MobileIncident; compact?: boolean }>()
const store = useIncidentStore()
const router = useRouter()
const acting = ref(false)

const statusTone: Record<string, string> = {
  待接单: 'wait', 已接单: 'doing', 已到场: 'doing', 处理中: 'doing', 待复核: 'review', 已关闭: 'closed',
}

function open(): void {
  router.push(`/mobile/alert/${props.incident.id}`)
}

async function accept(event: Event): Promise<void> {
  event.stopPropagation()
  acting.value = true
  try {
    await store.accept(props.incident.id)
    ElMessage.success('已接单')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '接单失败')
  } finally {
    acting.value = false
  }
}
</script>

<template>
  <article class="m-alert-card" :data-risk="props.incident.risk" @click="open">
    <header>
      <span class="m-risk-tag">{{ props.incident.risk }}</span>
      <span class="m-status-tag" :data-tone="statusTone[props.incident.status]">{{ props.incident.status }}</span>
      <time>{{ props.incident.time }}</time>
    </header>
    <h4>{{ props.incident.title }}</h4>
    <p class="m-alert-card__meta">
      <span>{{ props.incident.area }}</span>
      <span>{{ props.incident.target }}</span>
      <span>距当前位置 {{ props.incident.distanceM }}m</span>
    </p>
    <p v-if="!compact && props.incident.evidence.kind === 'collision'" class="m-alert-card__extra">
      当前距离 {{ props.incident.evidence.distance }}m · 相对速度 {{ props.incident.evidence.relSpeed }}m/s
    </p>
    <footer v-if="props.incident.status === '待接单'">
      <button type="button" class="m-btn m-btn--ghost" @click.stop="open">查看</button>
      <button type="button" class="m-btn m-btn--primary" :disabled="acting" @click="accept">接单</button>
    </footer>
  </article>
</template>
