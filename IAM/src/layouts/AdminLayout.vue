<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  usePreferenceStore,
  THEME_OPTIONS,
  LAYOUT_OPTIONS,
} from '@/stores/preference'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const prefs = usePreferenceStore()

// side 布局下可手动折叠；compact 固定窄栏；top 无侧栏
const collapsed = ref(false)
const isSideCollapsed = computed(() => prefs.layout === 'compact' || (prefs.layout === 'side' && collapsed.value))

interface NavItem {
  id: string
  label: string
  icon: string
  path: string
  perm: string
}

const navMenu: NavItem[] = [
  { id: 'iam-users', label: '用户管理', icon: 'UserFilled', path: '/system/users', perm: 'iam:user:list:view' },
  { id: 'iam-roles', label: '角色管理', icon: 'Avatar', path: '/system/roles', perm: 'iam:role:list:view' },
  { id: 'iam-menus', label: '菜单与权限', icon: 'Menu', path: '/system/menus', perm: 'iam:menu:tree:view' },
]

const filteredMenu = computed(() => navMenu.filter((item) => auth.permCodes.has(item.perm)))
const activeMenu = computed(() => route.path)

function handleSelect(path: string) {
  if (path && path !== route.path) router.push(path)
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出确认', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
    auth.logout()
    router.push('/login')
  } catch {
    /* 取消 */
  }
}

const roleNames = computed(() => auth.userRoles.map((r) => r.name).join('、') || '未分配角色')

// ---- 外观设置抽屉 ----
const prefVisible = ref(false)
</script>

<template>
  <div class="admin-layout" :data-layout="prefs.layout" :class="{ collapsed: isSideCollapsed }">
    <!-- 左侧菜单（side / compact 布局显示，top 布局隐藏） -->
    <aside v-show="prefs.layout !== 'top'" class="sidebar">
      <div class="sidebar-logo">
        <div class="logo-icon">IAM</div>
        <span v-if="!isSideCollapsed" class="logo-text">统一身份与权限</span>
      </div>

      <el-scrollbar class="sidebar-scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="isSideCollapsed"
          :collapse-transition="false"
          class="sidebar-menu"
          @select="handleSelect"
        >
          <el-menu-item v-for="item in filteredMenu" :key="item.id" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </el-menu>
      </el-scrollbar>

      <div v-if="prefs.layout === 'side'" class="sidebar-collapse" @click="collapsed = !collapsed">
        <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
      </div>
    </aside>

    <!-- 主内容区 -->
    <div class="main-area">
      <header class="topbar">
        <!-- top 布局：Logo + 横向菜单 -->
        <div v-if="prefs.layout === 'top'" class="topbar-brand">
          <div class="logo-icon logo-icon-sm">IAM</div>
          <span class="brand-name">统一身份与权限</span>
          <el-menu
            :default-active="activeMenu"
            mode="horizontal"
            class="top-menu"
            @select="handleSelect"
          >
            <el-menu-item v-for="item in filteredMenu" :key="item.id" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </el-menu-item>
          </el-menu>
        </div>
        <div v-else class="topbar-left">
          <span class="page-title">{{ route.meta.title || '统一身份与权限管理' }}</span>
        </div>

        <div class="topbar-right">
          <el-tooltip content="外观设置：切换皮肤与布局" placement="bottom">
            <el-button circle size="small" class="pref-btn" @click="prefVisible = true">
              <el-icon><Brush /></el-icon>
            </el-button>
          </el-tooltip>
          <el-tag v-if="auth.isSuperAdmin" type="danger" size="small" effect="dark" class="super-tag">超级管理员</el-tag>
          <el-tooltip :content="roleNames" placement="bottom">
            <span class="user-roles">{{ roleNames }}</span>
          </el-tooltip>
          <el-dropdown trigger="click">
            <div class="user-info">
              <el-avatar :size="32" class="user-avatar">{{ auth.currentUser?.name?.charAt(0) || 'U' }}</el-avatar>
              <div class="user-detail">
                <span class="user-name">{{ auth.currentUser?.name }}</span>
                <span class="user-account">{{ auth.currentUser?.username }}</span>
              </div>
              <el-icon><CaretBottom /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  <span style="font-size:12px">区块链ID: {{ auth.currentUser?.blockchainId || '未绑定' }}</span>
                </el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <RouterView v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </RouterView>
      </main>
    </div>

    <!-- 外观设置抽屉 -->
    <el-drawer v-model="prefVisible" title="外观设置" size="330px" direction="rtl">
      <div class="pref-section">
        <div class="pref-section-title">皮肤主题（4 套）</div>
        <div class="theme-grid">
          <div
            v-for="t in THEME_OPTIONS"
            :key="t.id"
            class="theme-card"
            :class="{ active: prefs.theme === t.id }"
            @click="prefs.setTheme(t.id)"
          >
            <div class="theme-swatch">
              <span class="sw-sidebar" :style="{ background: t.colors[0] }"></span>
              <span class="sw-primary" :style="{ background: t.colors[1] }"></span>
            </div>
            <div class="theme-meta">
              <div class="theme-name">{{ t.name }}</div>
              <div class="theme-desc">{{ t.desc }}</div>
            </div>
            <el-icon v-if="prefs.theme === t.id" class="theme-check"><Select /></el-icon>
          </div>
        </div>
      </div>

      <div class="pref-section">
        <div class="pref-section-title">布局方式（3 种）</div>
        <div class="layout-grid">
          <div
            v-for="l in LAYOUT_OPTIONS"
            :key="l.id"
            class="layout-card"
            :class="{ active: prefs.layout === l.id }"
            @click="prefs.setLayout(l.id)"
          >
            <div class="layout-thumb" :data-thumb="l.id">
              <span class="thumb-bar"></span>
              <span class="thumb-body"><i></i><i></i></span>
            </div>
            <div class="layout-name">{{ l.name }}</div>
            <div class="layout-desc">{{ l.desc }}</div>
          </div>
        </div>
      </div>

      <el-button plain style="width:100%;margin-top:8px" @click="prefs.reset()">恢复默认（科技蓝 · 左侧菜单）</el-button>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.admin-layout {
  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: var(--iam-content-bg);

  // side 布局折叠
  &.collapsed .sidebar {
    width: var(--iam-sidebar-collapsed);
  }
  // compact 布局：侧栏常驻窄栏
  &[data-layout='compact'] .sidebar {
    width: var(--iam-sidebar-collapsed);
  }
  // top 布局：无侧栏，主区全宽；顶栏单行，左侧品牌+横向菜单，右侧用户区
  &[data-layout='top'] {
    .topbar {
      padding: 0 24px;
      gap: 16px;
    }
    .topbar-brand {
      flex: 1;
      min-width: 0;
      display: flex;
      align-items: center;
      gap: 28px;
    }
    .top-menu {
      flex: 1;
    }
    .content {
      padding: 20px 24px;
    }
  }
}

