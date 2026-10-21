<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useFeedback } from "../shared/composables/useFeedback";
import { useAppearance } from "./appearance";

const { act, busy } = useFeedback();
const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: [] }>();
const panel = ref<HTMLElement | null>(null);
const { preference, themes, layouts, setTheme, setLayout, reset } =
  useAppearance();

function handleKeydown(event: KeyboardEvent) {
  if (props.open && event.key === "Escape") emit("close");
}

watch(
  () => props.open,
  async (open) => {
    document.body.classList.toggle("appearance-open", open);
    if (open) {
      await nextTick();
      panel.value?.focus();
    }
  },
);

onMounted(() => window.addEventListener("keydown", handleKeydown));
onBeforeUnmount(() => {
  window.removeEventListener("keydown", handleKeydown);
  document.body.classList.remove("appearance-open");
});
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="appearance-overlay" @mousedown.self="emit('close')">
      <aside
        ref="panel"
        class="appearance-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="appearance-title"
        tabindex="-1"
      >
        <div class="appearance-heading">
          <h2 id="appearance-title">外观设置</h2>
          <button
            class="appearance-close"
            type="button"
            aria-label="关闭外观设置"
            @click="emit('close')"
          >
            ×
          </button>
        </div>

        <section class="appearance-section" aria-labelledby="theme-title">
          <h3 id="theme-title">皮肤主题（4 套）</h3>
          <div class="theme-options">
            <button
              v-for="theme in themes"
              :key="theme.id"
              type="button"
              class="theme-option"
              :class="{ active: preference.theme === theme.id }"
              :aria-pressed="preference.theme === theme.id"
              :disabled="busy"
              @click="
                act(
                  {
                    action: 'UPDATE',
                    menuId: 'appearance',
                    summary: '修改外观主题',
                  },
                  () => setTheme(theme.id),
                )
              "
            >
              <span class="theme-swatch" aria-hidden="true">
                <i :style="{ background: theme.colors[0] }"></i>
                <i :style="{ background: theme.colors[1] }"></i>
              </span>
              <span class="option-copy">
                <strong>{{ theme.name }}</strong>
                <small>{{ theme.description }}</small>
              </span>
              <span
                v-if="preference.theme === theme.id"
                class="option-check"
                aria-hidden="true"
                >✓</span
              >
            </button>
          </div>
        </section>

        <section class="appearance-section" aria-labelledby="layout-title">
          <h3 id="layout-title">布局方式（3 种）</h3>
          <div class="layout-options">
            <button
              v-for="layout in layouts"
              :key="layout.id"
              type="button"
              class="layout-option"
              :class="{ active: preference.layout === layout.id }"
              :aria-pressed="preference.layout === layout.id"
              :disabled="busy"
              @click="
                act(
                  {
                    action: 'UPDATE',
                    menuId: 'appearance',
                    summary: '修改页面布局',
                  },
                  () => setLayout(layout.id),
                )
              "
            >
              <span
                class="layout-thumb"
                :data-thumb="layout.id"
                aria-hidden="true"
              >
                <i class="thumb-bar"></i
                ><i class="thumb-body"><b></b><b></b></i>
              </span>
              <strong>{{ layout.name }}</strong>
              <small>{{ layout.description }}</small>
            </button>
          </div>
        </section>

        <button
          class="appearance-reset"
          type="button"
          :disabled="busy"
          @click="
            act(
              {
                action: 'UPDATE',
                menuId: 'appearance',
                summary: '恢复默认外观',
              },
              reset,
            )
          "
        >
          恢复默认（科技蓝 · 左侧菜单）
        </button>
      </aside>
    </div>
  </Teleport>
</template>
