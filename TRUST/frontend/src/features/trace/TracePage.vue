<script setup lang="ts">
import { ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { fmt, label } from "../../shared/presentation";
import { typeLabels } from "../events/presentation";
import { traceEvents } from "./api";
const emit = defineEmits<{ open: [id: string] }>();
const traceKind = ref("BATCH"),
  traceValue = ref("STEEL-2026-001"),
  traceResult = ref<ApiRecord | null>(null);
const { busy, run } = useFeedback();
async function trace() {
  await run(async () => {
    traceResult.value = await traceEvents(traceKind.value, traceValue.value);
  });
}
</script>

<template>
  <article class="panel">
    <form class="toolbar" @submit.prevent="trace">
      <select v-model="traceKind">
        <option value="BATCH">批次号</option>
        <option value="BUNDLE">捆号</option>
        <option value="HANDOVER">交接单号</option>
        <option value="EVENT">来源系统:事件号</option></select
      ><input v-model="traceValue" required aria-label="溯源查询值" /><button
        class="primary"
        :disabled="busy"
      >
        查看溯源
      </button>
    </form>
    <template v-if="traceResult"
      ><div class="trace-meta">
        关联到 {{ traceResult.items.length }} 条事件
        <span class="muted">按业务发生时间排列，更正历史一并保留</span>
      </div>
      <p v-if="traceResult.missingReferences.length" class="alert warning">
        链路不完整，待补记录：{{ traceResult.missingReferences.join("、") }}
      </p>
      <p v-if="traceResult.truncated" class="alert warning">
        关联结果较多，请缩小查询范围。
      </p>
      <div class="timeline">
        <div v-for="e in traceResult.items" :key="e.id" class="timeline-item">
          <div class="timeline-date">{{ fmt(e.occurred_at) }}</div>
          <div class="timeline-card">
            <div>
              <strong>{{ typeLabels[e.event_type] || e.event_type }}</strong
              ><span class="badge" :data-state="e.chain_state">{{
                label(e.chain_state)
              }}</span>
            </div>
            <p>{{ e.batch_id }} · {{ e.source_event_id }} · v{{ e.version }}</p>
            <button class="text-button" @click="emit('open', e.id)">
              查看记录与证据 →
            </button>
          </div>
        </div>
      </div>
      <div v-if="!traceResult.items.length" class="empty">
        没有找到当前授权范围内的关联记录。
      </div></template
    >
    <div v-else class="empty">
      输入一个批次、捆号或交接单，开始查看来源与去向。
    </div>
  </article>
</template>
