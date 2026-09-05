// 规则配置中心 Mock 数据（纯前端演示，无规则引擎 / 无后端）
import { EDGE_NODE_IDS, bumpVersion, type EdgeNodeState, type RuleCategory, type RuleRisk, type RuleStatus, type SafetyRule, type SimulationInput, type SimulationResult } from '@/types/rule'

function syncedNodes(version: string): EdgeNodeState[] {
  return EDGE_NODE_IDS.map((node) => ({ node, version, state: 'synced' }))
}

interface RuleSeed {
  id: string
  name: string
  category: RuleCategory
  areas: string[]
  version: string
  risk: RuleRisk
  status: RuleStatus
  updatedAt: string
  owner: string
  approver: string
  effectiveAt: string
  params: SafetyRule['params']
  actions: string[]
  highRisk: boolean
  relatedModules: string[]
  description: string
  versions: SafetyRule['versions']
  edgeNodes?: EdgeNodeState[]
}

const SEEDS: RuleSeed[] = [
  {
    id: 'RULE-PER-001', name: '人员进入龙门吊动态禁区', category: '人员安全', areas: ['装卸区 A', '龙门吊作业区'],
    version: 'v3.3', risk: '紧急', status: '已生效', updatedAt: '2026-09-04 10:22', owner: '安全员 王建国', approver: '安全总监 赵民',
    effectiveAt: '2026-09-04 10:30', highRisk: true,
    relatedModules: ['人员定位', '电子围栏', '告警中心'],
    description: '人员定位进入龙门吊动态作业范围时触发紧急告警并联动设备禁动。',
    params: [
      { label: '持续时间阈值', value: '2 秒' },
      { label: '连续采样', value: '2 次' },
      { label: '告警等级', value: '紧急', danger: true },
      { label: '撤离时限', value: '15 秒' },
      { label: '定位精度要求', value: '≤ 0.5m' },
    ],
    actions: ['现场声光提醒', '人员手环提醒', '通知安全员', '设备禁动', 'PLC 联动'],
    versions: [
      { version: 'v3.3', date: '2026-09-04', note: '缩短持续时间阈值，撤离时限调整为 15 秒', author: '王建国', state: '当前',
        diffs: [{ label: '持续时间阈值', from: '3 秒', to: '2 秒' }, { label: '撤离时限', from: '20 秒', to: '15 秒' }] },
      { version: 'v3.2', date: '2026-08-18', note: '扩大动态禁区半径覆盖范围', author: '李明', state: '历史版本',
        diffs: [{ label: '动态禁区半径', from: '6m', to: '8m' }] },
      { version: 'v3.1', date: '2026-07-28', note: '初始规则', author: '系统管理员', state: '历史版本' },
    ],
  },
  {
    id: 'RULE-PER-002', name: '电子围栏越界判定', category: '人员安全', areas: ['装卸区 A', '装卸区 B', '箱区 B'],
    version: 'v2.1', risk: '严重', status: '已生效', updatedAt: '2026-08-26 15:10', owner: '安全员 李娜', approver: '安全总监 赵民',
    effectiveAt: '2026-08-26 16:00', highRisk: false,
    relatedModules: ['电子围栏', '人员定位', '告警中心'],
    description: '人员越过电子围栏边界并持续停留时生成越界告警。',
    params: [
      { label: '越界判定距离', value: '0.8m' },
      { label: '持续时间阈值', value: '3 秒' },
      { label: '连续采样', value: '3 次' },
      { label: '告警等级', value: '严重' },
    ],
    actions: ['现场声光提醒', '人员手环提醒', '通知安全员'],
    versions: [
      { version: 'v2.1', date: '2026-08-26', note: '优化边界抖动过滤', author: '李娜', state: '当前' },
      { version: 'v2.0', date: '2026-07-30', note: '初始规则', author: '系统管理员', state: '历史版本' },
    ],
  },
  {
    id: 'RULE-PER-003', name: '危险区域人员滞留提醒', category: '人员安全', areas: ['维修通道', '翻箱机作业区'],
    version: 'v1.6', risk: '预警', status: '待评审', updatedAt: '2026-09-03 17:40', owner: '安全员 李娜', approver: '待审批',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['人员定位', '告警中心'],
    description: '人员在危险区域滞留超过时限时逐级提醒。',
    params: [
      { label: '滞留时限', value: '90 秒' },
      { label: '升级时限', value: '180 秒' },
      { label: '告警等级', value: '预警' },
    ],
    actions: ['人员手环提醒', '通知安全员'],
    versions: [{ version: 'v1.6', date: '2026-09-03', note: '滞留时限由 60 秒调整为 90 秒', author: '李娜', state: '当前' }],
  },
  {
    id: 'RULE-PER-004', name: '人员定位信号丢失处置', category: '人员安全', areas: ['全部区域'],
    version: 'v1.2', risk: '预警', status: '草稿', updatedAt: '2026-09-02 11:05', owner: '安全员 王建国', approver: '—',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['人员定位'],
    description: '手环定位信号丢失超过时限后提示现场核查。',
    params: [
      { label: '信号丢失时限', value: '45 秒' },
      { label: '告警等级', value: '预警' },
    ],
    actions: ['通知安全员'],
    versions: [{ version: 'v1.2', date: '2026-09-02', note: '草稿：补充低电量联动', author: '王建国', state: '当前' }],
  },
  {
    id: 'RULE-DEV-003', name: '转运车辆距离预警', category: '设备安全', areas: ['翻箱机作业区', '装卸区 A'],
    version: 'v2.4', risk: '严重', status: '已生效', updatedAt: '2026-09-04 09:18', owner: '设备管理员 周海', approver: '安全总监 赵民',
    effectiveAt: '2026-09-04 09:30', highRisk: true,
    relatedModules: ['设备防碰撞', '告警中心'],
    description: '按设备间距与相对速度分级预警，紧急距离内联动减速 / 停机。',
    params: [
      { label: '预警距离', value: '10m' },
      { label: '严重距离', value: '6m' },
      { label: '紧急距离', value: '3m', danger: true },
      { label: '相对速度修正', value: '开启' },
      { label: '制动距离修正', value: '开启' },
      { label: '天气修正', value: '开启' },
    ],
    actions: ['通知司机', '通知安全员', '减速请求', '设备停机', 'PLC 联动'],
    versions: [
      { version: 'v2.4', date: '2026-09-04', note: '雨天距离阈值修正系数调整', author: '周海', state: '当前',
        diffs: [{ label: '预警距离', from: '8m', to: '10m' }, { label: '紧急距离', from: '2.5m', to: '3m' }] },
      { version: 'v2.3', date: '2026-08-12', note: '加入相对速度修正', author: '周海', state: '历史版本' },
      { version: 'v2.2', date: '2026-07-20', note: '初始规则', author: '系统管理员', state: '历史版本' },
    ],
  },
  {
    id: 'RULE-DEV-009', name: '车辆通道距离预警', category: '设备安全', areas: ['车辆通道', '翻箱机作业区'],
    version: 'v1.5', risk: '严重', status: '已生效', updatedAt: '2026-08-20 14:32', owner: '设备管理员 周海', approver: '安全总监 赵民',
    effectiveAt: '2026-08-20 15:00', highRisk: false,
    relatedModules: ['设备防碰撞', '告警中心'],
    description: '车辆通道会车 / 跟车距离分级预警。',
    params: [
      { label: '预警距离', value: '8m' },
      { label: '严重距离', value: '5m' },
      { label: '紧急距离', value: '2.5m', danger: true },
      { label: '相对速度修正', value: '开启' },
    ],
    actions: ['通知司机', '通知调度员', '减速请求'],
    versions: [
      { version: 'v1.5', date: '2026-08-20', note: '通道限速联动调整', author: '周海', state: '当前' },
      { version: 'v1.4', date: '2026-07-15', note: '初始规则', author: '系统管理员', state: '历史版本' },
    ],
  },
  {
    id: 'RULE-DEV-002', name: '龙门吊旋转半径入侵', category: '设备安全', areas: ['龙门吊作业区'],
    version: 'v2.0', risk: '紧急', status: '版本异常', updatedAt: '2026-09-01 08:50', owner: '设备管理员 周海', approver: '安全总监 赵民',
    effectiveAt: '2026-09-01 09:10', highRisk: true,
    relatedModules: ['设备防碰撞', '人员定位', '告警中心'],
    description: '人员 / 车辆进入吊臂旋转半径时触发紧急联动。',
    params: [
      { label: '旋转半径', value: '12m' },
      { label: '告警等级', value: '紧急', danger: true },
      { label: '制动距离修正', value: '开启' },
    ],
    actions: ['现场声光提醒', '通知司机', '设备禁动', 'PLC 联动'],
    versions: [
      { version: 'v2.0', date: '2026-09-01', note: '旋转半径扩大至 12m', author: '周海', state: '当前' },
      { version: 'v1.9', date: '2026-08-02', note: '初始规则', author: '系统管理员', state: '历史版本' },
    ],
    edgeNodes: [
      { node: 'EDGE-01', version: 'v2.0', state: 'synced' },
      { node: 'EDGE-02', version: 'v2.0', state: 'synced' },
      { node: 'EDGE-03', version: 'v1.9', state: 'mismatch' },
      { node: 'EDGE-04', version: 'v2.0', state: 'synced' },
    ],
  },
  {
    id: 'RULE-DEV-010', name: '设备超速联动减速', category: '设备安全', areas: ['车辆通道', '装卸区 B'],
    version: 'v1.3', risk: '严重', status: '已批准', updatedAt: '2026-09-03 16:20', owner: '设备管理员 周海', approver: '安全总监 赵民',
    effectiveAt: '待发布', highRisk: false,
    relatedModules: ['设备防碰撞'],
    description: '车辆超过区域限速时自动请求减速。',
    params: [
      { label: '区域限速', value: '15 km/h' },
      { label: '超速比例', value: '10%' },
      { label: '告警等级', value: '严重' },
    ],
    actions: ['通知司机', '减速请求', '通知调度员'],
    versions: [{ version: 'v1.3', date: '2026-09-03', note: '评审通过，待发布', author: '周海', state: '当前' }],
  },
  {
    id: 'RULE-DEV-011', name: '雨天制动距离修正', category: '设备安全', areas: ['全部区域'],
    version: 'v0.8', risk: '预警', status: '草稿', updatedAt: '2026-09-02 10:02', owner: '设备管理员 周海', approver: '—',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['设备防碰撞'],
    description: '草稿：小雨 / 雾天下距离阈值按系数放大。',
    params: [
      { label: '小雨修正系数', value: '1.15' },
      { label: '雾天修正系数', value: '1.3' },
    ],
    actions: ['减速请求'],
    versions: [{ version: 'v0.8', date: '2026-09-02', note: '草稿：待现场数据验证', author: '周海', state: '当前' }],
  },
  {
    id: 'RULE-AI-002', name: '未佩戴安全帽识别', category: 'AI识别', areas: ['装卸区 A', '装卸区 B'],
    version: 'v1.8', risk: '一般', status: '已生效', updatedAt: '2026-08-28 13:44', owner: '安全员 李娜', approver: '安全总监 赵民',
    effectiveAt: '2026-08-28 14:00', highRisk: false,
    relatedModules: ['AI违规识别', '告警中心'],
    description: 'PPE 模型识别未佩戴安全帽，低置信度进入人工复核。',
    params: [
      { label: '置信度阈值', value: '85%' },
      { label: '持续时间', value: '2 秒' },
      { label: '连续帧', value: '5 帧' },
      { label: '低置信度处理', value: '进入人工复核' },
      { label: '模型', value: 'PPE-Detection-v2.4.1' },
    ],
    actions: ['进入人工复核', '通知安全员'],
    versions: [
      { version: 'v1.8', date: '2026-08-28', note: '阈值由 80% 提升至 85%', author: '李娜', state: '当前' },
      { version: 'v1.7', date: '2026-08-05', note: '初始规则', author: '系统管理员', state: '历史版本' },
    ],
  },
  {
    id: 'RULE-AI-003', name: '翻越护栏识别', category: 'AI识别', areas: ['装卸区 A', '箱区 B'],
    version: 'v1.4', risk: '严重', status: '已生效', updatedAt: '2026-08-22 09:30', owner: '安全员 李娜', approver: '安全总监 赵民',
    effectiveAt: '2026-08-22 10:00', highRisk: false,
    relatedModules: ['AI违规识别', '告警中心'],
    description: '行为识别模型检测翻越护栏动作并生成违规事件。',
    params: [
      { label: '置信度阈值', value: '88%' },
      { label: '连续帧', value: '6 帧' },
      { label: '告警等级', value: '严重' },
      { label: '模型', value: 'Behavior-v1.9' },
    ],
    actions: ['进入人工复核', '现场声光提醒', '通知安全员'],
    versions: [{ version: 'v1.4', date: '2026-08-22', note: '降低夜间误报', author: '李娜', state: '当前' }],
  },
  {
    id: 'RULE-AI-005', name: '低置信度事件复核策略', category: 'AI识别', areas: ['全部区域'],
    version: 'v0.9', risk: '一般', status: '草稿', updatedAt: '2026-09-01 15:26', owner: '安全员 李娜', approver: '—',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['AI违规识别'],
    description: '草稿：60%~85% 置信度统一进入人工复核队列，不自动升级。',
    params: [
      { label: '复核区间下限', value: '60%' },
      { label: '复核区间上限', value: '85%' },
    ],
    actions: ['进入人工复核'],
    versions: [{ version: 'v0.9', date: '2026-09-01', note: '草稿：复核 SLA 待定', author: '李娜', state: '当前' }],
  },
  {
    id: 'RULE-AI-006', name: '摄像头遮挡 / 清晰度下降告警', category: 'AI识别', areas: ['全部区域'],
    version: 'v1.1', risk: '预警', status: '待评审', updatedAt: '2026-09-03 18:02', owner: '安全员 李娜', approver: '待审批',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['AI违规识别', '运维监控'],
    description: '摄像头画面质量下降时标记 AI 能力受限，不输出"无违规"结论。',
    params: [
      { label: '清晰度阈值', value: '0.55' },
      { label: '遮挡判定时长', value: '10 秒' },
    ],
    actions: ['通知安全员', '通知调度员'],
    versions: [{ version: 'v1.1', date: '2026-09-03', note: '提交评审', author: '李娜', state: '当前' }],
  },
  {
    id: 'RULE-ALM-001', name: '严重事件分级升级策略', category: '告警策略', areas: ['全部区域'],
    version: 'v2.2', risk: '严重', status: '已生效', updatedAt: '2026-09-03 21:15', owner: '调度员 陈晓', approver: '安全总监 赵民',
    effectiveAt: '2026-09-04 08:30', highRisk: false,
    relatedModules: ['告警中心'],
    description: '严重 / 紧急事件按确认与处置时限自动升级。',
    params: [
      { label: '严重确认时限', value: '3 分钟' },
      { label: '紧急确认时限', value: '1 分钟' },
      { label: '升级层级', value: '2 级' },
    ],
    actions: ['通知安全员', '通知调度员'],
    versions: [{ version: 'v2.2', date: '2026-09-04', note: '紧急确认时限收紧', author: '陈晓', state: '当前' }],
  },
  {
    id: 'RULE-ALM-002', name: 'SLA 超时升级', category: '告警策略', areas: ['全部区域'],
    version: 'v1.7', risk: '预警', status: '待评审', updatedAt: '2026-09-03 19:12', owner: '调度员 陈晓', approver: '待审批',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['告警中心', '统计分析'],
    description: '处置 SLA 即将超时 / 已超时自动提醒并升级。',
    params: [
      { label: '预警提前量', value: '5 分钟' },
      { label: '超时升级', value: '开启' },
    ],
    actions: ['通知调度员', '通知安全员'],
    versions: [{ version: 'v1.7', date: '2026-09-03', note: '提交评审', author: '陈晓', state: '当前' }],
  },
  {
    id: 'RULE-ALM-003', name: '重复告警聚合', category: '告警策略', areas: ['全部区域'],
    version: 'v1.1', risk: '一般', status: '已停用', updatedAt: '2026-07-30 17:48', owner: '调度员 陈晓', approver: '安全总监 赵民',
    effectiveAt: '2026-07-18 09:00', highRisk: false,
    relatedModules: ['告警中心'],
    description: '同对象同类型 5 分钟内重复告警聚合展示（评估中，暂时停用）。',
    params: [{ label: '聚合窗口', value: '5 分钟' }],
    actions: [],
    versions: [{ version: 'v1.1', date: '2026-07-30', note: '停用：聚合导致漏报风险，待重新评估', author: '陈晓', state: '当前' }],
  },
  {
    id: 'RULE-ALM-004', name: '误报自动收敛建议', category: '告警策略', areas: ['全部区域'],
    version: 'v0.4', risk: '一般', status: '草稿', updatedAt: '2026-09-02 16:40', owner: '调度员 陈晓', approver: '—',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['告警中心', 'AI违规识别'],
    description: '草稿：同一规则高频误报时给出收敛建议，不自动关闭告警。',
    params: [{ label: '误报样本阈值', value: '20 次 / 周' }],
    actions: ['进入人工复核'],
    versions: [{ version: 'v0.4', date: '2026-09-02', note: '草稿', author: '陈晓', state: '当前' }],
  },
  {
    id: 'RULE-LNK-001', name: '紧急事件设备联动编排', category: '联动策略', areas: ['装卸区 A', '龙门吊作业区', '翻箱机作业区'],
    version: 'v2.5', risk: '紧急', status: '已生效', updatedAt: '2026-09-04 11:06', owner: '设备管理员 周海', approver: '安全总监 赵民',
    effectiveAt: '2026-09-04 11:20', highRisk: true,
    relatedModules: ['告警中心', '设备防碰撞', '人员定位'],
    description: '紧急告警按声光 → 手环 → 通知 → 减速 → 停机 → PLC 回执顺序联动。',
    params: [
      { label: '联动触发等级', value: '紧急', danger: true },
      { label: '设备停机', value: '自动执行', danger: true },
      { label: 'PLC 联动', value: '开启', danger: true },
      { label: 'PLC 回执超时', value: '8 秒' },
    ],
    actions: ['现场声光提醒', '人员手环提醒', '通知安全员', '通知调度员', '减速请求', '设备停机', 'PLC 联动'],
    versions: [
      { version: 'v2.5', date: '2026-09-04', note: 'PLC 回执超时由 10 秒收紧至 8 秒', author: '周海', state: '当前' },
      { version: 'v2.4', date: '2026-08-14', note: '初始规则', author: '系统管理员', state: '历史版本' },
    ],
  },
  {
    id: 'RULE-LNK-002', name: '现场声光提醒策略', category: '联动策略', areas: ['全部区域'],
    version: 'v1.4', risk: '预警', status: '已生效', updatedAt: '2026-08-19 13:12', owner: '安全员 王建国', approver: '安全总监 赵民',
    effectiveAt: '2026-08-19 13:30', highRisk: false,
    relatedModules: ['告警中心'],
    description: '预警及以上事件触发现场声光提醒。',
    params: [
      { label: '触发等级', value: '预警' },
      { label: '提醒持续', value: '20 秒' },
    ],
    actions: ['现场声光提醒'],
    versions: [{ version: 'v1.4', date: '2026-08-19', note: '音量曲线调整', author: '王建国', state: '当前' }],
  },
  {
    id: 'RULE-LNK-003', name: '夜间联动加强策略', category: '联动策略', areas: ['全部区域'],
    version: 'v0.6', risk: '严重', status: '草稿', updatedAt: '2026-09-01 20:31', owner: '安全员 王建国', approver: '—',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['告警中心'],
    description: '草稿：22:00-06:00 夜间作业自动下调一级触发阈值。',
    params: [
      { label: '夜间时段', value: '22:00 - 06:00' },
      { label: '阈值下调', value: '15%' },
    ],
    actions: ['现场声光提醒', '通知安全员'],
    versions: [{ version: 'v0.6', date: '2026-09-01', note: '草稿', author: '王建国', state: '当前' }],
  },
  {
    id: 'RULE-NTF-001', name: '安全员通知矩阵', category: '通知策略', areas: ['全部区域'],
    version: 'v2.0', risk: '一般', status: '已生效', updatedAt: '2026-08-15 10:40', owner: '调度员 陈晓', approver: '安全总监 赵民',
    effectiveAt: '2026-08-15 11:00', highRisk: false,
    relatedModules: ['告警中心'],
    description: '按风险等级与区域匹配值班安全员通知通道。',
    params: [
      { label: '通知通道', value: '手环 + 终端' },
      { label: '送达确认', value: '开启' },
    ],
    actions: ['通知安全员'],
    versions: [{ version: 'v2.0', date: '2026-08-15', note: '值班表轮换同步', author: '陈晓', state: '当前' }],
  },
  {
    id: 'RULE-NTF-002', name: '调度值班升级通知', category: '通知策略', areas: ['全部区域'],
    version: 'v1.2', risk: '预警', status: '待评审', updatedAt: '2026-09-03 21:08', owner: '调度员 陈晓', approver: '待审批',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['告警中心'],
    description: '事件升级后逐级通知值班调度与值班主任。',
    params: [{ label: '逐级间隔', value: '90 秒' }],
    actions: ['通知调度员'],
    versions: [{ version: 'v1.2', date: '2026-09-03', note: '提交评审', author: '陈晓', state: '当前' }],
  },
  {
    id: 'RULE-NTF-003', name: '班组安全日报推送', category: '通知策略', areas: ['全部区域'],
    version: 'v0.5', risk: '一般', status: '草稿', updatedAt: '2026-09-02 09:47', owner: '调度员 陈晓', approver: '—',
    effectiveAt: '—', highRisk: false,
    relatedModules: ['统计分析'],
    description: '草稿：每日 08:00 向班组推送前一日安全简报。',
    params: [{ label: '推送时间', value: '每日 08:00' }],
    actions: [],
    versions: [{ version: 'v0.5', date: '2026-09-02', note: '草稿', author: '陈晓', state: '当前' }],
  },
]

