<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Bell,
  Check,
  Cpu,
  Monitor,
  Refresh,
  VideoPlay,
  Warning,
} from '@element-plus/icons-vue'
import type { AiInferenceRequest, AiInferenceResult, AiModelInfo, DetectedObjectBox } from '@/types/aiModel'
import { aiModelApi } from '@/api/aiModels'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const canExecute = computed(() => !!auth.user && !auth.user.readOnly && auth.can('safety:module:ai:model:execute'))

const props = defineProps<{
  activeModel?: AiModelInfo | undefined
}>()

defineEmits<{
  modelSelected: [modelId: string]
}>()

// 预设典型实际识别案例
const SCENARIOS = [
  {
    id: 'ppe_violation',
    name: '作业人员劳保穿戴合规(安全帽/反光衣)',
    tag: 'PPE检测',
    area: '装卸区 A-03 机位',
    camera: 'CAM-LOAD-01',
    modelRecommend: 'MODEL-YOLO-PPE-01',
    description: '针对作业人员未戴安全帽、未穿反光背心等违章行为的高精度检测',
  },
  {
    id: 'danger_intrusion',
    name: '龙门吊重载禁区人员越界入侵',
    tag: '禁区入侵',
    area: '龙门吊 2 号主轨道',
    camera: 'CAM-CRANE-02',
    modelRecommend: 'MODEL-YOLO-DANGER-02',
    description: '结合现场红线边界，实时识别人员擅自跨越进入起重机作业盲区',
  },
  {
    id: 'fire_smoke',
    name: '露天堆料区初起火灾与烟雾环境异常',
    tag: '烟火识别',
    area: '散货堆料仓 B2',
    camera: 'CAM-YARD-04',
    modelRecommend: 'MODEL-DETR-FIRE-03',
    description: '捕捉堆场微小明火与可疑烟雾，智能过滤扬尘干扰，防患于未然',
  },
  {
    id: 'fall_detected',
    name: '装卸高台作业人员跌倒与异常姿态',
    tag: '姿态跌倒',
    area: '集装箱平台步道',
    camera: 'CAM-PLAT-01',
    modelRecommend: 'MODEL-POSE-FALL-04',
    description: '基于人体骨骼关键点追踪，对人员打滑摔倒、久卧不起进行紧急响应',
  },
  {
    id: 'crane_sway',
    name: '门式起重机吊具剧烈偏摆防撞预警',
    tag: '偏摆防撞',
    area: '龙门吊小车吊装位',
    camera: 'CAM-CRANE-TOP-01',
    modelRecommend: 'MODEL-CRANE-SWAY-05',
    description: '实时视觉解算吊钩偏摆角与地面设施相对间距，提供超前防撞卡控',
  },
]

const currentScenario = ref('ppe_violation')
const inferThreshold = ref(0.70)
const isInferring = ref(false)
const showBoxes = ref(true)
const selectedBox = ref<DetectedObjectBox | null>(null)
const inferenceResult = ref<AiInferenceResult | null>(null)
const isEscalating = ref(false)
const escalatedAlertId = ref('')

const activeScenarioInfo = computed(() => {
  const found = SCENARIOS.find((s) => s.id === currentScenario.value)
  return found ?? SCENARIOS[0]!
})

watch(
  () => props.activeModel?.confidenceThreshold,
  (val) => {
    if (val && val > 0) inferThreshold.value = val
  },
  { immediate: true }
)

// 切换案例时自动重置或执行推理
function selectScenario(scenarioId: string): void {
  currentScenario.value = scenarioId
  inferenceResult.value = null
  selectedBox.value = null
  escalatedAlertId.value = ''
}

// 执行实际识别推理
async function executeInference(): Promise<void> {
  if (!canExecute.value) return
  isInferring.value = true
  selectedBox.value = null
  escalatedAlertId.value = ''
  try {
    const payload: AiInferenceRequest = {
      scenario: currentScenario.value,
      confidenceThreshold: inferThreshold.value,
    }
    if (props.activeModel?.id) {
      payload.modelId = props.activeModel.id
    }
    const res = await aiModelApi.infer(payload)
    inferenceResult.value = res
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : 'AI 识别推理失败')
  } finally {
    isInferring.value = false
  }
}

