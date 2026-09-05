<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { Check, Loading } from '@element-plus/icons-vue'
import type { FenceRecord } from '@/types/fence'

const props = defineProps<{ modelValue: boolean; fence: FenceRecord | undefined }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; completed: [] }>()
const syncing = ref(false), synced = ref(0)
let timer: ReturnType<typeof setInterval> | undefined
const nodes = ['EDGE-01', 'EDGE-02', 'EDGE-03', 'EDGE-04']
const statusText = computed(() => synced.value === 4 ? '4 / 4 下发成功' : syncing.value ? `${synced.value} / 4 正在下发` : '等待确认发布')

function publish(): void {
  syncing.value = true; synced.value = 0
  timer = setInterval(() => {
    synced.value += 1
    if (synced.value === 4) { clearInterval(timer); timer = undefined; syncing.value = false; setTimeout(() => { emit('completed'); emit('update:modelValue', false) }, 450) }
  }, 430)
}
watch(() => props.modelValue, (open) => { if (open) { synced.value = 0; syncing.value = false } })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <ElDialog :model-value="modelValue" width="520px" class="fence-publish-dialog" :close-on-click-modal="!syncing" :show-close="!syncing" @update:model-value="$emit('update:modelValue', $event)">
    <template #header><div class="publish-dialog-heading"><span>EDGE RELEASE</span><h2>发布电子围栏</h2><p>围栏规则将下发到 4 个边缘节点</p></div></template>
    <div v-if="fence" class="publish-fence-summary"><small>即将发布</small><b>{{ fence.id }} {{ fence.name }}</b><span>版本 {{ fence.version }}</span></div>
    <div class="publish-node-list"><div v-for="(node, index) in nodes" :key="node"><span>{{ node }}</span><em v-if="index < synced"><el-icon><Check /></el-icon>成功</em><em v-else-if="syncing && index === synced" class="syncing"><el-icon class="is-loading"><Loading /></el-icon>下发中</em><em v-else>等待</em></div></div>
    <div class="publish-progress"><span :style="{ width: `${synced * 25}%` }"></span></div><p class="publish-status">{{ statusText }}</p>
    <template #footer><button type="button" :disabled="syncing" @click="$emit('update:modelValue', false)">取消</button><button type="button" class="primary" :disabled="syncing || synced === 4" @click="publish">确认发布</button></template>
  </ElDialog>
</template>
