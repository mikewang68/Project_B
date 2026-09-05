<script setup lang="ts">
const props = defineProps<{ status: string }>()

function tone(): string {
  if (['已生效', '在线', '正常', 'success'].includes(props.status)) return 'success'
  if (['紧急', '高风险', '危险', '版本不一致', 'failed'].includes(props.status)) return 'danger'
  if (['待评审', '待发布', '低电量', '关注', 'warning'].includes(props.status)) return 'warning'
  if (['草稿', '已停用', '离线', '无信号'].includes(props.status)) return 'muted'
  return 'primary'
}
</script>

<template><span class="status-badge" :data-tone="tone()"><i></i>{{ status }}</span></template>