// 一键上报为正式安全告警
async function escalateToAlert(): Promise<void> {
  if (!canExecute.value || !inferenceResult.value) return
  isEscalating.value = true
  try {
    const res = await aiModelApi.escalate({
      inferenceId: inferenceResult.value.inferenceId,
      modelId: inferenceResult.value.modelId,
      modelName: inferenceResult.value.modelName,
      scenario: inferenceResult.value.scenario,
      area: activeScenarioInfo.value.area,
      riskLevel: inferenceResult.value.riskLevel,
      summary: inferenceResult.value.summary,
      imageSource: inferenceResult.value.imageSource,
      objects: inferenceResult.value.detectedObjects,
    })
    if (res.success) {
      escalatedAlertId.value = res.alertId
      ElMessage.success({
        message: `${res.message}，可在告警中心流转处理`,
        duration: 5000,
      })
    }
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '告警上报失败')
  } finally {
    isEscalating.value = false
  }
}

function goToAlerts(): void {
  void router.push('/alarms')
}

// 查看模型页面和切换案例均不自动执行；执行需要显式操作和当前权限。
</script>

<template>
  <div class="ai-case-workspace">
    <!-- 案例切换横条 -->
    <div class="scenario-selector-bar dashboard-card">
      <div class="selector-heading">
        <el-icon><Monitor /></el-icon>
        <span>选择识别案例：</span>
      </div>
      <div class="scenario-chips">
        <button
          v-for="item in SCENARIOS"
          :key="item.id"
          type="button"
          class="scenario-chip"
          :class="{ 'is-active': currentScenario === item.id }"
          @click="selectScenario(item.id)"
        >
          <span class="chip-tag">{{ item.tag }}</span>
          <span class="chip-name">{{ item.name }}</span>
        </button>
      </div>
    </div>

    <!-- 核心工作区：左侧监控画面 + 目标框标定，右侧推理控制 + 结构化结果 + 闭环上报 -->
    <div class="case-main-grid">
      <!-- 左侧：真实监控画面与 SVG 矢量目标框 -->
      <div class="video-monitor-card dashboard-card">
        <div class="monitor-header">
          <div class="monitor-title">
            <span class="rec-dot"></span>
            <b>案例示意画面（非现场视频） · {{ activeScenarioInfo.camera }}</b>
            <small>{{ activeScenarioInfo.area }}</small>
          </div>
          <div class="monitor-tools">
            <label class="toggle-boxes">
              <input v-model="showBoxes" type="checkbox" />
              <span>标注检测框</span>
            </label>
            <span class="model-tag">
              <el-icon><Cpu /></el-icon>
              {{ activeModel?.name || 'YOLOv8-Safety' }}
            </span>
          </div>
        </div>

        <div class="monitor-screen" :class="{ 'is-inferring': isInferring }">
          <!-- 工业监控 OSD 水印 -->
          <div class="osd-overlay top-left">
            <span>{{ activeScenarioInfo.camera }} (4K UHD)</span>
            <span>FPS: {{ activeModel?.fps || 25.0 }} | 1080P@50Hz</span>
          </div>
          <div class="osd-overlay top-right">
            <span>{{ inferenceResult?.timestamp || '未执行' }}</span>
          </div>

          <!-- 模拟现场高清矢量监控画面 -->
          <svg class="monitor-svg" viewBox="0 0 100 100" preserveAspectRatio="none">
            <defs>
              <linearGradient id="yardBg" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#2c3e50" />
                <stop offset="55%" stop-color="#34495e" />
                <stop offset="100%" stop-color="#1a252f" />
              </linearGradient>
              <pattern id="grid" width="10" height="10" patternUnits="userSpaceOnUse">
                <path d="M 10 0 L 0 0 0 10" fill="none" stroke="rgba(255,255,255,0.04)" stroke-width="0.3" />
              </pattern>
            </defs>

            <!-- 场地底色与网格 -->
            <rect width="100" height="100" fill="url(#yardBg)" />
            <rect width="100" height="100" fill="url(#grid)" />

            <!-- 案例场景环境构件 -->
            <!-- 龙门吊支架与钢梁 -->
            <path d="M 10 10 L 25 80 M 90 10 L 75 80 M 10 10 L 90 10" stroke="#7f8c8d" stroke-width="1.2" fill="none" />
            <!-- 地面导轨与警戒线 -->
            <line x1="5" y1="78" x2="95" y2="78" stroke="#f1c40f" stroke-width="0.8" stroke-dasharray="2, 1" />
            <line x1="5" y1="84" x2="95" y2="84" stroke="#e74c3c" stroke-width="1" stroke-dasharray="3, 1" />

            <!-- 集装箱堆叠示意 -->
            <rect x="70" y="45" width="22" height="30" fill="#2980b9" opacity="0.6" stroke="#3498db" stroke-width="0.5" />
            <rect x="73" y="22" width="20" height="23" fill="#d35400" opacity="0.6" stroke="#e67e22" stroke-width="0.5" />

            <!-- 案例特有场景元素 -->
            <g v-if="currentScenario === 'ppe_violation'">
              <!-- 人员 1：违章作业人员（未佩戴安全帽，未穿反光背心） -->
              <circle cx="35.5" cy="29" r="4.2" fill="#e0a96d" />
              <rect x="30" y="38" width="11" height="28" rx="2" fill="#2c3e50" />
              <!-- 人员 2：合规人员（戴白色安全帽，穿橙色反光衣） -->
              <circle cx="74" cy="30" r="4.2" fill="#ecf0f1" />
              <path d="M 70 27 Q 74 22 78 27 Z" fill="#ffffff" stroke="#bdc3c7" stroke-width="0.4" />
              <rect x="68" y="38" width="12" height="28" rx="2" fill="#e67e22" />
              <rect x="71" y="42" width="6" height="18" fill="#f1c40f" opacity="0.8" />
            </g>

            <g v-else-if="currentScenario === 'danger_intrusion'">
              <!-- 人员翻越红线进入禁区 -->
              <circle cx="51" cy="45" r="4.5" fill="#e0a96d" />
              <rect x="46" y="52" width="10" height="24" rx="2" fill="#34495e" />
              <rect x="35" y="32" width="38" height="56" fill="rgba(231,76,60,0.12)" stroke="#e74c3c" stroke-width="0.8" stroke-dasharray="2, 1" />
            </g>

            <g v-else-if="currentScenario === 'fire_smoke'">
              <!-- 烟雾团块与火点 -->
              <ellipse cx="65" cy="34" rx="13" ry="16" fill="rgba(236,240,241,0.3)" />
              <ellipse cx="62" cy="28" rx="10" ry="12" fill="rgba(189,195,199,0.35)" />
              <circle cx="66" cy="45" r="5" fill="#e74c3c" />
              <circle cx="66" cy="45" r="2.5" fill="#f39c12" />
            </g>

            <g v-else-if="currentScenario === 'fall_detected'">
              <!-- 人员摔倒横卧 -->
              <rect x="33" y="58" width="34" height="12" rx="3" fill="#34495e" />
              <circle cx="37" cy="64" r="4" fill="#e0a96d" />
            </g>

            <g v-else-if="currentScenario === 'crane_sway'">
              <!-- 吊钩与倾斜集装箱 -->
              <line x1="50" y1="10" x2="56" y2="35" stroke="#95a5a6" stroke-width="0.8" />
              <polygon points="46,35 66,38 64,56 44,53" fill="#e74c3c" opacity="0.75" stroke="#c0392b" stroke-width="0.6" />
            </g>

            <!-- AI 目标检测结果框（Bounding Box） -->
            <template v-if="showBoxes && inferenceResult && !isInferring">
              <g
                v-for="(box, idx) in inferenceResult.detectedObjects"
                :key="idx"
                class="detection-box-group"
                :class="{
                  'is-violation': box.isViolation,
                  'is-selected': selectedBox === box,
                  'is-severe': box.riskLevel === 'SEVERE',
                  'is-warning': box.riskLevel === 'WARNING',
                  'is-normal': box.riskLevel === 'NORMAL'
                }"
                @click="selectedBox = box"
              >
                <!-- 矩形边框 -->
                <rect
                  :x="box.x"
                  :y="box.y"
                  :width="box.width"
                  :height="box.height"
                  class="bbox-rect"
                />

                <!-- 四个直角强化准星 -->
                <path
                  :d="`
                    M ${box.x} ${box.y + 2} L ${box.x} ${box.y} L ${box.x + 2} ${box.y}
                    M ${box.x + box.width - 2} ${box.y} L ${box.x + box.width} ${box.y} L ${box.x + box.width} ${box.y + 2}
                    M ${box.x} ${box.y + box.height - 2} L ${box.x} ${box.y + box.height} L ${box.x + 2} ${box.y + box.height}
                    M ${box.x + box.width - 2} ${box.y + box.height} L ${box.x + box.width} ${box.y + box.height} L ${box.x + box.width} ${box.y + box.height - 2}
                  `"
                  class="corner-mark"
                />

                <!-- 标签背景与文本 -->
                <rect
                  :x="box.x"
                  :y="Math.max(1, box.y - 4.5)"
                  :width="Math.min(30, box.width + 8)"
                  height="4.2"
                  class="bbox-tag-bg"
                />
                <text
                  :x="box.x + 0.8"
                  :y="Math.max(3.8, box.y - 1.2)"
                  class="bbox-tag-text"
                >
                  {{ box.labelZh }} {{ Math.round(box.confidence * 100) }}%
                </text>
              </g>
            </template>
          </svg>

          <!-- 推理加载中动画 -->
          <div v-if="isInferring" class="inferring-overlay">
            <div class="infer-spinner"></div>
            <span>神经网络前向推理中 (NPU/TensorRT)…</span>
          </div>
        </div>

        <div class="monitor-footer">
          <span>* 提示：点击监控画面中的检测框可定位右侧目标详情；支持通过阈值滑块过滤低置信度候选框。</span>
        </div>
      </div>

      <!-- 右侧：控制面板 + 结构化识别明细 + 业务闭环 -->
      <div class="case-sidebar-col">
        <!-- 推理控制与模型选用提示卡片 -->
        <div class="infer-control-card dashboard-card">
          <div class="control-header">
            <h3>AI 推理执行引擎</h3>
            <span class="engine-badge">{{ activeModel?.engine || 'TensorRT FP16' }}</span>
          </div>

          <div class="control-param-row">
            <div class="param-label">
              <span>当前选用模型：</span>
              <b>{{ activeModel?.name || 'YOLOv8-SafetyHelmet-v2.3' }}</b>
            </div>
            <div class="param-threshold">
              <span>置信度阈值 (Score): <b>{{ (inferThreshold * 100).toFixed(0) }}%</b></span>
              <el-slider
                v-model="inferThreshold"
                :min="0.30"
                :max="0.95"
                :step="0.05"
                @change="executeInference"
              />
            </div>
          </div>

          <div class="control-actions">
            <button
 v-permission="'safety:module:ai:model:execute'"              type="button"
              class="primary-infer-btn"
              :disabled="isInferring"
              @click="executeInference"
            >
              <el-icon><VideoPlay /></el-icon>
              <span>{{ isInferring ? '模型推理计算中…' : '执行 AI 智能识别推理' }}</span>
            </button>
            <button
 v-permission="'safety:module:ai:model:execute'"              type="button"
              class="refresh-btn"
              title="重新检测"
              @click="executeInference"
            >
              <el-icon><Refresh /></el-icon>
            </button>
          </div>
        </div>

        <!-- 识别结论与明细卡片 -->
        <div v-if="inferenceResult" class="infer-result-card dashboard-card">
          <div class="result-header">
            <div class="result-title">
              <h3>识别分析报告</h3>
              <span class="latency-pill">耗时: {{ inferenceResult.latencyMs }} ms</span>
            </div>
            <span
              class="status-pill"
              :class="{
                'is-danger': inferenceResult.violationCount > 0,
                'is-safe': inferenceResult.violationCount === 0
              }"
            >
              {{ inferenceResult.violationCount > 0 ? `检出 ${inferenceResult.violationCount} 项违规` : '未发现违规' }}
            </span>
          </div>

          <!-- 诊断结论 -->
          <div class="summary-box" :class="{ 'has-violation': inferenceResult.violationCount > 0 }">
            <el-icon><Warning /></el-icon>
            <div>
              <strong>{{ inferenceResult.summary }}</strong>
              <p>{{ inferenceResult.suggestion }}</p>
            </div>
          </div>

          <!-- 检出目标明细表 -->
          <div class="objects-list-wrapper">
            <h4>目标检测清单 ({{ inferenceResult.detectedObjects.length }} 个候选目标)</h4>
            <div class="objects-table">
              <div
                v-for="(obj, i) in inferenceResult.detectedObjects"
                :key="i"
                class="object-row"
                :class="{
                  'is-active': selectedBox === obj,
                  'is-violation': obj.isViolation
                }"
                @click="selectedBox = obj"
              >
                <div class="obj-badge" :data-risk="obj.riskLevel">
                  {{ obj.isViolation ? '违规' : '合规' }}
                </div>
                <div class="obj-meta">
                  <b>{{ obj.labelZh }}</b>
                  <small>{{ obj.note }}</small>
                </div>
                <div class="obj-score">
                  <span>{{ (obj.confidence * 100).toFixed(1) }}%</span>
                  <div class="score-bar-bg">
                    <div class="score-bar-fill" :style="{ width: `${obj.confidence * 100}%` }"></div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 业务闭环：一键上报为正式安全告警 -->
          <div class="escalate-section">
            <template v-if="escalatedAlertId">
              <div class="escalated-success-card">
                <el-icon><Check /></el-icon>
                <div>
                  <strong>已上报生成系统安全告警</strong>
                  <p>告警单号: <b>{{ escalatedAlertId }}</b>（已推送五端与广播）</p>
                </div>
                <button type="button" class="view-alert-link" @click="goToAlerts">
                  前往告警中心处置
                </button>
              </div>
            </template>
            <template v-else-if="inferenceResult.violationCount > 0">
              <button
 v-permission="'safety:module:ai:model:execute'"                type="button"
                class="escalate-btn"
                :disabled="isEscalating"
                @click="escalateToAlert"
              >
                <el-icon><Bell /></el-icon>
                <span>{{ isEscalating ? '上报建单中…' : '一键上报为安全管控中心告警单（业务闭环）' }}</span>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-case-workspace {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 顶部案例选择条 */
.scenario-selector-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 16px;
  border-radius: 12px;
  background: #ffffff;
}
.selector-heading {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text-primary, #303133);
  white-space: nowrap;
}
.scenario-chips {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.scenario-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 8px;
  border: 1px solid var(--color-border, #dcdfe6);
  background: #ffffff;
  color: var(--color-text-regular, #606266);
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s ease;
}
.scenario-chip:hover {
  border-color: var(--color-primary, #409eff);
  color: var(--color-primary, #409eff);
  background: #f0f7ff;
}
.scenario-chip.is-active {
  border-color: var(--color-primary, #409eff);
  background: #ecf5ff;
  color: var(--color-primary, #409eff);
  font-weight: 700;
  box-shadow: 0 2px 6px rgba(64,158,255,0.15);
}
.chip-tag {
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 10px;
  background: #ebeef5;
  color: #909399;
}
.scenario-chip.is-active .chip-tag {
  background: #409eff;
  color: #ffffff;
}

/* 主栅格 */
.case-main-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(360px, 1fr);
  gap: 14px;
  align-items: start;
}

/* 监控画面卡片 */
.video-monitor-card {
  padding: 14px;
  background: #ffffff;
  border-radius: 12px;
}
.monitor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.monitor-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rec-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f56c6c;
  animation: pulse-red 1.5s infinite;
}
@keyframes pulse-red {
  0% { opacity: 0.4; }
  50% { opacity: 1; }
  100% { opacity: 0.4; }
}
.monitor-title b {
  font-size: 14px;
  color: #303133;
}
.monitor-title small {
  font-size: 11px;
  color: #909399;
}
.monitor-tools {
  display: flex;
  align-items: center;
  gap: 12px;
}
.toggle-boxes {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #606266;
  cursor: pointer;
}
.model-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11px;
  background: #f4f4f5;
  color: #606266;
}

.monitor-screen {
  position: relative;
  width: 100%;
  height: 400px;
  background: #000000;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: inset 0 0 20px rgba(0,0,0,0.8);
}
.monitor-svg {
  width: 100%;
  height: 100%;
  display: block;
}
.osd-overlay {
  position: absolute;
  z-index: 10;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-family: monospace;
  font-size: 11px;
  color: #00ff66;
  text-shadow: 0 1px 3px rgba(0,0,0,0.9);
  pointer-events: none;
}
.osd-overlay.top-left { top: 10px; left: 12px; }
.osd-overlay.top-right { top: 10px; right: 12px; align-items: flex-end; }

/* 目标检测框 SVG 样式 */
.detection-box-group {
  cursor: pointer;
}
.bbox-rect {
  fill: rgba(64, 158, 255, 0.15);
  stroke: #409eff;
  stroke-width: 0.6;
  transition: all 0.2s ease;
}
.corner-mark {
  stroke: #409eff;
  stroke-width: 1.2;
  fill: none;
}
.bbox-tag-bg {
  fill: #409eff;
  rx: 0.8;
}
.bbox-tag-text {
  font-size: 2.4px;
  font-weight: 700;
  fill: #ffffff;
}

.detection-box-group.is-severe .bbox-rect {
  fill: rgba(245, 108, 108, 0.22);
  stroke: #f56c6c;
}
.detection-box-group.is-severe .corner-mark {
  stroke: #f56c6c;
}
.detection-box-group.is-severe .bbox-tag-bg {
  fill: #f56c6c;
}

.detection-box-group.is-warning .bbox-rect {
  fill: rgba(230, 162, 60, 0.2);
  stroke: #e6a23c;
}
.detection-box-group.is-warning .corner-mark {
  stroke: #e6a23c;
}
.detection-box-group.is-warning .bbox-tag-bg {
  fill: #e6a23c;
}

.detection-box-group.is-normal .bbox-rect {
  fill: rgba(103, 194, 58, 0.15);
  stroke: #67c23a;
}
.detection-box-group.is-normal .corner-mark {
  stroke: #67c23a;
}
.detection-box-group.is-normal .bbox-tag-bg {
  fill: #67c23a;
}

.detection-box-group.is-selected .bbox-rect {
  stroke-width: 1.2;
  filter: drop-shadow(0 0 3px #ffffff);
}

.inferring-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: rgba(0,0,0,0.65);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #ffffff;
  font-size: 13px;
}
.infer-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid rgba(255,255,255,0.2);
  border-top-color: #409eff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.monitor-footer {
  margin-top: 8px;
  font-size: 11px;
  color: #909399;
}

/* 右侧控制栏 */
.case-sidebar-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.infer-control-card, .infer-result-card {
  padding: 14px;
  background: #ffffff;
  border-radius: 12px;
}
.control-header, .result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.control-header h3, .result-header h3 {
  font-size: 14px;
  font-weight: 700;
  color: #303133;
  margin: 0;
}
.engine-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}

.control-param-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  background: #f8fafc;
  border-radius: 8px;
  margin-bottom: 12px;
}
.param-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
}
.param-label b {
  color: #303133;
}
.param-threshold {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #606266;
}
.param-threshold b {
  color: #409eff;
}

.control-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.primary-infer-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 40px;
  border: none;
  border-radius: 8px;
  background: #409eff;
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s ease;
}
.primary-infer-btn:hover:not(:disabled) {
  background: #66b1ff;
  box-shadow: 0 3px 8px rgba(64,158,255,0.3);
}
.primary-infer-btn:disabled {
  background: #a0cfff;
  cursor: not-allowed;
}
.refresh-btn {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  background: #ffffff;
  color: #606266;
  cursor: pointer;
}
.refresh-btn:hover {
  color: #409eff;
  border-color: #409eff;
}

/* 识别结果明细 */
.result-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.latency-pill {
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  background: #f0f9eb;
  color: #67c23a;
  font-weight: 600;
}
.status-pill {
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 700;
}
.status-pill.is-danger {
  background: #fef0f0;
  color: #f56c6c;
}
.status-pill.is-safe {
  background: #f0f9eb;
  color: #67c23a;
}

.summary-box {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f4f4f5;
  color: #606266;
  margin-bottom: 12px;
  font-size: 12px;
}
.summary-box.has-violation {
  background: #fdf6ec;
  color: #b87a1e;
  border-left: 3px solid #e6a23c;
}
.summary-box strong {
  display: block;
  font-size: 13px;
  margin-bottom: 3px;
}
.summary-box p {
  margin: 0;
  font-size: 11px;
  line-height: 1.4;
}

.objects-list-wrapper h4 {
  font-size: 12px;
  color: #909399;
  margin: 0 0 8px 0;
}
.objects-table {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 200px;
  overflow-y: auto;
}
.object-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f9fafb;
  border: 1px solid #ebeef5;
  cursor: pointer;
  transition: all 0.2s ease;
}
.object-row:hover, .object-row.is-active {
  background: #ecf5ff;
  border-color: #b3d8ff;
}
.obj-badge {
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}
.obj-badge[data-risk="SEVERE"] { background: #fde2e2; color: #f56c6c; }
.obj-badge[data-risk="WARNING"] { background: #faecd8; color: #e6a23c; }
.obj-badge[data-risk="NORMAL"] { background: #e1f3d8; color: #67c23a; }

.obj-meta {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.obj-meta b { font-size: 12px; color: #303133; }
.obj-meta small { font-size: 10px; color: #909399; }

.obj-score {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  font-size: 11px;
  font-weight: 600;
}
.score-bar-bg {
  width: 44px;
  height: 4px;
  border-radius: 2px;
  background: #ebeef5;
  overflow: hidden;
}
.score-bar-fill {
  height: 100%;
  background: #409eff;
}

.escalate-section {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed #ebeef5;
}
.escalate-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 38px;
  border: 1px solid #f78989;
  border-radius: 8px;
  background: #fef0f0;
  color: #f56c6c;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s ease;
}
.escalate-btn:hover:not(:disabled) {
  background: #f56c6c;
  color: #ffffff;
  box-shadow: 0 3px 8px rgba(245,108,108,0.3);
}
.escalated-success-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f0f9eb;
  border: 1px solid #c2e7b0;
  color: #67c23a;
  font-size: 12px;
}
.escalated-success-card strong {
  display: block;
}
.escalated-success-card p {
  margin: 2px 0 0 0;
  font-size: 11px;
}
.view-alert-link {
  margin-left: auto;
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid #67c23a;
  background: #ffffff;
  color: #67c23a;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
}
.view-alert-link:hover {
  background: #67c23a;
  color: #ffffff;
}
</style>
