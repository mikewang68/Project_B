<script setup lang="ts">
import type { ApiRecord } from "../../shared/types";
defineProps<{ check: ApiRecord | null }>();
</script>

<template>
  <article v-if="check" class="panel">
    <h2>{{ check.ok ? "核验通过" : "核验未通过" }}</h2>
    <div v-for="(c, i) in check.checks" :key="i" class="check-row">
      <span :class="c.ok ? 'check-ok' : 'check-fail'"
        >{{ c.ok ? "✓" : "!" }} {{ c.item }}</span
      ><span class="muted">{{ c.message || "一致" }}</span>
    </div>
    <p class="muted small">{{ check.meaning }}</p>
  </article>
</template>
