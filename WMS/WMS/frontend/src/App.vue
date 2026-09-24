<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { usePreferenceStore, THEME_OPTIONS, LAYOUT_OPTIONS } from '@/stores/preference'
import NavigationIcon from '@/components/NavigationIcon.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const prefs = usePreferenceStore()
const prefVisible = ref(false)
const collapsed = ref(false)
const isSideCollapsed = computed(() => prefs.layout === 'compact' || (prefs.layout === 'side' && collapsed.value))
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
  <div v-else class="app-shell admin-layout" :data-layout="prefs.layout" :class="{ collapsed: isSideCollapsed }">
    <aside v-if="prefs.layout !== 'top'" class="sidebar" aria-label="仓储管理系统导航">
      <div class="brand"><span class="brand-mark">W</span><strong>仓储管理系统</strong></div>
      <nav><RouterLink v-for="menu in auth.user?.menus" :key="menu.code" :to="menu.path" :aria-label="menu.name" :title="menu.name" :class="{ active: route.path === menu.path }"><NavigationIcon :path="menu.path" /><span class="menu-label">{{ menu.name }}</span></RouterLink></nav>
      <div class="sidebar-status"><span></span> openGauss 已连接</div>
      <button v-if="prefs.layout === 'side'" class="sidebar-collapse" type="button" :aria-expanded="!collapsed" :aria-label="collapsed ? '展开菜单' : '折叠菜单'" @click="collapsed = !collapsed">{{ collapsed ? '»' : '«' }}<span class="menu-label">折叠菜单</span></button>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div class="topbar-heading"><strong>{{ prefs.layout === 'top' ? 'W · 仓储管理系统' : route.meta.title }}</strong><small>{{ prefs.layout === 'top' ? route.meta.title : '仓储管理系统' }} / {{ auth.user?.companyName }}</small></div>
        <div class="topbar-actions">
          <el-button class="pref-btn" plain @click="prefVisible = true">外观设置</el-button>
          <button class="tenant" type="button" @click="tenantDialog = true">{{ auth.user?.tenant.currentWarehouse.name }} · {{ auth.user?.tenant.currentOwner.name }}</button>
          <el-dropdown trigger="click"><button class="user-trigger" type="button">{{ auth.user?.displayName }} ▾</button><template #dropdown><el-dropdown-menu><el-dropdown-item @click="passwordDialog = true">修改密码</el-dropdown-item><el-dropdown-item divided @click="signOut">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div>
      </header>
      <nav v-if="prefs.layout === 'top'" class="top-menu" aria-label="仓储管理系统顶部导航"><RouterLink v-for="menu in auth.user?.menus" :key="menu.code" :to="menu.path" :class="{ active: route.path === menu.path }"><NavigationIcon :path="menu.path" /><span>{{ menu.name }}</span></RouterLink></nav>
      <section class="content"><RouterView /></section>
    </main>

    <el-drawer v-model="prefVisible" title="外观设置" size="min(420px, 100vw)" class="preference-drawer">
      <div class="pref-section">
        <h3 class="pref-section-title">主题皮肤（4 套）</h3>
        <div class="theme-grid" role="group" aria-label="主题皮肤">
          <button v-for="t in THEME_OPTIONS" :key="t.id" type="button" class="theme-card" :class="{ active: prefs.theme === t.id }" :aria-pressed="prefs.theme === t.id" :data-theme-choice="t.id" @click="prefs.setTheme(t.id)">
            <span class="theme-swatch" aria-hidden="true"><span class="sw-sidebar" :style="{ background: t.colors[0] }"></span><span class="sw-primary" :style="{ background: t.colors[1] }"></span></span>
            <span class="theme-meta"><span class="theme-name">{{ t.name }}</span><span class="theme-desc">{{ t.desc }}</span></span><span v-if="prefs.theme === t.id" class="theme-check" aria-hidden="true">✓</span>
          </button>
        </div>
      </div>
      <div class="pref-section">
        <h3 class="pref-section-title">布局方式（3 种）</h3>
        <div class="layout-grid" role="group" aria-label="布局方式">
          <button v-for="l in LAYOUT_OPTIONS" :key="l.id" type="button" class="layout-card" :class="{ active: prefs.layout === l.id }" :aria-pressed="prefs.layout === l.id" :data-layout-choice="l.id" @click="prefs.setLayout(l.id)">
            <span class="layout-thumb" :data-thumb="l.id" aria-hidden="true"><span class="thumb-bar"></span><span class="thumb-body"><i></i><i></i></span></span><span class="layout-name">{{ l.name }}</span><span class="layout-desc">{{ l.desc }}</span>
          </button>
        </div>
      </div>
      <el-button plain class="reset-preferences" @click="prefs.reset(); collapsed = false">恢复默认（科技蓝 · 左侧菜单）</el-button>
      <p class="preference-note">设置仅保存在当前浏览器，不改变业务数据和其他系统的偏好。</p>
    </el-drawer>

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
