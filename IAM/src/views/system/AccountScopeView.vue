<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import { LEVEL_LABELS } from '@/iam/project-workspace'
const auth = useAuthStore()
</script>

<template>
  <el-card v-if="auth.userContext">
    <template #header><h2>我的权限范围</h2></template>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="用户">{{ auth.userContext.displayName }}</el-descriptions-item>
      <el-descriptions-item label="管理层级">{{ LEVEL_LABELS[auth.userContext.adminLevel] }}</el-descriptions-item>
      <el-descriptions-item label="项目">{{ auth.userContext.projectId || '全局' }}</el-descriptions-item>
      <el-descriptions-item label="区域">{{ auth.userContext.regionId || '按已授权范围' }}</el-descriptions-item>
      <el-descriptions-item label="子系统">{{ auth.userContext.subsystemCode || '按已授权范围' }}</el-descriptions-item>
      <el-descriptions-item label="操作模式">{{ auth.userContext.readOnly ? '只读' : '按岗位授权' }}</el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="auth.userContext.scopeMode === 'DENY'" type="warning" :closable="false" title="当前没有有效业务范围，请联系上级管理员。" style="margin-top:20px" />
    <h3>当前有效菜单与动作</h3>
    <el-empty v-if="!Object.keys(auth.userContext.menuOperations).length" description="当前没有业务动作授权" />
    <el-table v-else :data="Object.entries(auth.userContext.menuOperations).map(([menu,actions]) => ({ menu, actions }))">
      <el-table-column prop="menu" label="菜单" />
      <el-table-column label="动作"><template #default="{ row }">{{ row.actions.join('、') }}</template></el-table-column>
    </el-table>
  </el-card>
</template>
