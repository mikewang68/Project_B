<script setup lang="ts">
import { Check, Close, Edit, RefreshRight, UploadFilled } from '@element-plus/icons-vue'
import StatusBadge from '@/components/shared/StatusBadge.vue'
import type { FenceDraft, FenceRecord } from '@/types/fence'

defineProps<{
  fence: FenceRecord | undefined
  editing: boolean
  isEditingExisting?: boolean
  draft: FenceDraft
  pointCount: number
}>()

defineEmits<{
  'update:draft': [draft: FenceDraft]
  edit: []
  cancel: []
  save: []
  review: []
  publish: []
  fail: []
  retry: []
}>()
</script>

<template>
  <aside class="fence-detail-card dashboard-card">
    <template v-if="editing">
      <header class="fence-panel-heading">
        <div>
          <span>{{ isEditingExisting ? 'EDIT GEOFENCE' : 'NEW GEOFENCE' }}</span>
          <h2>{{ isEditingExisting ? '编辑电子围栏' : '新建电子围栏' }}</h2>
          <p>{{ isEditingExisting ? '调整围栏参数与外围逻辑缓冲区隔离带' : '在地图上点击至少 3 个点形成闭合多边形' }}</p>
        </div>
        <b>{{ pointCount }} 点</b>
      </header>

      <div class="fence-form">
        <label>
          围栏名称
          <el-input :model-value="draft.name" placeholder="例如：作业警戒红线" @update:model-value="$emit('update:draft', { ...draft, name: $event })" />
        </label>
        <label>
          围栏类型
          <el-select :model-value="draft.kind" @update:model-value="$emit('update:draft', { ...draft, kind: $event })">
            <el-option v-for="item in ['危险区域','设备区域','临时围栏','预警区域','授权区域']" :key="item" :label="item" :value="item" />
          </el-select>
        </label>
        <label>
          外围逻辑缓冲区（隔离带距离 · 米）
          <el-input-number
            :model-value="draft.bufferDistance ?? 5"
            :min="1"
            :max="30"
            :step="1"
            controls-position="right"
            style="width: 100%"
            @update:model-value="$emit('update:draft', { ...draft, bufferDistance: Number($event) })"
          />
          <small style="color: var(--color-text-tertiary); display: block; margin-top: 4px; font-size: 11px;">
            人员进入隔离带时触发黄色手环震动预警，不达物理红线不紧急停机
          </small>
        </label>
        <label>
          风险等级
          <el-select :model-value="draft.riskLevel" @update:model-value="$emit('update:draft', { ...draft, riskLevel: $event })">
            <el-option label="一般" value="一般" />
            <el-option label="严重" value="严重" />
            <el-option label="紧急" value="紧急" />
          </el-select>
        </label>
        <label>
          适用班组
          <el-input :model-value="draft.teams" @update:model-value="$emit('update:draft', { ...draft, teams: $event })" />
        </label>
        <div class="form-two">
          <label>
            开始时间
            <el-input :model-value="draft.startsAt" @update:model-value="$emit('update:draft', { ...draft, startsAt: $event })" />
          </label>
          <label>
            结束时间
            <el-input :model-value="draft.endsAt" @update:model-value="$emit('update:draft', { ...draft, endsAt: $event })" />
          </label>
        </div>
      </div>

      <div class="fence-panel-actions">
        <button v-if="isEditingExisting" type="button" @click="$emit('cancel')">
          <el-icon><Close /></el-icon>取消编辑
        </button>
        <button v-permission="'safety:fence:edit'" type="button" @click="$emit('save')">
          {{ isEditingExisting ? '保存修改' : '保存草稿' }}
        </button>
        <button v-permission="'safety:fence:edit'" type="button" class="primary" @click="$emit('review')">
          <el-icon><Check /></el-icon>{{ isEditingExisting ? '保存并提交' : '提交评审' }}
        </button>
      </div>
    </template>

    <template v-else-if="fence">
      <header class="fence-panel-heading">
        <div>
          <span>{{ fence.id }}</span>
          <h2>{{ fence.name }}</h2>
          <p>{{ fence.kind }} · {{ fence.area }}</p>
        </div>
        <StatusBadge :status="fence.status" />
      </header>

      <dl class="fence-detail-list">
        <div><dt>当前版本</dt><dd>{{ fence.version }}</dd></div>
        <div><dt>状态</dt><dd><StatusBadge :status="fence.status" /></dd></div>
        <div class="wide">
          <dt>外围逻辑缓冲区（隔离带）</dt>
          <dd>
            <span style="color: #e6a23c; font-weight: 700;">{{ fence.bufferDistance ?? 5 }} 米隔离带</span>
            <small style="color: var(--color-text-tertiary); margin-left: 8px;">(靠近预警 / 未触红线不停机)</small>
          </dd>
        </div>
        <div><dt>生效时间</dt><dd>{{ fence.effectiveAt }}</dd></div>
        <div><dt>失效时间</dt><dd>{{ fence.expiresAt }}</dd></div>
        <div><dt>适用班组</dt><dd>{{ fence.teams }}</dd></div>
        <div><dt>审批人</dt><dd>{{ fence.approver }}</dd></div>
        <div class="wide">
          <dt>边缘节点下发状态</dt>
          <dd :class="{ warning: fence.edgeSynced < fence.edgeTotal }">
            {{ fence.edgeSynced }} / {{ fence.edgeTotal }} 已同步
          </dd>
        </div>
      </dl>

      <div class="publish-flow">
        <span class="done">草稿</span><i></i><span class="done">待评审</span><i></i>
        <span :class="{ done: fence.status !== '待评审' }">已批准</span><i></i>
        <span :class="{ done: fence.edgeSynced > 0 }">下发节点</span><i></i>
        <span :class="{ done: fence.status === '已生效' }">已生效</span>
      </div>

      <div v-if="fence.status === '版本不一致'" class="edge-warning">
        <b>EDGE-03 版本不一致</b>
        <p>当前规则版本与平台不一致，请重新下发。</p>
        <button v-permission="'safety:fence:publish'" type="button" @click="$emit('retry')">
          <el-icon><RefreshRight /></el-icon>重新下发
        </button>
      </div>

      <div class="edge-node-grid">
        <span v-for="node in fence.nodes" :key="node.id" :data-state="node.state">
          <i></i>{{ node.id }}
          <b>{{ node.state === 'success' ? '✓' : node.state === 'failed' ? '失败' : node.state === 'syncing' ? '下发中' : '待下发' }}</b>
        </span>
      </div>

      <div class="fence-panel-actions">
        <button v-permission="'safety:fence:edit'" type="button" @click="$emit('edit')">
          <el-icon><Edit /></el-icon>编辑围栏
        </button>
        <button v-if="fence.status === '已生效'" v-permission="'safety:fence:edit'" type="button" @click="$emit('fail')">模拟下发失败</button>
        <button v-if="fence.status === '草稿'" v-permission="'safety:fence:edit'" type="button" class="primary" @click="$emit('review')">
          <el-icon><Check /></el-icon>提交评审
        </button>
        <button v-else-if="['待发布','待评审'].includes(fence.status)" v-permission="'safety:fence:publish'" type="button" class="primary" @click="$emit('publish')">
          <el-icon><UploadFilled /></el-icon>发布
        </button>
      </div>
    </template>
  </aside>
</template>
