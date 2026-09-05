<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ company: 'default', username: 'admin', password: '' })
const rules: FormRules = {
  company: [{ required: true, message: '请输入公司代码', trigger: 'blur' }],
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit(): Promise<void> {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    await auth.signIn(form)
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (reason) {
    ElMessage.error(reason instanceof ApiError ? reason.message : '登录失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-intro">
      <div class="login-brand"><span>W</span><strong>MT-WMS</strong></div>
      <p class="eyebrow">WAREHOUSE MANAGEMENT</p>
      <h1>让仓储作业清晰、可靠、可追踪</h1>
      <p>统一管理基础资料、库存、入出库、财务统计与数据交换，核心业务已完成重构。</p>
      <ul><li>公司、仓库、货主三级业务上下文</li><li>服务端 Session 与细粒度权限校验</li><li>openGauss 持久化与可重复迁移</li></ul>
    </section>
    <section class="login-card" aria-labelledby="login-title">
      <div><p class="eyebrow">欢迎回来</p><h2 id="login-title">登录仓储系统</h2><p class="login-help">请输入公司、账号和密码</p></div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="公司代码" prop="company"><el-input v-model="form.company" autocomplete="organization" placeholder="例如：default" /></el-form-item>
        <el-form-item label="账号" prop="username"><el-input v-model="form.username" autocomplete="username" placeholder="用户名或手机号" /></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" autocomplete="current-password" show-password placeholder="请输入密码" @keyup.enter="submit" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="submitting" class="login-submit">登录</el-button>
      </el-form>
      <el-alert title="本地初始账号：admin，初始密码：Admin@123456" type="info" :closable="false" show-icon />
    </section>
  </main>
</template>
