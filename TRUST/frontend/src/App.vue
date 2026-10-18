<script setup lang="ts">
import { ref, watch } from "vue";
import type { ApiRecord } from "./shared/types";
import { provideFeedback } from "./shared/composables/useFeedback";
import { useSession } from "./features/auth/useSession";
import { preparePageRead, clearPageReads } from "./shared/http/actionContext";
import LoginPage from "./features/auth/LoginPage.vue";
import WorkspaceShell from "./app/WorkspaceShell.vue";
import EventLedgerPage from "./features/events/EventLedgerPage.vue";
import EventDetail from "./features/events/EventDetail.vue";
import EventEntryPage from "./features/events/EventEntryPage.vue";
import TracePage from "./features/trace/TracePage.vue";
import TasksPage from "./features/archiving/TasksPage.vue";
import OperationsPage from "./features/operations/OperationsPage.vue";
import IdentityPage from "./features/identities/IdentityPage.vue";
import WalletPage from "./features/wallet/WalletPage.vue";
const { run, act, busy, error, clearResults } = provideFeedback();
const entered = ref(false);
const entryPending = ref(false);
const { me, canWrite, canAdmin, signIn, signOut, quickSignIn, iamSignIn } =
  useSession();
const tab = ref("events"),
  selectedId = ref(""),
  correction = ref<ApiRecord | null>(null);
async function navigate(id: string) {
  await act({ action: "NAVIGATE", menuId: id, summary: "切换功能页面" }, () => {
    preparePageRead(id);
    tab.value = id;
    selectedId.value = "";
  });
}
async function openEvent(id: string) {
  await act(
    { action: "READ", menuId: "events", objectId: id, summary: "打开事件详情" },
    () => {
      preparePageRead(`event:${id}`);
      selectedId.value = id;
    },
  );
}
async function correct(record: ApiRecord) {
  await act(
    {
      action: "UPDATE",
      menuId: "create",
      objectId: record.id,
      summary: "开始追加更正",
    },
    () => {
      preparePageRead("create");
      correction.value = record;
      tab.value = "create";
      selectedId.value = "";
    },
  );
}
async function enter() {
  await act(
    { action: "NAVIGATE", menuId: "events", summary: "进入工作台" },
    () => {
      preparePageRead("events");
      entered.value = true;
    },
  );
}
watch(me, (user) => {
  entered.value = false;
  clearResults();
  clearPageReads();
  entryPending.value = Boolean(user);
});
watch([entryPending, busy], () => {
  if (!entryPending.value || busy.value) return;
  entryPending.value = false;
  void enter();
});
function saved(id: string) {
  preparePageRead(`event:${id}`);
  correction.value = null;
  tab.value = "events";
  selectedId.value = id;
}
async function logout() {
  await act(
    { action: "LOGOUT", menuId: tab.value, summary: "退出工作台" },
    async () => {
      await signOut();
      tab.value = "events";
      selectedId.value = "";
      correction.value = null;
    },
  );
}
</script>

<template>
  <LoginPage
    v-if="!me"
    :sign-in="signIn"
    :quick-sign-in="quickSignIn"
    :iam-sign-in="iamSignIn"
  />
  <section v-else-if="!entered" class="panel">
    <p role="alert">{{ error || "正在保存页面进入记录…" }}</p>
    <button :disabled="busy" @click="enter">重试进入工作台</button>
    <button :disabled="busy" @click="run(signOut)">退出登录（恢复入口）</button>
  </section>
  <WorkspaceShell
    v-else
    :me="me"
    :tab="tab"
    @navigate="navigate"
    @logout="logout"
  >
    <EventDetail
      v-if="selectedId"
      :id="selectedId"
      :can-write="canWrite"
      @close="navigate('events')"
      @open="openEvent"
      @correct="correct"
    />
    <KeepAlive>
      <IdentityPage v-if="!selectedId && tab === 'identities'" />
      <WalletPage v-else-if="!selectedId && tab === 'wallets'" :me="me" />
      <EventLedgerPage
        v-else-if="!selectedId && tab === 'events'"
        :can-write="canWrite"
        @open="openEvent"
        @create="navigate('create')"
      />
      <EventEntryPage
        v-else-if="!selectedId && tab === 'create'"
        :can-write="canWrite"
        :previous="correction"
        @saved="saved"
        @cancel="correction = null"
      />
      <TracePage v-else-if="!selectedId && tab === 'trace'" @open="openEvent" />
      <TasksPage
        v-else-if="!selectedId && tab === 'tasks'"
        :can-write="canWrite"
        @open="openEvent"
      />
      <OperationsPage
        v-else-if="!selectedId && tab === 'status'"
        :can-admin="canAdmin"
      />
    </KeepAlive>
  </WorkspaceShell>
</template>
