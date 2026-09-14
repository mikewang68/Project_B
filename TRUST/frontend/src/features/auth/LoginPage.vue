<script setup lang="ts">
import { ref } from "vue";
import { useFeedback } from "../../shared/composables/useFeedback";
const props = defineProps<{
  signIn: (username: string, password: string) => Promise<void>;
}>();
const username = ref(""),
  password = ref("");
const { busy, error, run } = useFeedback();
async function signIn() {
  await run(async () => {
    await props.signIn(username.value, password.value);
    password.value = "";
  });
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
      <p class="small muted">账号信息由项目开发环境单独提供。</p>
    </form>
  </main>
</template>
