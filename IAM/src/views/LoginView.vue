<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const demoAccounts = [
  { label: '系统管理员', username: 'admin', password: 'Admin@123', desc: '全部权限' },
  { label: '调度管理员', username: 'dispatcher01', password: 'Dispatch@123', desc: '无IAM后台权限' },
  { label: '运维工程师', username: 'operator01', password: 'Operate@123', desc: '无IAM后台权限' },
  { label: '现场操作员', username: 'field01', password: 'Field@123', desc: '无IAM后台权限' },
  { label: '大屏访客', username: 'viewer', password: 'Viewer@123', desc: '只读业务' },
]

function fillAccount(acc: { username: string; password: string }) {
  form.username = acc.username
  form.password = acc.password
}

async function handleLogin() {
  if (!formRef.value) return
  await formRef.value.validate((valid) => {
    if (!valid) return
    loading.value = true
    // 模拟网络延迟
    setTimeout(() => {
      const result = auth.login({ username: form.username, password: form.password })
      loading.value = false
      if (result.success) {
        ElMessage.success(`欢迎，${result.user?.name}`)
        // 优先跳回原目标页；但需校验该页当前用户是否有权限，避免"登录即 403"；
        // 无 redirect 或 redirect 无权限时，落到第一个有权限的 IAM 页面
        const redirect = router.currentRoute.value.query.redirect as string
        let target = auth.landingPath
        if (redirect) {
          const needPerm = router.resolve(redirect).meta?.perm as string | undefined
          if (!needPerm || auth.isSuperAdmin || auth.permCodes.has(needPerm)) {
            target = redirect
          }
        }
        router.push(target)
      } else {
        ElMessage.error(result.message || '登录失败')
      }
    }, 400)
  })
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌区 -->
    <div class="login-brand">
      <div class="brand-logo">
        <div class="logo-icon">IAM</div>
        <div class="logo-text">
          <h1>B 项目统一身份与权限管理系统</h1>
          <p>Identity & Access Management</p>
        </div>
      </div>
      <div class="brand-features">
        <div class="feature-item">
          <span class="feature-dot"></span>
          <span>用户 · 角色 · 权限 统一管理</span>
        </div>
        <div class="feature-item">
          <span class="feature-dot"></span>
          <span>模块访问 + 增删改查执行 细粒度授权</span>
        </div>
        <div class="feature-item">
          <span class="feature-dot"></span>
          <span>前端动态菜单与按钮级权限闭环</span>
        </div>
        <div class="feature-item">
          <span class="feature-dot feature-dot-reserved"></span>
          <span>区块链身份 ID 预留（后续操作上链溯源）</span>
        </div>
      </div>
      <div class="brand-footer">
        <p>© 2026 B 项目 · 统一身份与权限管理系统（IAM）</p>
      </div>
    </div>

    <!-- 右侧登录表单 -->
    <div class="login-form-wrap">
      <div class="login-card">
        <h2>账号登录</h2>
        <p class="login-subtitle">请使用已授权的管理员账号登录</p>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码" prefix-icon="Lock" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
              登 录
            </el-button>
          </el-form-item>
        </el-form>

        <div class="demo-section">
          <div class="demo-title">演示账号（点击快速填充）</div>
          <div class="demo-grid">
            <div
              v-for="acc in demoAccounts"
              :key="acc.username"
              class="demo-account"
              @click="fillAccount(acc)"
            >
              <div class="demo-acc-name">{{ acc.label }}</div>
              <div class="demo-acc-user">{{ acc.username }}</div>
              <div class="demo-acc-desc">{{ acc.desc }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: var(--app-page-bg);
}

/* 左侧深海军蓝品牌区（统一导航色 #24364A → #1F3043 同族渐变） */
.login-brand {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 56px;
  background: linear-gradient(135deg, #24364a 0%, #1f3043 100%);
  position: relative;
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    top: -50%;
    right: -30%;
    width: 80%;
    height: 200%;
    background: radial-gradient(ellipse, rgba(64, 158, 255, 0.07) 0%, transparent 70%);
    pointer-events: none;
  }
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 16px;
  position: relative;
  z-index: 1;
}

.logo-icon {
  width: 56px;
  height: 56px;
  border-radius: var(--radius-md);
  background: var(--app-color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 1px;
  box-shadow: 0 4px 14px rgba(31, 45, 61, 0.28);
}

.logo-text h1 {
  font-size: 22px;
  color: #ffffff;
  margin: 0;
  font-weight: 600;
}

.logo-text p {
  font-size: 13px;
  color: var(--app-nav-text);
  margin: 4px 0 0;
}

.brand-features {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--app-nav-text);
  font-size: 15px;
}

.feature-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-color-primary);
  flex-shrink: 0;
}

.feature-dot-reserved {
  background: var(--app-color-warning);
}

.brand-footer {
  position: relative;
  z-index: 1;
  color: var(--app-nav-text-secondary);
  font-size: 12px;
}

/* 右侧浅灰蓝底 + 白色登录卡片 */
.login-form-wrap {
  width: 480px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: var(--app-page-bg);
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 36px 32px;
  background: var(--app-surface);
  border: 1px solid var(--app-border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--app-card-shadow);
}

.login-card h2 {
  font-size: 26px;
  color: var(--app-text-title);
  margin: 0 0 8px;
  font-weight: 600;
}

.login-subtitle {
  font-size: 13px;
  color: var(--app-text-secondary);
  margin: 0 0 28px;
}

.login-btn {
  width: 100%;
  height: 44px;
  font-size: 16px;
  letter-spacing: 4px;
}

.demo-section {
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--app-divider-color);
}

.demo-title {
  font-size: 12px;
  color: var(--app-text-secondary);
  margin-bottom: 12px;
}

.demo-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.demo-account {
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--app-surface-secondary);
  border: 1px solid var(--app-border-light);
  cursor: pointer;
  transition:
    background-color 0.2s,
    border-color 0.2s;

  &:hover {
    background: var(--app-color-primary-light);
    border-color: var(--app-color-primary);
  }
}

.demo-acc-name {
  font-size: 13px;
  color: var(--app-text-primary);
  font-weight: 500;
}

.demo-acc-user {
  font-size: 11px;
  color: var(--app-color-primary);
  margin: 2px 0;
  font-family: monospace;
}

.demo-acc-desc {
  font-size: 11px;
  color: var(--app-text-secondary);
}
</style>
