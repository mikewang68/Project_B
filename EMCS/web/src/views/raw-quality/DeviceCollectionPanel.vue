<template>
  <el-card class="device-collection">
    <template #header>
      <div class="collection-heading"><b>设备采集事件</b><span>六类设备 · 原始点号与质量留痕</span>
        <el-switch v-model="automatic" active-text="每 5 秒刷新" /><el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-else-if="!enabled" title="设备采集尚未启用。配置独立接收身份并完成迁移后可接入模拟数据。" type="info" :closable="false" />
    <template v-else>
      <p>仅展示已存入 EMCS 的事件，不代表公共底座已 COMMITTED。模拟读数不用于生产考核。</p>
      <el-table :data="rows" v-loading="loading" empty-text="尚未接收设备事件">
        <el-table-column prop="device_id" label="设备实例" min-width="140" />
        <el-table-column label="设备类型" min-width="140"><template #default="{row}">{{ devices[row.equipment_code] || row.equipment_code }}</template></el-table-column>
        <el-table-column prop="area_code" label="区域" width="100" />
        <el-table-column label="观测时间" min-width="185"><template #default="{row}">{{ new Date(row.observed_ts_ms).toLocaleString() }}</template></el-table-column>
        <el-table-column label="数据标记" width="120"><template #default="{row}"><el-tag :type="row.simulated ? 'warning' : 'info'">{{ row.simulated ? '模拟' : '现场来源' }}</el-tag></template></el-table-column>
        <el-table-column prop="point_count" label="点数" width="80" />
        <el-table-column label="操作" width="100"><template #default="{row}"><el-button link @click="show(row.event_id)">查看原始点</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="load" />
    </template>
    <el-dialog v-model="visible" title="采集事件与点位" width="85%" append-to-body>
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
      <div v-if="detail"><p>事件：{{ detail.event_id }}</p><p>原文 SHA-256：{{ detail.wire_sha256 }}</p>
        <el-table :data="detail.points" max-height="480">
          <el-table-column prop="point_code" label="原始点号" min-width="210" />
          <el-table-column label="名称" min-width="170"><template #default="{row}">{{ points[row.point_code]?.name || row.point_code }}</template></el-table-column>
          <el-table-column label="读数" min-width="130"><template #default="{row}">{{ displayReading(row.value_json) }}</template></el-table-column>
          <el-table-column prop="unit" label="单位" width="100" />
          <el-table-column prop="quality_code" label="原始质量码" width="140" />
        </el-table>
      </div>
    </el-dialog>
  </el-card>
</template>
<script setup>
import { ref, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue'
import request from '@/utils/request'
import { displayReading } from './collectionDisplay'
const rows=ref([]), total=ref(0), page=ref(1), enabled=ref(false), loading=ref(false), error=ref('')
const devices=ref({}), points=ref({}), detail=ref(null), detailError=ref(''), visible=ref(false), automatic=ref(false)
let timer, active=true, listSequence=0, detailSequence=0
async function load() {
  const sequence=++listSequence
  loading.value=true;error.value=''
  try { const response=await request({url:'/collection/events',params:{pageNum:page.value,pageSize:20}})
    if(sequence===listSequence){enabled.value=response.data.enabled;rows.value=response.data.rows;total.value=response.data.total}
  } catch {if(sequence===listSequence)error.value='设备事件查询失败或当前身份未获授权，请联系管理员核对采集查询权限。'}
  finally {if(sequence===listSequence)loading.value=false}
}
async function show(id) {
  const sequence=++detailSequence
  visible.value=true;detail.value=null;detailError.value=''
  try {const response=await request({url:'/collection/event',params:{eventId:id}});if(sequence===detailSequence)detail.value=response.data}
  catch {if(sequence===detailSequence)detailError.value='事件详情不可用或不在当前数据范围内。'}
}
onMounted(async()=>{
  try {const response=await request({url:'/collection/catalog'});devices.value=Object.fromEntries(response.data.devices.map(d=>[d.equipmentCode,d.name]));points.value=Object.fromEntries(response.data.points.map(p=>[p.pointCode,p]))} catch { /* event rows still retain source identifiers */ }
  await load();timer=setInterval(()=>{if(active&&automatic.value&&!loading.value)load()},5000)
})
onActivated(()=>{active=true});onDeactivated(()=>{active=false});onBeforeUnmount(()=>{active=false;listSequence++;detailSequence++;clearInterval(timer)})
</script>
<style scoped>
.device-collection {margin-bottom:20px}
.collection-heading {display:flex;align-items:center;flex-wrap:wrap;gap:16px}
.collection-heading span,p {font-size:13px;color:var(--el-text-color-secondary)}
.el-pagination {margin-top:16px}
</style>
