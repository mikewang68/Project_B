/**
 * 原始数据与质量 mock 数据构造 + 演示状态机
 * REQ-010–023、062
 * 契约来源：docs/mock-contracts.md §2（v0.1）
 * 演示叙事：INJ-01 主案例（A 区 4 电表 07-06 断传→补传→重算）
 *          INJ-02 佐证（BC-A1 07-01 数据跳变）
 *          INJ-07 佐证（WP-A1 07-05 覆盖率不足 <80%）
 *
 * 演示可重复：所有 mutate（POST 补传/重算）落在 module-level state；
 *           页面刷新即复位到 INJ-01 初始态（与后端 datagen 全量复位语义等价）。
 *
 * 数据口径：2 区 12 设备 48 计量点 10 周历史 · 固定种子 42 · 无真实数据。
 * 演示日锚定：DEMO_NOW=2026-07-12 23:59（契约全局约定）
 */

// -------- 演示日 & 场景锚点 --------
const DEMO_NOW = '2026-07-12 23:59:00'
// 第二幕主案例日：07-06（A 区 4 电表断传 09:20-13:40）
const SCENE_DATE = '2026-07-06'
const OUTAGE_START = `${SCENE_DATE} 09:20:00`
const OUTAGE_END = `${SCENE_DATE} 13:40:00`

// -------- 采集点台账（48 点位；只暴露 demo 关注的关键点，其余按批次） --------
function buildPointTree() {
  // 12 台设备 × 平均 4 点位 = 48。演示重点：A 区电表（INJ-01）+ WP-A1 水表（INJ-07）
  return [
    {
      zone: 'A', deviceId: 'GC-A1', deviceName: 'A 区桥吊 #1', energyType: 'ELEC',
      points: [
        { pointId: 'BC-A1', pointName: 'A-桥吊 #1 主进线', unit: 'kWh', samplingInterval: '15min', status: '启用' },
        { pointId: 'BC-A2', pointName: 'A-桥吊 #1 提升电机', unit: 'kWh', samplingInterval: '15min', status: '启用' },
        { pointId: 'BC-A3', pointName: 'A-桥吊 #1 行走电机', unit: 'kWh', samplingInterval: '15min', status: '启用' },
        { pointId: 'BC-A4', pointName: 'A-桥吊 #1 辅助电机', unit: 'kWh', samplingInterval: '15min', status: '启用' },
        // #24-2 状态点：INJ-03 待机段样例（backend 用 statusValue 字段回填）
        { pointId: 'BC-A1-STATUS', pointName: 'A-桥吊 #1 运行状态', unit: '', samplingInterval: '15min', status: '启用' }
      ]
    },
    {
      zone: 'A', deviceId: 'BT-A2', deviceName: 'A 区装卸皮带 #2', energyType: 'ELEC',
      points: [
        { pointId: 'BT-A2-M', pointName: 'A-皮带 #2 主电机', unit: 'kWh', samplingInterval: '15min', status: '启用' },
        { pointId: 'BT-A2-C', pointName: 'A-皮带 #2 张紧器', unit: 'kWh', samplingInterval: '15min', status: '启用' }
      ]
    },
    {
      zone: 'A', deviceId: 'AC-A1', deviceName: 'A 区空压机组 #1', energyType: 'ELEC',
      points: [
        { pointId: 'AC-A1-E', pointName: 'A-空压机 #1 电耗', unit: 'kWh', samplingInterval: '15min', status: '启用' },
        { pointId: 'AC-A1-F', pointName: 'A-空压机 #1 排气流量', unit: 'Nm³', samplingInterval: '5min', status: '启用' }
      ]
    },
    {
      zone: 'A', deviceId: 'WP-A', deviceName: 'A 区供水', energyType: 'WATER',
      points: [
        { pointId: 'WP-A1', pointName: 'A-总水表', unit: 'm³', samplingInterval: '15min', status: '启用' },
        { pointId: 'WP-A2', pointName: 'A-冲洗水表', unit: 'm³', samplingInterval: '15min', status: '启用' }
      ]
    },
    {
      zone: 'B', deviceId: 'SF-B3', deviceName: 'B 区筒仓风机 #3', energyType: 'ELEC',
      points: [
        { pointId: 'SF-B3-M', pointName: 'B-筒仓风机 #3 主电机', unit: 'kWh', samplingInterval: '15min', status: '启用' },
        { pointId: 'SF-B3-B', pointName: 'B-筒仓风机 #3 轴承温度', unit: '℃', samplingInterval: '15min', status: '启用' }
      ]
    },
    {
      zone: 'B', deviceId: 'PT-B1', deviceName: 'B 区气力输送机组', energyType: 'ELEC',
      points: [
        { pointId: 'PT-B1-E', pointName: 'B-气力输送 主电机', unit: 'kWh', samplingInterval: '15min', status: '启用' },
        { pointId: 'PT-B1-F', pointName: 'B-气力输送 气流量', unit: 'Nm³', samplingInterval: '5min', status: '启用' }
      ]
    },
    {
      zone: 'B', deviceId: 'AP-B1', deviceName: 'B 区供气总管', energyType: 'AIR',
      points: [
        { pointId: 'AP-B1-F', pointName: 'B-供气总管 流量', unit: 'Nm³', samplingInterval: '5min', status: '启用' },
        { pointId: 'AP-B1-P', pointName: 'B-供气总管 压力', unit: 'MPa', samplingInterval: '5min', status: '启用' }
      ]
    },
    {
      zone: 'B', deviceId: 'CB-B2', deviceName: 'B-05 电表（补传演示）', energyType: 'ELEC',
      points: [
        { pointId: 'B-05', pointName: 'B-05 汇流电表', unit: 'kWh', samplingInterval: '15min', status: '启用' }
      ]
    }
  ]
}

