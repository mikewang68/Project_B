<script setup lang="ts">
import type { MobileIncident } from '@/types/incident'

defineProps<{ incidents: MobileIncident[] }>()

function riskSummary(i: MobileIncident): string {
  // 联动失败（如 PLC 回执超时）优先提示，三端可见
  if (i.linkage?.plc === 'failed' || i.linkage?.shutdown === 'failed') {
    return '联动异常 · PLC 回执超时，需人工接管'
  }
  if (i.evidence.kind === 'collision') return `距离：${i.evidence.distance}m · 相对速度 ${i.evidence.relSpeed}m/s`
  if (i.evidence.kind === 'ai') return `AI 置信度 ${i.evidence.confidence}% · ${i.evidence.camera}`
  if (i.evidence.kind === 'personnel') return `${i.target.split('（')[0]} · 手环${i.evidence.bandState.replace('在线 · ', '')}`
  return i.evidence.description
}

const statusTone: Record<string, string> = {
  待接单: 'wait', 已接单: 'doing', 已到场: 'doing', 处理中: 'doing', 待复核: 'review', 已关闭: 'closed',
}
</script>

<template>
  <section class="bs-panel bs-feed">
    <div class="bs-panel__head">
      <div><span>REALTIME EVENTS</span><h2>实时安全事件</h2></div>
      <span class="bs-feed__live"><i></i>LIVE</span>
    </div>
    <div class="bs-feed__list">
      <TransitionGroup name="bs-feed-item">
        <article
          v-for="incident in incidents"
          :key="incident.id"
          class="bs-feed-item"
          :data-risk="incident.risk"
        >
          <div class="bs-feed-item__risk">{{ incident.risk }}</div>
          <div class="bs-feed-item__body">
            <b>{{ incident.title }}</b>
            <p>{{ riskSummary(incident) }}</p>
            <footer>
              <span>{{ incident.area }}</span>
              <span :data-state="statusTone[incident.status]">{{ incident.status }}</span>
              <time>{{ incident.time }}</time>
            </footer>
          </div>
        </article>
      </TransitionGroup>
    </div>
  </section>
</template>
