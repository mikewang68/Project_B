import type { AiEvent, AiEventType, AiTimelineNode, CameraHealth, DetectionBox, ReviewStatus, RiskLevel } from '@/types/ai'

function pad(n: number): string { return String(n).padStart(2, '0') }
function addSec(time: string, delta: number): string {
  const [h, m, s] = time.split(':').map(Number) as [number, number, number]
  const total = (((h * 60 + m) * 60 + s + delta) % 86400 + 86400) % 86400
  return `${pad(Math.floor(total / 3600))}:${pad(Math.floor(total / 60) % 60)}:${pad(total % 60)}`
}

type Stage = 'queue' | 'confirmed' | 'false' | 'uncertain' | 'assigned' | 'processing'
function buildTimeline(time: string, typeText: string, stage: Stage, reviewTime = '22:16:03', extra?: string): AiTimelineNode[] {
  const nodes: AiTimelineNode[] = [
    { time, text: `AI 检测到疑似${typeText}`, state: 'done' },
    { time: addSec(time, 1), text: '事件进入复核队列', state: 'done' },
  ]
  if (stage === 'queue') {
    nodes.push({ time: addSec(time, 2), text: '等待安全员人工复核', state: 'active' })
    return nodes
  }
  nodes.push({ time: addSec(time, 64), text: '安全员查看事件', state: 'done' })
  if (stage === 'uncertain') {
    nodes.push({ time: reviewTime, text: '置信度不足，转入人工复核队列', state: 'active' })
    return nodes
  }
  if (stage === 'false') {
    nodes.push({ time: reviewTime, text: `标记为误报（${extra ?? '遮挡误判'}）`, state: 'active' })
    return nodes
  }
  nodes.push({ time: reviewTime, text: '确认该事件为真实违规', state: 'done' })
  if (stage === 'confirmed') {
    nodes.push({ time: reviewTime, text: '等待派单处置', state: 'active' })
    return nodes
  }
  nodes.push({ time: addSec(reviewTime, 17), text: extra ?? '已派单至安全员 王建国', state: stage === 'processing' ? 'done' : 'active' })
  if (stage === 'processing') nodes.push({ time: addSec(reviewTime, 120), text: '责任人现场处置中', state: 'active' })
  return nodes
}

const personBox = (score: number, x = 40, y = 46, w = 23, h = 44): DetectionBox => ({ id: 'person', label: 'PERSON', score, x, y, w, h, tone: 'person' })

interface Seed {
  id: string
  type: AiEventType
  camera: string
  cameraName: string
  area: string
  confidence: number
  durationSec: number
  model: string
  threshold: number
  time: string
  status: ReviewStatus
  risk: RiskLevel
  health?: CameraHealth
  rule: string
  relatedPerson: string
  relatedDevice: string
  judgeText: string
  stage: Stage
  boxes: DetectionBox[]
  reviewer?: string
  reviewTime?: string
  falseReason?: string
  assignee?: string
  assignmentPriority?: '普通' | '紧急'
  processStatus?: '待处理' | '处理中' | '已完成'
  assignmentNote?: string
}

