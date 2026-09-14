<script setup lang="ts">
import type { ApiRecord } from "../../shared/types";
import { label } from "../../shared/presentation";
import { useFeedback } from "../../shared/composables/useFeedback";
import { downloadEvidence } from "./api";
defineProps<{ evidence: ApiRecord[] }>();
const { busy, run } = useFeedback();
</script>

<template>
  <h3>证据文件</h3>
  <div v-if="!evidence.length" class="empty small-empty">
    该事件未附证据文件，事件清单仍会归档存证。
  </div>
  <div v-for="ev in evidence" :key="ev.id" class="file-row">
    <div>
      <strong>{{ ev.filename }}</strong
      ><span
        >{{ (ev.size_bytes / 1024).toFixed(1) }} KiB ·
        {{ label(ev.storage_state) }}</span
      >
    </div>
    <button
      @click="run(() => downloadEvidence(ev.id, ev.filename))"
      :disabled="busy || !ev.cid"
    >
      下载
    </button>
  </div>
</template>
