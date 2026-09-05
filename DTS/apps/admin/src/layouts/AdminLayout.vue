<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()

const collapsed = ref(false)

/**
 * 数字孪生系统自身的导航菜单。
 * 用户/角色/权限等管理能力已剥离到独立 IAM 系统（iam-system），此处不再包含。
 */
interface NavItem {
  id: string
  label: string
  icon: string
  path?: string
  children?: NavItem[]
}

const navMenu: NavItem[] = [
  {
    id: 'dt',
    label: '数字孪生系统',
    icon: 'DataAnalysis',
    children: [
      { id: 'dt-home', label: '三维作业台', icon: 'Monitor', path: '/' },
      { id: 'dt-screen', label: '综合态势大屏', icon: 'DataBoard', path: '/bigscreen' },
      { id: 'dt-calib', label: '落位校核', icon: 'Aim', path: '/calibration' },
    ],
  },
]

const activeMenu = computed(() => route.path)

function handleSelect(path: string) {
  if (path && path !== route.path) {
    router.push(path)
  }
}
</script>

<template>
  <div class="admin-layout" :class="{ collapsed }">
    <!-- 左侧菜单 -->
    <aside class="sidebar">
      <div class="sidebar-logo">
        <div class="logo-icon">DT</div>
        <span v-if="!collapsed" class="logo-text">B项目数字孪生</span>
      </div>

      <el-scrollbar class="sidebar-scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          :collapse-transition="false"
          background-color="#0d1424"
          text-color="#8aa8bd"
          active-text-color="#00b4d8"
          class="sidebar-menu"
          @select="handleSelect"
        >
          <template v-for="group in navMenu" :key="group.id">
            <el-sub-menu v-if="group.children && group.children.length > 1" :index="group.id">
              <template #title>
                <el-icon><component :is="group.icon" /></el-icon>
                <span>{{ group.label }}</span>
              </template>
              <el-menu-item
                v-for="item in group.children"
                :key="item.id"
                :index="item.path"
              >
                <el-icon><component :is="item.icon" /></el-icon>
                <span>{{ item.label }}</span>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item
              v-else-if="group.children && group.children.length === 1"
              :index="group.children[0].path"
            >
              <el-icon><component :is="group.children[0].icon" /></el-icon>
              <template #title>{{ group.children[0].label }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>

      <div class="sidebar-collapse" @click="collapsed = !collapsed">
        <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
      </div>
    </aside>

    <!-- 主内容区 -->
    <div class="main-area">
      <!-- 顶部栏 -->
      <header class="topbar">
        <div class="topbar-left">
          <span class="page-title">{{ route.meta.title || '数字孪生平台' }}</span>
        </div>
        <div class="topbar-right">
          <el-tooltip placement="bottom">
            <template #content>用户认证与功能权限已由独立 IAM 系统统一管理，当前为本地免登录运行模式</template>
            <el-tag type="info" size="small" effect="plain" class="mode-tag">
              <el-icon style="vertical-align:-2px;margin-right:4px"><Lock /></el-icon>本地运行 · 权限由 IAM 统一管理
            </el-tag>
          </el-tooltip>
        </div>
      </header>

      <!-- 内容 -->
      <main class="content">
        <RouterView v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.admin-layout {
  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: #f0f2f5;

  &.collapsed {
    .sidebar { width: 64px; }
    .logo-text { display: none; }
  }
}

.sidebar {
  width: 220px;
  background: #0d1424;
  display: flex;
  flex-direction: column;
  transition: width 0.25s;
  flex-shrink: 0;
}

.sidebar-logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.logo-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #00b4d8, #0077b6);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
}

.logo-text {
  font-size: 14px;
  color: #c8e0ec;
  font-weight: 600;
  white-space: nowrap;
}

.sidebar-scroll {
  flex: 1;
  overflow: hidden;
}

.sidebar-menu {
  border-right: none;
}

:deep(.el-menu) {
  border-right: none;
}

:deep(.el-sub-menu__title:hover),
:deep(.el-menu-item:hover) {
  background: rgba(0, 180, 216, 0.08) !important;
}

:deep(.el-menu-item.is-active) {
  background: rgba(0, 180, 216, 0.12) !important;
  border-right: 3px solid #00b4d8;
}

.sidebar-collapse {
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #5a7a90;
  cursor: pointer;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  transition: color 0.2s;

  &:hover { color: #00b4d8; }
}

.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.topbar {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.mode-tag {
  font-weight: 500;
  cursor: help;
}

.content {
  flex: 1;
  overflow: auto;
  padding: 20px;
  background: #f0f2f5;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
