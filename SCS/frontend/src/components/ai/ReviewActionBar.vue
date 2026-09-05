<script setup lang="ts">
import { Check, CircleClose, QuestionFilled, Promotion, RefreshRight, Lock } from '@element-plus/icons-vue'
import type { AiEvent } from '@/types/ai'

const props = defineProps<{ event: AiEvent }>()
const emit = defineEmits<{
  confirm: []
  falseReport: []
  uncertain: []
  assign: []
  processing: []
  close: []
}>()

const actionable = (s: AiEvent['status']): boolean => ['待复核', '不确定', '已确认违规', '已派单', '处理中'].includes(s)
</script>

<template>
  <div class="review-action-bar">
    <template v-if="event.status === '待复核'">
      <button type="button" @click="emit('falseReport')"><el-icon><CircleClose /></el-icon>标记误报</button>
      <button type="button" @click="emit('uncertain')"><el-icon><QuestionFilled /></el-icon>暂不确定</button>
      <button type="button" class="danger" @click="emit('confirm')"><el-icon><Check /></el-icon>确认违规</button>
    </template>

    <template v-else-if="event.status === '不确定'">
      <button type="button" @click="emit('falseReport')"><el-icon><CircleClose /></el-icon>标记误报</button>
      <button type="button" class="danger" @click="emit('confirm')"><el-icon><Check /></el-icon>确认违规</button>
      <button type="button" class="primary" @click="emit('assign')"><el-icon><Promotion /></el-icon>派单处理</button>
    </template>

    <template v-else-if="event.status === '已确认违规'">
      <button type="button" class="primary" @click="emit('assign')"><el-icon><Promotion /></el-icon>派单处理</button>
    </template>

    <template v-else-if="event.status === '已派单'">
      <span class="review-action-bar__note">责任人：{{ event.assignee }} · 处理状态：{{ event.processStatus }}</span>
      <button type="button" class="primary" @click="emit('processing')"><el-icon><RefreshRight /></el-icon>标记处理中</button>
    </template>

    <template v-else-if="event.status === '处理中'">
      <span class="review-action-bar__note">{{ event.assignee }} 正在现场处置</span>
      <button type="button" class="primary" @click="emit('close')"><el-icon><Lock /></el-icon>关闭事件</button>
    </template>

    <p v-else class="review-action-bar__final">
      当前状态为「{{ event.status }}」，{{ actionable(event.status) ? '' : '复核流程已结束' }}
      <template v-if="event.reviewer">复核人：{{ event.reviewer }} · {{ event.reviewTime }}</template>
    </p>
  </div>
</template>