const seeds: Seed[] = [
  {
    id: 'AI-E-20260903-026', type: '未佩戴安全帽', camera: 'CAM-03', cameraName: '装卸区 A 球机', area: '装卸区 A',
    confidence: 94.8, durationSec: 3.2, model: 'PPE-Detection-v2.4.1', threshold: 85, time: '22:14:08', status: '待复核', risk: '高',
    rule: '装卸作业区域必须佩戴安全帽', relatedPerson: '作业人员 P-1042', relatedDevice: '龙门吊 G-CRANE-01',
    judgeText: '连续 3.2 秒识别到人员头部未检测到安全帽，置信度高于 85% 规则阈值。',
    stage: 'queue',
    boxes: [personBox(97, 39, 44, 24, 44), { id: 'helmet', label: 'NO HELMET', score: 94.8, x: 44, y: 42, w: 13, h: 14, tone: 'violation' }],
  },
  {
    id: 'AI-E-20260903-025', type: '翻越护栏', camera: 'CAM-05', cameraName: '铁路线 B 枪机', area: '铁路装卸线 B',
    confidence: 88.4, durationSec: 2.1, model: 'Fence-Guard-v1.8.2', threshold: 85, time: '22:09:41', status: '待复核', risk: '高',
    rule: '禁止翻越装卸线安全护栏', relatedPerson: '外协人员 P-1077', relatedDevice: '—',
    judgeText: '识别到人员跨越护栏边界的连续动作，姿态轨迹与翻越行为模型匹配。',
    stage: 'queue',
    boxes: [personBox(92, 55, 26, 21, 42), { id: 'rail', label: 'FENCE LINE', x: 12, y: 60, w: 76, h: 7, tone: 'zone' }],
  },
  {
    id: 'AI-E-20260903-024', type: '闯入危险区域', camera: 'CAM-02', cameraName: '龙门吊下枪机', area: '龙门吊作业区',
    confidence: 91.2, durationSec: 5.6, model: 'Zone-Intrusion-v2.1.0', threshold: 85, time: '22:05:17', status: '待复核', risk: '高', health: '画面质量下降',
    rule: '吊装作业半径内禁止人员进入', relatedPerson: '作业人员 P-1031', relatedDevice: '龙门吊 G-CRANE-02',
    judgeText: '人员进入吊装回转半径危险区域并持续停留，当前摄像头画面质量下降，建议结合现场确认。',
    stage: 'queue',
    boxes: [{ id: 'zone', label: 'DANGER ZONE', x: 28, y: 22, w: 48, h: 58, tone: 'zone' }, personBox(91.2, 45, 40, 17, 32)],
  },
  {
    id: 'AI-E-20260903-023', type: '人员滞留', camera: 'CAM-06', cameraName: '箱区通道球机', area: '箱区通道 C',
    confidence: 76.5, durationSec: 42, model: 'Linger-Detect-v1.5.3', threshold: 70, time: '21:58:02', status: '待复核', risk: '中',
    rule: '通道区域滞留超过 30 秒预警', relatedPerson: '作业人员 P-1056', relatedDevice: '—',
    judgeText: '人员在通道区域静止滞留超过 30 秒阈值，需确认是否为正常作业停留。',
    stage: 'queue',
    boxes: [{ id: 'zone', label: 'LINGER ZONE', x: 24, y: 18, w: 52, h: 62, tone: 'zone' }, personBox(82.1, 42, 36, 17, 34)],
  },
  {
    id: 'AI-E-20260903-022', type: '摄像头异常', camera: 'CAM-03', cameraName: '装卸区 A 球机', area: '装卸区 A',
    confidence: 0, durationSec: 0, model: 'Camera-Health-v1.2.0', threshold: 0, time: '21:52:36', status: '待复核', risk: '中', health: '画面质量下降',
    rule: '画面清晰度低于可用阈值时降级', relatedPerson: '—', relatedDevice: '摄像头 CAM-03',
    judgeText: '检测到画面遮挡 / 清晰度下降，AI 识别能力受限；摄像头异常不代表现场无违规。',
    stage: 'queue',
    boxes: [],
  },
  {
    id: 'AI-E-20260903-021', type: '未佩戴安全帽', camera: 'CAM-01', cameraName: '装卸区 B 球机', area: '装卸区 B',
    confidence: 96.1, durationSec: 2.8, model: 'PPE-Detection-v2.4.1', threshold: 85, time: '21:46:55', status: '已确认违规', risk: '高',
    rule: '装卸作业区域必须佩戴安全帽', relatedPerson: '作业人员 P-1024', relatedDevice: '翻箱机 TIP-02',
    judgeText: '连续 2.8 秒未检测到安全帽，特征稳定。', reviewer: '李娜', reviewTime: '21:48:12',
    stage: 'confirmed',
    boxes: [personBox(96.9, 40, 45, 23, 43), { id: 'helmet', label: 'NO HELMET', score: 96.1, x: 45, y: 43, w: 13, h: 13, tone: 'violation' }],
  },
  {
    id: 'AI-E-20260903-020', type: '闯入危险区域', camera: 'CAM-04', cameraName: '翻箱机区枪机', area: '翻箱机作业区',
    confidence: 93.7, durationSec: 4.1, model: 'Zone-Intrusion-v2.1.0', threshold: 85, time: '21:37:20', status: '已确认违规', risk: '高',
    rule: '设备运行区域禁止人员进入', relatedPerson: '外协人员 P-1068', relatedDevice: '翻箱机 TIP-03',
    judgeText: '人员进入设备运行区域，轨迹与危险区重叠 4.1 秒。', reviewer: '李娜', reviewTime: '21:39:02',
    stage: 'confirmed',
    boxes: [{ id: 'zone', label: 'DANGER ZONE', x: 30, y: 24, w: 46, h: 56, tone: 'zone' }, personBox(94.2, 47, 42, 16, 31)],
  },
  {
    id: 'AI-E-20260903-016', type: '未佩戴安全帽', camera: 'CAM-01', cameraName: '装卸区 B 球机', area: '装卸区 B',
    confidence: 92.3, durationSec: 3.6, model: 'PPE-Detection-v2.4.1', threshold: 85, time: '20:58:44', status: '已确认违规', risk: '高',
    rule: '装卸作业区域必须佩戴安全帽', relatedPerson: '作业人员 P-1019', relatedDevice: '转运车辆 VEH-08',
    judgeText: '未检测到安全帽持续 3.6 秒，置信度高于阈值。', reviewer: '王建国', reviewTime: '21:01:18',
    stage: 'confirmed',
    boxes: [personBox(93.5, 42, 44, 22, 42), { id: 'helmet', label: 'NO HELMET', score: 92.3, x: 47, y: 42, w: 12, h: 13, tone: 'violation' }],
  },
  {
    id: 'AI-E-20260903-018', type: '人员滞留', camera: 'CAM-08', cameraName: '维修通道枪机', area: '维修通道',
    confidence: 71.0, durationSec: 68, model: 'Linger-Detect-v1.5.3', threshold: 70, time: '21:21:09', status: '误报', risk: '低',
    rule: '通道区域滞留超过 30 秒预警', relatedPerson: '维修人员 P-1007', relatedDevice: '—',
    judgeText: '逆光下将固定工器具误判为滞留人员。', reviewer: '李娜', reviewTime: '21:24:40', falseReason: '光照问题',
    stage: 'false',
    boxes: [{ id: 'zone', label: 'LINGER ZONE', x: 24, y: 18, w: 52, h: 62, tone: 'zone' }, personBox(71, 44, 38, 16, 32)],
  },
  {
    id: 'AI-E-20260903-017', type: '翻越护栏', camera: 'CAM-05', cameraName: '铁路线 B 枪机', area: '铁路装卸线 B',
    confidence: 95.4, durationSec: 3.4, model: 'Fence-Guard-v1.8.2', threshold: 85, time: '21:12:33', status: '已派单', risk: '高',
    rule: '禁止翻越装卸线安全护栏', relatedPerson: '外协人员 P-1071', relatedDevice: '—',
    judgeText: '翻越护栏动作完整，模型匹配度高。', reviewer: '李娜', reviewTime: '21:14:05',
    stage: 'assigned', assignee: '安全员 王建国', assignmentPriority: '紧急', processStatus: '待处理', assignmentNote: '已电话通知现场劝阻',
    boxes: [personBox(95, 54, 25, 22, 43), { id: 'rail', label: 'FENCE LINE', x: 12, y: 60, w: 76, h: 7, tone: 'zone' }],
  },
]

