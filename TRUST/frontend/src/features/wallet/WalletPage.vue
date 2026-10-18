<script setup lang="ts">
import { ref, computed } from "vue";
import type { ApiRecord } from "../../shared/types";
import { json, post } from "../../shared/http/client";
import { useFeedback } from "../../shared/composables/useFeedback";
import { usePageLoad } from "../../shared/composables/usePageLoad";
import WalletChainAuthorization from "./WalletChainAuthorization.vue";
import { fmt } from "../../shared/presentation";
const props = defineProps<{ me: ApiRecord }>();
const { run, act, busy } = useFeedback();
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
const changeForm = ref<HTMLFormElement | null>(null);
function startBinding() {
  void act(
    { action: "READ", menuId: "wallets", summary: "打开来源绑定申请" },
    () => {
      action.value = "BIND";
      changeForm.value?.scrollIntoView({ behavior: "smooth", block: "start" });
    },
  );
}
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
  await act(
    {
      action: "CREATE",
      menuId: "wallets",
      summary: "请求钱包变更（停用需二次确认）",
    },
    async () => {
      if (
        action.value === "DISABLE" &&
        !window.confirm(
          "停用后，此钱包不能再签名；已经固定使用它但尚未上链的事件也可能无法补办。请先确认相关上链、核验、导出和补办已完成。仍要提交停用申请吗？",
        )
      )
        return;
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
    },
  );
}
async function review(id: string, approve: boolean) {
  await act(
    {
      action: "UPDATE",
      menuId: "wallets",
      objectId: id,
      summary: "复核钱包变更申请",
    },
    async () => {
      await post(`/wallets/requests/${id}/review`, { approve });
      await load();
    },
  );
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
        <button
          :disabled="busy"
          @click="
            act(
              { action: 'READ', menuId: 'wallets', summary: '刷新钱包列表' },
              load,
            )
          "
        >
          刷新
        </button>
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
      <div class="section-title">
        <h2>来源系统绑定</h2>
        <button v-if="allowed('trust:wallet:manage')" @click="startBinding">
          申请来源绑定
        </button>
      </div>
      <p class="small muted">
        此处显示应用内的绑定结果。绑定批准后，还须由维护者确认该来源、事件类型和证书的链上授权；钱包可用或网络正常都不代表已经具备上链权限。
      </p>
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
    <WalletChainAuthorization :bindings="data.bindings" />
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
      ref="changeForm"
      v-if="allowed('trust:wallet:manage')"
      class="panel wallet-form"
      @submit.prevent="propose"
    >
      <h2>提交变更申请</h2>
      <p class="small muted">
        <span class="required-mark" aria-hidden="true">*</span>
        为必填项。登记证书后还需申请来源绑定，并由不同用户复核。
      </p>
      <label
        >操作<select aria-label="操作" v-model="action">
          <option value="REGISTER">登记证书版本</option>
          <option value="BIND">绑定 / 更换来源身份</option>
          <option value="DISABLE">停用钱包</option>
        </select></label
      >
      <template v-if="action === 'REGISTER'"
        ><label
          ><span
            >钱包名称<span class="required-mark" aria-hidden="true">
              *</span
            ></span
          ><input
            aria-label="钱包名称"
            v-model="label"
            required
            maxlength="120" /></label
        ><label
          ><span
            >所属 MSP<span class="required-mark" aria-hidden="true">
              *</span
            ></span
          ><input
            aria-label="所属 MSP"
            v-model="mspId"
            required
            maxlength="80" /></label
        ><label
          ><span
            >运维预置的密钥引用<span class="required-mark" aria-hidden="true">
              *</span
            ></span
          ><input
            aria-label="运维预置的密钥引用"
            v-model="keyRef"
            required
            pattern="[A-Za-z0-9_-]{1,100}" /></label
      ></template>
      <label v-else
        ><span
          >钱包<span class="required-mark" aria-hidden="true"> *</span></span
        ><select aria-label="钱包" v-model="walletId" required>
          <option value="">请选择</option>
          <option v-for="w in data.wallets" :key="w.id" :value="w.id">
            {{ w.label }} · {{ names[w.state] }}
          </option>
        </select></label
      >
      <label v-if="action === 'BIND'"
        ><span
          >来源系统编码<span class="required-mark" aria-hidden="true">
            *</span
          ></span
        ><input
          aria-label="来源系统编码"
          v-model="sourceSystem"
          required
          pattern="[A-Za-z0-9._-]{1,80}"
      /></label>
      <label
        ><span
          >变更原因<span class="required-mark" aria-hidden="true">
            *</span
          ></span
        ><textarea
          aria-label="变更原因"
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
