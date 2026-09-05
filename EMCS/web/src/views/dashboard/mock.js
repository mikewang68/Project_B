/**
 * 能源总览 mock 数据构造
 * REQ-057 至 062、019、053、029
 *
 * 契约来源：PRD §5.1 页面级字段表 + 演示数据规范（2 区 12 设备 48 计量点 10 周历史）
 * 数据口径纪律：只允许构造占位数据、固定随机种子；无真实计量数据、无真实人名工号。
 *
 * 后端就绪后：本文件删除或缩减为静态字典，替换为 overview.js 里的真实 request 调用。
 */

// -------- 固定种子伪随机（用于视觉稳定，不用于统计） --------
function seeded(seed) {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 0xffffffff
  }
}

// -------- 24 小时负荷曲线（构造，含峰谷与两个尖峰异常） --------
function build24hLoad() {
  const rand = seeded(42) // 演示数据规范固定种子
  const hours = []
  const load = []
  const baselineMid = []
  const baselineHigh = []
  const baselineLow = []
  // 构造一条典型的白班峰型：夜间低谷（3~4 点），10:00-14:00 峰，20:00 回落
  const shape = [
    280, 265, 260, 262, 285, 340, 480, 620, 780, 900, 1050, 1120,
    1142, 1090, 985, 1020, 940, 820, 690, 580, 470, 390, 335, 300
  ]
  for (let h = 0; h < 24; h++) {
    hours.push(String(h).padStart(2, '0') + ':00')
    const jitter = (rand() - 0.5) * 60
    load.push(Math.round(shape[h] + jitter))
    const base = shape[h] * 0.94
    baselineMid.push(Math.round(base))
    baselineHigh.push(Math.round(base * 1.12))
    baselineLow.push(Math.round(base * 0.88))
  }
  // 尖峰联动 R09 基线偏差（演示态，无 INJ 注入）
  // R09 判定：日能耗超出基线带 +20%（PRD §7.2）
  const anomalies = [
    { hourIndex: 12, value: 1142, ruleId: 'R09', note: 'R09 基线偏差 · 演示态' },
    { hourIndex: 15, value: 1020, ruleId: 'R09', note: 'R09 基线偏差 · 演示态' }
  ]
  // 当前时刻游标（演示：06:12 → hourIndex 6）
  return {
    hours,
    load,
    baselineHigh,
    baselineLow,
    baselineMid,
    anomalies,
    peak: { hour: '12:00', value: 1142, note: '超基线上限 20% · 触发 R09' },
    valley: { hour: '03:00', value: 260 },
    nowIndex: 6,
    unit: 'kW',
    samplingInterval: '15min',
    formulaVersion: 'f-1.3'
  }
}

// -------- 6 张 KPI 卡（字段口径严格对齐 PRD §5.1 表） --------
function buildKpis() {
  return [
    {
      code: 'ENERGY_DAY',
      label: '当日总能耗',
      unit: 'kWh',
      value: 18462,
      tag: '今日',
      tagKind: 'neutral',
      delta: { value: '+6.4%', kind: 'up', label: '环比昨日' },
      subLabel: '电力口径 · 合格样点聚合',
      reqAnchor: 'REQ-024 / 025 / 019'
    },
    {
      code: 'BASELINE_DEV',
      label: '基线偏差',
      unit: '%',
      value: '+22.4',
      tag: '超阈',
      tagKind: 'warn',
      delta: { value: '阈值 +20%', kind: 'up', label: '' },
      subLabel: 'REQ-029 演示态 · 报告期 vs 基线期 · 触发 R09',
      reqAnchor: 'REQ-029 / R09'
    },
    {
      code: 'OPEN_ALARMS',
      label: '未关闭告警',
      unit: '条',
      value: 14,
      tag: '3 严重',
      tagKind: 'hi',
      delta: { value: '+3', kind: 'up', label: 'vs 昨日' },
      subLabel: 'R06 压缩空气疑似泄漏 · 严重级',
      reqAnchor: 'REQ-039 / 042'
    },
    {
      code: 'COVERAGE',
      label: '当日采样覆盖率',
      unit: '%',
      value: 96.8,
      tag: '≥95%',
      tagKind: 'ok',
      delta: { value: '-1.1pp', kind: 'dn', label: '因 B-05 缺测' },
      subLabel: '48/48 pts · 高于阈值 95%',
      reqAnchor: 'REQ-019'
    },
    {
      code: 'COST_MTD',
      label: '当月累计成本',
      unit: '万元',
      value: 62.4,
      tag: '当月',
      tagKind: 'neutral',
      delta: { value: '+3.7%', kind: 'up', label: '同比去年' },
      subLabel: '单价 v2026-07 · 峰:平:谷 41:38:21',
      reqAnchor: 'REQ-051 / 052'
    },
    {
      code: 'SUGGESTIONS',
      label: '待办节能建议',
      unit: '条',
      value: 7,
      tag: '流转中',
      tagKind: 'neutral',
      delta: { value: '待审 3 · 已派 2 · 执行 2', kind: 'neutral', label: '' },
      subLabel: '节能建议状态分档 · REQ-045',
      reqAnchor: 'REQ-045 / 047'
    }
  ]
}

