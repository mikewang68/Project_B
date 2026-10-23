<template>
  <el-alert v-if="userStore.readOnly" title="当前可信身份为只读：写入与敏感导出已禁用。" type="info" :closable="false" />
  <router-view />
</template>

<script setup>
import useUserStore from '@/store/modules/user'
const userStore = useUserStore()
import useSettingsStore from '@/store/modules/settings'
import { handleThemeStyle } from '@/utils/theme'

onMounted(() => {
  nextTick(() => {
    // 初始化主题样式
    handleThemeStyle(useSettingsStore().theme)
  })
})
</script>
