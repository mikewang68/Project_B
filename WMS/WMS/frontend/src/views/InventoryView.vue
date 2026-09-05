<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { listMasterData, type MasterItem } from '@/api/masterData'
import {
  adjustInventory, countInventory, createInventorySerial, freezeInventory, generateReplenishment, listBalances, listReplenishmentRules, listSerials,
  listTransactions, listWarnings, moveInventory, saveReplenishmentRule, unfreezeInventory, type InventoryBalance,
  type InventorySerial, type InventoryTransaction, type InventoryWarning, type ReplenishmentRule,
} from '@/api/inventory'

type OperationType='MOVE'|'FREEZE'|'UNFREEZE'|'COUNT'|'SERIAL'
const active=ref('balances'),loading=ref(false),saving=ref(false)
const balances=ref<InventoryBalance[]>([]),transactions=ref<InventoryTransaction[]>([]),warnings=ref<InventoryWarning[]>([]),serials=ref<InventorySerial[]>([])
const replenishmentRules=ref<ReplenishmentRule[]>([])
const goods=ref<MasterItem[]>([]),locations=ref<MasterItem[]>([])
const adjustDialog=ref(false),operationDialog=ref(false),operationType=ref<OperationType>('MOVE'),selected=ref<InventoryBalance|null>(null)
const ruleDialog=ref(false),editingRuleId=ref<number|null>(null)
const adjustForm=reactive({goodCode:'',locationCode:'',quantityAfter:0,batchCode:'',qualityType:'ZP',supplierCode:'',productDate:'',expireDate:'',lpn:'',remark:''})
const operationForm=reactive({quantity:1,destinationLocationCode:'',actualQuantity:0,serialCode:'',weightKg:0,source:'SYSTEM',remark:''})
const ruleForm=reactive({goodCode:'',locationCode:'',minQty:0,maxQty:0,status:'ENABLED',remark:''})
const operationTitle=computed(()=>({MOVE:'移库',FREEZE:'冻结',UNFREEZE:'解冻',COUNT:'盘点',SERIAL:'生成 RFID/唯一标签'}[operationType.value]))
const typeText:Record<string,string>={ADJUST:'库存调整',MOVE_OUT:'移库出',MOVE_IN:'移库入',FREEZE:'冻结',UNFREEZE:'解冻',COUNT:'盘点'}
const qualityText:Record<string,string>={ZP:'正品',CC:'残次',DJ:'待检',ZT:'在途',JS:'寄售',XS:'销售'}

