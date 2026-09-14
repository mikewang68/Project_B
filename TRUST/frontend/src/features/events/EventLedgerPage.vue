<script setup lang="ts">
import { useEventLedger } from "./useEventLedger";
import { typeLabels } from "./presentation";
import { fmt, label } from "../../shared/presentation";
defineProps<{ canWrite: boolean }>();
const emit = defineEmits<{ open: [id: string]; create: [] }>();
const { rows, total, page, query, loadEvents, busy, run } = useEventLedger();
</script>

<template>
  <div class="summary-strip">
    <div>
      <span>已接收事件</span><strong>{{ total }}</strong>
    </div>
    <div><span>证据保存</span><strong class="word">独立 IPFS</strong></div>
    <div><span>可信登记</span><strong class="word">Fabric</strong></div>
  </div>
  <article class="panel">
    <form
      class="toolbar"
      @submit.prevent="
        page = 0;
        run(loadEvents);
      "
    >
      <input
        v-model="query"
        aria-label="搜索事件"
        placeholder="搜索批次、来源事件号或业务对象"
      /><button :disabled="busy">查询</button
      ><button
        type="button"
        v-if="canWrite"
        class="primary"
        @click="emit('create')"
      >
        ＋ 录入事件
      </button>
    </form>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>业务事件</th>
            <th>批次 / 对象</th>
            <th>业务发生时间</th>
            <th>文件</th>
            <th>上链</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>
              <strong>{{ typeLabels[row.event_type] || row.event_type }}</strong
              ><small>{{ row.source_event_id }}</small>
            </td>
            <td>
              {{ row.batch_id }}<small>{{ row.object_id }}</small>
            </td>
            <td>{{ fmt(row.occurred_at) }}</td>
            <td>
              <span class="badge" :data-state="row.file_state">{{
                label(row.file_state)
              }}</span>
            </td>
            <td>
              <span class="badge" :data-state="row.chain_state">{{
                label(row.chain_state)
              }}</span>
            </td>
            <td>
              <button class="text-button" @click="emit('open', row.id)">
                详情 →
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!rows.length" class="empty">
      暂无记录。可以录入事件，或导入项目样例。
    </div>
    <div class="pagination">
      <span>共 {{ total }} 条 · 第 {{ page + 1 }} 页</span
      ><button
        :disabled="page === 0 || busy"
        @click="
          page--;
          run(loadEvents);
        "
      >
        上一页</button
      ><button
        :disabled="(page + 1) * 20 >= total || busy"
        @click="
          page++;
          run(loadEvents);
        "
      >
        下一页
      </button>
    </div>
  </article>
</template>
