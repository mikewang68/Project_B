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
  { label: '系统管理员', username: 'admin', password: 'Admin@123', desc: '全部维护权限' },
  { label: '运维观摩', username: 'viewer', password: 'Viewer@123', desc: '只读 / 可导出日志' },
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
        // 无 redirect 或 redirect 无权限时，落到第一个有权限的 SYS 页面
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
        <div class="logo-icon">SYS</div>
        <div class="logo-text">
          <h1>B 项目系统设置与维护系统</h1>
          <p>System Settings & Maintenance</p>
        </div>
      </div>
      <div class="brand-features">
        <div class="feature-item">
          <span class="feature-dot"></span>
          <span>数据字典：分类与字典项统一维护</span>
        </div>
        <div class="feature-item">
          <span class="feature-dot"></span>
          <span>操作日志：登录 / 操作全程留痕可导出</span>
        </div>
        <div class="feature-item">
          <span class="feature-dot"></span>
          <span>系统配置：基础、安全、会话参数集中管理</span>
        </div>
        <div class="feature-item">
          <span class="feature-dot feature-dot-reserved"></span>
          <span>账号与权限由 IAM 统一认证中心管控</span>
        </div>
      </div>
      <div class="brand-footer">
        <p>© 2026 B 项目 · 系统设置与维护系统（SYS）</p>
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
  background: #0a0e1a;
}

.login-brand {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 56px;
  background: linear-gradient(135deg, #0d1424 0%, #131f3a 50%, #0a1628 100%);
  position: relative;
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    top: -50%;
    right: -30%;
    width: 80%;
    height: 200%;
    background: radial-gradient(ellipse, rgba(0, 180, 216, 0.08) 0%, transparent 70%);
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
  border-radius: 12px;
  background: linear-gradient(135deg, var(--iam-logo-from), var(--iam-logo-to));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 1px;
  box-shadow: 0 4px 20px rgba(0, 180, 216, 0.3);
}

.logo-text h1 {
  font-size: 22px;
  color: #e8f4f8;
  margin: 0;
  font-weight: 600;
}

.logo-text p {
  font-size: 13px;
  color: #7a9bb5;
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
  color: #b8d4e3;
  font-size: 15px;
}

.feature-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--iam-primary);
  box-shadow: 0 0 8px var(--iam-primary);
  flex-shrink: 0;
}

.feature-dot-reserved {
  background: #f4a261;
  box-shadow: 0 0 8px rgba(244, 162, 97, 0.5);
}

.brand-footer {
  position: relative;
  z-index: 1;
  color: #4a6a80;
  font-size: 12px;
}

.login-form-wrap {
  width: 480px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: #0f1524;
}

.login-card {
  width: 100%;
  max-width: 400px;
}

.login-card h2 {
  font-size: 26px;
  color: #e8f4f8;
  margin: 0 0 8px;
  font-weight: 600;
}

.login-subtitle {
  font-size: 13px;
  color: #6a8aa0;
  margin: 0 0 28px;
}

.login-btn {
  width: 100%;
  height: 44px;
  font-size: 16px;
  letter-spacing: 4px;
  background: linear-gradient(135deg, var(--iam-logo-from), var(--iam-logo-to));
  border: none;

  &:hover {
    opacity: 0.9;
  }
}

.demo-section {
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.demo-title {
  font-size: 12px;
  color: #5a7a90;
  margin-bottom: 12px;
}

.demo-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.demo-account {
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: var(--iam-sidebar-active-bg, rgba(0, 180, 216, 0.1));
    border-color: var(--iam-primary);
  }
}

.demo-acc-name {
  font-size: 13px;
  color: #c8e0ec;
  font-weight: 500;
}

.demo-acc-user {
  font-size: 11px;
  color: var(--iam-primary);
  margin: 2px 0;
  font-family: monospace;
}

.demo-acc-desc {
  font-size: 11px;
  color: #5a7a90;
}

/* 覆盖 element-plus 深色输入框 */
:deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.04);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
}

:deep(.el-input__inner) {
  color: #d0e8f0;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--iam-primary) inset;
}
</style>
