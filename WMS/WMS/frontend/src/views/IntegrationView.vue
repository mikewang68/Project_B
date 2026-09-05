<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { UploadFile } from 'element-plus'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { createExport, createImport, getQimenConfig, listQimenLogs, listTasks, saveQimenConfig, type AsyncTask, type QimenLog } from '@/api/integration'

const active=ref('tasks'),loading=ref(false),tasks=ref<AsyncTask[]>([]),logs=ref<QimenLog[]>([]),importResource=ref('GOODS'),selected=ref<File>(),configExists=ref(false)
const exports=[['GOODS','货品'],['PARTNERS','合作伙伴'],['INVENTORY','库存'],['STOCKIN','入库单'],['STOCKOUT','出库单'],['FINANCE','财务费用']]
const imports=[['GOODS','货品'],['PARTNERS','合作伙伴'],['STOCKIN','入库单'],['STOCKOUT','出库单']]
const form=reactive({customerId:'',appKey:'MT-WMS',appSecret:'',callbackUrl:'',payloadFormat:'JSON',enabled:true})
let timer:number|undefined
async function loadTasks(){try{tasks.value=await listTasks()}catch(e){error(e)}}
async function loadConfig(){try{const c=await getQimenConfig();configExists.value=!!c;if(c)Object.assign(form,{customerId:c.customerId,appKey:c.appKey,appSecret:'',callbackUrl:c.callbackUrl??'',payloadFormat:c.payloadFormat,enabled:c.enabled})}catch(e){error(e)}}
async function loadLogs(){try{logs.value=await listQimenLogs()}catch(e){error(e)}}
async function runExport(resource:string){loading.value=true;try{await createExport(resource);ElMessage.success('导出任务已创建');await loadTasks()}catch(e){error(e)}finally{loading.value=false}}
function choose(file:UploadFile){selected.value=file.raw}
async function runImport(){if(!selected.value)return void ElMessage.warning('请先选择 .xlsx 文件');loading.value=true;try{await createImport(importResource.value,selected.value);selected.value=undefined;ElMessage.success('导入任务已创建');await loadTasks()}catch(e){error(e)}finally{loading.value=false}}
function download(task:AsyncTask){window.open(`/api/v1/integration/tasks/${task.id}/download`,'_blank')}
function template(){window.open(`/api/v1/integration/templates/${importResource.value}`,'_blank')}
async function saveConfig(){if(!form.customerId)return void ElMessage.warning('客户ID不能为空');try{await saveQimenConfig(form);form.appSecret='';configExists.value=true;ElMessage.success('奇门配置已保存')}catch(e){error(e)}}
async function tabChanged(name:string|number){if(name==='qimen'){await Promise.all([loadConfig(),loadLogs()])}}
function error(e:unknown){ElMessage.error(e instanceof ApiError?e.message:e instanceof Error?e.message:'操作失败')}
onMounted(()=>{void loadTasks();timer=window.setInterval(()=>{if(tasks.value.some(x=>x.state==='PROCESSING'))void loadTasks()},2000)})
onBeforeUnmount(()=>{if(timer)window.clearInterval(timer)})
</script>

