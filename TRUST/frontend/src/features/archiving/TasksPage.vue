<script setup lang="ts">
import { usePageLoad } from "../../shared/composables/usePageLoad";
import { ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { usePolling } from "../../shared/composables/usePolling";
import { label } from "../../shared/presentation";
import { listTasks, retryTask } from "./api";
defineProps<{ canWrite: boolean }>();
const emit = defineEmits<{ open: [id: string] }>();
const tasks = ref<ApiRecord[]>([]);
const { busy, notice, run } = useFeedback();
async function load() {
  tasks.value = await listTasks();
}
async function retry(id: string) {
  await run(async () => {
    await retryTask(id);
    notice.value = "补办任务已安排。";
    await load();
  });
}
usePageLoad(load);
usePolling(load, () => !busy.value);
</script>

<template>
  <article class="panel">
    <div class="section-title">
      <h2>存证处理任务</h2>
      <button @click="run(load)" :disabled="busy">刷新</button>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>事件 / 批次</th>
            <th>处理状态</th>
            <th>尝试次数</th>
            <th>最近原因</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.event_id">
            <td>
              <strong>{{ t.source_event_id }}</strong
              ><small>{{ t.batch_id }}</small>
            </td>
            <td>
              <span class="badge" :data-state="t.state">{{
                label(t.state)
              }}</span>
            </td>
            <td>{{ t.attempts }}</td>
            <td class="reason">{{ t.last_error || "—" }}</td>
            <td>
              <button
                v-if="canWrite && t.state !== 'DONE'"
                @click="retry(t.event_id)"
                :disabled="busy || t.state === 'RUNNING'"
              >
                补办</button
              ><button class="text-button" @click="emit('open', t.event_id)">
                详情
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!tasks.length" class="empty">暂无补办任务。</div>
  </article>
</template>
