<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { identityLabel, networkLabel, crlLabel, identityExpiry, identityError } from './identity-display';
const rows = ref<Record<string, unknown>[]>([]), error = ref(''), loading = ref(false);
async function refresh() {
  loading.value = true; error.value = '';
  try {
    const response = await fetch('/api/v1/identity-management', { headers: { Accept:'application/json' } });
    if (!response.ok) throw new Error(response.status === 403 ? '没有身份查看权限' : '身份查询失败，请确认登录和后端状态');
    if (!response.headers.get('content-type')?.includes('application/json')) throw new Error('后端返回格式错误');
    rows.value = await response.json();
  } catch(e) { error.value = e instanceof Error ? e.message : '身份查询失败'; }
  finally { loading.value = false; }
}
onMounted(refresh);
</script>
<template>
  <section class="identity-panel">
    <header><div><h2>业务用户 Fabric 身份</h2><p>查看当前组织的证书和网络核验结果；用户停用、恢复、轮换与撤销由 IAM 用户管理发起。</p></div>
      <button :disabled="loading" @click="refresh">{{ loading ? '正在刷新…' : '刷新状态' }}</button></header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!rows.length && !loading">当前组织暂无已分配身份。</p>
    <div class="identity-grid">
      <article v-for="row in rows" :key="String(row.id)">
        <strong>{{ identityLabel(row.status) }}</strong><h3>{{ row.user_id }}</h3>
        <dl><dt>身份编号</dt><dd>{{ row.id }}</dd><dt>发行方 / 租户</dt><dd>{{ row.issuer }} / {{ row.tenant }}</dd>
          <dt>MSP / 证书版本</dt><dd>{{ row.msp_id || '待签发' }} / {{ row.certificate_version }}</dd>
          <dt>有效期至（北京时间）</dt><dd class="expiry">{{ identityExpiry(row.not_after) }}</dd><dt>证书指纹</dt><dd>{{ row.fingerprint || '待签发' }}</dd>
          <dt>网络认可</dt><dd>{{ networkLabel(row.network_state) }}</dd>
          <dt>撤销传播</dt><dd>{{ crlLabel(row.crl_state) }}</dd>
        </dl><p>业务合约权限：未自动授予</p><p v-if="row.last_error">{{ identityError(row.status) }}</p>
      <details><summary>技术详情</summary><p>身份：{{ row.status }}；网络：{{ row.network_state }}；CRL：{{ row.crl_state }}；错误码：{{ row.last_error || '—' }}</p></details></article>
    </div>
  </section>
</template>
<style scoped>
.identity-panel { padding: 24px; } header { display:flex; justify-content:space-between; gap:24px; align-items:center; }
.identity-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(min(100%,320px),1fr)); gap:20px; margin-top:24px; }
article { min-width:0; padding:24px; border:1px solid #d5dfe9; border-radius:12px; background:white; } h3 { overflow-wrap:anywhere; }
dt { color:#586b80; margin-top:12px; } dd { margin:4px 0 0; overflow-wrap:anywhere; } strong { color:#285077; }
button { padding:10px 20px; cursor:pointer; } [role=alert] { color:#b42318; }
.expiry { white-space:nowrap; font-size:13px; }
@media (max-width:600px) { .identity-panel { padding:12px; } header { flex-wrap:wrap; gap:12px; } article { padding:12px; } }
</style>
