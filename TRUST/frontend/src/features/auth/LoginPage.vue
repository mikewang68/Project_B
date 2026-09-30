<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useFeedback } from "../../shared/composables/useFeedback";

interface QuickAccount {
  username: string;
  password?: string;
  role: string;
  orgId: string;
}

const props = defineProps<{
  signIn: (username: string, password: string) => Promise<void>;
  quickSignIn: (username: string) => Promise<void>;
  iamSignIn: (
    username: string,
    password: string,
    orgId: string,
  ) => Promise<void>;
}>();
const username = ref(""),
  password = ref("");
const quickAccounts = ref<QuickAccount[]>([]);
const simulated = ref(false), iamRequired = ref(false), identityReady = ref(false);
const localLoginEnabled = ref(false);
const identityError = ref("");
const mode = ref("LOCAL"),
  orgId = ref("B-PROJECT");
const { busy, error, run } = useFeedback();

const accountNames: Record<string, string> = {
  admin: "管理员",
  editor: "录入员",
  viewer: "查询员",
  "external-viewer": "外部查询员",
};

onMounted(async () => {
  try {
    const metadata = await fetch("/api/v1/identity-mode", { cache: "no-store" });
    if (!metadata.ok) throw new Error("身份模式不可用，请稍后重试");
    const identityMode = await metadata.json();
    if (typeof identityMode.simulated !== "boolean" || typeof identityMode.iamRequired !== "boolean")
      throw new Error("身份模式不可用，请稍后重试");
    simulated.value = identityMode.simulated;
    iamRequired.value = identityMode.iamRequired;
    localLoginEnabled.value = identityMode.localLoginEnabled === true || !iamRequired.value;
    if (iamRequired.value) mode.value = "IAM";
    identityReady.value = true;
  } catch {
    identityError.value = "无法确认身份接入状态，请刷新后重试。";
    return;
  }
  if (!localLoginEnabled.value) return;
  try {
    let response = await fetch("/api/v1/dev-login", { cache: "no-store" });
    if (
      !response.ok &&
      ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname)
    ) {
      response = await fetch("/__dev/quick-login", { cache: "no-store" });
    }
    if (!response.ok) return;
    const data = (await response.json()) as { accounts?: QuickAccount[] };
    if (Array.isArray(data.accounts)) quickAccounts.value = data.accounts;
  } catch {
    // 未启用开发快捷登录时保留标准登录表单。
  }
});

async function signIn() {
  await run(async () => {
    if (mode.value === "IAM")
      await props.iamSignIn(username.value, password.value, orgId.value);
    else await props.signIn(username.value, password.value);
    password.value = "";
  });
}

async function quickSignIn(account: QuickAccount) {
  if (!account.password) {
    await run(() => props.quickSignIn(account.username));
    return;
  }
  username.value = account.username;
  mode.value = "LOCAL";
  password.value = account.password;
  await signIn();
}
</script>

<template>
  <main class="login-screen">
    <div class="login-intro">
      <span class="eyebrow">B PROJECT / PLATFORM</span>
      <h1>让每一份记录<br />都有据可查。</h1>
      <p>可信存证与全流程溯源 · 平台公共能力</p>
      <div class="login-line">
        <span>业务事件</span><i>→</i><span>证据归档</span><i>→</i
        ><span>可信核验</span>
      </div>
    </div>
    <form class="login-card" @submit.prevent="signIn">
      <span class="eyebrow dark">{{ simulated ? "隔离测试环境" : "开发联调环境" }}</span>
      <p v-if="simulated" class="alert" role="status">{{ mode === 'IAM' ? '隔离测试 · 模拟 IAM 身份，仅用于 TRUST 联调验收' : '隔离测试 · 本地开发身份，不具备钱包管理权限' }}</p>
      <p v-if="identityError" class="alert error" role="alert">{{ identityError }}</p>
      <h2>登录工作台</h2>
      <label
        >身份来源<select v-model="mode">
          <option value="IAM">{{ simulated ? "模拟 IAM 测试账号" : "平台 IAM 账号" }}</option>
          <option v-if="localLoginEnabled" value="LOCAL">开发测试账号（本地）</option>
        </select></label
      >
      <label v-if="mode === 'IAM'"
        >授权业务范围<input v-model="orgId" required maxlength="80"
      /></label>
      <p class="small muted">
        钱包管理需使用 IAM 账号及对应权限。开发快捷登录不具备钱包管理权。
      </p>
      <section
        v-if="mode === 'LOCAL' && quickAccounts.length"
        class="quick-login"
        aria-labelledby="quick-login-title"
      >
        <div class="quick-login-heading">
          <strong id="quick-login-title">开发账号快捷登录</strong>
          <span>开发环境</span>
        </div>
        <div class="quick-account-grid">
          <button
            v-for="account in quickAccounts"
            :key="account.username"
            type="button"
            class="quick-account"
            :disabled="busy"
            :aria-label="`${accountNames[account.username] || account.username}快捷登录`"
            @click="quickSignIn(account)"
          >
            <strong>{{
              accountNames[account.username] || account.username
            }}</strong>
            <span>{{
              account.orgId === "B-PROJECT" ? "项目组织" : "外部组织"
            }}</span>
          </button>
        </div>
        <div class="login-divider"><span>或使用账号密码</span></div>
      </section>
      <label
        >账号<input
          v-model="username"
          autocomplete="username"
          required
          placeholder="请输入账号" /></label
      ><label
        >密码<input
          v-model="password"
          type="password"
          autocomplete="current-password"
          required
          placeholder="请输入密码"
      /></label>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <button class="primary full" :disabled="busy || !identityReady">
        {{ busy ? "正在登录…" : "进入工作台" }}
      </button>
    </form>
  </main>
</template>
