<script setup lang="ts">
import { ref } from "vue";
import type { ApiRecord } from "./shared/types";
import { provideFeedback } from "./shared/composables/useFeedback";
import { useSession } from "./features/auth/useSession";
import LoginPage from "./features/auth/LoginPage.vue";
import WorkspaceShell from "./app/WorkspaceShell.vue";
import EventLedgerPage from "./features/events/EventLedgerPage.vue";
import EventDetail from "./features/events/EventDetail.vue";
import EventEntryPage from "./features/events/EventEntryPage.vue";
import TracePage from "./features/trace/TracePage.vue";
import TasksPage from "./features/archiving/TasksPage.vue";
import OperationsPage from "./features/operations/OperationsPage.vue";
const { run } = provideFeedback();
const { me, canWrite, canAdmin, signIn, signOut, quickSignIn } = useSession();
const tab = ref("events"),
  selectedId = ref(""),
  correction = ref<ApiRecord | null>(null);
function navigate(id: string) {
  tab.value = id;
  selectedId.value = "";
}
function openEvent(id: string) {
  selectedId.value = id;
}
function correct(record: ApiRecord) {
  correction.value = record;
  navigate("create");
}
function saved(id: string) {
  correction.value = null;
  tab.value = "events";
  selectedId.value = id;
}
async function logout() {
  await run(async () => {
    await signOut();
    tab.value = "events";
    selectedId.value = "";
    correction.value = null;
  });
}
</script>

<template>
  <LoginPage v-if="!me" :sign-in="signIn" :quick-sign-in="quickSignIn" />
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
      @close="selectedId = ''"
      @open="openEvent"
      @correct="correct"
    />
    <KeepAlive>
      <EventLedgerPage
        v-if="!selectedId && tab === 'events'"
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
