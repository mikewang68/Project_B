<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createClient } from '@/api/request'
import { TOKEN_KEY } from '@/api/iam'

interface AddressRow { app_code:string; domain_name:string; public_scheme:string; public_port:number|null;
  origin_scheme:string;origin_ip:string;origin_port:number|null;module_path:string;address_version:number }
interface Revision { revision_id:string;state:string;published_version:number|null;address_json:string;validation_message:string|null;created_at:string }
const api=createClient({tokenKey:TOKEN_KEY,prefix:'iam'})
const rows=ref<AddressRow[]>([]),history=ref<Revision[]>([]),selected=ref<AddressRow>(),busy=ref(false),error=ref('')
const form=reactive({domainName:'',publicScheme:'https',publicPort:443 as number|null,
  originScheme:'http',originIp:'',originPort:null as number|null,modulePath:'/'})
async function action(task:()=>Promise<void>){busy.value=true;error.value='';try{await task()}catch(e){error.value=e instanceof Error?e.message:'操作失败'}finally{busy.value=false}}
async function refresh(){rows.value=await api.request<AddressRow[]>('/navigation/addresses');
  if(selected.value){const current=rows.value.find(r=>r.app_code===selected.value!.app_code);if(current)await select(current)}}
async function select(row:AddressRow){selected.value=row;Object.assign(form,{domainName:row.domain_name,publicScheme:row.public_scheme,
  publicPort:row.public_port,originScheme:row.origin_scheme,originIp:row.origin_ip,originPort:row.origin_port,modulePath:row.module_path});
  history.value=await api.request<Revision[]>(`/navigation/addresses/${row.app_code}/history`)}
function selectRow(row:AddressRow){void action(()=>select(row))}
async function draft(){if(!selected.value)return;await api.request(`/navigation/addresses/${selected.value.app_code}/draft`,{
  method:'POST',body:{expectedVersion:selected.value.address_version,address:{...form}}});await refresh();ElMessage.success('草稿已保存，当前访问地址尚未改变')}
async function revision(row:Revision,operation:'validate'|'publish'|'rollback'){
  const result=await api.request<Revision>(`/navigation/addresses/revisions/${row.revision_id}/${operation}`,{method:'POST',body:{}})
  await refresh();if(result.state==='FAILED')throw new Error(result.validation_message||'地址校验未通过')
  ElMessage.success(operation==='validate'?'目标地址校验通过':'地址版本已发布')}
onMounted(()=>void action(refresh))
</script>

<template>
  <section v-loading="busy">
    <h2>系统访问地址</h2>
    <el-alert type="info" :closable="false" title="域名优先；公开访问端口与回源端口分别维护。保存草稿后先校验，再发布；失败时继续使用当前版本。" />
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <el-table :data="rows" highlight-current-row @row-click="selectRow">
      <el-table-column prop="app_code" label="系统" width="100" />
      <el-table-column prop="domain_name" label="访问域名" />
      <el-table-column prop="origin_ip" label="回源 / 备用 IP" />
      <el-table-column prop="origin_port" label="回源端口" width="110" />
      <el-table-column prop="module_path" label="模块路径" />
      <el-table-column prop="address_version" label="生效版本" width="100" />
    </el-table>
    <el-form v-if="selected" label-width="140px" style="max-width:760px;margin-top:24px" @submit.prevent="action(draft)">
      <h3>{{ selected.app_code }} 地址草稿</h3>
      <el-form-item label="域名"><el-input v-model="form.domainName" placeholder="留空时使用登记的 IP、端口和路径" /></el-form-item>
      <el-form-item label="公开协议"><el-input v-model="form.publicScheme" readonly /></el-form-item>
      <el-form-item label="公开端口"><el-input-number v-model="form.publicPort" :min="1" :max="65535" /></el-form-item>
      <el-form-item label="回源 / IP 协议"><el-select v-model="form.originScheme"><el-option label="HTTP" value="http" /><el-option label="HTTPS" value="https" /></el-select></el-form-item>
      <el-form-item label="回源 / 备用 IP"><el-input v-model="form.originIp" /></el-form-item>
      <el-form-item label="回源 / IP 端口"><el-input-number v-model="form.originPort" :min="1" :max="65535" /></el-form-item>
      <el-form-item label="模块路径"><el-input v-model="form.modulePath" /></el-form-item>
      <el-form-item><el-button type="primary" native-type="submit">保存新草稿</el-button></el-form-item>
    </el-form>
    <h3 v-if="selected">版本记录</h3>
    <el-table v-if="selected" :data="history">
      <el-table-column prop="created_at" label="创建时间" width="190" />
      <el-table-column prop="state" label="状态" width="120" />
      <el-table-column prop="published_version" label="发布版本" width="100" />
      <el-table-column prop="address_json" label="地址配置" show-overflow-tooltip />
      <el-table-column prop="validation_message" label="校验结果" show-overflow-tooltip />
      <el-table-column label="操作" width="220"><template #default="{row}">
        <el-button v-if="row.state==='DRAFT'" link @click="action(()=>revision(row,'validate'))">校验</el-button>
        <el-button v-if="row.state==='VALIDATED'" link type="primary" @click="action(()=>revision(row,'publish'))">发布</el-button>
        <el-button v-if="row.state==='SUPERSEDED'" link @click="action(()=>revision(row,'rollback'))">回退到此版</el-button>
      </template></el-table-column>
    </el-table>
  </section>
</template>
