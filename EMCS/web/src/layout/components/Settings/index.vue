<template>
  <el-drawer v-model="show" title="外观设置" size="360px" append-to-body>
    <div class="pref-section"><div class="pref-section-title">主题皮肤（4 套）</div><div class="theme-grid">
      <button v-for="t in THEME_OPTIONS" :key="t.id" class="theme-card" :class="{active:prefs.theme===t.id}" :aria-pressed="prefs.theme===t.id" @click="prefs.setTheme(t.id)">
        <div class="theme-swatch"><span class="sw-sidebar" :style="{background:t.colors[0]}"/><span class="sw-primary" :style="{background:t.colors[1]}"/></div>
        <div class="theme-meta"><div class="theme-name">{{t.name}}</div><div class="theme-desc">{{t.desc}}</div></div><el-icon v-if="prefs.theme===t.id" class="theme-check"><Select/></el-icon>
      </button>
    </div></div>
    <div class="pref-section"><div class="pref-section-title">布局方式（3 种）</div><div class="layout-grid">
      <button v-for="l in LAYOUT_OPTIONS" :key="l.id" class="layout-card" :class="{active:prefs.layout===l.id}" :aria-pressed="prefs.layout===l.id" @click="prefs.setLayout(l.id)">
        <div class="layout-thumb" :data-thumb="l.id"><span class="thumb-bar"/><span class="thumb-body"><i/><i/></span></div><div class="layout-name">{{l.name}}</div><div class="layout-desc">{{l.desc}}</div>
      </button>
    </div></div>
    <el-button plain style="width:100%" @click="prefs.reset()">恢复默认（科技蓝 · 左侧菜单）</el-button>
  </el-drawer>
</template>
<script setup>
import { usePreferenceStore, THEME_OPTIONS, LAYOUT_OPTIONS } from '@/stores/preference'
const prefs = usePreferenceStore()
const show = ref(false)
defineExpose({openSetting: () => {show.value=true}})
</script>
<style scoped lang="scss">
/* ---- 外观设置抽屉 ---- */
.pref-section { margin-bottom: 28px; }
.pref-section-title {
  font-size: 13px; font-weight: 600; color: var(--iam-text-strong); margin-bottom: 12px;
}
.theme-grid { display: flex; flex-direction: column; gap: 10px; }
.theme-card {
  position: relative; display: flex; align-items: center; gap: 12px;
  padding: 10px 12px; border: 1px solid var(--el-border-color);
  border-radius: 10px; cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
  &:hover { border-color: var(--iam-primary); }
  &.active { border-color: var(--iam-primary); box-shadow: 0 0 0 2px var(--el-color-primary-light-8); }
}
.theme-swatch {
  display: flex; width: 56px; height: 36px; border-radius: 6px;
  overflow: hidden; flex-shrink: 0; border: 1px solid rgba(0, 0, 0, 0.08);
  .sw-sidebar { width: 38%; height: 100%; }
  .sw-primary { flex: 1; height: 100%; }
}
.theme-meta { flex: 1; min-width: 0; }
.theme-name { font-size: 13px; font-weight: 600; color: var(--iam-text-strong); }
.theme-desc { font-size: 11px; color: var(--iam-text-muted); margin-top: 2px; }
.theme-check { color: var(--iam-primary); font-size: 16px; }

.layout-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.layout-card {
  border: 1px solid var(--el-border-color); border-radius: 10px;
  padding: 10px 8px; text-align: center; cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
  &:hover { border-color: var(--iam-primary); }
  &.active { border-color: var(--iam-primary); box-shadow: 0 0 0 2px var(--el-color-primary-light-8); }
}
.layout-thumb {
  height: 44px; border-radius: 4px; background: var(--el-fill-color-light);
  padding: 4px; display: flex; gap: 3px; margin-bottom: 6px; overflow: hidden;
  .thumb-bar { background: var(--iam-primary); border-radius: 2px; flex-shrink: 0; }
  .thumb-body {
    flex: 1; display: flex; flex-direction: column; gap: 3px;
    i { flex: 1; background: var(--el-color-primary-light-7); border-radius: 2px; }
  }
  &[data-thumb='side'] .thumb-bar { width: 26%; height: 100%; }
  &[data-thumb='compact'] {
    flex-direction: row;
    .thumb-bar { width: 14%; height: 100%; }
  }
  &[data-thumb='top'] {
    flex-direction: column;
    .thumb-bar { width: 100%; height: 26%; }
  }
}
.layout-name { font-size: 12px; font-weight: 600; color: var(--iam-text-strong); }
.layout-desc { font-size: 10px; color: var(--iam-text-muted); margin-top: 2px; }

.theme-card,.layout-card { background:var(--app-surface); font:inherit; text-align:left; }
</style>
