<script setup lang="ts">
import { usePageLoad } from "../../shared/composables/usePageLoad";
import { ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { fmt, label } from "../../shared/presentation";
import { getStatus } from "./api";
import { listAudit } from "../audit/api";
import AuditLog from "../audit/AuditLog.vue";
const props = defineProps<{ canAdmin: boolean }>();
const status = ref<ApiRecord | null>(null),
  audit = ref<ApiRecord[]>([]);
const { busy, run } = useFeedback();
async function load() {
  status.value = await getStatus();
  if (props.canAdmin) audit.value = await listAudit();
}
usePageLoad(load);
</script>

<template>
  <div class="component-grid" v-if="status">
    <article
      v-for="c in [
        { name: '事件数据库', node: '数据库节点', state: status.database },
        {
          name: 'IPFS 文件归档',
          node: 'IPFS 独立节点',
          state: status.ipfs.state,
        },
        { name: 'Fabric 存证网络', node: 'Fabric 节点', state: status.fabric },
      ]"
      :key="c.name"
      class="panel component"
    >
      <span class="badge" :data-state="c.state">{{ label(c.state) }}</span>
      <h2>{{ c.name }}</h2>
      <p>{{ c.node }}</p>
    </article>
  </div>
  <article class="panel" v-if="status">
    <div class="section-title">
      <h2>运行概况</h2>
      <button @click="run(load)" :disabled="busy">重新检查</button>
    </div>
    <dl class="facts">
      <div>
        <dt>检查时间</dt>
        <dd>{{ fmt(status.checkedAt) }}</dd>
      </div>
      <div>
        <dt>IPFS 仓库占用</dt>
        <dd>
          {{
            status.ipfs.repoBytes == null
              ? "暂不可读"
              : (status.ipfs.repoBytes / 1024 / 1024).toFixed(2) + " MiB"
          }}
        </dd>
      </div>
      <div>
        <dt>开发预算</dt>
        <dd>10 GiB</dd>
      </div>
      <div>
        <dt>已接收事件</dt>
        <dd>{{ status.events }}</dd>
      </div>
    </dl>
    <p class="small muted">
      组件独立启停。依赖故障时，已接收的事件保留待处理状态。
    </p>
  </article>
  <AuditLog v-if="canAdmin" :audit="audit" />
</template>
