<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import { readPage, writePage } from "./app/pageLocation";
import { nav } from "./app/navigation";
import { canOpenPage, provideAccess } from "./features/auth/access";
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
const initialPage = readPage();
const locationWarning = ref(initialPage.warning);
if (initialPage.warning) writePage("events");
const { me, signIn, signOut, quickSignIn, iamSignIn } =
  useSession();
const allowed = provideAccess(me);
function authorizedPage(page: string) {
  if (!me.value || canOpenPage(me.value, page)) return page;
  locationWarning.value = "当前账号无权访问所选页面，已返回可用页面。";
  const fallback = nav.find(([id]) => canOpenPage(me.value, id))?.[0] || "";
  writePage(fallback || "events");
  return fallback;
}
const tab = ref(initialPage.page),
  selectedId = ref(""),
  correction = ref<ApiRecord | null>(null);
let navigationVersion = 0;
let attemptedHistory = -1;
const pendingHistory = ref<(ReturnType<typeof readPage> & { version: number }) | null>(null);
async function navigate(id: string) {
  if (!canOpenPage(me.value, id)) return;
  const version = ++navigationVersion, user = me.value;
  await act({ action: "NAVIGATE", menuId: id, summary: "切换功能页面" }, () => {
    if (version !== navigationVersion || user !== me.value) return;
    preparePageRead(id);
    tab.value = id;
    writePage(id, "push");
    locationWarning.value = "";
    selectedId.value = "";
  });
}
async function openEvent(id: string) {
  if (!allowed("trust:event:read")) return;
  await act(
    { action: "READ", menuId: "events", objectId: id, summary: "打开事件详情" },
    () => {
      preparePageRead(`event:${id}`);
      selectedId.value = id;
    },
  );
}
async function correct(record: ApiRecord) {
  if (!allowed("trust:event:correct")) return;
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
      writePage("create");
      selectedId.value = "";
    },
  );
}
async function enter() {
  if (pendingHistory.value) {
    attemptedHistory = -1;
    await resumeHistory();
    return;
  }
  tab.value = authorizedPage(tab.value);
  if (!tab.value) { entered.value = true; return; }
  const version = navigationVersion, user = me.value, page = tab.value;
  await act(
    { action: "NAVIGATE", menuId: page, summary: "进入工作台" },
    () => {
      if (version !== navigationVersion || user !== me.value) return;
      preparePageRead(page);
      entered.value = true;
    },
  );
}
watch(me, (user) => {
  navigationVersion++;
  pendingHistory.value = null;
  const target = readPage();
  tab.value = authorizedPage(target.page);
  locationWarning.value = target.warning || locationWarning.value;
  if (target.warning) writePage("events");
  selectedId.value = "";
  correction.value = null;
  entered.value = false;
  clearResults();
  clearPageReads();
  entryPending.value = Boolean(user);
});
async function resumeHistory() {
  const target = pendingHistory.value, user = me.value;
  if (!target || !user || busy.value || attemptedHistory === target.version) return;
  attemptedHistory = target.version;
  const page = authorizedPage(target.page);
  if (!page) { tab.value = ""; pendingHistory.value = null; entered.value = true; return; }
  await act({ action: "NAVIGATE", menuId: page, summary: "恢复历史页面" }, () => {
    if (target.version !== navigationVersion || user !== me.value) return;
    preparePageRead(page);
    tab.value = page;
    locationWarning.value = target.warning || locationWarning.value;
    selectedId.value = "";
    correction.value = null;
    pendingHistory.value = null;
    entered.value = true;
  });
}
function restoreHistory() {
  const target = readPage();
  const version = ++navigationVersion;
  if (target.warning) writePage("events");
  entered.value = false;
  selectedId.value = "";
  correction.value = null;
  if (!me.value) {
    tab.value = target.page;
    locationWarning.value = target.warning;
    return;
  }
  pendingHistory.value = { ...target, version };
}
watch([pendingHistory, busy, me], () => { void resumeHistory(); });
onMounted(() => window.addEventListener("popstate", restoreHistory));
onBeforeUnmount(() => window.removeEventListener("popstate", restoreHistory));
watch([entryPending, busy], () => {
  if (!entryPending.value || busy.value) return;
  entryPending.value = false;
  void enter();
});
function saved(id: string) {
  correction.value = null;
  if (!allowed("trust:event:read")) {
    selectedId.value = "";
    tab.value = authorizedPage("create");
    writePage(tab.value);
    return;
  }
  preparePageRead(`event:${id}`);
  tab.value = "events";
  writePage("events");
  selectedId.value = id;
}
async function logout() {
  // The server records logout from its existing session and always clears it even when
  // audit/IAM is unavailable. A client audit receipt must not gate this recovery action.
  await run(async () => {
    await signOut();
    selectedId.value = "";
    correction.value = null;
  });
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
    <p v-if="locationWarning" class="alert" role="status">
      {{ locationWarning }}
    </p>
    <p v-if="!tab" class="panel" role="status">当前账号没有可访问的 TRUST 页面，请联系权限管理员。</p>
    <EventDetail
      v-if="selectedId"
      :id="selectedId"
      :can-write="allowed('trust:event:correct')"
      @close="navigate('events')"
      @open="openEvent"
      @correct="correct"
    />
    <KeepAlive>
      <IdentityPage v-if="!selectedId && tab === 'identities'" />
      <WalletPage v-else-if="!selectedId && tab === 'wallets'" :me="me" />
      <EventLedgerPage
        v-else-if="!selectedId && tab === 'events'"
        :can-write="allowed('trust:event:submit')"
        @open="openEvent"
        @create="navigate('create')"
      />
      <EventEntryPage
        v-else-if="!selectedId && tab === 'create'"
        :can-write="allowed(correction ? 'trust:event:correct' : 'trust:event:submit')"
        :previous="correction"
        @saved="saved"
        @cancel="correction = null"
      />
      <TracePage v-else-if="!selectedId && tab === 'trace'" @open="openEvent" />
      <TasksPage
        v-else-if="!selectedId && tab === 'tasks'"
        :can-write="allowed('trust:task:retry')"
        @open="openEvent"
      />
      <OperationsPage
        v-else-if="!selectedId && tab === 'status'"
        :can-admin="allowed('trust:audit:read')"
        :read-only="me.readOnly === true"
        :verified-identity="me.identityProvider === 'IAM' && !me.simulated"
      />
    </KeepAlive>
  </WorkspaceShell>
</template>
