<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Check,
  Cpu,
  DataLine,
  Odometer,
  Operation,
  Refresh,
  Setting,
  Timer,
} from '@element-plus/icons-vue'
import AiSubNav from '@/components/ai/AiSubNav.vue'
import AiCaseRecognitionPanel from '@/components/ai/AiCaseRecognitionPanel.vue'
import type { AiModelInfo, UpdateModelConfigRequest } from '@/types/aiModel'
import { aiModelApi } from '@/api/aiModels'

const models = ref<AiModelInfo[]>([])
const loading = ref(false)
const selectedModelId = ref('')

// 编辑模型配置对话框
const configDialogVisible = ref(false)
const editingModel = ref<AiModelInfo | null>(null)
const configForm = ref<UpdateModelConfigRequest>({
  confidenceThreshold: 0.70,
  iouThreshold: 0.45,
  engine: 'TensorRT FP16',
})
const isSavingConfig = ref(false)

const selectedModel = computed(() =>
  models.value.find((m) => m.id === selectedModelId.value) ||
  models.value.find((m) => m.isSelected) ||
  models.value[0]
)

// 顶部指标聚合计算
const stats = computed(() => {
  const total = models.value.length
  const activeCount = models.value.filter((m) => m.status === 'ACTIVE').length
  const avgLatency = total > 0
    ? (models.value.reduce((acc, m) => acc + m.latencyMs, 0) / total).toFixed(1)
    : '38.5'
  const avgMap = total > 0
    ? ((models.value.reduce((acc, m) => acc + m.accuracyMap, 0) / total) * 100).toFixed(1)
    : '94.0'
  return { total, activeCount, avgLatency, avgMap }
})

async function loadModels(): Promise<void> {
  loading.value = true
  try {
    const list = await aiModelApi.list()
    models.value = list
    const currentActive = list.find((m) => m.isSelected)
    if (currentActive) {
      selectedModelId.value = currentActive.id
    } else if (list.length > 0 && !selectedModelId.value) {
      selectedModelId.value = list[0]!.id
    }
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '加载 AI 模型列表失败')
  } finally {
    loading.value = false
  }
}

// 选用模型
async function selectModel(id: string): Promise<void> {
  try {
    const updated = await aiModelApi.select(id)
    selectedModelId.value = updated.id
    ElMessage.success(`已成功选用模型【${updated.name}】为生效模型`)
    await loadModels()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '选用模型失败')
  }
}

// 打开调参配置弹窗
function openConfigDialog(model: AiModelInfo): void {
  editingModel.value = model
  configForm.value = {
    confidenceThreshold: model.confidenceThreshold,
    iouThreshold: model.iouThreshold,
    engine: model.engine,
    status: model.status,
  }
  configDialogVisible.value = true
}

// 保存调参配置
async function saveConfig(): Promise<void> {
  if (!editingModel.value) return
  isSavingConfig.value = true
  try {
    await aiModelApi.updateConfig(editingModel.value.id, configForm.value)
    ElMessage.success(`模型【${editingModel.value.name}】参数配置已更新`)
    configDialogVisible.value = false
    await loadModels()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '保存配置失败')
  } finally {
    isSavingConfig.value = false
  }
}

onMounted(() => {
  void loadModels()
})
</script>