/* ============ 侧边栏 ============ */
.sidebar {
  width: var(--iam-sidebar-width);
  background: var(--iam-sidebar-bg);
  display: flex;
  flex-direction: column;
  transition: width 0.25s;
  flex-shrink: 0;
}

.sidebar-logo {
  height: var(--iam-topbar-height);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  background: var(--iam-sidebar-bg-deep);
  border-bottom: 1px solid var(--iam-sidebar-divider);
  flex-shrink: 0;
}

.logo-icon {
  width: 40px;
  height: 32px;
  border-radius: var(--radius-sm);
  background: var(--iam-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
}
.logo-icon-sm {
  width: 34px;
  height: 28px;
  font-size: 12px;
}

.logo-text,
.brand-name {
  font-size: 14px;
  color: var(--iam-sidebar-active);
  font-weight: 600;
  white-space: nowrap;
}
.brand-name {
  color: var(--iam-text-strong);
}

.sidebar-scroll {
  flex: 1;
  overflow: hidden;
}

.sidebar-menu {
  border-right: none;
  background: transparent;

  :deep(.el-menu) {
    border-right: none;
    background: transparent;
  }
  :deep(.el-menu-item) {
    color: var(--iam-sidebar-text);
  }
  :deep(.el-menu-item:hover) {
    background: var(--iam-sidebar-hover-bg) !important;
    color: var(--iam-sidebar-active);
  }
  :deep(.el-menu-item.is-active) {
    color: var(--iam-sidebar-active);
    background: var(--iam-sidebar-active-bg) !important;
    font-weight: 600;
  }
  // 折叠态 tooltip 与弹出菜单跟随
  :deep(.el-menu--collapse .el-menu-item) {
    justify-content: center;
  }
}

.sidebar-collapse {
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--iam-sidebar-text);
  cursor: pointer;
  background: var(--iam-sidebar-bg-deep);
  border-top: 1px solid var(--iam-sidebar-divider);
  transition: color 0.2s;
  &:hover {
    color: var(--iam-sidebar-active);
  }
}

