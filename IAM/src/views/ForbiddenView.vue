<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

function goHome() {
  // 回到当前用户实际有权限的落地页，避免无 / 权限时再次被踢回 403
  router.push(auth.landingPath)
}

async function goLogin() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="forbidden-page">
    <div class="forbidden-card">
      <div class="forbidden-code">403</div>
      <h1>无权限访问</h1>
      <p>您当前的账号没有访问该页面的权限，请联系管理员分配相应角色与权限。</p>
      <div class="user-info">
        <span>当前用户：{{ auth.currentUser?.name }}（{{ auth.currentUser?.username }}）</span>
        <span>角色：{{ auth.userRoles.map((r) => r.name).join('、') || '未分配' }}</span>
      </div>
      <div class="actions">
        <el-button type="primary" @click="goHome">返回首页</el-button>
        <el-button @click="goLogin">切换账号</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.forbidden-page {
  width: 100vw;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--app-page-bg);
}

.forbidden-card {
  text-align: center;
  max-width: 480px;
  padding: 48px 40px;
  background: var(--app-surface);
  border: 1px solid var(--app-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--app-card-shadow);
}

.forbidden-code {
  font-size: 96px;
  font-weight: 800;
  color: var(--app-color-primary);
  line-height: 1;
  margin-bottom: 16px;
}

h1 {
  font-size: 24px;
  margin: 0 0 12px;
  color: var(--app-text-title);
}

p {
  font-size: 14px;
  color: var(--app-text-regular);
  line-height: 1.6;
  margin: 0 0 24px;
}

.user-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px;
  background: var(--app-surface-secondary);
  border: 1px solid var(--app-divider-color);
  border-radius: var(--radius-md);
  margin-bottom: 24px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}
</style>