export function buildRules(): SafetyRule[] {
  return SEEDS.map((s) => ({
    ...s,
    areas: [...s.areas],
    params: s.params.map((p) => ({ ...p })),
    actions: [...s.actions],
    relatedModules: [...s.relatedModules],
    versions: s.versions.map((v) => ({ ...v, diffs: v.diffs?.map((d) => ({ ...d })) })),
    platformVersion: s.version,
    edgeNodes: s.edgeNodes ? s.edgeNodes.map((n) => ({ ...n })) : syncedNodes(s.version),
  }))
}

/** 冲突检查 Mock：同区域同设备类型、阈值不一致的规则对 */
export interface RuleConflict {
  ruleA: string
  ruleB: string
  area: string
  deviceKind: string
  paramLabel: string
  valueA: string
  valueB: string
  highRisk: boolean
}

export function mockConflicts(): RuleConflict[] {
  return [
    {
      ruleA: 'RULE-DEV-003', ruleB: 'RULE-DEV-009', area: '翻箱机作业区', deviceKind: '转运车辆',
      paramLabel: '预警距离', valueA: '10m', valueB: '8m', highRisk: true,
    },
  ]
}

/** 规则仿真 Mock：仅对设备距离规则做数值判定，其余返回通用结果 */
export function runRuleSimulation(rule: SafetyRule, input: SimulationInput): SimulationResult {
  const weatherFactor = input.weather === '小雨' ? 1.15 : input.weather === '雾天' ? 1.3 : 1
  const warn = 10 * weatherFactor
  const severe = 6 * weatherFactor
  const emergency = 3 * weatherFactor
  const d = input.distance
  let level: SimulationResult['level'] = '安全'
  const actions: string[] = []
  if (d <= emergency) {
    level = '紧急风险'
    actions.push('设备停机', 'PLC 联动', '通知司机', '通知安全员')
  } else if (d <= severe) {
    level = '严重风险'
    actions.push('减速请求', '通知司机', '通知安全员')
  } else if (d <= warn) {
    level = '预警风险'
    actions.push('通知司机', '现场声光提醒')
  } else {
    actions.push('保持监测')
  }
  const thresholdNote = input.weather === '晴'
    ? `按${rule.version}标准阈值：预警 ${warn.toFixed(1)}m / 严重 ${severe.toFixed(1)}m / 紧急 ${emergency.toFixed(1)}m`
    : `${input.weather}天气修正 ×${weatherFactor}：预警 ${warn.toFixed(1)}m / 严重 ${severe.toFixed(1)}m / 紧急 ${emergency.toFixed(1)}m`
  const escalation = d > emergency && d - emergency < 2
    ? `距离继续下降至 ${emergency.toFixed(1)}m 内，预计升级为紧急风险并触发设备停机`
    : level === '安全'
      ? '距离继续缩小至预警阈值内将触发预警风险'
      : '若相对速度持续增大，升级时间将进一步缩短'
  return {
    level,
    matchedRule: `${rule.id} ${rule.version}`,
    thresholdNote,
    actions,
    escalation,
  }
}

export { bumpVersion }
