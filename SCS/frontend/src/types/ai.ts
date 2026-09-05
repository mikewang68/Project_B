// AI 违规识别模块类型定义（本阶段全部为前端 Mock，不接后端）

export type AiEventType = '未佩戴安全帽' | '翻越护栏' | '闯入危险区域' | '人员滞留' | '摄像头异常'

export type ReviewStatus = '待复核' | '已确认违规' | '误报' | '不确定' | '已派单' | '处理中' | '已关闭'

export type RiskLevel = '高' | '中' | '低'

export type CameraHealth = '正常' | '画面质量下降' | '离线'

/** AI 场景图类型，决定 Mock 截图的构图 */
export type AiSceneKind = 'helmet' | 'fence' | 'intrusion' | 'linger' | 'camera'

/** 检测框，坐标为相对截图的百分比 */
export interface DetectionBox {
  id: string
  /** PERSON / NO HELMET / DANGER ZONE 等英文标签 */
  label: string
  /** 0-100 置信度，区域框可为空 */
  score?: number
  x: number
  y: number
  w: number
  h: number
  /** person=人物框（主色）；violation=违规框（风险色）；zone=危险区域边界（警示虚线） */
  tone: 'person' | 'violation' | 'zone'
}

export interface AiTimelineNode {
  time: string
  text: string
  /** done=已完成节点；active=当前节点；pending=未发生 */
  state: 'done' | 'active' | 'pending'
}

export interface AiEvent {
  id: string
  type: AiEventType
  camera: string
  cameraName: string
  area: string
  /** 0-100 */
  confidence: number
  /** 持续秒数 */
  durationSec: number
  model: string
  /** 规则阈值 0-100 */
  threshold: number
  /** 触发时间 HH:mm:ss */
  time: string
  status: ReviewStatus
  risk: RiskLevel
  health: CameraHealth
  scene: AiSceneKind
  boxes: DetectionBox[]
  /** 命中的区域规则 */
  rule: string
  relatedPerson: string
  relatedDevice: string
  /** AI 判定结论文案 */
  judgeText: string
  /** 以下为人工复核后回填 */
  reviewer?: string | undefined
  reviewTime?: string | undefined
  falseReason?: string | undefined
  assignee?: string | undefined
  assignmentPriority?: '普通' | '紧急' | undefined
  processStatus?: '待处理' | '处理中' | '已完成' | undefined
  assignmentNote?: string | undefined
  timeline: AiTimelineNode[]
  /** 新事件入场动画标记 */
  fresh?: boolean | undefined
  /** 确认违规 / 派单后关联的统一告警 ID（Alert 主链）；误报永远为空 */
  linkedAlertId?: string | undefined
  /** ISO-8601 事件时间（后端返回，前端展示仍用 time 的 HH:mm:ss） */
  occurredAt?: string | undefined
}

export const AI_EVENT_TYPES: AiEventType[] = ['未佩戴安全帽', '翻越护栏', '闯入危险区域', '人员滞留', '摄像头异常']

export const REVIEW_STATUSES: ReviewStatus[] = ['待复核', '已确认违规', '误报', '不确定', '已派单', '处理中', '已关闭']

export const FALSE_REASONS = ['遮挡误判', '光照问题', '目标识别错误', '区域配置问题', '其他'] as const

export const ASSIGNEES = ['安全员 王建国', '安全员 李娜', '班长 刘志明', '值班员 陈晓'] as const