function flattenPoints() {
  const flat = []
  for (const g of buildPointTree()) {
    for (const p of g.points) {
      flat.push({
        zone: g.zone,
        deviceId: g.deviceId,
        deviceName: g.deviceName,
        energyType: g.energyType,
        pointId: p.pointId,
        pointName: p.pointName,
        unit: p.unit,
        samplingInterval: p.samplingInterval,
        status: p.status
      })
    }
  }
  return flat
}

// -------- 15min 时间戳序列 --------
function ts15min(dateStr, startHour, endHour) {
  const arr = []
  const [y, m, d] = dateStr.split('-').map(Number)
  for (let h = startHour; h < endHour; h++) {
    for (let mm = 0; mm < 60; mm += 15) {
      const hh = String(h).padStart(2, '0')
      const mmS = String(mm).padStart(2, '0')
      arr.push(`${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')} ${hh}:${mmS}:00`)
    }
  }
  return arr
}

// -------- 状态点位读数构造（*-STATUS） --------
// 场景：06:00-09:00 running · 09:00-12:00 running · 12:00-13:00 standby ·
//       13:00-17:00 standby（INJ-03 疑似空载）· 17:00-18:00 stopped
function buildStatusReadings(pointId) {
  const times = ts15min(SCENE_DATE, 6, 18)
  return times.map((t) => {
    const hh = parseInt(t.slice(11, 13), 10)
    let sv = 'running'
    if (hh >= 13 && hh < 17) sv = 'standby'
    else if (hh >= 17) sv = 'stopped'
    return {
      ts: t, cumulative: null, delta: null, unit: '',
      quality: 'ok', sourceBatchId: 'B-STATUS-20260706',
      ingestedAt: t.replace(/:\d\d$/, ':03'),
      isBackfill: false, remark: null,
      statusValue: sv
    }
  })
}

// -------- 07-06 单点读数构造（08:00-16:00 覆盖断传窗） --------
// 演示态：从初始态（断传窗全 miss）到补传后（fix + isBackfill）
function buildInitialReadings(pointId) {
  // 状态点位走另一条支线（#24-2）
  if ((pointId || '').endsWith('-STATUS')) return buildStatusReadings(pointId)
  const times = ts15min(SCENE_DATE, 6, 18)  // 06:00-18:00, 48 条

  // 断传窗 index 范围（09:20 起 → 首个受影响的 15min slot 是 09:30, 至 13:30 结束）
  const outageStart = times.findIndex((t) => t >= `${SCENE_DATE} 09:30:00`)
  const outageEnd = times.findIndex((t) => t >= `${SCENE_DATE} 13:45:00`)

  // 典型日负荷型（15min 序列，08:00 起）；未真值，占位
  const baseKwhPerSlot = [
    // 06:00-07:45 (8 slots)
    3.2, 3.1, 3.0, 3.0, 3.4, 3.5, 4.2, 5.0,
    // 08:00-09:45 (8)
    8.5, 12.0, 15.5, 18.2, 20.1, 22.0, 21.5, 20.8,
    // 10:00-11:45 (8)
    23.5, 24.8, 25.2, 26.0, 25.4, 25.0, 26.5, 27.0,
    // 12:00-13:45 (8)
    27.2, 26.8, 25.9, 25.4, 24.6, 23.8, 22.4, 21.9,
    // 14:00-15:45 (8)
    22.0, 20.5, 19.8, 18.0, 16.2, 14.8, 12.5, 10.5,
    // 16:00-17:45 (8)
    8.6, 7.0, 5.8, 5.0, 4.6, 4.2, 4.0, 3.8
  ]

  let cumulative = 15420.5 // 起始累计值
  return times.map((t, i) => {
    const inOutage = i >= outageStart && i < outageEnd
    const delta = inOutage ? null : baseKwhPerSlot[i] || 0
    if (!inOutage) cumulative = +(cumulative + delta).toFixed(2)
    return {
      ts: t,
      cumulative: inOutage ? null : cumulative,
      delta,
      unit: 'kWh',
      quality: inOutage ? 'miss' : 'ok',
      sourceBatchId: inOutage ? null : 'B-20260706-01',
      ingestedAt: inOutage ? null : t.replace(/:\d\d$/, ':05'), // 5s 延迟入库
      isBackfill: false,
      remark: inOutage ? '采集离线' : null
    }
  })
}