/* ============ 主区 / 顶栏 ============ */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.topbar {
  height: var(--iam-topbar-height);
  background: var(--iam-topbar-bg);
  border-bottom: 1px solid var(--iam-topbar-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.topbar-left {
  display: flex;
  align-items: center;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--iam-text-strong);
}

// 顶部横向菜单（top 布局）
.top-menu {
  border-bottom: none !important;
  background: transparent !important;
  height: 56px;

  :deep(.el-menu-item) {
    color: var(--iam-text-base);
    border-bottom: 3px solid transparent;
    height: 56px;
    line-height: 56px;
  }
  :deep(.el-menu-item:hover) {
    color: var(--iam-primary);
    background: transparent;
  }
  :deep(.el-menu-item.is-active) {
    color: var(--iam-primary);
    border-bottom-color: var(--iam-primary);
    background: transparent;
  }
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.pref-btn {
  color: var(--iam-primary);
  border-color: var(--iam-primary);
}

.super-tag {
  font-weight: 500;
}

.user-roles {
  font-size: 12px;
  color: var(--iam-text-muted);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: help;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
  transition: background 0.2s;
  &:hover {
    background: var(--el-fill-color-light);
  }
}

.user-avatar {
  background: var(--iam-primary);
  color: #fff;
  font-weight: 600;
}

.user-detail {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.user-name {
  font-size: 13px;
  color: var(--iam-text-strong);
  font-weight: 500;
}
.user-account {
  font-size: 11px;
  color: var(--iam-text-muted);
}

.content {
  flex: 1;
  overflow: auto;
  padding: 20px;
  background: var(--iam-content-bg);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ============ 外观设置抽屉 ============ */
.pref-section {
  margin-bottom: 28px;
}
.pref-section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--iam-text-strong);
  margin-bottom: 12px;
}

.theme-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.theme-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  cursor: pointer;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
  &:hover {
    border-color: var(--iam-primary);
  }
  &.active {
    border-color: var(--iam-primary);
    box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
  }
}
.theme-swatch {
  display: flex;
  width: 56px;
  height: 36px;
  border-radius: 6px;
  overflow: hidden;
  flex-shrink: 0;
  border: 1px solid rgba(0, 0, 0, 0.08);
  .sw-sidebar {
    width: 38%;
    height: 100%;
  }
  .sw-primary {
    flex: 1;
    height: 100%;
  }
}
.theme-meta {
  flex: 1;
  min-width: 0;
}
.theme-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--iam-text-strong);
}
.theme-desc {
  font-size: 11px;
  color: var(--iam-text-muted);
  margin-top: 2px;
}
.theme-check {
  color: var(--iam-primary);
  font-size: 16px;
}

.layout-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.layout-card {
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  padding: 10px 8px;
  text-align: center;
  cursor: pointer;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
  &:hover {
    border-color: var(--iam-primary);
  }
  &.active {
    border-color: var(--iam-primary);
    box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
  }
}
.layout-thumb {
  height: 44px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  padding: 4px;
  display: flex;
  gap: 3px;
  margin-bottom: 6px;
  overflow: hidden;
  .thumb-bar {
    background: var(--iam-primary);
    border-radius: 2px;
    flex-shrink: 0;
  }
  .thumb-body {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 3px;
    i {
      flex: 1;
      background: var(--el-color-primary-light-7);
      border-radius: 2px;
    }
  }
  // side：左竖条
  &[data-thumb='side'] .thumb-bar {
    width: 26%;
    height: 100%;
  }
  // compact：更窄左竖条
  &[data-thumb='compact'] {
    flex-direction: row;
    .thumb-bar {
      width: 14%;
      height: 100%;
    }
  }
  // top：顶部横条（纵向排列）
  &[data-thumb='top'] {
    flex-direction: column;
    .thumb-bar {
      width: 100%;
      height: 26%;
    }
  }
}
.layout-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--iam-text-strong);
}
.layout-desc {
  font-size: 10px;
  color: var(--iam-text-muted);
  margin-top: 2px;
}
</style>