// -------- Top5 用能对象（成本口径）--------
function buildTop5() {
  return [
    { rank: 1, name: 'B-筒仓风机 #3', meta: 'B 区 · 电 · 24×7 运行', value: 128406, unit: '¥', share: '20.6%', barPct: 100, kind: 'hot' },
    { rank: 2, name: 'A-桥吊 #1', meta: 'A 区 · 电 · 装卸主力', value: 96204, unit: '¥', share: '15.4%', barPct: 75, kind: 'cool' },
    { rank: 3, name: 'B-气力输送机组', meta: 'B 区 · 电 + 压缩空气', value: 84712, unit: '¥', share: '13.6%', barPct: 66, kind: 'cool' },
    { rank: 4, name: 'A-装卸皮带 #2', meta: 'A 区 · 电 · 白班主用能', value: 61338, unit: '¥', share: '9.8%', barPct: 48, kind: 'cool' },
    { rank: 5, name: 'A-空压机组 #1', meta: 'A 区 · 电 · 压缩空气源', value: 52890, unit: '¥', share: '8.5%', barPct: 41, kind: 'cool' }
  ]
}

// -------- Top5 用能对象（能耗口径 · 电 kWh）--------
// 排序与成本榜有意不同：24×7 连续运行的设备能耗份额比装卸类设备大，
// A-桥吊 因峰段电价高在成本榜排 2、能耗榜排 4；A-空压机 #1 反之。
function buildTop5ByEnergy() {
  return [
    { rank: 1, name: 'B-筒仓风机 #3', meta: 'B 区 · 电 · 24×7 运行', value: 152864, unit: 'kWh', share: '22.4%', barPct: 100, kind: 'hot' },
    { rank: 2, name: 'B-气力输送机组', meta: 'B 区 · 电 · 粉煤灰输送', value: 118420, unit: 'kWh', share: '17.4%', barPct: 77, kind: 'cool' },
    { rank: 3, name: 'A-空压机组 #1', meta: 'A 区 · 电 · 24h 供气源', value: 96128, unit: 'kWh', share: '14.1%', barPct: 63, kind: 'cool' },
    { rank: 4, name: 'A-桥吊 #1', meta: 'A 区 · 电 · 装卸主力（含峰段）', value: 71540, unit: 'kWh', share: '10.5%', barPct: 47, kind: 'cool' },
    { rank: 5, name: 'A-装卸皮带 #2', meta: 'A 区 · 电 · 白班主用能', value: 62204, unit: 'kWh', share: '9.1%', barPct: 41, kind: 'cool' }
  ]
}

