<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { iamApi } from '@/api/iam'
import type { HealthFinding, PermissionHealthReport, Severity } from '@/iam/insight'

const loading = ref(false)
const report = ref<PermissionHealthReport | null>(null)

const onlyHigh = ref(false)
const severityFilter = ref<'' | Severity>('')
const typeFilter = ref('')

async function load() {
  loading.value = true
  try {
    report.value = await iamApi.permissionHealth()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '诊断加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

const typeOptions = computed(() => {
  const set = new Set<string>()
  report.value?.findings.forEach((f) => set.add(f.type))
  return [...set].sort()
})

const filteredFindings = computed<HealthFinding[]>(() => {
  let list = report.value?.findings ?? []
  if (onlyHigh.value) list = list.filter((f) => f.severity === 'HIGH')
  if (severityFilter.value) list = list.filter((f) => f.severity === severityFilter.value)
  if (typeFilter.value) list = list.filter((f) => f.type === typeFilter.value)
  return list
})

function severityTagType(s: Severity): 'danger' | 'warning' | 'info' {
  return s === 'HIGH' ? 'danger' : s === 'MEDIUM' ? 'warning' : 'info'
}
</script>

<template>
  <div class="health-view" v-loading="loading">
    <!-- 概览 -->
    <el-card class="overview-card" shadow="never">
      <div class="overview-head">
        <span class="overview-title">权限健康诊断</span>
        <el-button type="primary" plain size="small" @click="load">
          <el-icon><Refresh /></el-icon>重新诊断
        </el-button>
      </div>
      <div class="stat-row">
        <div class="stat-item">
          <div class="stat-num">{{ report?.summary.userCount ?? '-' }}</div>
          <div class="stat-label">用户</div>
        </div>
        <div class="stat-item">
          <div class="stat-num">{{ report?.summary.roleCount ?? '-' }}</div>
          <div class="stat-label">角色</div>
        </div>
        <div class="stat-item">
          <div class="stat-num">{{ report?.summary.permissionCount ?? '-' }}</div>
          <div class="stat-label">权限</div>
        </div>
        <div class="stat-item stat-danger">
          <div class="stat-num">{{ report?.summary.high ?? 0 }}</div>
          <div class="stat-label">高风险</div>
        </div>
        <div class="stat-item stat-warning">
          <div class="stat-num">{{ report?.summary.medium ?? 0 }}</div>
          <div class="stat-label">中风险</div>
        </div>
        <div class="stat-item stat-info">
          <div class="stat-num">{{ report?.summary.low ?? 0 }}</div>
          <div class="stat-label">低风险</div>
        </div>
      </div>
    </el-card>

    <!-- 问题列表 -->
    <el-card class="findings-card" shadow="never">
      <div class="filter-bar">
        <span class="findings-title">诊断发现（{{ filteredFindings.length }}）</span>
        <el-checkbox v-model="onlyHigh">只看高风险</el-checkbox>
        <el-select v-model="severityFilter" placeholder="全部级别" clearable size="small" style="width: 120px">
          <el-option label="高风险" value="HIGH" />
          <el-option label="中风险" value="MEDIUM" />
          <el-option label="低风险" value="LOW" />
        </el-select>
        <el-select v-model="typeFilter" placeholder="全部类型" clearable size="small" style="width: 180px">
          <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
        </el-select>
      </div>

      <el-table :data="filteredFindings" stripe row-key="ruleId" style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-box">
              <div class="expand-section">
                <span class="expand-label">证据：</span>
                <el-tag
                  v-for="(ev, i) in row.evidence"
                  :key="i"
                  size="small"
                  class="evidence-tag"
                  :type="row.severity === 'HIGH' ? 'danger' : 'info'"
                >
                  {{ ev }}
                </el-tag>
              </div>
              <div class="expand-section">
                <span class="expand-label">建议：</span>
                <span class="recommend-text">{{ row.recommendation }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="severityTagType(row.severity)" size="small" effect="dark">
              {{ row.severity === 'HIGH' ? '高' : row.severity === 'MEDIUM' ? '中' : '低' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ruleId" label="规则" width="100" />
        <el-table-column prop="type" label="类型" width="190" />
        <el-table-column prop="title" label="问题" width="200" />
        <el-table-column prop="description" label="说明" min-width="260" show-overflow-tooltip />
      </el-table>

      <el-empty
        v-if="!loading && filteredFindings.length === 0"
        description="当前筛选条件下没有诊断发现"
      />
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.health-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.overview-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 18px;
}

.overview-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--iam-text-strong);
}

.stat-row {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.stat-item {
  flex: 1;
  min-width: 96px;
  padding: 12px 16px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
  text-align: center;

  .stat-num {
    font-size: 26px;
    font-weight: 700;
    color: var(--iam-text-strong);
    line-height: 1.2;
  }
  .stat-label {
    font-size: 12px;
    color: var(--iam-text-muted);
    margin-top: 2px;
  }
  &.stat-danger {
    background: var(--el-color-danger-light-9);
    .stat-num { color: var(--el-color-danger); }
  }
  &.stat-warning {
    background: var(--el-color-warning-light-9);
    .stat-num { color: var(--el-color-warning-dark-2); }
  }
  &.stat-info {
    background: var(--el-color-info-light-9);
    .stat-num { color: var(--el-color-info); }
  }
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}

.findings-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--iam-text-strong);
  margin-right: auto;
}

.expand-box {
  padding: 8px 20px 12px 48px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.expand-section {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
}

.expand-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--iam-text-strong);
  flex-shrink: 0;
  line-height: 24px;
}

.evidence-tag {
  margin: 0 6px 6px 0;
}

.recommend-text {
  font-size: 13px;
  color: var(--iam-text-base);
  line-height: 1.6;
}
</style>