function applyBackfillToReadings(readings, batchId, when) {
  // 断传窗内 miss → fix + isBackfill；重建累计值链
  const outageStart = readings.findIndex((r) => r.quality === 'miss')
  if (outageStart === -1) return readings
  const outageEnd = readings.length - readings.slice().reverse().findIndex((r) => r.quality === 'miss')

  // 从最后一个正常点往后累加
  let lastGood = null
  for (let i = outageStart - 1; i >= 0; i--) {
    if (readings[i].quality === 'ok') { lastGood = readings[i]; break }
  }
  let cumulative = lastGood?.cumulative || 15420.5

  // 断传时段的估计增量（对齐正常段均值）
  const slotAvg = 22.5
  for (let i = outageStart; i < outageEnd; i++) {
    cumulative = +(cumulative + slotAvg).toFixed(2)
    readings[i] = {
      ...readings[i],
      cumulative,
      delta: slotAvg,
      quality: 'fix',
      sourceBatchId: batchId,
      ingestedAt: when,
      isBackfill: true,
      remark: '补传写入 · 幂等键（点位+时间）'
    }
  }
  // 断传后的正常段累计值也需要顺延，但为了演示简单保留原样（不影响 delta 与曲线）
  return readings
}

// -------- 采集任务状态 --------
function buildTasks() {
  return [
    {
      taskId: 'CT-A-ELEC',
      taskName: 'A 区电表批量采集',
      dataSource: 'gateway',
      lastSuccessAt: `${SCENE_DATE} 09:15:00`,
      lastFailureAt: `${SCENE_DATE} 09:20:00`,
      lastError: '连续 3 个周期无数据（网关离线）',
      failureBatchCount: 17,
      currentState: 'failed',
      affectedPoints: ['BC-A1', 'BC-A2', 'BC-A3', 'BC-A4'],
      samplingInterval: '15min',
      timeoutThreshold: '45min'
    },
    {
      taskId: 'CT-A-WATER',
      taskName: 'A 区水表批量采集',
      dataSource: 'gateway',
      lastSuccessAt: `${DEMO_NOW}`,
      lastFailureAt: null,
      lastError: null,
      failureBatchCount: 0,
      currentState: 'running',
      affectedPoints: ['WP-A1', 'WP-A2'],
      samplingInterval: '15min',
      timeoutThreshold: '45min'
    },
    {
      taskId: 'CT-B-ELEC',
      taskName: 'B 区电表批量采集',
      dataSource: 'gateway',
      lastSuccessAt: `${DEMO_NOW}`,
      lastFailureAt: null,
      lastError: null,
      failureBatchCount: 0,
      currentState: 'running',
      affectedPoints: ['SF-B3-M', 'SF-B3-B', 'PT-B1-E', 'B-05'],
      samplingInterval: '15min',
      timeoutThreshold: '45min'
    },
    {
      taskId: 'CT-B-AIR',
      taskName: 'B 区气流量采集',
      dataSource: 'gateway',
      lastSuccessAt: `${DEMO_NOW}`,
      lastFailureAt: null,
      lastError: null,
      failureBatchCount: 0,
      currentState: 'running',
      affectedPoints: ['AP-B1-F', 'AP-B1-P', 'PT-B1-F', 'AC-A1-F'],
      samplingInterval: '5min',
      timeoutThreshold: '15min'
    }
  ]
}

