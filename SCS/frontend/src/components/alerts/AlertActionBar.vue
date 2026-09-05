<script setup lang="ts">
import { Check, Promotion, Switch, VideoPlay, Promotion as Linkage, DocumentChecked, Lock, Top } from '@element-plus/icons-vue'
import type { AlertEvent } from '@/types/alert'

const props = defineProps<{ alert: AlertEvent; actionPending?: boolean }>()
const emit = defineEmits<{
  confirm: []
  assign: []
  transfer: []
  start: []
  treat: []
  linkage: []
  review: []
  escalate: []
}>()

const severe = (a: AlertEvent): boolean => a.risk === '严重' || a.risk === '紧急'
</script>

<template>
  <div class="alert-action-bar">
    <!-- 待确认 -->
    <template v-if="alert.status === '待确认'">
      <button v-if="alert.linkageAvailable && severe(alert)" type="button" :disabled="actionPending" @click="emit('linkage')">
        <el-icon><Linkage /></el-icon>发起联动
      </button>
      <button type="button" class="primary" :disabled="actionPending" @click="emit('confirm')"><el-icon><Check /></el-icon>确认事件</button>
    </template>

    <!-- 已确认 / 待派单 -->
    <template v-else-if="alert.status === '已确认' || alert.status === '待派单'">
      <button v-if="alert.linkageAvailable && severe(alert) && !alert.linkageFinished" type="button" :disabled="actionPending" @click="emit('linkage')">
        <el-icon><Linkage /></el-icon>发起联动
      </button>
      <button type="button" class="primary" :disabled="actionPending" @click="emit('assign')"><el-icon><Promotion /></el-icon>派单</button>
    </template>

    <!-- 待处理 -->
    <template v-else-if="alert.status === '待处理'">
      <button type="button" :disabled="actionPending" @click="emit('transfer')"><el-icon><Switch /></el-icon>转派</button>
      <button type="button" :disabled="actionPending" @click="emit('escalate')"><el-icon><Top /></el-icon>事件升级</button>
      <button type="button" class="primary" :disabled="actionPending" @click="emit('start')"><el-icon><VideoPlay /></el-icon>开始处理</button>
    </template>

    <!-- 处理中 / 已升级 -->
    <template v-else-if="alert.status === '处理中' || alert.status === '已升级'">
      <button type="button" :disabled="actionPending" @click="emit('transfer')"><el-icon><Switch /></el-icon>转派</button>
      <button type="button" :disabled="actionPending" @click="emit('escalate')"><el-icon><Top /></el-icon>事件升级</button>
      <button v-if="alert.linkageAvailable && severe(alert) && !alert.linkageFinished && !alert.linkageFailed" type="button" :disabled="actionPending" @click="emit('linkage')">
        <el-icon><Linkage /></el-icon>发起联动
      </button>
      <button type="button" class="primary" :disabled="actionPending" @click="emit('treat')"><el-icon><DocumentChecked /></el-icon>提交处置结果</button>
    </template>

    <!-- 待复核：严重/紧急必须复核 -->
    <template v-else-if="alert.status === '待复核'">
      <span class="alert-action-bar__note">处置结果已提交，需管理复核后关闭</span>
      <button type="button" class="primary" :disabled="actionPending" @click="emit('review')"><el-icon><Lock /></el-icon>安全复核</button>
    </template>

    <!-- 已关闭 -->
    <template v-else>
      <p class="alert-action-bar__final">
        事件已关闭<template v-if="alert.reviewUser"> · 复核人 {{ alert.reviewUser }} · {{ alert.reviewTime }}</template>
      </p>
    </template>
  </div>
</template>
