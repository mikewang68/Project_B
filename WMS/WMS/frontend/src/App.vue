<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const tenantDialog = ref(false)
const tenantSaving = ref(false)
const passwordDialog = ref(false)
const passwordSaving = ref(false)
const tenantForm = reactive({ warehouseCode: '', ownerCode: '' })
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const isLogin = computed(() => route.name === 'login')

watch(() => auth.user?.tenant, (tenant) => {
  if (!tenant) return
  tenantForm.warehouseCode = tenant.currentWarehouse.code
  tenantForm.ownerCode = tenant.currentOwner.code
}, { immediate: true })

watch(() => auth.user?.passwordChangeRequired, (required) => {
  if (required) passwordDialog.value = true
}, { immediate: true })

async function saveTenant(): Promise<void> {
  tenantSaving.value = true
  try {
    await auth.selectTenant(tenantForm.warehouseCode, tenantForm.ownerCode)
    tenantDialog.value = false
    ElMessage.success('业务范围已切换')
    if (route.path !== '/') await router.push('/')
  } catch (reason) {
    ElMessage.error(reason instanceof ApiError ? reason.message : '切换失败')
  } finally { tenantSaving.value = false }
}

async function savePassword(): Promise<void> {
  if (passwordForm.newPassword.length < 8) return void ElMessage.warning('新密码至少8位')
  if (passwordForm.newPassword !== passwordForm.confirmPassword) return void ElMessage.warning('两次新密码不一致')
  passwordSaving.value = true
  try {
    await auth.updatePassword(passwordForm.currentPassword, passwordForm.newPassword)
    passwordDialog.value = false
    Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmPassword: '' })
    ElMessage.success('密码修改成功')
  } catch (reason) {
    ElMessage.error(reason instanceof ApiError ? reason.message : '密码修改失败')
  } finally { passwordSaving.value = false }
}

async function signOut(): Promise<void> {
  await auth.signOut()
  await router.replace('/login')
}
</script>

<template>
  <RouterView v-if="isLogin" />
  <div v-else class="app-shell">
    <aside class="sidebar" aria-label="仓储系统导航">
      <div class="brand"><span class="brand-mark">W</span><div><strong>MT-WMS</strong><small>物资进出存云仓库</small></div></div>
      <nav><RouterLink v-for="menu in auth.user?.menus" :key="menu.code" :to="menu.path" :class="{ active: route.path === menu.path }">{{ menu.name }}</RouterLink></nav>
      <div class="sidebar-status"><span></span> openGauss 已连接</div>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div><strong>{{ route.meta.title }}</strong><small>MT-WMS / {{ auth.user?.companyName }}</small></div>
        <div class="topbar-actions">
          <button class="tenant" type="button" @click="tenantDialog = true">{{ auth.user?.tenant.currentWarehouse.name }} · {{ auth.user?.tenant.currentOwner.name }}</button>
          <el-dropdown trigger="click"><button class="user-trigger" type="button">{{ auth.user?.displayName }} ▾</button><template #dropdown><el-dropdown-menu><el-dropdown-item @click="passwordDialog = true">修改密码</el-dropdown-item><el-dropdown-item divided @click="signOut">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div>
      </header>
      <section class="content"><RouterView /></section>
    </main>

    <el-dialog v-model="tenantDialog" title="切换仓库与货主" width="460px">
      <el-form label-position="top">
        <el-form-item label="当前仓库"><el-select v-model="tenantForm.warehouseCode" style="width:100%"><el-option v-for="item in auth.user?.tenant.warehouses" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.code" /></el-select></el-form-item>
        <el-form-item label="当前货主"><el-select v-model="tenantForm.ownerCode" style="width:100%"><el-option v-for="item in auth.user?.tenant.owners" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.code" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="tenantDialog = false">取消</el-button><el-button type="primary" :loading="tenantSaving" @click="saveTenant">确定切换</el-button></template>
    </el-dialog>

    <el-dialog v-model="passwordDialog" title="修改登录密码" width="460px" :close-on-click-modal="!auth.user?.passwordChangeRequired" :close-on-press-escape="!auth.user?.passwordChangeRequired" :show-close="!auth.user?.passwordChangeRequired">
      <el-alert v-if="auth.user?.passwordChangeRequired" title="当前使用初始或兼容密码，请立即修改" type="warning" :closable="false" show-icon />
      <el-form label-position="top" class="password-form"><el-form-item label="原密码"><el-input v-model="passwordForm.currentPassword" type="password" show-password /></el-form-item><el-form-item label="新密码"><el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="至少8位" /></el-form-item><el-form-item label="确认新密码"><el-input v-model="passwordForm.confirmPassword" type="password" show-password /></el-form-item></el-form>
      <template #footer><el-button v-if="!auth.user?.passwordChangeRequired" @click="passwordDialog = false">取消</el-button><el-button type="primary" :loading="passwordSaving" @click="savePassword">修改密码</el-button></template>
    </el-dialog>
  </div>
</template>