// -------- 覆盖率水位 --------
function coverageForPoint(pointId, readings) {
  const total = readings.length
  const good = readings.filter((r) => r.quality === 'ok' || r.quality === 'fix' || r.quality === 'late').length
  const pct = +((good / total) * 100).toFixed(1)
  let band = 'ok'
  if (pct < 80) band = 'insufficient'
  else if (pct < 95) band = 'degraded'
  // INJ-07 特例：WP-A1 覆盖率 70%（不基于 readings，直接硬回）
  if (pointId === 'WP-A1') {
    return { pct: 70.0, threshold: { warn: 95, serious: 80 }, band: 'insufficient', missingSlotCount: 29 }
  }
  return { pct, threshold: { warn: 95, serious: 80 }, band, missingSlotCount: total - good }
}

// -------- 质量分布（含 INJ-02 累计 jump） --------
function buildQualityBreakdown(readings) {
  const counts = { ok: 0, miss: 0, late: 0, dup: 0, jump: 0, est: 0, fix: 0, frozen: 0 }
  readings.forEach((r) => { counts[r.quality] = (counts[r.quality] || 0) + 1 })
  // INJ-02 累计佐证：+1 jump（历史累计，独立于当前时间窗）
  counts.jump += 1
  const total = readings.length + 1
  return Object.entries(counts)
    .filter(([_, c]) => c > 0)
    .map(([q, c]) => ({
      quality: q,
      count: c,
      pct: +((c / total) * 100).toFixed(1),
      sampleTs: q === 'jump' ? '2026-07-01 14:15:00' : undefined
    }))
}

// -------- 状态机（module-level，前端演示可重复） --------
const state = {
  readings: buildInitialReadings('BC-A1'),
  backfillBatches: [],
  recomputeHints: [],
  tasksVersion: 0 // 记状态变化次数，供 UI 强制刷新
}

function resetState() {
  state.readings = buildInitialReadings('BC-A1')
  state.backfillBatches = []
  state.recomputeHints = []
  state.tasksVersion = 0
}

// -------- 主入口：GET /raw-quality/summary --------
export default function mockRawQuality(query = {}) {
  const pointId = query.pointId || 'BC-A1'
  const zone = query.zone || 'ALL'
  const points = flattenPoints()
    .filter((p) => zone === 'ALL' || p.zone === zone)

  // 当前只对 BC-A1（第二幕主案例）做完整 readings；其它点位返回空 + 提示
  const isMainScene = pointId === 'BC-A1' || pointId === 'BC-A2' || pointId === 'BC-A3' || pointId === 'BC-A4'
  const isStatusScene = (pointId || '').endsWith('-STATUS')
  const readings = isStatusScene
    ? buildInitialReadings(pointId)  // 完整 48 条状态序列（#24-2）
    : isMainScene
      ? state.readings
      : (pointId === 'WP-A1' ? [] : buildInitialReadings(pointId).slice(0, 12))
  const cov = coverageForPoint(pointId, readings.length ? readings : [{}, {}, {}, {}, {}, {}, {}, {}, {}, {}])

  const selectedMeta = points.find((p) => p.pointId === pointId) || points[0]

  // 采集任务：BC-A1 演示进度改变时更新 CT-A-ELEC 状态
  const tasks = buildTasks()
  const backfillDone = state.backfillBatches.length > 0
  if (backfillDone) {
    const t = tasks.find((t) => t.taskId === 'CT-A-ELEC')
    t.currentState = 'running'
    t.lastSuccessAt = state.backfillBatches[0].triggeredAt
    t.lastError = `${t.lastError}（已补传恢复）`
    t.failureBatchCount = 0
  }

  return {
    signature: {
      version: 'v0.3.4-demo',
      formulaVersion: 'f-1.3',
      sigId: 'a8e3-4b71',
      seed: 42,
      generatedAt: DEMO_NOW,
      coverage: cov.pct
    },
    demoState: {
      enabled: true,
      hint: '第二幕主案例：INJ-01 A 区电表 07-06 09:20-13:40 断传 · 演示补传→重算流转',
      reqAnchor: 'REQ-014'
    },
    filters: { pointId, timeStart: `${SCENE_DATE} 06:00:00`, timeEnd: `${SCENE_DATE} 18:00:00`, zone },
    points,
    selected: {
      pointId: selectedMeta.pointId,
      pointName: selectedMeta.pointName,
      deviceId: selectedMeta.deviceId,
      deviceName: selectedMeta.deviceName,
      zone: selectedMeta.zone,
      energyType: selectedMeta.energyType,
      unit: selectedMeta.unit,
      samplingInterval: selectedMeta.samplingInterval,
      currentTaskState: isMainScene ? (backfillDone ? 'running' : 'failed') : 'running',
      coverageToday: cov.pct
    },
    readings,
    coverage: cov,
    tasks,
    backfillBatches: state.backfillBatches,
    recomputeHints: state.recomputeHints,
    qualityBreakdown: buildQualityBreakdown(readings.length ? readings : [{ quality: 'ok' }])
  }
}

