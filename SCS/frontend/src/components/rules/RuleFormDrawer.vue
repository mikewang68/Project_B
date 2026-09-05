<script setup lang="ts">
import { reactive, watch } from 'vue'
import { ElDrawer } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import { RULE_ACTIONS, RULE_AREAS, RULE_CATEGORIES, RULE_LEVELS, RULE_OWNERS, type RuleCategory, type RuleRisk, type SafetyRule } from '@/types/rule'

const props = defineProps<{ modelValue: boolean; rule: SafetyRule | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  save: [payload: RuleFormPayload, submitReview: boolean]
}>()

export interface RuleFormPayload {
  id: string
  name: string
  category: RuleCategory
  owner: string
  risk: RuleRisk
  areas: string[]
  params: Array<{ label: string; value: string; danger: boolean }>
  actions: string[]
  highRisk: boolean
  version: string
  editId?: string | undefined
}

const form = reactive<RuleFormPayload>({
  id: '', name: '', category: '人员安全', owner: '安全员 王建国', risk: '预警',
  areas: [], params: [], actions: [], highRisk: false, version: 'v1.0',
})

watch(() => props.modelValue, (open) => {
  if (!open) return
  if (props.rule) {
    const r = props.rule
    Object.assign(form, {
      id: r.id, name: r.name, category: r.category, owner: r.owner, risk: r.risk,
      areas: [...r.areas], params: r.params.map((p) => ({ label: p.label, value: p.value, danger: !!p.danger })),
      actions: [...r.actions], highRisk: r.highRisk, editId: r.id, version: r.version,
    })
  } else {
    Object.assign(form, {
      id: 'RULE-NEW-001', name: '', category: '人员安全', owner: '安全员 王建国', risk: '预警',
      areas: [], params: [{ label: '持续时间阈值', value: '', danger: false }], actions: [], highRisk: false, editId: undefined, version: 'v1.0',
    })
  }
})

function toggleArea(a: string): void {
  if (a === '全部区域') { form.areas = form.areas.includes('全部区域') ? [] : ['全部区域']; return }
  form.areas = form.areas.filter((x) => x !== '全部区域')
  form.areas = form.areas.includes(a) ? form.areas.filter((x) => x !== a) : [...form.areas, a]
}
function toggleAction(a: string): void {
  form.actions = form.actions.includes(a) ? form.actions.filter((x) => x !== a) : [...form.actions, a]
  form.highRisk = ['设备禁动', '设备停机', 'PLC 联动'].some((x) => form.actions.includes(x)) || form.risk === '紧急'
}
function addParam(): void { form.params.push({ label: '', value: '', danger: false }) }
function removeParam(i: number): void { form.params.splice(i, 1) }
function onRiskChange(): void { form.highRisk = form.risk === '紧急' || ['设备禁动', '设备停机', 'PLC 联动'].some((x) => form.actions.includes(x)) }

function valid(): boolean { return form.name.trim().length > 0 && form.areas.length > 0 && form.params.some((p) => p.label && p.value) }
function save(submit: boolean): void {
  if (!valid()) return
  emit('save', { ...form, areas: [...form.areas], params: form.params.map((p) => ({ ...p })), actions: [...form.actions] }, submit)
  emit('update:modelValue', false)
}
</script>

<template>
  <ElDrawer :model-value="modelValue" size="600px" :with-header="false" class="rule-form-drawer"
    @update:model-value="emit('update:modelValue', $event)">
    <header class="rule-form-header">
      <div><span>RULE {{ rule ? 'EDIT' : 'CREATE' }}</span>
        <h2>{{ rule ? '编辑规则' : '新建规则' }}</h2>
        <p>表单式配置，复杂规则引擎不在本阶段范围</p>
      </div>
    </header>
    <div class="rule-form-scroll">
      <section class="rule-form-block">
        <h4>基本信息</h4>
        <div class="rule-form-grid">
          <label><small>规则编号</small><el-input v-model="form.id" /></label>
          <label><small>规则名称 <i>*</i></small><el-input v-model="form.name" placeholder="例如：人员进入封闭作业区" /></label>
          <label><small>规则类型</small>
            <el-select v-model="form.category" style="width:100%">
              <el-option v-for="c in RULE_CATEGORIES" :key="c" :label="c" :value="c" />
            </el-select>
          </label>
          <label><small>负责人</small>
            <el-select v-model="form.owner" style="width:100%">
              <el-option v-for="o in RULE_OWNERS" :key="o" :label="o" :value="o" />
            </el-select>
          </label>
          <label class="wide"><small>风险等级</small>
            <div class="rule-seg">
              <button v-for="l in RULE_LEVELS" :key="l" type="button" :class="{ active: form.risk === l }" @click="form.risk = l; onRiskChange()">{{ l }}</button>
            </div>
          </label>
        </div>
      </section>

      <section class="rule-form-block">
        <h4>判定条件 / 参数</h4>
        <div class="rule-param-editor">
          <div v-for="(p, i) in form.params" :key="i" class="rule-param-editor__row">
            <el-input v-model="p.label" placeholder="参数名，如：预警距离" />
            <el-input v-model="p.value" placeholder="参数值，如：10m" />
            <label class="rule-param-editor__danger"><input v-model="p.danger" type="checkbox" />高危</label>
            <button type="button" class="rule-param-editor__del" @click="removeParam(i)"><el-icon><Delete /></el-icon></button>
          </div>
          <button type="button" class="rule-param-editor__add" @click="addParam"><el-icon><Plus /></el-icon>增加参数</button>
        </div>
      </section>

      <section class="rule-form-block">
        <h4>联动动作</h4>
        <div class="rule-form-tags">
          <button v-for="a in RULE_ACTIONS" :key="a" type="button" :class="{ active: form.actions.includes(a) }"
            @click="toggleAction(a)">{{ a }}</button>
        </div>
      </section>

      <section class="rule-form-block">
        <h4>适用范围</h4>
        <div class="rule-form-tags">
          <button v-for="a in RULE_AREAS" :key="a" type="button" :class="{ active: form.areas.includes(a) }"
            @click="toggleArea(a)">{{ a }}</button>
        </div>
      </section>

      <div v-if="form.highRisk" class="rule-form-highrisk">
        该规则包含紧急等级 / 设备禁动 / 停机 / PLC 联动等高危参数，提交与发布时需要二次确认。
      </div>
    </div>
    <footer class="rule-form-footer">
      <button type="button" class="rule-btn ghost" @click="emit('update:modelValue', false)">取消</button>
      <div>
        <button type="button" class="rule-btn" @click="save(false)">保存草稿</button>
        <button type="button" class="rule-btn primary" @click="save(true)">提交评审</button>
      </div>
    </footer>
  </ElDrawer>
</template>
