<script setup lang="ts">
import { usePageLoad } from "../../shared/composables/usePageLoad";
import { ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { label, recoveryHint } from "../../shared/presentation";
import { listTasks, retryTask } from "./api";
import { useAccess } from "../auth/access";
const allowed = useAccess();
defineProps<{ canWrite: boolean }>();
const emit = defineEmits<{ open: [id: string] }>();
const tasks = ref<ApiRecord[]>([]);
const { busy, notice, act } = useFeedback();
async function load() {
  tasks.value = await listTasks();
}
async function retry(id: string) {
  await act(
    { action: "EXECUTE", menuId: "tasks", objectId: id, summary: "申请补办" },
    async () => {
      await retryTask(id);
      notice.value = "补办任务已安排。";
      await load();
    },
  );
}
usePageLoad(load, "tasks");
</script>

<template>
  <p class="small muted" role="status">
    状态不会自动更新。请点击“刷新”查看最新补办结果。
  </p>
  <article class="panel">
    <div class="section-title">
      <h2>存证处理任务</h2>
      <button
        @click="
          act(
            { action: 'READ', menuId: 'tasks', summary: '刷新补办任务' },
            load,
          )
        "
        :disabled="busy"
      >
        刷新
      </button>
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
            <td class="reason">
              <p v-if="recoveryHint(t.last_error)">
                {{ recoveryHint(t.last_error) }}
              </p>
              <details v-if="recoveryHint(t.last_error)">
                <summary>查看原始错误</summary>
                {{ t.last_error }}
              </details>
              <template v-else>{{ t.last_error || "—" }}</template>
            </td>
            <td>
              <button
                v-if="canWrite && t.state !== 'DONE'"
                @click="retry(t.event_id)"
                :disabled="busy || t.state === 'RUNNING'"
              >
                补办</button
              ><button class="text-button" :disabled="!allowed('trust:event:read')" @click="emit('open', t.event_id)">
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