// -------- POST /raw-quality/backfill --------
export function mockBackfill({ pointId = 'BC-A1', cacheStart, cacheEnd, dryRun = false } = {}) {
  if (dryRun) {
    // 契约 §2.2：dryRun 也返回 {batch, derivedRecomputeHints[]} 完整结构（对齐 backend 事实）
    return {
      batch: {
        batchId: 'DRY-RUN',
        triggeredBy: 'ops_user',
        triggeredAt: `${SCENE_DATE} 14:02:15`,
        cacheStart: cacheStart || OUTAGE_START,
        cacheEnd: cacheEnd || OUTAGE_END,
        pointIds: [pointId],
        recordsIngested: 17,
        dupHandledCount: 0,
        failureReason: null,
        status: 'dryRun',
        affectedPeriods: [`${SCENE_DATE}D`, '2026-07M']
      },
      derivedRecomputeHints: []
    }
  }
  const batchId = `BF-${SCENE_DATE.replace(/-/g, '')}-${String(state.backfillBatches.length + 1).padStart(2, '0')}`
  const triggeredAt = `${SCENE_DATE} 14:02:15`
  const affectedPeriods = [`${SCENE_DATE}D`, '2026-07M']

  // mutate readings：miss → fix + isBackfill
  state.readings = applyBackfillToReadings(state.readings, batchId, triggeredAt)

  const batch = {
    batchId,
    triggeredBy: 'ops_user',
    triggeredAt,
    cacheStart: cacheStart || OUTAGE_START,
    cacheEnd: cacheEnd || OUTAGE_END,
    pointIds: ['BC-A1', 'BC-A2', 'BC-A3', 'BC-A4'],
    recordsIngested: 17 * 4, // 4 点位 × 17 缺测槽
    dupHandledCount: 0,
    failureReason: null,
    status: 'succeeded',
    affectedPeriods
  }
  state.backfillBatches.unshift(batch)

  // 派生日/月重算提示（初始 pending）
  affectedPeriods.forEach((periodKey) => {
    if (!state.recomputeHints.find((h) => h.periodKey === periodKey)) {
      state.recomputeHints.push({
        periodKey,
        scope: periodKey.endsWith('D') ? 'day' : 'month',
        status: 'pending',
        triggeredBy: batchId,
        oldVersion: 'v1.0',
        newVersion: null,
        diffSummary: null,
        finishedAt: null
      })
    }
  })
  state.tasksVersion++

  // 契约 §2.2：响应形状 = 新 BackfillBatch + 派生的 recomputeHint（日/月统计待重算）
  const derivedRecomputeHints = affectedPeriods
    .map((pk) => state.recomputeHints.find((h) => h.periodKey === pk))
    .filter(Boolean)
  return { batch, derivedRecomputeHints }
}

// -------- POST /raw-quality/recompute --------
export function mockRecompute({ periodKey, scope } = {}) {
  const hint = state.recomputeHints.find((h) => h.periodKey === periodKey)
  if (!hint) {
    return { error: 'periodKey not found', periodKey }
  }
  hint.status = 'done'
  hint.newVersion = 'v1.1'
  hint.finishedAt = `${SCENE_DATE} 14:05:42`
  // 演示叙事：日统计 12,480 → 18,320 kWh（+46.8%）
  if (scope === 'day' || periodKey.endsWith('D')) {
    hint.diffSummary = { metric: '当日总能耗', oldValue: 12480, newValue: 18320, deltaPct: 46.8 }
  } else {
    hint.diffSummary = { metric: '当月累计能耗', oldValue: 462100, newValue: 467940, deltaPct: 1.26 }
  }
  state.tasksVersion++
  return hint
}

// 供开发/测试面板复位（生产 build 可不暴露）
export function __resetMockState() {
  resetState()
}
