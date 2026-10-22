<script setup lang="ts">
import { ref, watch } from "vue";
import { json } from "../http/client";
import { useFeedback } from "../composables/useFeedback";
import { fmt } from "../presentation";
const props = defineProps<{ url: string; menuId: string; title?: string }>();
type Entry = Record<string, unknown>;
const rows = ref<Entry[]>([]),
  page = ref(0),
  more = ref(false),
  loaded = ref(false);
const { act, busy, notice } = useFeedback();
const labels: Record<string, string> = {
  state: "状态",
  status: "处理状态",
  label: "名称",
  fingerprint: "证书指纹",
  source_system: "来源系统",
  wallet_id: "钱包",
  binding_revision: "绑定修订",
  change_request_id: "批准申请",
  action: "操作",
  proposed_by: "申请人",
  reviewed_by: "复核人",
  reviewed_at: "复核时间",
  user_id: "用户",
  org_id: "组织",
  identity_revision: "身份修订",
  desired_state: "期望状态",
  certificate_version: "证书版本",
  last_error: "处理提示",
  not_before: "生效时间",
  not_after: "到期时间",
  network_state: "网络核验",
  crl_state: "撤销状态",
  archive_paused: "暂停归档",
  chain_paused: "暂停上链",
  file_state: "文件状态",
  chain_state: "上链状态",
  manifest_cid: "清单标识",
  tx_id: "交易号",
  block_number: "区块号",
};
const operations: Record<string, string> = {
  BASELINE: "迁移基线",
  CREATE: "创建",
  UPDATE: "修改",
  PROCESS: "处理结果",
  RESULT: "操作结果",
  RETRY: "重新处理",
  BIND: "来源绑定",
  DISABLE: "停用",
  REVIEW: "复核",
  SYNC: "同步",
  ROTATE: "轮换",
  REVOKE: "撤销",
};
function values(row: Entry) {
  return Object.entries(row).filter(([key]) => key in labels);
}
function load(target = 0) {
  return act(
    { action: "READ", menuId: props.menuId, summary: "查看历史版本" },
    async () => {
      const result = await json(`${props.url}?page=${target}`);
      rows.value = result.items;
      page.value = target;
      more.value = result.hasMore;
      loaded.value = true;
    },
  );
}
watch(
  () => props.url,
  () => {
    rows.value = [];
    page.value = 0;
    more.value = false;
    loaded.value = false;
  },
);
</script>
<template>
  <details class="version-history">
    <summary>{{ title || "历史版本" }}</summary>
    <button :disabled="busy" @click="load(page)">
      {{ loaded ? "刷新历史" : "读取历史" }}
    </button>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="loaded && !rows.length">当前范围没有历史记录。</p>
    <ol v-if="rows.length">
      <li
        v-for="row in rows"
        :key="`${row.certificate_version ?? 'object'}:${row.version}`"
      >
        <p>
          <strong
            >版本 {{ row.version }} ·
            {{ operations[String(row.operation)] || row.operation }}</strong
          >
          · {{ fmt(String(row.happened_at)) }}
        </p>
        <p>
          操作者：{{ row.actor }}；原因：{{
            row.operation === "BASELINE"
              ? "迁移时保存的状态，更早快照未补造"
              : row.reason
          }}
        </p>
        <dl>
          <template v-for="[key, value] in values(row)" :key="key"
            ><dt>{{ labels[key] }}</dt>
            <dd>
              {{
                typeof value === "boolean"
                  ? value
                    ? "是"
                    : "否"
                  : (value ?? "—")
              }}
            </dd></template
          >
        </dl>
      </li>
    </ol>
    <div v-if="loaded">
      <button :disabled="busy || page === 0" @click="load(page - 1)">
        上一页
      </button>
      第 {{ page + 1 }} 页
      <button :disabled="busy || !more" @click="load(page + 1)">下一页</button>
    </div>
  </details>
</template>
<style scoped>
.version-history {
  margin: 12px 0;
  overflow-wrap: anywhere;
}
summary {
  cursor: pointer;
  font-weight: 600;
  margin-bottom: 8px;
}
ol {
  padding-left: 24px;
}
li {
  border-bottom: 1px solid var(--line, #ddd);
  padding: 8px 0;
}
dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px 12px;
}
dd {
  margin: 0;
}
</style>
