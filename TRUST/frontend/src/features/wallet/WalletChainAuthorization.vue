<script setup lang="ts">
import { ref, watch } from "vue";
import type { ApiRecord } from "../../shared/types";
import { json } from "../../shared/http/client";
import { useFeedback } from "../../shared/composables/useFeedback";
import { fmt } from "../../shared/presentation";
const props = defineProps<{ bindings: ApiRecord[] }>();
const { act, busy } = useFeedback();
const selected = ref(""),
  eventType = ref("WAREHOUSE_IN");
const result = ref<ApiRecord | null>(null);
watch(
  () => props.bindings,
  () => {
    result.value = null;
    if (!props.bindings.some((b) => b.source_system === selected.value))
      selected.value = "";
  },
);
const labels: Record<string, string> = {
  AUTHORIZED: "已授权",
  UNAUTHORIZED: "未授权",
  UNKNOWN: "未知：查询覆盖不足或暂不可用",
  INCONSISTENT: "异常：组织节点查询结果不一致",
};
async function query(bindings: ApiRecord[]) {
  result.value = null;
  const binding = bindings.find((b) => b.source_system === selected.value);
  if (!binding) return;
  await act(
    {
      action: "READ",
      menuId: "wallets",
      objectId: binding.wallet_id,
      summary: "查询钱包链授权",
    },
    async () => {
      result.value = await json(
        `/wallets/${encodeURIComponent(binding.wallet_id)}/chain-authorization?sourceSystem=${encodeURIComponent(binding.source_system)}&eventType=${encodeURIComponent(eventType.value)}`,
      );
    },
  );
}
</script>
<template>
  <article class="panel">
    <h2>链授权状态</h2>
    <p>
      查询当前绑定证书在各配置组织上的来源和事件类型许可。该结果不代表钱包当前可签名，也不代替交易提交验证。
    </p>
    <form @submit.prevent="query(bindings)">
      <label
        >来源系统
        <select v-model="selected" required @change="result = null">
          <option value="" disabled>选择已绑定来源</option>
          <option
            v-for="b in bindings"
            :key="b.source_system"
            :value="b.source_system"
          >
            {{ b.source_system }}
          </option>
        </select>
      </label>
      <label
        >事件类型
        <input
          v-model="eventType"
          required
          pattern="[A-Z_]{1,40}"
          maxlength="40"
          @input="result = null"
        />
      </label>
      <button :disabled="busy || !selected">查询授权</button>
    </form>
    <div v-if="result" aria-live="polite">
      <p>
        <strong>{{ labels[result.status] || labels.UNKNOWN }}</strong> ·
        {{ fmt(result.checkedAt) }}
      </p>
      <p>
        {{ result.sourceSystem }} / {{ result.eventType }} · 钱包状态：{{
          result.walletState
        }}
      </p>
      <p class="fingerprint">证书指纹：{{ result.fingerprint }}</p>
      <p
        v-for="observation in result.observations"
        :key="observation.organization"
      >
        {{ observation.organization }}：{{
          observation.available ? "查询成功" : "查询不可用"
        }}
      </p>
      <p>
        未授权时将来源、证书指纹、事件类型及查询时间交给链维护负责人；此页面不修改链策略。
      </p>
    </div>
  </article>
</template>