function key(){return globalThis.crypto?.randomUUID?.()??`${Date.now()}-${Math.random()}`}
function message(reason:unknown,fallback:string){ElMessage.error(reason instanceof ApiError?reason.message:fallback)}
async function load(){loading.value=true;try{
  [balances.value,transactions.value,warnings.value,serials.value,replenishmentRules.value,goods.value,locations.value]=await Promise.all([
    listBalances(),listTransactions(),listWarnings(),listSerials(),listReplenishmentRules(),listMasterData('goods'),listMasterData('locations'),
  ])
}catch(e){message(e,'库存数据加载失败')}finally{loading.value=false}}
function openAdjust(){Object.assign(adjustForm,{goodCode:goods.value[0]?.code??'',locationCode:locations.value[0]?.code??'',quantityAfter:0,batchCode:'',qualityType:'ZP',supplierCode:'',productDate:'',expireDate:'',lpn:'',remark:''});adjustDialog.value=true}
function openOperation(type:OperationType,row:InventoryBalance){selected.value=row;operationType.value=type;Object.assign(operationForm,{quantity:1,destinationLocationCode:locations.value.find(x=>x.code!==row.locationCode)?.code??'',actualQuantity:Number(row.totalQty),serialCode:'',weightKg:0,source:'SYSTEM',remark:''});operationDialog.value=true}
function openRule(row?:ReplenishmentRule){editingRuleId.value=row?.id??null;Object.assign(ruleForm,{goodCode:row?.goodCode??goods.value[0]?.code??'',locationCode:row?.locationCode??locations.value[0]?.code??'',minQty:Number(row?.minQty??0),maxQty:Number(row?.maxQty??0),status:row?.status??'ENABLED',remark:row?.remark??''});ruleDialog.value=true}
async function submitAdjust(){if(!adjustForm.goodCode||!adjustForm.locationCode)return void ElMessage.warning('请选择货品和库位');saving.value=true;try{
  const result=await adjustInventory({...adjustForm,productDate:adjustForm.productDate||null,expireDate:adjustForm.expireDate||null,idempotencyKey:key()})
  ElMessage.success(`调整成功：${result.operationCode}`);adjustDialog.value=false;await load()
}catch(e){message(e,'库存调整失败')}finally{saving.value=false}}
async function submitOperation(){const row=selected.value;if(!row)return;saving.value=true;try{
  let code=''
  if(operationType.value==='MOVE'){const result=await moveInventory({balanceId:row.id,destinationLocationCode:operationForm.destinationLocationCode,quantity:operationForm.quantity,idempotencyKey:key(),remark:operationForm.remark});code=result.operationCode}
  if(operationType.value==='FREEZE'){const result=await freezeInventory({balanceId:row.id,quantity:operationForm.quantity,idempotencyKey:key(),remark:operationForm.remark});code=result.operationCode}
  if(operationType.value==='UNFREEZE'){const result=await unfreezeInventory({balanceId:row.id,quantity:operationForm.quantity,idempotencyKey:key(),remark:operationForm.remark});code=result.operationCode}
  if(operationType.value==='COUNT'){const result=await countInventory({balanceId:row.id,actualQuantity:operationForm.actualQuantity,idempotencyKey:key(),remark:operationForm.remark});code=result.operationCode}
  if(operationType.value==='SERIAL'){const result=await createInventorySerial({balanceId:row.id,serialCode:operationForm.serialCode,quantity:operationForm.quantity,weightKg:operationForm.weightKg,source:operationForm.source});code=result.serialCode}
  ElMessage.success(`${operationTitle.value}成功：${code}`);operationDialog.value=false;await load()
}catch(e){message(e,`${operationTitle.value}失败`)}finally{saving.value=false}}
async function submitRule(){if(ruleForm.maxQty<ruleForm.minQty)return void ElMessage.warning('补齐数量不能小于告警数量');saving.value=true;try{await saveReplenishmentRule(editingRuleId.value,ruleForm);ElMessage.success('补货规则已保存');ruleDialog.value=false;await load()}catch(e){message(e,'补货规则保存失败')}finally{saving.value=false}}
async function runReplenishment(){saving.value=true;try{const result=await generateReplenishment({idempotencyKey:key()});ElMessage.success(`补货完成：${result.operationCode}，共移动 ${qty(result.movedQuantity)}`);await load()}catch(e){message(e,'自动补货失败')}finally{saving.value=false}}
function qty(value:number){return Number(value).toLocaleString('zh-CN',{minimumFractionDigits:0,maximumFractionDigits:3})}
function time(value:string){return new Date(value).toLocaleString('zh-CN',{hour12:false})}
onMounted(load)
</script>

