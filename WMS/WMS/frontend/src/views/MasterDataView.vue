<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { listMasterData, saveMasterData, type MasterItem, type MasterResource, type SaveMasterItem } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'

const auth=useAuthStore()
const tabs:{key:MasterResource;label:string}[]=[{key:'warehouses',label:'仓库'},{key:'areas',label:'库区'},{key:'work-areas',label:'工作区'},{key:'locations',label:'库位'},{key:'owners',label:'货主'},{key:'partners',label:'合作伙伴'},{key:'categories',label:'货类'},{key:'goods',label:'货品'},{key:'components',label:'组合件'}]
const active=ref<MasterResource>('warehouses'),items=ref<MasterItem[]>([]),loading=ref(false),saving=ref(false),dialog=ref(false),editingId=ref<number|null>(null)
const areas=ref<MasterItem[]>([]),workAreas=ref<MasterItem[]>([]),categories=ref<MasterItem[]>([])
const form=reactive<SaveMasterItem>({code:'',name:'',type:'',status:'ENABLED',parentCode:'',secondaryCode:'',barcode:'',specification:'',unit:'',contact:'',telephone:'',address:'',remark:'',quantity:0,price:0})
const currentLabel=computed(()=>tabs.find(t=>t.key===active.value)?.label??'基础资料')
const showContact=computed(()=>['warehouses','owners','partners'].includes(active.value))
const showType=computed(()=>['areas','locations','partners','goods'].includes(active.value))
const typeOptions=computed(()=>active.value==='areas'?[['STORAGE','存储区'],['TEMP','临时区'],['PIECE','零散区'],['BAD','坏品区'],['RETURN','退货区'],['REPAIR','维修区']]:active.value==='locations'?[['L1','L1'],['L2','L2'],['L3','L3'],['L4','L4'],['L5','L5']]:active.value==='partners'?[['CLIENT','客户'],['SUPPLIER','供应商'],['CLIENT_SUPPLIER','客户兼供应商'],['EXPRESS','物流商']]:[['ZC','正常商品'],['ZH','组合商品'],['BC','包材'],['HC','耗材'],['OTHER','其他']])

async function load(){loading.value=true;try{items.value=await listMasterData(active.value);if(active.value==='locations')await loadDependencies('location');if(active.value==='goods')await loadDependencies('good')}catch(e){ElMessage.error(e instanceof ApiError?e.message:'基础资料加载失败')}finally{loading.value=false}}
async function loadDependencies(kind:'location'|'good'){if(kind==='location'){[areas.value,workAreas.value]=await Promise.all([listMasterData('areas'),listMasterData('work-areas')])}else categories.value=await listMasterData('categories')}
function defaults(){return{code:'',name:'',type:active.value==='areas'?'STORAGE':active.value==='locations'?'L3':active.value==='partners'?'CLIENT':active.value==='goods'?'ZC':'',status:'ENABLED',parentCode:'default',secondaryCode:'default',barcode:'',specification:'',unit:'',contact:'',telephone:'',address:'',remark:'',quantity:0,price:0}}
function create(){editingId.value=null;Object.assign(form,defaults());dialog.value=true}
function edit(row:MasterItem){editingId.value=row.id;Object.assign(form,defaults(),row);dialog.value=true}
async function submit(){if(!form.code.trim()||!form.name.trim())return void ElMessage.warning('请填写编码和名称');saving.value=true;try{await saveMasterData(active.value,editingId.value,form);ElMessage.success(`${currentLabel.value}已保存`);dialog.value=false;await load();if(active.value==='warehouses'||active.value==='owners')await auth.refreshSession()}catch(e){ElMessage.error(e instanceof ApiError?e.message:'保存失败')}finally{saving.value=false}}
onMounted(load)
</script>

