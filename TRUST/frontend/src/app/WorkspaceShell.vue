<script setup lang="ts">
import { ref } from "vue";
import type { ApiRecord } from "../shared/types";
import { useFeedback } from "../shared/composables/useFeedback";
import { nav, descriptions } from "./navigation";
import AppearancePanel from "./AppearancePanel.vue";
import { useAppearance } from "./appearance";

defineProps<{ me: ApiRecord; tab: string }>();
const emit = defineEmits<{ navigate: [id: string]; logout: [] }>();
const { busy, error, notice } = useFeedback();
const { preference } = useAppearance();
const appearanceOpen = ref(false);
</script>

<template>
  <div class="shell" :data-layout="preference.layout">
    <aside class="workspace-sidebar">
      <div class="brand">
        <div class="brand-mark">B</div>
        <div class="brand-copy">可信存证<span>平台公共能力</span></div>
      </div>
      <span class="aside-label">工作空间</span>
      <nav class="workspace-nav" aria-label="工作空间导航">
        <button
          v-for="[id, name, n] in nav"
          :key="id"
          :class="{ active: tab === id }"
          :title="preference.layout === 'compact' ? name : undefined"
          @click="emit('navigate', id)"
        >
          <span>{{ n }}</span
          ><b>{{ name }}</b>
        </button>
      </nav>
      <div class="aside-bottom">
        <span class="dot"></span> 独立开发环境
        <p>业务有记录 · 证据可核验</p>
      </div>
    </aside>

    <div class="workspace">
      <header class="workspace-topbar">
        <div class="top-layout-brand">
          <div class="brand-mark small">B</div>
          <strong>可信存证</strong>
        </div>
        <span class="breadcrumb">B项目 <b>/</b> 可信存证与溯源</span>
        <nav class="top-layout-nav" aria-label="工作空间导航">
          <button
            v-for="[id, name] in nav"
            :key="id"
            :class="{ active: tab === id }"
            @click="emit('navigate', id)"
          >
            {{ name }}
          </button>
        </nav>
        <div class="topbar-actions">
          <button
            class="appearance-trigger"
            type="button"
            aria-haspopup="dialog"
            @click="appearanceOpen = true"
          >
            外观设置
          </button>
          <span class="account">
            {{ me.username }} <span class="muted">· {{ me.orgId }}</span>
          </span>
          <button class="text-button" @click="emit('logout')">退出</button>
        </div>
      </header>

      <section class="content">
        <div class="page-heading">
          <div>
            <span class="eyebrow dark">TRUST &amp; TRACEABILITY</span>
            <h1>{{ nav.find((n) => n[0] === tab)?.[1] }}</h1>
            <p>{{ descriptions[tab] }}</p>
          </div>
          <span class="environment">开发联调 · v0.1</span>
        </div>
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>
        <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
        <div v-if="busy" class="progress" role="status">正在处理…</div>

        <slot />
      </section>
      <footer>
        可信存证与溯源 · B项目平台公共能力
        <span>上链核验与源头真实性校验分别承担不同责任。</span>
      </footer>
    </div>
  </div>

  <AppearancePanel :open="appearanceOpen" @close="appearanceOpen = false" />
</template>