// -------- 告警列表 --------
function buildAlarms() {
  return [
    {
      time: '06:08:12',
      level: 'crit',
      ruleId: 'R06',
      ruleVersion: 'v2026-06',
      title: '非作业时段气流量持续偏高',
      lead: '疑似压缩空气泄漏 · 持续 2h05min 高于作业均值 30% 阈值',
      obj: 'B 区 · 供气总管',
      mergedCount: 3
    },
    {
      time: '05:47:03',
      level: 'warn',
      ruleId: 'R09',
      ruleVersion: 'v2026-06',
      title: '基线偏差超上限 20%（演示态）',
      lead: '报告期日能耗较基线期偏高 22.4% · 桥吊 #1 峰值时段贡献主要偏差',
      obj: 'A 区 · 桥吊 #1',
      mergedCount: 2
    },
    {
      time: '04:31:44',
      level: 'crit',
      ruleId: 'R05',
      ruleVersion: 'v2026-06',
      title: '设备高耗且作业未增加',
      lead: '作业量 0 · 实测功率 62kW · 判为疑似空载',
      obj: 'B 区 · 筒仓风机 #3',
      mergedCount: 1
    },
    {
      time: '03:14:20',
      level: 'warn',
      ruleId: 'R01',
      ruleVersion: 'v2026-06',
      title: '采集离线（连续 3 个周期无数据）',
      lead: 'B-05 电表离线 48 分钟 · 已恢复 · 待补传',
      obj: 'B-05 · 电表',
      mergedCount: 1
    },
    {
      time: '02:52:07',
      level: 'info',
      ruleId: 'R02',
      ruleVersion: 'v2026-06',
      title: '数据迟到 32 分钟',
      lead: '已按迟到质量标注入库',
      obj: 'A-11 · 水表',
      mergedCount: 1
    },
    {
      time: '01:14:12',
      level: 'crit',
      ruleId: 'R11',
      ruleVersion: 'v2026-06',
      title: '采样覆盖率不足',
      lead: 'WP-A1 覆盖率 70%（<80% 阈值）· 当日水表不进入正式统计',
      obj: 'A 区 · WP-A1 水表',
      mergedCount: 1
    }
  ]
}

// -------- 48 计量点信号阵列（12×4 网格） --------
function buildQuality() {
  // 生成 48 个格子，默认 ok，散布几个特殊状态
  const cells = new Array(48).fill('ok')
  // 缺测：两个位置（B 区）
  cells[21] = 'miss'
  cells[34] = 'miss'
  // 迟到：两个位置
  cells[5] = 'late'
  cells[19] = 'late'
  // 估算：一个
  cells[29] = 'est'
  // 人工修正：一个
  cells[14] = 'fix'

  return {
    coverage: 96.8,
    coverageThreshold: 95,
    total: 48,
    categories: [
      { key: 'ok', label: '正常', count: 42, pct: '87.5%' },
      { key: 'miss', label: '缺测', count: 2, pct: '4.2%' },
      { key: 'late', label: '迟到', count: 2, pct: '4.2%' },
      { key: 'est', label: '估算', count: 1, pct: '2.1%' },
      { key: 'fix', label: '人工修正', count: 1, pct: '2.1%' }
    ],
    cells,
    zoneSplit: 'A 区 24 点 · B 区 24 点',
    latestIngestAt: '06:11:47'
  }
}

// -------- 主入口 --------
export default function mockOverview(_query) {
  return {
    signature: {
      version: 'v0.3.4-demo',
      formulaVersion: 'f-1.3',
      priceVersion: 'v2026-07',
      baselineVersion: 'EB-2026-07',
      sigId: 'a8e3-4b71',
      seed: 42,
      generatedAt: '2026-07-13 06:12:38',
      coverage: 96.8
    },
    demoState: {
      enabled: true,
      hint: 'REQ-029 能源基线演示态：demo 环境已显式启用 EnPI，10 周构造历史（8 周基线 + 2 周报告期）作为演示折算。',
      reqAnchor: 'REQ-029'
    },
    filters: {
      timeRange: 'week',
      zone: 'ALL',
      energyType: 'ELEC'
    },
    kpis: buildKpis(),
    trend: build24hLoad(),
    topObjects: buildTop5(),
    topObjectsByEnergy: buildTop5ByEnergy(),
    alarms: buildAlarms(),
    quality: buildQuality()
  }
}