<template>
  <div class="page-stack">
    <header class="page-heading"><div><p class="eyebrow">MASTER DATA</p><h1>仓库基础资料</h1><p>资料按当前公司、仓库和货主自动隔离；切换业务范围后显示对应数据。</p></div><el-button type="primary" @click="create">新建{{ currentLabel }}</el-button></header>
    <section class="panel master-panel">
      <el-tabs v-model="active" @tab-change="load"><el-tab-pane v-for="tab in tabs" :key="tab.key" :label="tab.label" :name="tab.key" /></el-tabs>
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="code" label="编码" min-width="130" /><el-table-column prop="name" label="名称" min-width="170" />
        <el-table-column v-if="showType" prop="type" label="类型/优先级" min-width="120" />
        <el-table-column v-if="active==='locations'||active==='goods'||active==='components'" prop="parentCode" :label="active==='locations'?'库区':active==='goods'?'货类':'组件货品'" min-width="110" />
        <el-table-column v-if="active==='locations'" prop="secondaryCode" label="工作区" min-width="110" />
        <el-table-column v-if="active==='goods'" prop="barcode" label="条码" min-width="140" />
        <el-table-column v-if="active==='components'" prop="quantity" label="组件数量" min-width="110" />
        <el-table-column v-if="showContact" prop="contact" label="联系人" min-width="110" /><el-table-column v-if="showContact" prop="telephone" label="联系电话" min-width="130" />
        <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="scope.row.status==='ENABLED'?'success':'info'">{{ scope.row.status==='ENABLED'?'启用':'停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button link type="primary" @click="edit(scope.row)">编辑</el-button></template></el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialog" :title="`${editingId?'编辑':'新建'}${currentLabel}`" width="650px">
      <el-form label-position="top"><div class="form-grid"><el-form-item label="编码"><el-input v-model="form.code" :disabled="editingId!==null" /></el-form-item><el-form-item label="名称"><el-input v-model="form.name" /></el-form-item></div>
        <div v-if="showType||active==='locations'||active==='goods'" class="form-grid">
          <el-form-item v-if="showType" label="类型/优先级"><el-select v-model="form.type" style="width:100%"><el-option v-for="option in typeOptions" :key="option[0]" :label="option[1]" :value="option[0]" /></el-select></el-form-item>
          <el-form-item v-if="active==='locations'" label="库区"><el-select v-model="form.parentCode" style="width:100%"><el-option v-for="x in areas" :key="x.id" :label="x.name" :value="x.code" /></el-select></el-form-item>
          <el-form-item v-if="active==='locations'" label="工作区"><el-select v-model="form.secondaryCode" style="width:100%"><el-option v-for="x in workAreas" :key="x.id" :label="x.name" :value="x.code" /></el-select></el-form-item>
          <el-form-item v-if="active==='goods'" label="货类"><el-select v-model="form.parentCode" style="width:100%"><el-option v-for="x in categories" :key="x.id" :label="x.name" :value="x.code" /></el-select></el-form-item>
        </div>
        <div v-if="active==='goods'" class="form-grid"><el-form-item label="条码"><el-input v-model="form.barcode" placeholder="留空则使用货品编码" /></el-form-item><el-form-item label="规格"><el-input v-model="form.specification" /></el-form-item><el-form-item label="单位"><el-input v-model="form.unit" /></el-form-item><el-form-item label="参考价格"><el-input-number v-model="form.price" :min="0" :precision="4" style="width:100%" /></el-form-item></div>
        <div v-if="active==='components'" class="form-grid"><el-form-item label="组件货品编码"><el-input v-model="form.parentCode" /></el-form-item><el-form-item label="组件数量"><el-input-number v-model="form.quantity" :min="0.001" :precision="3" style="width:100%" /></el-form-item></div>
        <template v-if="showContact"><div class="form-grid"><el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item><el-form-item label="联系电话"><el-input v-model="form.telephone" /></el-form-item></div><el-form-item label="地址"><el-input v-model="form.address" /></el-form-item></template>
        <div class="form-grid"><el-form-item label="状态"><el-select v-model="form.status" style="width:100%"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item><el-form-item v-if="active==='locations'" label="最大承重（kg）"><el-input-number v-model="form.quantity" :min="0" style="width:100%" /></el-form-item></div>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" /></el-form-item></el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
    </el-dialog>
  </div>
</template>
