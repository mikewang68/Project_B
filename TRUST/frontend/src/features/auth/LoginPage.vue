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
}>();
const username = ref(""),
  password = ref("");
const quickAccounts = ref<QuickAccount[]>([]);
const { busy, error, run } = useFeedback();

const accountNames: Record<string, string> = {
  admin: "管理员",
  editor: "录入员",
  viewer: "查询员",
  "external-viewer": "外部查询员",
};

onMounted(async () => {
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
    await props.signIn(username.value, password.value);
    password.value = "";
  });
}

async function quickSignIn(account: QuickAccount) {
  if (!account.password) {
    await run(() => props.quickSignIn(account.username));
    return;
  }
  username.value = account.username;
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
      <span class="eyebrow dark">开发联调环境</span>
      <h2>登录工作台</h2>
      <section
        v-if="quickAccounts.length"
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
          placeholder="请输入开发账号" /></label
      ><label
        >密码<input
          v-model="password"
          type="password"
          autocomplete="current-password"
          required
          placeholder="请输入密码"
      /></label>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <button class="primary full" :disabled="busy">
        {{ busy ? "正在登录…" : "进入工作台" }}
      </button>
    </form>
  </main>
</template>