<template>
  <div class="page-stack">
    <section class="page-heading"><div><p class="eyebrow">DATA EXCHANGE</p><h1>导入导出与集成</h1><p>Excel 文件任务、执行结果与奇门接口配置集中管理。</p></div><el-button :loading="loading" @click="loadTasks">刷新任务</el-button></section>
    <section class="panel integration-panel"><el-tabs v-model="active" @tab-change="tabChanged">
      <el-tab-pane label="导入导出任务" name="tasks">
        <div class="exchange-grid"><article><h3>Excel 导出</h3><p>按当前公司、仓库和货主生成真实 .xlsx 文件。</p><div class="button-grid"><el-button v-for="item in exports" :key="item[0]" @click="runExport(item[0]!)">导出{{ item[1] }}</el-button></div></article><article><h3>Excel 导入</h3><p>使用模板批量新增或更新数据，入出库单导入后保持草稿状态。</p><div class="import-row"><el-select v-model="importResource"><el-option v-for="item in imports" :key="item[0]" :label="item[1]" :value="item[0]"/></el-select><el-button @click="template">下载模板</el-button><el-upload :auto-upload="false" :limit="1" accept=".xlsx" :show-file-list="true" :on-change="choose"><el-button>选择文件</el-button></el-upload><el-button type="primary" :loading="loading" @click="runImport">创建导入任务</el-button></div></article></div>
        <el-table :data="tasks" height="410"><el-table-column prop="taskCode" label="任务号" width="120"/><el-table-column prop="name" label="任务名称"/><el-table-column prop="taskType" label="类型" width="90"/><el-table-column prop="resourceType" label="资源" width="110"/><el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.state==='COMPLETED'?'success':row.state==='FAILED'?'danger':'warning'">{{ row.state }}</el-tag></template></el-table-column><el-table-column prop="successRows" label="成功行" width="90"/><el-table-column prop="failureRows" label="失败行" width="90"/><el-table-column prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip/><el-table-column prop="createdAt" label="创建时间" width="190"/><el-table-column label="文件" width="90"><template #default="{row}"><el-button v-if="row.state==='COMPLETED'" link type="primary" @click="download(row)">下载</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="奇门接口" name="qimen">
        <el-alert title="接口地址：/open/qimen（JSON）和 /open/qimen/xml（XML）；支持商品同步、入库/退货/出库建单、取消和库存查询。" type="info" :closable="false"/>
        <div class="qimen-grid"><el-form label-position="top"><div class="form-grid"><el-form-item label="客户ID"><el-input v-model="form.customerId"/></el-form-item><el-form-item label="App Key"><el-input v-model="form.appKey"/></el-form-item><el-form-item :label="configExists?'App Secret（留空则不修改）':'App Secret'"><el-input v-model="form.appSecret" type="password" show-password/></el-form-item><el-form-item label="数据格式"><el-select v-model="form.payloadFormat"><el-option label="JSON" value="JSON"/><el-option label="XML" value="XML"/></el-select></el-form-item></div><el-form-item label="完成回传地址"><el-input v-model="form.callbackUrl" placeholder="第三方奇门网关地址，未配置时不会发起外部请求"/></el-form-item><el-form-item><el-switch v-model="form.enabled" active-text="启用接口"/></el-form-item><el-button type="primary" @click="saveConfig">保存配置</el-button></el-form><aside><h3>签名与安全</h3><ul><li>MD5 参数排序签名，与旧版奇门 2.0 规则兼容。</li><li>密钥仅在服务端保存，页面不会回显明文。</li><li>每次接收、回传及失败原因都会记录。</li><li>重复外部单号按幂等方式返回成功。</li></ul></aside></div>
        <h3>最近接口日志</h3><el-table :data="logs" height="300"><el-table-column prop="direction" label="方向" width="70"/><el-table-column prop="methodName" label="方法"/><el-table-column prop="relatedOrderCode" label="关联单号"/><el-table-column label="结果" width="90"><template #default="{row}"><el-tag :type="row.success?'success':'danger'">{{ row.success?'成功':'失败' }}</el-tag></template></el-table-column><el-table-column prop="errorMessage" label="错误信息" show-overflow-tooltip/><el-table-column prop="createdAt" label="时间" width="190"/></el-table>
      </el-tab-pane>
    </el-tabs></section>
  </div>
</template>

<style scoped>
.integration-panel{padding-top:6px}.integration-panel :deep(.el-tabs__header){padding:0 14px}.exchange-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:12px 0 20px}.exchange-grid article{padding:18px;border:1px solid var(--wms-border);border-radius:8px;background:#fafcff}.exchange-grid h3{margin:0 0 6px}.exchange-grid p{color:var(--wms-text-secondary)}.button-grid{display:flex;flex-wrap:wrap;gap:8px}.import-row{display:flex;align-items:flex-start;gap:8px}.import-row .el-select{width:130px}.qimen-grid{display:grid;grid-template-columns:2fr 1fr;gap:28px;margin:22px 0}.qimen-grid aside{padding:18px;border-radius:8px;background:#f5f7fa}.qimen-grid aside h3{margin-top:0}.qimen-grid li{margin:10px 0;line-height:1.6}
</style>
