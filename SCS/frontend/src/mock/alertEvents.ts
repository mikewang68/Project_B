import { buildLinkageTemplate, type AlertEvent, type AlertStatus, type LinkageStep, type TimelineNode } from '@/types/alert'

function pad(n: number): string { return String(n).padStart(2, '0') }
function addSec(time: string, delta: number): string {
  const [h, m, s] = time.split(':').map(Number) as [number, number, number]
  const total = (((h * 60 + m) * 60 + s + delta) % 86400 + 86400) % 86400
  return `${pad(Math.floor(total / 3600))}:${pad(Math.floor(total / 60) % 60)}:${pad(total % 60)}`
}

function tl(time: string, text: string, state: TimelineNode['state'] = 'done'): TimelineNode {
  return { time, text, state }
}

/** 把联动模板推进到第 doneCount 步成功，其后 running 1 步，其余等待 */
function linkageProgress(doneCount: number, running = false): LinkageStep[] {
  const steps = buildLinkageTemplate()
  return steps.map((step, i) => {
    if (i < doneCount) return { ...step, state: 'success', detail: stepDetail(step.id, true) }
    if (i === doneCount && running) return { ...step, state: 'running', detail: '正在执行' }
    return step
  })
}
function stepDetail(id: string, ok: boolean): string {
  const map: Record<string, string> = {
    'sound-light': ok ? '已执行' : '等待执行',
    band: ok ? '已发送' : '等待发送',
    'safety-officer': ok ? '已送达' : '等待送达',
    dispatcher: ok ? '已送达' : '等待送达',
    slowdown: ok ? '已发送' : '等待发送',
    shutdown: ok ? '已执行' : '等待执行',
    plc: ok ? '已确认' : '等待回执',
  }
  return map[id] ?? ''
}

const personBox = (score: number, x = 40, y = 46, w = 23, h = 44) => ({ id: 'person', label: 'PERSON', score, x, y, w, h, tone: 'person' as const })