export function createInitialAiEvents(): AiEvent[] {
  return seeds.map((seed) => ({
    id: seed.id,
    type: seed.type,
    camera: seed.camera,
    cameraName: seed.cameraName,
    area: seed.area,
    confidence: seed.confidence,
    durationSec: seed.durationSec,
    model: seed.model,
    threshold: seed.threshold,
    time: seed.time,
    status: seed.status,
    risk: seed.risk,
    health: seed.health ?? '正常',
    scene: sceneOf(seed.type),
    boxes: seed.boxes,
    rule: seed.rule,
    relatedPerson: seed.relatedPerson,
    relatedDevice: seed.relatedDevice,
    judgeText: seed.judgeText,
    reviewer: seed.reviewer,
    reviewTime: seed.reviewTime,
    falseReason: seed.falseReason,
    assignee: seed.assignee,
    assignmentPriority: seed.assignmentPriority,
    processStatus: seed.processStatus,
    assignmentNote: seed.assignmentNote,
    timeline: buildTimeline(seed.time, seed.type, seed.stage, seed.reviewTime ?? '22:16:03',
      seed.assignee ? `已派单至${seed.assignee}` : seed.falseReason),
  }))
}

export function sceneOf(type: AiEventType): AiEvent['scene'] {
  if (type === '未佩戴安全帽') return 'helmet'
  if (type === '翻越护栏') return 'fence'
  if (type === '闯入危险区域') return 'intrusion'
  if (type === '人员滞留') return 'linger'
  return 'camera'
}

/** 顶部指标的历史基数：列表之外今日已归档的事件量 */
export const AI_METRIC_BASE = { confirmed: 8, falsePositive: 8, todayTotal: 26 }

let demoSeq = 27
export function nextEventSeq(): number { return demoSeq++ }
export function resetEventSeq(): void { demoSeq = 27 }
