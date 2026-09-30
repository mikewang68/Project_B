<script setup lang="ts">
import { ref, computed } from "vue";
import type { ApiRecord } from "../../shared/types";
import { json, post } from "../../shared/http/client";
import { useFeedback } from "../../shared/composables/useFeedback";
import { usePageLoad } from "../../shared/composables/usePageLoad";
import { fmt } from "../../shared/presentation";
const props = defineProps<{ me: ApiRecord }>();
const { run, busy } = useFeedback();
const allowed = (permission: string) =>
  props.me.identityProvider === "IAM" && props.me.roles.includes(permission);
const canRead = computed(() => allowed("trust:wallet:read"));
const data = ref<ApiRecord>({
  wallets: [],
  bindings: [],
  requests: [],
  signatures: [],
});
const action = ref("REGISTER"),
  label = ref(""),
  mspId = ref("Org1MSP"),
  keyRef = ref("");
const walletId = ref(""),
  sourceSystem = ref("WMS"),
  reason = ref("");
const names: Record<string, string> = {
  REGISTER: "登记证书版本",
  BIND: "绑定来源系统",
  DISABLE: "停用钱包",
  PENDING: "待复核",
  APPROVED: "已批准",
  REJECTED: "已拒绝",
  ACTIVE: "可用",
  DISABLED: "已停用",
};
async function load() {
  if (canRead.value) data.value = await json("/wallets");
}
usePageLoad(load);
async function propose() {
  await run(async () => {
    const binding = data.value.bindings.find(
      (v: ApiRecord) => v.source_system === sourceSystem.value,
    );
    await post("/wallets/requests", {
      action: action.value,
      reason: reason.value,
      change: {
        label: label.value,
        mspId: mspId.value,
        keyRef: keyRef.value,
        walletId: walletId.value,
        sourceSystem: sourceSystem.value,
        expectedRevision: binding?.revision || 0,
      },
    });
    reason.value = "";
    await load();
  });
}
async function review(id: string, approve: boolean) {
  await run(async () => {
    await post(`/wallets/requests/${id}/review`, { approve });
    await load();
  });
}
</script>

<template>
  <article v-if="!canRead" class="panel">
    <h2>需要平台身份授权</h2>
    <p>
      请使用 IAM
      账号登录，并由权限管理员授予托管钱包查看权限。本地开发账号不能管理签名身份。
    </p>
  </article>
  <template v-else>
    <article class="panel">
      <div class="section-title">
        <h2>签名身份与证书版本</h2>
        <button :disabled="busy" @click="run(load)">刷新</button>
      </div>
      <p class="small muted">
        私钥由后台保护。平台停用会禁止新签名，不等同于联盟网络吊销。证书版本登记后仍需完成链上来源授权。
      </p>
      <p v-if="!data.wallets.length">
        尚未登记钱包。由运维预置加密密钥引用后，在下方提交登记申请。
      </p>
      <div class="table-wrap" v-else>
        <table>
          <thead>
            <tr>
              <th>名称 / 所属 MSP</th>
              <th>证书指纹</th>
              <th>到期时间</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="w in data.wallets" :key="w.id">
              <td>
                {{ w.label }}
                <p class="small muted">{{ w.msp_id }} · {{ w.key_ref }}</p>
              </td>
              <td>
                <code class="fingerprint">{{ w.fingerprint }}</code>
              </td>
              <td>{{ fmt(w.not_after) }}</td>
              <td>
                {{
                  new Date(w.not_after).getTime() < Date.now()
                    ? "已过期，禁止签名"
                    : names[w.state]
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>
    <article class="panel">
      <h2>来源系统绑定</h2>
      <p v-if="!data.bindings.length">尚未配置来源绑定；业务事件将保留待办。</p>
      <dl class="facts">
        <div v-for="b in data.bindings" :key="b.source_system">
          <dt>{{ b.source_system }} · 版本 {{ b.revision }}</dt>
          <dd>
            {{
              data.wallets.find((w: ApiRecord) => w.id === b.wallet_id)
                ?.label || b.wallet_id
            }}
          </dd>
        </div>
      </dl>
    </article>
    <article class="panel">
      <h2>最近签名记录</h2>
      <p v-if="!data.signatures.length">暂无签名记录。</p>
      <div class="table-wrap" v-else>
        <table>
          <thead>
            <tr>
              <th>来源事件</th>
              <th>业务提交身份</th>
              <th>证书指纹 / 交易号</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in data.signatures" :key="s.tx_id">
              <td>{{ s.source_system }} · {{ s.source_event_id }}</td>
              <td>{{ s.submitted_by }}</td>
              <td>
                <code class="fingerprint">{{ s.fingerprint }}</code>
                <p class="small fingerprint">{{ s.tx_id }}</p>
              </td>
              <td>
                {{
                  s.state === "COMMITTED"
                    ? "有效提交"
                    : s.state === "PREPARED"
                      ? "已签名，待确认"
                      : "由其他交易完成"
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>
    <form
      v-if="allowed('trust:wallet:manage')"
      class="panel wallet-form"
      @submit.prevent="propose"
    >
      <h2>提交变更申请</h2>
      <label
        >操作<select v-model="action">
          <option value="REGISTER">登记证书版本</option>
          <option value="BIND">绑定 / 更换来源身份</option>
          <option value="DISABLE">停用钱包</option>
        </select></label
      >
      <template v-if="action === 'REGISTER'"
        ><label
          >钱包名称<input v-model="label" required maxlength="120" /></label
        ><label>所属 MSP<input v-model="mspId" required maxlength="80" /></label
        ><label
          >运维预置的密钥引用<input
            v-model="keyRef"
            required
            pattern="[A-Za-z0-9_-]{1,100}" /></label
      ></template>
      <label v-else
        >钱包<select v-model="walletId" required>
          <option value="">请选择</option>
          <option v-for="w in data.wallets" :key="w.id" :value="w.id">
            {{ w.label }} · {{ names[w.state] }}
          </option>
        </select></label
      >
      <label v-if="action === 'BIND'"
        >来源系统编码<input
          v-model="sourceSystem"
          required
          pattern="[A-Za-z0-9._-]{1,80}"
      /></label>
      <label
        >变更原因<textarea
          v-model="reason"
          required
          maxlength="500"
        ></textarea></label
      ><button :disabled="busy">提交复核</button>
    </form>
    <article class="panel">
      <h2>变更记录与复核</h2>
      <p v-if="!data.requests.length">暂无变更申请。</p>
      <div v-for="r in data.requests" :key="r.id" class="wallet-request">
        <strong>{{ names[r.action] }} · {{ names[r.state] }}</strong>
        <p>{{ r.reason }}</p>
        <p class="small muted">
          申请人：{{ r.proposed_by }} · {{ fmt(r.created_at)
          }}<span v-if="r.reviewed_by"> · 复核人：{{ r.reviewed_by }}</span>
        </p>
        <pre>{{ JSON.stringify(JSON.parse(r.payload), null, 2) }}</pre>
        <div v-if="r.state === 'PENDING' && allowed('trust:wallet:review')">
          <button :disabled="busy" @click="review(r.id, true)">批准</button>
          <button :disabled="busy" @click="review(r.id, false)">拒绝</button>
        </div>
      </div>
    </article>
  </template>
</template>
<style scoped>
.wallet-form {
  display: grid;
  gap: 14px;
}
.wallet-form label {
  display: grid;
  gap: 6px;
}
.fingerprint {
  overflow-wrap: anywhere;
}
.wallet-request {
  border-top: 1px solid var(--border, #ddd);
  padding: 16px 0;
}
.wallet-request pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
}
</style>