<template>
  <section class="page-content ai-models-page">
    <!-- 顶部子导航（与 AI 违规复核无缝平级切换） -->
    <AiSubNav :active-model-name="selectedModel?.name" />

    <!-- 顶部核心指标统计 -->
    <div class="models-overview-grid">
      <div class="stat-card dashboard-card">
        <div class="stat-icon primary">
          <el-icon><Cpu /></el-icon>
        </div>
        <div class="stat-data">
          <small>工业AI模型库</small>
          <strong>{{ stats.total }} <span class="stat-unit">款</span></strong>
          <span class="stat-sub">涵盖PPE/禁区/烟火/姿态</span>
        </div>
      </div>

      <div class="stat-card dashboard-card">
        <div class="stat-icon success">
          <el-icon><Check /></el-icon>
        </div>
        <div class="stat-data">
          <small>当前生效选用模型</small>
          <strong class="stat-highlight">{{ selectedModel?.name || 'YOLOv8-Safety' }}</strong>
          <span class="stat-sub">{{ selectedModel?.engine }} · {{ selectedModel?.taskType }}</span>
        </div>
      </div>

      <div class="stat-card dashboard-card">
        <div class="stat-icon warning">
          <el-icon><Timer /></el-icon>
        </div>
        <div class="stat-data">
          <small>平均端到端推理时延</small>
          <strong>{{ stats.avgLatency }} <span class="stat-unit">ms</span></strong>
          <span class="stat-sub">边缘加速卡 NPU / TensorRT FP16</span>
        </div>
      </div>

      <div class="stat-card dashboard-card">
        <div class="stat-icon info">
          <el-icon><DataLine /></el-icon>
        </div>
        <div class="stat-data">
          <small>工业场景基准 mAP</small>
          <strong>{{ stats.avgMap }}<span class="stat-unit">%</span></strong>
          <span class="stat-sub">严格评测集 mAP@0.5 指标</span>
        </div>
      </div>
    </div>

    <!-- 核心板块 1：AI 视觉模型选用与参数管理 -->
    <div class="models-section dashboard-card">
      <div class="section-header">
        <div class="section-title">
          <el-icon><Operation /></el-icon>
          <h2>工业视觉 AI 模型库与选用管理</h2>
          <span class="badge-count">已接入 {{ models.length }} 款模型</span>
        </div>
        <div class="section-actions">
          <button type="button" class="btn-refresh" @click="loadModels">
            <el-icon><Refresh /></el-icon>
            <span>刷新模型状态</span>
          </button>
        </div>
      </div>

      <!-- 模型卡片网格 -->
      <div class="model-cards-grid">
        <div
          v-for="item in models"
          :key="item.id"
          class="model-card"
          :class="{
            'is-selected': item.isSelected,
            'is-active-card': selectedModelId === item.id
          }"
          @click="selectedModelId = item.id"
        >
          <!-- 选用角标 -->
          <div v-if="item.isSelected" class="selected-ribbon">
            <el-icon><Check /></el-icon>
            <span>当前选用生效中</span>
          </div>

          <div class="card-top">
            <div class="model-meta">
              <span class="task-badge">{{ item.taskType }}</span>
              <span class="engine-badge">{{ item.engine }}</span>
            </div>
            <h3 class="model-name">{{ item.name }}</h3>
            <p class="model-desc">{{ item.description }}</p>
          </div>

          <!-- 核心指标条 -->
          <div class="card-metrics">
            <div class="metric-item">
              <small>检测精度 mAP</small>
              <b>{{ (item.accuracyMap * 100).toFixed(1) }}%</b>
              <div class="mini-bar">
                <div class="mini-bar-fill" :style="{ width: `${item.accuracyMap * 100}%` }"></div>
              </div>
            </div>
            <div class="metric-item">
              <small>推理时延</small>
              <b>{{ item.latencyMs }} ms</b>
              <span class="fps-note">{{ item.fps }} FPS</span>
            </div>
            <div class="metric-item">
              <small>判定阈值</small>
              <b class="threshold-val">{{ (item.confidenceThreshold * 100).toFixed(0) }}%</b>
              <span class="fps-note">IoU: {{ item.iouThreshold }}</span>
            </div>
          </div>

          <!-- 类别标签 -->
          <div class="classes-row">
            <span class="classes-label">支持类别:</span>
            <div class="classes-tags">
              <span v-for="cls in item.supportedClasses" :key="cls" class="cls-tag">
                {{ cls }}
              </span>
            </div>
          </div>

          <!-- 底部操作按钮 -->
          <div class="card-footer">
            <span class="weight-note">{{ item.weightSize }} · {{ item.version }}</span>
            <div class="footer-buttons">
              <button
                type="button"
                class="btn-config"
                v-permission="'safety:module:ai:model:edit'" @click.stop="openConfigDialog(item)"
              >
                <el-icon><Setting /></el-icon>
                <span>调参配置</span>
              </button>

              <button
                v-if="!item.isSelected"
                type="button"
                class="btn-select"
                v-permission="'safety:module:ai:model:edit'" @click.stop="selectModel(item.id)"
              >
                <span>选用此模型</span>
              </button>
              <span v-else class="active-indicator">
                <el-icon><Check /></el-icon>已选用
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 核心板块 2：现场实际识别案例工作台（Real Recognition Case） -->
    <div class="case-section">
      <div class="section-banner">
        <div class="banner-title">
          <el-icon><Odometer /></el-icon>
          <h2>实际工况 AI 视觉识别案例工作台</h2>
          <span class="live-tag">真实前向推理闭环</span>
        </div>
        <p class="banner-subtitle">
          选用工业 AI 检测模型，在典型现场工况监控画面上执行目标检测识别，查看毫秒级推理耗时与目标标定，并可一键闭环生成安全告警
        </p>
      </div>

      <AiCaseRecognitionPanel
        :active-model="selectedModel"
        @model-selected="selectModel"
      />
    </div>

    <!-- 模型参数配置对话框 -->
    <el-dialog
      v-model="configDialogVisible"
      :title="`调优模型参数 · ${editingModel?.name}`"
      width="520px"
      append-to-body
    >
      <div class="config-modal-body">
        <div class="form-group">
          <label>置信度阈值 (Confidence Threshold: <b>{{ (configForm.confidenceThreshold! * 100).toFixed(0) }}%</b>)</label>
          <small>低于此分数的检测目标框将被过滤，防止边缘虚警误报</small>
          <el-slider
            v-model="configForm.confidenceThreshold"
            :min="0.25"
            :max="0.95"
            :step="0.05"
            show-input
          />
        </div>

        <div class="form-group">
          <label>NMS 重叠度阈值 (IoU Threshold: <b>{{ configForm.iouThreshold }}</b>)</label>
          <small>非极大值抑制交并比，消除同一违规目标的重叠重复检出框</small>
          <el-slider
            v-model="configForm.iouThreshold"
            :min="0.20"
            :max="0.80"
            :step="0.05"
            show-input
          />
        </div>

        <div class="form-group">
          <label>推理硬件与引擎</label>
          <el-select v-model="configForm.engine" style="width: 100%">
            <el-option label="NVIDIA TensorRT FP16 (高性能推荐)" value="TensorRT FP16" />
            <el-option label="ONNX Runtime CPU/GPU (通用跨平台)" value="ONNX Runtime" />
            <el-option label="Huawei Ascend CANN (昇腾国产化NPU)" value="Ascend CANN" />
            <el-option label="PyTorch LibTorch C++ (原生开发引擎)" value="PyTorch" />
          </el-select>
        </div>
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="configDialogVisible = false">取消</el-button>
          <el-button
 v-permission="'safety:module:ai:model:edit'"            type="primary"
            :loading="isSavingConfig"
            @click="saveConfig"
          >
            保存并应用配置
          </el-button>
        </span>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.ai-models-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 20px;
}

