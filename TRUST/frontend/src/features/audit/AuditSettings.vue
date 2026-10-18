<script setup lang="ts">
import { ref } from "vue";
import { json } from "../../shared/http/client";
import { useFeedback } from "../../shared/composables/useFeedback";
import { usePageLoad } from "../../shared/composables/usePageLoad";
import { fmt } from "../../shared/presentation";

type Settings = {
  canManage: boolean;
  configured: boolean;
  collectionEnabled: true;
  archivePaused: boolean;
  chainPaused: boolean;
  pendingCount: number;
  failedCount: number;
  oldestPendingAt: string | null;
  lastError?: string | null;
};
const settings = ref<Settings | null>(null),
  loadError = ref("");
const archivePaused = ref(false),
  chainPaused = ref(false),
  reason = ref("");
const { act, busy, notice } = useFeedback();
async function load() {
  loadError.value = "";
  try {
    const current = (await json("/audit/settings")) as Settings;
    settings.value = current;
    archivePaused.value = current.archivePaused;
    chainPaused.value = current.chainPaused;
  } catch (error) {
    settings.value = null;
    loadError.value = error instanceof Error ? error.message : "设置读取失败";
    throw error;
  }
}
usePageLoad(load);
async function save() {
  if (!settings.value?.canManage) return;
  await act(
    { action: "UPDATE", menuId: "status", summary: "修改日志处理设置" },
    async () => {
      await json("/audit/settings", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          archivePaused: archivePaused.value,
          chainPaused: chainPaused.value,
          reason: reason.value,
        }),
      });
      reason.value = "";
      await load();
      notice.value = "日志处理设置已保存，已接收的原文和待办继续保留。";
    },
  );
}
</script>

<template>
  <article class="panel" aria-labelledby="audit-settings-heading">
    <div class="section-title">
      <h2 id="audit-settings-heading">日志存证设置</h2>
      <button
        :disabled="busy"
        @click="
          act(
            { action: 'READ', menuId: 'status', summary: '刷新日志处理状态' },
            load,
          )
        "
      >
        刷新日志状态
      </button>
    </div>
    <p>
      操作采集持续开启。暂停只影响后续归档或上链处理，原文及待办继续保存，恢复后继续处理。
    </p>
    <p v-if="loadError" role="alert">{{ loadError }}</p>
    <template v-if="settings">
      <p v-if="!settings.configured" class="alert warning">
        日志处理配置尚未就绪，已采集记录保留待办。
      </p>
      <dl class="facts">
        <div>
          <dt>原文归档</dt>
          <dd>{{ settings.archivePaused ? "已暂停" : "允许处理" }}</dd>
        </div>
        <div>
          <dt>上链处理</dt>
          <dd>{{ settings.chainPaused ? "已暂停" : "允许处理" }}</dd>
        </div>
        <div>
          <dt>待处理数量</dt>
          <dd>{{ settings.pendingCount }}</dd>
        </div>
        <div>
          <dt>失败数量</dt>
          <dd>{{ settings.failedCount }}</dd>
        </div>
        <div>
          <dt>最早待办</dt>
          <dd>
            {{
              settings.oldestPendingAt ? fmt(settings.oldestPendingAt) : "无"
            }}
          </dd>
        </div>
        <div>
          <dt>最近失败原因</dt>
          <dd>{{ settings.lastError || "无已报告原因" }}</dd>
        </div>
      </dl>
      <form
        v-if="settings.canManage"
        class="settings-form"
        @submit.prevent="save"
      >
        <label class="toggle"
          ><input
            type="checkbox"
            v-model="archivePaused"
            :disabled="busy"
          />暂停原文归档</label
        >
        <label class="toggle"
          ><input
            type="checkbox"
            v-model="chainPaused"
            :disabled="busy"
          />暂停上链处理</label
        >
        <label
          >修改原因<input
            v-model="reason"
            required
            maxlength="500"
            :disabled="busy"
            aria-label="日志设置修改原因"
        /></label>
        <button class="primary" :disabled="busy || !reason.trim()">
          保存日志设置
        </button>
      </form>
      <p v-else class="small muted">
        当前账号仅可查看。修改权限由服务端的专用管理身份配置决定。
      </p>
    </template>
  </article>
</template>

<style scoped>
.settings-form {
  display: grid;
  gap: 16px;
  margin-top: 20px;
}
.settings-form .toggle {
  display: flex;
  align-items: center;
  gap: 10px;
}
.settings-form input[type="checkbox"] {
  width: 18px;
  height: 18px;
  margin: 0;
}
.settings-form button {
  justify-self: start;
}
</style>
