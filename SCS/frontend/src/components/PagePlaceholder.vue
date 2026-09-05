<script setup lang="ts">
import { ArrowRight, Grid, Tools } from '@element-plus/icons-vue'

defineProps<{ title: string; description: string; section: string }>()
</script>

<template>
  <section class="page-content placeholder-page">
    <article class="placeholder-card">
      <div class="placeholder-icon"><el-icon><Tools /></el-icon></div>
      <span>{{ section }}</span>
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
      <div class="placeholder-note">
        <el-icon><Grid /></el-icon>
        <div><b>页面框架已就绪</b><small>具体业务能力将在后续阶段按模块逐步实现</small></div>
        <el-icon><ArrowRight /></el-icon>
      </div>
    </article>
  </section>
</template>
