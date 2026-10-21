/** AI 模型与识别推理类型定义 */

export interface AiModelInfo {
  id: string
  name: string
  taskType: string
  version: string
  engine: string
  accuracyMap: number
  latencyMs: number
  fps: number
  confidenceThreshold: number
  iouThreshold: number
  isSelected: boolean
  status: 'ACTIVE' | 'READY' | 'INACTIVE'
  weightSize: string
  supportedClasses: string[]
  description: string
  updatedAt?: string | undefined
}

export interface UpdateModelConfigRequest {
  confidenceThreshold?: number | undefined
  iouThreshold?: number | undefined
  engine?: string | undefined
  isSelected?: boolean | undefined
  status?: string | undefined
}

export interface DetectedObjectBox {
  label: string
  labelZh: string
  confidence: number
  isViolation: boolean
  riskLevel: 'SEVERE' | 'WARNING' | 'NORMAL'
  x: number
  y: number
  width: number
  height: number
  note: string
}

export interface AiInferenceRequest {
  modelId?: string | undefined
  scenario?: string | undefined
  caseId?: string | undefined
  imageSource?: string | undefined
  confidenceThreshold?: number | undefined
}

export interface AiInferenceResult {
  inferenceId: string
  modelId: string
  modelName: string
  modelVersion: string
  engineUsed: string
  latencyMs: number
  scenario: string
  scenarioName: string
  imageSource: string
  detectedObjects: DetectedObjectBox[]
  violationCount: number
  riskLevel: string
  summary: string
  suggestion: string
  timestamp: string
}

export interface EscalateCaseRequest {
  inferenceId: string
  modelId: string
  modelName: string
  scenario: string
  area: string
  riskLevel: string
  summary: string
  imageSource: string
  objects: DetectedObjectBox[]
}

export interface EscalateCaseResult {
  success: boolean
  alertId: string
  riskLevel: string
  status: string
  message: string
}