export function createInitialAlerts(): AlertEvent[] {
  return [
    {
      id: 'ALM-20260904-001', title: '人员进入龙门吊作业区域', risk: '紧急', eventType: '危险区域闯入',
      time: '13:21:08', area: '装卸区 A', target: '赵磊（P-1003）', source: '人员安全', status: '处理中',
      assignee: '安全员 王建国', slaRemainingSec: 222, ruleId: 'RULE-PER-001', ruleVersion: 'v3.3', durationSec: 96,
      evidence: {
        kind: 'personnel',
        track: [{ x: 12, y: 82 }, { x: 24, y: 70 }, { x: 38, y: 58 }, { x: 52, y: 44 }, { x: 63, y: 30 }],
        currentPosition: '装卸区 A · 龙门吊 G-CRANE-01 回转半径内', fence: '吊装作业禁入区（红色边界）',
        band: 'BAND-1003', bandState: '在线 · 持续震动提醒中', heartRate: '118 次/分',
      },
      linkageAvailable: true, linkage: linkageProgress(5, true),
      timeline: [
        tl('13:21:08', '检测到人员进入危险区域'), tl('13:21:09', '生成紧急告警'),
        tl('13:21:09', '现场声光提醒启动'), tl('13:21:10', '人员手环震动提醒已发送'),
        tl('13:21:10', '通知安全员 王建国'), tl('13:21:11', '通知调度员'),
        tl('13:21:14', '向 G-CRANE-01 发送减速请求'), tl('13:21:30', '安全员确认事件，王建国接单'),
        tl('13:21:31', '发起设备停机，等待 PLC 回执', 'active'),
      ],
      confirmUser: '王建国', confirmTime: '13:21:30', acceptTime: '13:21:30', priority: '紧急', slaLimitMin: 10,
    },
    {
      id: 'ALM-20260904-002', title: '作业人员未佩戴安全帽', risk: '严重', eventType: '未佩戴安全帽',
      time: '13:18:52', area: '装卸区 B', target: '作业人员 P-1042', source: 'AI违规', status: '待确认',
      assignee: '待分配', slaRemainingSec: 752, ruleId: 'RULE-AI-002', ruleVersion: 'v1.8', durationSec: 3,
      evidence: {
        kind: 'ai', scene: 'helmet', confidence: 94.8, model: 'PPE-Detection-v2.4.1', camera: 'CAM-01', time: '13:18:52',
        boxes: [personBox(97, 39, 44, 24, 44), { id: 'helmet', label: 'NO HELMET', score: 94.8, x: 44, y: 42, w: 13, h: 14, tone: 'violation' }],
      },
      linkageAvailable: false, linkage: buildLinkageTemplate(),
      timeline: [tl('13:18:52', 'AI 识别到未佩戴安全帽'), tl('13:18:53', '生成告警并进入待确认队列', 'active')],
      slaLimitMin: 15,
    },
    {
      id: 'ALM-20260904-003', title: '设备间距小于安全阈值', risk: '严重', eventType: '设备接近预警',
      time: '13:15:40', area: '箱区 B', target: 'G-CRANE-01 / 转运车 VEH-08', source: '设备防碰撞', status: '待确认',
      assignee: '待分配', slaRemainingSec: -266, ruleId: 'RULE-DEV-003', ruleVersion: 'v2.4', durationSec: 24,
      evidence: {
        kind: 'collision', distance: 4.2, relSpeed: 1.8, trend: [9.6, 8.4, 7.5, 6.8, 5.9, 5.1, 4.2],
        radar: '毫米波雷达正常 · 激光雷达正常', brakeDistance: '当前制动距离 3.6m',
      },
      linkageAvailable: true, linkage: buildLinkageTemplate(),
      timeline: [tl('13:15:16', '设备间距进入 10m 预警圈'), tl('13:15:40', '间距跌破 6m 严重阈值，生成告警', 'active')],
      slaLimitMin: 10,
    },
    {
      id: 'ALM-20260904-004', title: '人员长时间滞留作业通道', risk: '预警', eventType: '人员滞留',
      time: '13:09:27', area: '箱区通道 C', target: '孙倩（P-1021）', source: '人员安全', status: '待派单',
      assignee: '待分配', slaRemainingSec: 134, ruleId: 'RULE-PER-003', ruleVersion: 'v1.6', durationSec: 186,
      evidence: {
        kind: 'personnel',
        track: [{ x: 40, y: 50 }, { x: 42, y: 48 }, { x: 41, y: 49 }, { x: 40, y: 50 }, { x: 41, y: 49 }],
        currentPosition: '箱区通道 C（静止停留 186 秒）', fence: '通道滞留预警区', band: 'BAND-1021',
        bandState: '在线 · 电量 64%', heartRate: '86 次/分',
      },
      linkageAvailable: false, linkage: buildLinkageTemplate(),
      timeline: [tl('13:09:27', '滞留超过 30 秒阈值，生成预警'), tl('13:10:02', '安全员李娜核实事件，等待派单处置', 'active')],
      confirmUser: '李娜', confirmTime: '13:10:02', slaLimitMin: 20,
    },
    {
      id: 'ALM-20260904-005', title: '人员翻越安全护栏', risk: '预警', eventType: '翻越护栏',
      time: '12:58:11', area: '铁路装卸线 B', target: '外协人员 P-1077', source: 'AI违规', status: '待处理',
      assignee: '安全员 王建国', slaRemainingSec: 1085, ruleId: 'RULE-PER-002', ruleVersion: 'v2.1', durationSec: 2,
      evidence: {
        kind: 'ai', scene: 'fence', confidence: 88.4, model: 'Fence-Guard-v1.8.2', camera: 'CAM-05', time: '12:58:11',
        boxes: [personBox(92, 55, 26, 21, 42), { id: 'rail', label: 'FENCE LINE', x: 12, y: 60, w: 76, h: 7, tone: 'zone' }],
      },
      linkageAvailable: false, linkage: buildLinkageTemplate(),
      timeline: [tl('12:58:11', 'AI 识别到翻越护栏动作'), tl('12:59:03', '安全员确认事件'),
        tl('12:59:40', '事件已派发给王建国，等待接单', 'active')],
      confirmUser: '李娜', confirmTime: '12:59:03', priority: '普通', slaLimitMin: 30,
    },
    {
      id: 'ALM-20260904-006', title: '摄像头画面质量下降', risk: '一般', eventType: '视频设备异常',
      time: '12:46:33', area: '装卸区 A', target: '摄像头 CAM-02', source: '设备异常', status: '处理中',
      assignee: '值班员 陈晓', slaRemainingSec: 2110, ruleId: 'RULE-AI-006', ruleVersion: 'v1.1', durationSec: 905,
      evidence: {
        kind: 'device-metric', description: '画面清晰度持续低于可用阈值，AI 识别能力受限，不代表现场无违规。',
        metrics: [
          { label: '清晰度评分', value: '38 / 100', tone: 'warn' }, { label: '丢帧率', value: '12.4%', tone: 'warn' },
          { label: '在线时长', value: '46 天', tone: 'ok' }, { label: '异常原因', value: '镜头遮挡 / 积灰' },
        ],
      },
      linkageAvailable: false, linkage: buildLinkageTemplate(),
      timeline: [tl('12:46:33', '摄像头健康巡检发现画面质量下降'), tl('12:48:10', '值班员陈晓接单，前往现场清洁镜头', 'active')],
      confirmUser: '李娜', confirmTime: '12:47:02', acceptTime: '12:48:10', priority: '普通', slaLimitMin: 60,
    },
    {
      id: 'ALM-20260904-007', title: '翻箱机运行区域人员闯入', risk: '严重', eventType: '危险区域闯入',
      time: '12:31:19', area: '翻箱机作业区', target: '外协人员 P-1068 / TIP-03', source: '设备防碰撞', status: '待复核',
      assignee: '班长 刘志明', slaRemainingSec: 400, ruleId: 'RULE-PER-001', ruleVersion: 'v3.3', durationSec: 41,
      evidence: {
        kind: 'collision', distance: 2.8, relSpeed: 0.6, trend: [7.2, 6.1, 5.0, 4.1, 3.5, 3.0, 2.8],
        radar: '毫米波雷达正常', brakeDistance: '设备已制动停止',
      },
      linkageAvailable: true,
      linkage: (() => { const s = buildLinkageTemplate(); return s.map((x, i) => i < 7 ? { ...x, state: 'success' as const, detail: stepDetail(x.id, true) } : x) })(),
      linkageFinished: true,
      timeline: [
        tl('12:31:19', '人员进入翻箱机运行区域'), tl('12:31:20', '生成严重告警并启动联动'),
        tl('12:31:21', '现场声光 / 手环 / 通知全部送达'), tl('12:31:25', '翻箱机自动减速'),
        tl('12:31:33', '设备停机，PLC 已确认'), tl('12:33:02', '班长刘志明确认并接单'),
        tl('12:38:47', '提交处置结果：人员已撤离、设备已停止，等待管理复核', 'active'),
      ],
      confirmUser: '刘志明', confirmTime: '12:33:02', acceptTime: '12:33:02', priority: '紧急', slaLimitMin: 15,
      treatment: {
        measures: ['人员已撤离危险区域', '设备已停止运行', '现场确认无遗留风险'], result: '风险已解除',
        attachment: '现场处置照片_123840.jpg（Mock）', note: '已对该外协人员进行现场安全教育', submitTime: '12:38:47', handler: '刘志明',
      },
    },
    {
      id: 'ALM-20260904-008', title: '边缘节点磁盘使用率超过 85%', risk: '一般', eventType: '系统资源告警',
      time: '11:42:06', area: '机房', target: '边缘节点 EDGE-03', source: '系统异常', status: '已关闭',
      assignee: '值班员 陈晓', ruleId: 'RULE-ALM-001', ruleVersion: 'v2.2', durationSec: 1240,
      evidence: {
        kind: 'system-metric', description: '历史视频缓存占满数据盘，清理后恢复正常。',
        metrics: [
          { label: '峰值使用率', value: '91%', tone: 'warn' }, { label: '清理后使用率', value: '62%', tone: 'ok' },
          { label: 'CPU', value: '34%', tone: 'ok' }, { label: '内存', value: '58%', tone: 'ok' },
        ],
      },
      linkageAvailable: false, linkage: buildLinkageTemplate(),
      timeline: [tl('11:42:06', '磁盘使用率超过 85% 阈值'), tl('11:45:20', '陈晓接单处理'),
        tl('12:02:46', '清理历史缓存，使用率回落'), tl('12:05:11', '复核通过，事件关闭')],
      confirmUser: '陈晓', confirmTime: '11:43:40', acceptTime: '11:45:20', priority: '普通',
      treatment: { measures: ['现场确认无遗留风险'], result: '风险已解除', attachment: '磁盘清理记录_120246.txt（Mock）', note: '已调整缓存保留周期为 7 天', submitTime: '12:02:46', handler: '陈晓' },
      reviewUser: '李娜', reviewTime: '12:05:11',
    },
    {
      id: 'ALM-20260904-009', title: '作业人员手环电量低', risk: '预警', eventType: '穿戴设备异常',
      time: '11:36:50', area: '维修通道', target: '吴凯（P-1034）', source: '人员安全', status: '待确认',
      assignee: '待分配', slaRemainingSec: 588, ruleId: 'RULE-PER-003', ruleVersion: 'v1.6', durationSec: 0,
      evidence: {
        kind: 'personnel',
        track: [{ x: 30, y: 60 }, { x: 33, y: 57 }, { x: 36, y: 54 }],
        currentPosition: '维修通道（移动中）', fence: '常规作业区', band: 'BAND-1034',
        bandState: '在线 · 电量 12%', heartRate: '92 次/分',
      },
      linkageAvailable: false, linkage: buildLinkageTemplate(),
      timeline: [tl('11:36:50', '手环电量低于 15%，生成预警', 'active')], slaLimitMin: 20,
    },
    {
      id: 'ALM-20260904-010', title: '两台龙门吊运行轨迹交汇', risk: '严重', eventType: '设备交汇风险',
      time: '11:20:14', area: '箱区 A', target: 'G-CRANE-01 / G-CRANE-02', source: '设备防碰撞', status: '已升级',
      assignee: '安全员 王建国', slaRemainingSec: -72, ruleId: 'RULE-DEV-009', ruleVersion: 'v1.5', durationSec: 312,
      upgradedFrom: '预警',
      evidence: {
        kind: 'collision', distance: 5.4, relSpeed: 2.2, trend: [11.2, 9.8, 8.6, 7.4, 6.6, 5.9, 5.4],
        radar: '毫米波雷达正常 · 激光雷达正常', brakeDistance: '当前制动距离 4.8m',
      },
      linkageAvailable: true, linkage: linkageProgress(4),
      timeline: [tl('11:20:14', '轨迹交汇预测，生成预警'), tl('11:23:41', '风险持续升高，由预警升级为严重'),
        tl('11:24:02', '王建国接单处置'), tl('11:24:20', '联动执行至调度通知，继续处置中', 'active')],
      confirmUser: '王建国', confirmTime: '11:24:02', acceptTime: '11:24:02', priority: '紧急', slaLimitMin: 15,
    },
    {
      id: 'ALM-20260904-011', title: '人员接近电子围栏边界', risk: '一般', eventType: '围栏接近提醒',
      time: '10:58:32', area: '临时围栏区', target: '郑阳（P-1048）', source: '人员安全', status: '已关闭',
      assignee: '安全员 李娜', ruleId: 'RULE-PER-002', ruleVersion: 'v2.1', durationSec: 48,
      evidence: {
        kind: 'personnel',
        track: [{ x: 18, y: 74 }, { x: 24, y: 68 }, { x: 30, y: 62 }],
        currentPosition: '已返回安全区域', fence: '临时施工围栏', band: 'BAND-1048',
        bandState: '在线 · 电量 78%', heartRate: '88 次/分',
      },
      linkageAvailable: false, linkage: buildLinkageTemplate(),
      timeline: [tl('10:58:32', '人员距围栏边界小于 3m'), tl('10:59:20', '李娜确认并现场提醒'),
        tl('11:00:15', '人员撤离，风险解除，事件关闭')],
      confirmUser: '李娜', confirmTime: '10:59:20', acceptTime: '10:59:20', priority: '普通',
      treatment: { measures: ['人员已撤离危险区域'], result: '风险已解除', attachment: '无', note: '口头教育后返回岗位', submitTime: '11:00:10', handler: '李娜' },
      reviewUser: '李娜', reviewTime: '11:00:15',
    },
    {
      id: 'ALM-20260904-012', title: '龙门吊制动油压异常', risk: '严重', eventType: '机械设备异常',
      time: '10:22:47', area: '装卸区 A', target: 'G-CRANE-02', source: '设备异常', status: '已关闭',
      assignee: '设备管理员 周海', ruleId: 'RULE-DEV-002', ruleVersion: 'v2.0', durationSec: 1680,
      evidence: {
        kind: 'device-metric', description: '制动油压低于额定区间，停机检修后恢复。',
        metrics: [
          { label: '异常油压', value: '3.2 MPa', tone: 'danger' }, { label: '额定区间', value: '4.5–6.5 MPa' },
          { label: '检修后油压', value: '5.4 MPa', tone: 'ok' }, { label: '处理方式', value: '更换密封件' },
        ],
      },
      linkageAvailable: true,
      linkage: (() => { const s = buildLinkageTemplate(); return s.slice(0, 6).map((x) => ({ ...x, state: 'success' as const, detail: stepDetail(x.id, true) })) })(),
      linkageFinished: true,
      timeline: [tl('10:22:47', '制动油压跌破下限，生成严重告警'), tl('10:23:05', '联动减速并通知设备管理员'),
        tl('10:25:18', '周海接单，设备停机检修'), tl('10:50:47', '更换密封件，油压恢复'),
        tl('10:54:02', '复核通过，事件关闭')],
      confirmUser: '周海', confirmTime: '10:23:40', acceptTime: '10:25:18', priority: '紧急',
      treatment: { measures: ['设备已停止运行', '现场确认无遗留风险'], result: '风险已解除', attachment: '检修工单 WO-0904-03（Mock）', note: '已纳入本周设备保养计划', submitTime: '10:50:47', handler: '周海' },
      reviewUser: '刘志明', reviewTime: '10:54:02',
    },
  ]
}

/** 顶部统计历史基数，与初始列表配平：全部 128 = 待确认 8 + 处理中 17 + 已关闭 103 */
export const ALERT_METRIC_BASE = { pending: 5, active: 11, severe: 1, closed: 94, total: 128 }
