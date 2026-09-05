<script setup lang="ts">
import { Close, WarningFilled } from '@element-plus/icons-vue'
import { ElDrawer } from 'element-plus'
import RiskBadge from '@/components/shared/RiskBadge.vue'
import RuleStatusBadge from './RuleStatusBadge.vue'
import EdgeSyncPanel from './EdgeSyncPanel.vue'
import RuleVersionHistory from './RuleVersionHistory.vue'
import { ruleActionsOf, type SafetyRule } from '@/types/rule'

defineProps<{ modelValue: boolean; rule: SafetyRule | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  action: [action: string, rule: SafetyRule]
  edit: [rule: SafetyRule]
  simulate: [rule: SafetyRule]
}>()
</script>

<template>
  <ElDrawer :model-value="modelValue" size="640px" :with-header="false" class="rule-detail-drawer"
    @update:model-value="emit('update:modelValue', $event)">
    <template v-if="rule">
      <header class="rule-drawer-header">
        <div class="rule-drawer-header__main">
          <span>RULE DETAIL</span>
          <h2>{{ rule.name }}</h2>
          <p>{{ rule.id }} · {{ rule.category }}</p>
        </div>
        <div class="rule-drawer-header__badges">
          <RiskBadge :risk="rule.risk" />
          <RuleStatusBadge :status="rule.status" />
          <button type="button" aria-label="关闭" @click="emit('update:modelValue', false)"><el-icon><Close /></el-icon></button>
        </div>
      </header>

      <div class="rule-drawer-scroll">
        <div v-if="rule.status === '版本异常'" class="rule-mismatch-banner">
          <el-icon><WarningFilled /></el-icon>
          <span>检测到边缘节点规则版本与平台不一致，AI 判定与联动可能使用旧版本，请重新下发。</span>
        </div>

        <dl class="rule-base-grid">
          <div><dt>规则类型</dt><dd>{{ rule.category }}</dd></div>
          <div><dt>当前版本</dt><dd class="mono">{{ rule.version }}</dd></div>
          <div><dt>风险等级</dt><dd><RiskBadge :risk="rule.risk" /></dd></div>
          <div><dt>负责人</dt><dd>{{ rule.owner }}</dd></div>
          <div class="wide"><dt>适用范围</dt><dd>{{ rule.areas.join('、') }}</dd></div>
          <div><dt>审批人</dt><dd>{{ rule.approver }}</dd></div>
          <div><dt>生效时间</dt><dd>{{ rule.effectiveAt }}</dd></div>
          <div class="wide"><dt>更新时间</dt><dd>{{ rule.updatedAt }}</dd></div>
        </dl>

        <section class="rule-section">
          <h4>规则说明</h4>
          <p class="rule-desc">{{ rule.description }}</p>
        </section>

        <section class="rule-section">
          <h4>判定条件 / 参数</h4>
          <ul class="rule-param-list">
            <li v-for="p in rule.params" :key="p.label" :data-danger="!!p.danger">
              <span class="rule-param__label">
                {{ p.label }}
                <i v-if="p.danger" class="rule-param__danger">高危</i>
              </span>
              <b>{{ p.value }}</b>
            </li>
          </ul>
        </section>

        <section class="rule-section">
          <h4>联动 / 处置动作</h4>
          <div class="rule-action-tags">
            <span v-for="a in rule.actions" :key="a" class="rule-action-tag">{{ a }}</span>
            <span v-if="!rule.actions.length" class="rule-action-empty">无联动动作</span>
          </div>
        </section>

        <section class="rule-section">
          <h4>关联模块</h4>
          <div class="rule-related">
            <span v-for="m in rule.relatedModules" :key="m">{{ m }}</span>
          </div>
        </section>

        <EdgeSyncPanel :nodes="rule.edgeNodes" :platform-version="rule.platformVersion" />
        <RuleVersionHistory :rule="rule" />
      </div>

      <footer class="rule-drawer-footer">
        <button type="button" class="rule-btn ghost" @click="emit('simulate', rule)">模拟测试</button>
        <div class="rule-drawer-footer__right">
          <button v-for="a in ruleActionsOf(rule.status)" :key="a" type="button"
            class="rule-btn" :class="{ primary: ['提交评审', '批准', '发布', '重新下发'].includes(a), danger: a === '回滚' }"
            @click="emit('action', a, rule)">{{ a }}</button>
        </div>
      </footer>
    </template>
  </ElDrawer>
</template>