<template>
  <div class="page-stack">
    <header class="page-heading"><div><p class="eyebrow">INVENTORY LEDGER</p><h1>库存中心</h1><p>库存余额与业务流水同步入账，所有操作按当前公司、仓库和货主隔离。</p></div><div class="heading-actions"><el-button @click="load">刷新</el-button><el-button v-if="active==='replenishment'" @click="openRule()">新建补货规则</el-button><el-button v-if="active==='replenishment'" type="success" :loading="saving" @click="runReplenishment">生成补货移库</el-button><el-button type="primary" @click="openAdjust">库存调整</el-button></div></header>
    <section class="inventory-stats">
      <div><span>库存记录</span><strong>{{ balances.length }}</strong></div><div><span>库存总量</span><strong>{{ qty(balances.reduce((sum,x)=>sum+Number(x.totalQty),0)) }}</strong></div><div><span>冻结数量</span><strong>{{ qty(balances.reduce((sum,x)=>sum+Number(x.frozenQty),0)) }}</strong></div><div :class="{danger:warnings.length}"><span>库存预警</span><strong>{{ warnings.length }}</strong></div>
    </section>
    <section class="panel inventory-panel">
      <el-tabs v-model="active">
        <el-tab-pane label="库存余额" name="balances" /><el-tab-pane :label="`操作流水 ${transactions.length}`" name="transactions" /><el-tab-pane :label="`库存预警 ${warnings.length}`" name="warnings" /><el-tab-pane :label="`补货规则 ${replenishmentRules.length}`" name="replenishment" /><el-tab-pane :label="`唯一标签 ${serials.length}`" name="serials" />
      </el-tabs>
      <el-table v-if="active==='balances'" v-loading="loading" :data="balances" stripe>
        <el-table-column prop="goodCode" label="货品编码" min-width="115" /><el-table-column prop="goodName" label="货品名称" min-width="150" /><el-table-column prop="locationCode" label="库位" min-width="105" /><el-table-column prop="batchCode" label="批次" min-width="100"><template #default="s">{{ s.row.batchCode||'-' }}</template></el-table-column>
        <el-table-column label="质量" width="82"><template #default="s">{{ qualityText[s.row.qualityType]||s.row.qualityType }}</template></el-table-column><el-table-column label="可用" width="95" align="right"><template #default="s"><strong>{{ qty(s.row.availableQty) }}</strong></template></el-table-column><el-table-column label="已分配" width="90" align="right"><template #default="s">{{ qty(s.row.allocatedQty) }}</template></el-table-column><el-table-column label="冻结" width="90" align="right"><template #default="s"><span class="frozen-qty">{{ qty(s.row.frozenQty) }}</span></template></el-table-column><el-table-column label="总量" width="95" align="right"><template #default="s"><strong>{{ qty(s.row.totalQty) }}</strong></template></el-table-column>
        <el-table-column label="操作" width="255" fixed="right"><template #default="s"><el-button link type="primary" @click="openOperation('MOVE',s.row)">移库</el-button><el-button link type="warning" @click="openOperation('FREEZE',s.row)">冻结</el-button><el-button link :disabled="Number(s.row.frozenQty)<=0" @click="openOperation('UNFREEZE',s.row)">解冻</el-button><el-button link @click="openOperation('COUNT',s.row)">盘点</el-button><el-button link @click="openOperation('SERIAL',s.row)">标签</el-button></template></el-table-column>
      </el-table>
      <el-table v-else-if="active==='transactions'" v-loading="loading" :data="transactions" stripe><el-table-column prop="operationCode" label="业务单号" min-width="140" /><el-table-column label="类型" width="95"><template #default="s"><el-tag size="small" effect="plain">{{ typeText[s.row.transactionType]||s.row.transactionType }}</el-tag></template></el-table-column><el-table-column prop="goodCode" label="货品" min-width="110" /><el-table-column prop="locationCode" label="库位" min-width="95" /><el-table-column prop="relatedLocationCode" label="关联库位" min-width="95"><template #default="s">{{ s.row.relatedLocationCode||'-' }}</template></el-table-column><el-table-column label="变动" width="95" align="right"><template #default="s"><span :class="Number(s.row.quantityChange)>=0?'positive':'negative'">{{ Number(s.row.quantityChange)>0?'+':'' }}{{ qty(s.row.quantityChange) }}</span></template></el-table-column><el-table-column label="变动后" width="95" align="right"><template #default="s">{{ qty(s.row.quantityAfter) }}</template></el-table-column><el-table-column prop="operatorName" label="操作人" width="100" /><el-table-column label="时间" min-width="165"><template #default="s">{{ time(s.row.createdAt) }}</template></el-table-column></el-table>
      <el-table v-else-if="active==='warnings'" v-loading="loading" :data="warnings" stripe><el-table-column label="级别" width="100"><template #default="s"><el-tag :type="s.row.level==='LOW'?'warning':'danger'">{{ s.row.level==='LOW'?'低库存':'超储预警' }}</el-tag></template></el-table-column><el-table-column prop="goodCode" label="货品编码" /><el-table-column prop="goodName" label="货品名称" /><el-table-column label="当前库存" align="right"><template #default="s">{{ qty(s.row.currentQty) }}</template></el-table-column><el-table-column label="预警阈值" align="right"><template #default="s">{{ qty(s.row.thresholdQty) }}</template></el-table-column></el-table>
      <el-table v-else-if="active==='serials'" v-loading="loading" :data="serials" stripe><el-table-column prop="serialCode" label="标签编码" min-width="160" /><el-table-column prop="goodCode" label="货品编码" /><el-table-column prop="goodName" label="货品名称" /><el-table-column prop="locationCode" label="库位" /><el-table-column label="数量" align="right"><template #default="s">{{ qty(s.row.quantity) }}</template></el-table-column><el-table-column label="重量(kg)" align="right"><template #default="s">{{ qty(s.row.weightKg) }}</template></el-table-column><el-table-column prop="source" label="来源" /><el-table-column label="创建时间" min-width="165"><template #default="s">{{ time(s.row.createdAt) }}</template></el-table-column></el-table>
      <el-table v-else v-loading="loading" :data="replenishmentRules" stripe><el-table-column prop="goodCode" label="货品编码" min-width="120" /><el-table-column prop="goodName" label="货品名称" min-width="160" /><el-table-column prop="locationCode" label="目标库位" min-width="120" /><el-table-column label="当前数量" align="right"><template #default="s">{{ qty(s.row.currentQty) }}</template></el-table-column><el-table-column label="告警数量" align="right"><template #default="s">{{ qty(s.row.minQty) }}</template></el-table-column><el-table-column label="补齐数量" align="right"><template #default="s">{{ qty(s.row.maxQty) }}</template></el-table-column><el-table-column label="建议补货" align="right"><template #default="s"><strong :class="Number(s.row.suggestedQty)>0?'negative':''">{{ qty(s.row.suggestedQty) }}</strong></template></el-table-column><el-table-column label="状态" width="90"><template #default="s"><el-tag :type="s.row.status==='ENABLED'?'success':'info'">{{ s.row.status==='ENABLED'?'启用':'停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="90"><template #default="s"><el-button link type="primary" @click="openRule(s.row)">编辑</el-button></template></el-table-column></el-table>
    </section>

    <el-dialog v-model="adjustDialog" title="库存调整" width="680px">
      <el-alert title="调整到指定目标总量，系统会自动计算差异并生成流水。" type="info" :closable="false" show-icon />
      <el-form label-position="top" class="dialog-form"><div class="form-grid"><el-form-item label="货品"><el-select v-model="adjustForm.goodCode" filterable style="width:100%"><el-option v-for="x in goods" :key="x.id" :label="`${x.code} · ${x.name}`" :value="x.code" /></el-select></el-form-item><el-form-item label="库位"><el-select v-model="adjustForm.locationCode" filterable style="width:100%"><el-option v-for="x in locations" :key="x.id" :label="`${x.code} · ${x.name}`" :value="x.code" /></el-select></el-form-item></div><div class="form-grid"><el-form-item label="目标总量"><el-input-number v-model="adjustForm.quantityAfter" :min="0" :precision="3" style="width:100%" /></el-form-item><el-form-item label="批次"><el-input v-model="adjustForm.batchCode" placeholder="可选" /></el-form-item></div><div class="form-grid"><el-form-item label="质量类型"><el-select v-model="adjustForm.qualityType" style="width:100%"><el-option v-for="(label,value) in qualityText" :key="value" :label="label" :value="value" /></el-select></el-form-item><el-form-item label="LPN/容器号"><el-input v-model="adjustForm.lpn" /></el-form-item></div><el-form-item label="备注"><el-input v-model="adjustForm.remark" /></el-form-item></el-form>
      <template #footer><el-button @click="adjustDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitAdjust">确认调整</el-button></template>
    </el-dialog>
    <el-dialog v-model="operationDialog" :title="operationTitle" width="560px">
      <p v-if="selected" class="operation-context">{{ selected.goodCode }} · {{ selected.goodName }} / 库位 {{ selected.locationCode }} / 当前总量 {{ qty(selected.totalQty) }}</p>
      <el-form label-position="top">
        <el-form-item v-if="operationType==='MOVE'" label="目标库位"><el-select v-model="operationForm.destinationLocationCode" filterable style="width:100%"><el-option v-for="x in locations.filter(x=>x.code!==selected?.locationCode)" :key="x.id" :label="`${x.code} · ${x.name}`" :value="x.code" /></el-select></el-form-item>
        <el-form-item v-if="operationType==='COUNT'" label="实盘总量"><el-input-number v-model="operationForm.actualQuantity" :min="0" :precision="3" style="width:100%" /></el-form-item>
        <el-form-item v-if="['MOVE','FREEZE','UNFREEZE','SERIAL'].includes(operationType)" :label="operationType==='SERIAL'?'标签代表数量':'操作数量'"><el-input-number v-model="operationForm.quantity" :min="0.001" :precision="3" style="width:100%" /></el-form-item>
        <template v-if="operationType==='SERIAL'"><el-form-item label="标签编码"><el-input v-model="operationForm.serialCode" placeholder="留空由系统生成" /></el-form-item><el-form-item label="重量（kg）"><el-input-number v-model="operationForm.weightKg" :min="0" :precision="4" style="width:100%" /></el-form-item></template>
        <el-form-item v-else label="备注"><el-input v-model="operationForm.remark" /></el-form-item>
      </el-form><template #footer><el-button @click="operationDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitOperation">确认{{ operationTitle }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="ruleDialog" :title="editingRuleId?'编辑补货规则':'新建补货规则'" width="600px">
      <el-alert title="目标库位库存低于告警数量时，从其他库位按可用量由高到低补齐。" type="info" :closable="false" show-icon />
      <el-form label-position="top" class="dialog-form"><div class="form-grid"><el-form-item label="货品"><el-select v-model="ruleForm.goodCode" filterable :disabled="editingRuleId!==null" style="width:100%"><el-option v-for="x in goods" :key="x.id" :label="`${x.code} · ${x.name}`" :value="x.code" /></el-select></el-form-item><el-form-item label="目标库位"><el-select v-model="ruleForm.locationCode" filterable :disabled="editingRuleId!==null" style="width:100%"><el-option v-for="x in locations" :key="x.id" :label="`${x.code} · ${x.name}`" :value="x.code" /></el-select></el-form-item></div><div class="form-grid"><el-form-item label="告警数量"><el-input-number v-model="ruleForm.minQty" :min="0" :precision="3" style="width:100%" /></el-form-item><el-form-item label="补齐数量"><el-input-number v-model="ruleForm.maxQty" :min="0" :precision="3" style="width:100%" /></el-form-item></div><div class="form-grid"><el-form-item label="状态"><el-select v-model="ruleForm.status" style="width:100%"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item><el-form-item label="备注"><el-input v-model="ruleForm.remark" /></el-form-item></div></el-form>
      <template #footer><el-button @click="ruleDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitRule">保存规则</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.heading-actions{display:flex;gap:10px}.inventory-stats{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.inventory-stats>div{background:var(--el-bg-color);border:1px solid var(--el-border-color-lighter);border-radius:12px;padding:17px 20px;display:flex;align-items:center;justify-content:space-between}.inventory-stats span{color:var(--el-text-color-secondary);font-size:13px}.inventory-stats strong{font-size:24px;color:#183c65}.inventory-stats .danger strong{color:#d13c3c}.inventory-panel{overflow:hidden}.frozen-qty,.negative{color:#d77a16}.positive{color:#18835b}.dialog-form{margin-top:18px}.operation-context{padding:12px 14px;margin:0 0 18px;background:#f2f6fa;border-radius:8px;color:#45627f;font-size:13px}@media(max-width:900px){.inventory-stats{grid-template-columns:repeat(2,1fr)}}
</style>