/* 顶部概览指标卡 */
.models-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  background: #ffffff;
  border-radius: 12px;
}
.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
}
.stat-icon.primary { background: #ecf5ff; color: #409eff; }
.stat-icon.success { background: #f0f9eb; color: #67c23a; }
.stat-icon.warning { background: #fdf6ec; color: #e6a23c; }
.stat-icon.info { background: #f4f4f5; color: #909399; }

.stat-data {
  display: flex;
  flex-direction: column;
}
.stat-data small {
  font-size: 12px;
  color: var(--color-text-secondary, #909399);
}
.stat-data strong {
  font-size: 20px;
  font-weight: 700;
  color: var(--color-text-primary, #303133);
  margin: 2px 0;
}
.stat-highlight {
  font-size: 15px !important;
  color: #409eff !important;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 170px;
}
.stat-unit {
  font-size: 13px;
  font-weight: normal;
  color: #909399;
}
.stat-sub {
  font-size: 11px;
  color: #a8abb2;
}

/* 模型管理列表卡片 */
.models-section {
  padding: 16px 20px;
  background: #ffffff;
  border-radius: 12px;
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.section-title h2 {
  font-size: 16px;
  font-weight: 700;
  color: #303133;
  margin: 0;
}
.badge-count {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  background: #f4f4f5;
  color: #909399;
}
.btn-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 6px;
  border: 1px solid #dcdfe6;
  background: #ffffff;
  font-size: 12px;
  color: #606266;
  cursor: pointer;
  transition: all 0.2s ease;
}
.btn-refresh:hover {
  color: #409eff;
  border-color: #409eff;
}

/* 模型卡片网格 */
.model-cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}
.model-card {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 14px 16px;
  background: #fafbfc;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  transition: all 0.25s ease;
  overflow: hidden;
  cursor: pointer;
}
.model-card:hover {
  transform: translateY(-2px);
  border-color: #b3d8ff;
  box-shadow: 0 4px 14px rgba(64,158,255,0.12);
}
.model-card.is-selected {
  border-color: #409eff;
  background: #fbfdff;
  box-shadow: 0 4px 14px rgba(64,158,255,0.18);
}
.model-card.is-active-card {
  outline: 2px solid #409eff;
}

.selected-ribbon {
  position: absolute;
  top: 10px;
  right: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 700;
  background: #f0f9eb;
  color: #67c23a;
  border: 1px solid #c2e7b0;
}

.card-top {
  margin-bottom: 12px;
}
.model-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.task-badge {
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  background: #ecf5ff;
  color: #409eff;
}
.engine-badge {
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 10px;
  background: #f4f4f5;
  color: #909399;
}
.model-name {
  font-size: 15px;
  font-weight: 700;
  color: #303133;
  margin: 0 0 6px 0;
}
.model-desc {
  font-size: 11px;
  color: #606266;
  line-height: 1.4;
  margin: 0;
  min-height: 32px;
}

.card-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  padding: 10px;
  background: #ffffff;
  border-radius: 8px;
  border: 1px solid #f0f2f5;
  margin-bottom: 10px;
}
.metric-item {
  display: flex;
  flex-direction: column;
}
.metric-item small {
  font-size: 10px;
  color: #909399;
}
.metric-item b {
  font-size: 13px;
  color: #303133;
  margin: 2px 0;
}
.threshold-val {
  color: #409eff !important;
}
.fps-note {
  font-size: 10px;
  color: #a8abb2;
}
.mini-bar {
  width: 100%;
  height: 3px;
  border-radius: 2px;
  background: #ebeef5;
  margin-top: 3px;
  overflow: hidden;
}
.mini-bar-fill {
  height: 100%;
  background: #67c23a;
}

.classes-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
  font-size: 11px;
}
.classes-label {
  color: #909399;
  white-space: nowrap;
}
.classes-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.cls-tag {
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 10px;
  background: #f0f2f5;
  color: #606266;
}

.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px dashed #ebeef5;
}
.weight-note {
  font-size: 11px;
  color: #a8abb2;
}
.footer-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}
.btn-config {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid #dcdfe6;
  background: #ffffff;
  color: #606266;
  font-size: 11px;
  cursor: pointer;
}
.btn-config:hover {
  color: #409eff;
  border-color: #409eff;
}
.btn-select {
  padding: 5px 12px;
  border-radius: 6px;
  border: none;
  background: #409eff;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s ease;
}
.btn-select:hover {
  background: #66b1ff;
}
.active-indicator {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  font-weight: 700;
  color: #67c23a;
}

/* 板块 2：实际识别案例横幅 */
.case-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.section-banner {
  padding: 12px 18px;
  background: linear-gradient(135deg, #1f2937 0%, #111827 100%);
  border-radius: 12px;
  color: #ffffff;
}
.banner-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.banner-title h2 {
  font-size: 15px;
  font-weight: 700;
  margin: 0;
}
.live-tag {
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  background: #10b981;
  color: #ffffff;
  font-weight: 700;
}
.banner-subtitle {
  font-size: 11px;
  color: #9ca3af;
  margin: 0;
}

/* 调参弹窗 */
.config-modal-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-group label {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.form-group label b {
  color: #409eff;
}
.form-group small {
  font-size: 11px;
  color: #909399;
}
</style>
