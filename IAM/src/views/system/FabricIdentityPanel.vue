<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { iamApi } from '@/api/iam'
import { identityLabel, certificateLabel, networkLabel, crlLabel, identityExpiry, identityError } from '@/iam/identity-display'
import type { FabricIdentityTask } from '@/iam/types'
const props = defineProps<{ userId: string }>()
const task = ref<FabricIdentityTask>()
const error = ref('')
const busy = ref(false)
const operationKeys = new Map<string, string>()
async function refresh() {
  busy.value = true; error.value = ''
  try { task.value = await iamApi.identity(props.userId) }
  catch (e) { error.value = e instanceof Error ? e.message : '身份查询失败' }
  finally { busy.value = false }
}
async function operate(action: 'retry' | 'rotate' | 'revoke') {
  if (action !== 'retry') {
    try { await ElMessageBox.confirm(action === 'rotate' ? '为此用户申请新的证书版本？' : '撤销此停用用户的证书？撤销不能直接恢复，通道生效需要单独核验。', '确认身份操作') }
    catch { return }
  }
  busy.value = true
  const request = `${props.userId}:${action}`
  const key = operationKeys.get(request) || crypto.randomUUID()
  operationKeys.set(request, key)
  try { task.value = await iamApi.identityOperation(props.userId, action, key); operationKeys.delete(request); ElMessage.info('身份任务已保存，请刷新查看处理结果') }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '身份操作失败') }
  finally { busy.value = false }
}
watch(() => props.userId, refresh, { immediate: true })
</script>
<template>
  <section v-loading="busy" class="fabric-identity-panel">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="task">
      <el-alert :type="task.state === 'READY' ? 'success' : 'info'" :closable="false"
        :title="identityLabel(task.state)" />
      <el-descriptions :column="1" border style="margin: 16px 0">
        <el-descriptions-item label="业务用户 ID">{{ userId }}</el-descriptions-item>
        <el-descriptions-item label="供给任务">{{ task.taskId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="Fabric 身份">{{ task.identityId || '尚未分配' }}</el-descriptions-item>
        <el-descriptions-item label="错误">{{ (task.errorCode || task.identity?.errorCode) ? identityError(task.state) : '—' }}</el-descriptions-item>
      </el-descriptions>
      <p>身份可用表示证书已经通过网络核验。业务合约权限需另行授权。</p>
      <div class="certificate-scroll"><el-table style="min-width: 1060px" :data="task.identity?.certificates || []" empty-text="尚未签发证书">
        <el-table-column prop="version" label="版本" width="65" />
        <el-table-column prop="msp_id" label="MSP" />
        <el-table-column prop="enrollment_id" label="注册标识" show-overflow-tooltip />
        <el-table-column label="证书状态" min-width="145"><template #default="{ row }"><span :title="row.state">{{ certificateLabel(row.state) }}</span></template></el-table-column>
        <el-table-column label="有效期至（北京时间）" min-width="285"><template #default="{ row }"><span class="expiry" :title="String(row.not_after || '')">{{ identityExpiry(row.not_after) }}</span></template></el-table-column>
        <el-table-column prop="fingerprint" label="证书指纹" show-overflow-tooltip />
        <el-table-column label="网络核验" min-width="180"><template #default="{ row }"><span :title="row.network_state">{{ networkLabel(row.network_state) }}</span></template></el-table-column>
        <el-table-column label="CRL 状态" min-width="210"><template #default="{ row }"><span :title="row.crl_state">{{ crlLabel(row.crl_state) }}</span></template></el-table-column>
      </el-table></div>
      <details><summary>技术详情</summary><p>身份状态：{{ task.state }}；错误码：{{ task.errorCode || task.identity?.errorCode || '—' }}</p></details>
    </template>
    <div class="identity-actions">
      <el-button :disabled="busy" @click="refresh">刷新状态</el-button>
      <el-button v-perm="'iam:identity:retry:execute'" :disabled="busy || !['PENDING', 'RETRY'].includes(task?.state || '')" @click="operate('retry')">重试供给</el-button>
      <el-button v-perm="'iam:identity:rotate:execute'" :disabled="busy || task?.desiredState !== 'ACTIVE'" @click="operate('rotate')">轮换证书</el-button>
      <el-button v-perm="'iam:identity:revoke:execute'" type="danger" :disabled="busy || task?.desiredState === 'ACTIVE'" @click="operate('revoke')">撤销证书</el-button>
    </div>
  </section>
</template>

<style scoped>
.fabric-identity-panel { min-width:0; max-width:100%; overflow-wrap:anywhere; }
.certificate-scroll { max-width:100%; overflow-x:auto; }
.expiry { white-space:nowrap; }
.identity-actions { margin-top:20px; display:flex; flex-wrap:wrap; gap:10px; }
.identity-actions :deep(.el-button) { margin-left:0; }
details { margin-top:12px; }
</style>
