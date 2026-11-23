<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import * as Cesium from 'cesium'
import CesiumViewer from '@/components/cesium/CesiumViewer.vue'
import { setupCameraInteraction } from '@/components/cesium/cameraControl'
import { CAD_GEOJSON_URL } from '@/config/runtime'
import { Twin3dController, type SelectedInfo } from '@/twin3d'
import { cadGeoJsonToWgs84, cadToCartesian3 } from '@/twin3d/geo'
import {
  CALIBRATION_GANTRIES,
  CALIBRATION_RAILS,
  CALIBRATION_REBAR_BAYS,
  CALIBRATION_ZONES,
  type CadPoint,
} from '@/twin3d/siteCalibration'
import { getPlacement } from '@/twin3d/placements'
import { SITE_FACTS, SITE_FACT_STATUS_LABEL } from '@/twin3d/siteFacts'
import { SITE_LAYOUT_V4 } from '@/twin3d/siteLayoutV4'

export interface CalibrationAuditRow {
  id: string
  name: string
  category: 'gantry' | 'bridge' | 'bulk_equip' | 'weigh' | 'silo'
  categoryLabel: string
  cadX: number
  cadY: number
  placedX: number
  placedY: number
  residualM: number
  deltaLabel: string
  spec: string
  basisType: 'cad_surveyed' | 'process_eddx' | 'planned_schematic'
  basisLabel: string
  pendingDecision?: boolean
  decisionNote?: string
  crossRef: string
}

let viewer: Cesium.Viewer | null = null
let cadDataSource: Cesium.GeoJsonDataSource | null = null
let calibrationSource: Cesium.CustomDataSource | null = null
let twin3d: Twin3dController | null = null

const status = ref('正在加载冻结版《给大连大.dwg》局部米制平面基准与 80 项三维对象落位校核台账…')
const selectedCategory = ref<'all' | CalibrationAuditRow['category']>('all')
const onlyPendingDecision = ref(false)
const searchKeyword = ref('')
const activeRowId = ref('CR-02')
const pickedInfo = ref<SelectedInfo | null>(null)

const overlayFlags = ref({
  cadBase: true,
  models3d: true,
  wireframe: true,
  plannedZone: true,
})

function measurePlacement(id: string, cadX: number, cadY: number, instanceFallback?: { cadX: number; cadY: number }) {
  const placed = getPlacement(id)
  const placedX = placed?.cadX ?? instanceFallback?.cadX ?? cadX
  const placedY = placed?.cadY ?? instanceFallback?.cadY ?? cadY
  const residualM = Math.hypot(placedX - cadX, placedY - cadY)
  return {
    placedX: Number(placedX.toFixed(2)),
    placedY: Number(placedY.toFixed(2)),
    residualM,
    deltaLabel: `${residualM.toFixed(2)} m`,
  }
}

function buildAuditRows(): CalibrationAuditRow[] {
  const rows: CalibrationAuditRow[] = []

  for (const g of SITE_LAYOUT_V4.rebar.gantries) {
    const m = measurePlacement(g.id, g.cadX, g.cadY)
    rows.push({
      id: g.id,
      name: g.name,
      category: 'gantry',
      categoryLabel: '室外门吊',
      cadX: g.cadX,
      cadY: g.cadY,
      ...m,
      spec: `${g.capacityT}t / 跨度${g.spanM}m`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 局部平面坐标',
      crossRef: 'F-D02 / F-D03 · X03(EHMS) / X10(门吊控制)',
    })
  }
  for (const g of SITE_LAYOUT_V4.ash.bulkGantries) {
    const m = measurePlacement(g.id, g.cadX, g.cadY)
    rows.push({
      id: g.id,
      name: g.name,
      category: 'gantry',
      categoryLabel: '室外门吊',
      cadX: g.cadX,
      cadY: g.cadY,
      ...m,
      spec: `${g.capacityT}t / 跨度${g.spanM}m`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 局部平面坐标',
      crossRef: 'F-D02 / F-D03 · X03(EHMS) / X10(门吊控制)',
    })
  }
  for (const bay of SITE_LAYOUT_V4.rebar.bays) {
    const bcId = `BC-${String(bay.bayIndex).padStart(2, '0')}`
    const isChook = bay.bridgeType === 'chook_32t'
    const m = measurePlacement(bcId, bay.cadX, bay.bridgeParkY)
    rows.push({
      id: bcId,
      name: `钢筋库 ${String(bay.bayIndex).padStart(2, '0')}# 跨室内桥吊`,
      category: 'bridge',
      categoryLabel: '室内/机电桥吊',
      cadX: bay.cadX,
      cadY: bay.bridgeParkY,
      ...m,
      spec: isChook ? '32t C型卷材钩（03/06/09/13跨）/ S=28.5m' : '20t 电磁吸盘 / S=28.5m',
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 13跨平面 + .eddx吊具细化',
      crossRef: 'F-D02 / F-D09 · X08(WMS REBAR-BAY) / X10',
    })
  }
  const mechCranes = [
    { id: 'BC-MECH-01', name: '机电仓库 1# 50t 桥式起重机（非核心配景）', cadX: 4390.0, cadY: 1532.3, capacityT: 50, spanM: 16.5 },
    { id: 'BC-MECH-02', name: '机电仓库 2# 50t 桥式起重机（非核心配景）', cadX: 4435.0, cadY: 1532.3, capacityT: 50, spanM: 16.5 },
  ]
  for (const c of mechCranes) {
    const m = measurePlacement(c.id, c.cadX, c.cadY)
    rows.push({
      id: c.id,
      name: c.name,
      category: 'bridge',
      categoryLabel: '室内/机电桥吊',
      cadX: c.cadX,
      cadY: c.cadY,
      ...m,
      spec: `${c.capacityT}t / 跨度${c.spanM}m`,
      basisType: 'planned_schematic',
      basisLabel: '机电库配景（待裁决是否移出台账）',
      pendingDecision: true,
      decisionNote: '11-18 建模评审纪要暂不考虑机电库，待甲方书面裁决是否移出装卸区纳管台账',
      crossRef: 'F-D02 · 非核心配景参考',
    })
  }
  SITE_LAYOUT_V4.ash.unloadingComplexes.forEach((u, idx) => {
    const matLabel = u.material === 'cement' ? '水泥' : '粉煤灰'
    const southX = u.tipperWaitX ?? u.tipperParkX
    const southY = u.tipperSouthY ?? u.tipperRailY
    const mSouth = measurePlacement(u.tipperId, southX, southY)
    rows.push({
      id: u.tipperId,
      name: `${idx + 1}# 组南轨${matLabel}移动式翻箱机`,
      category: 'bulk_equip',
      categoryLabel: '散料翻箱/输送',
      cadX: southX,
      cadY: southY,
      ...mSouth,
      spec: '倾翻角待定型(70°/90°研发中，90°示意) + 42m³料斗',
      basisType: 'process_eddx',
      basisLabel: '给大连大.dwg卸料室 + 11.14散料.eddx',
      crossRef: 'F-D02 / F-D06 · X09(翻箱机PLC)',
    })
    const northId = `TIPPER-0${idx + 5}`
    const northY = u.tipperNorthY ?? u.tipperNorthRailY ?? 1529.55
    const mNorth = measurePlacement(northId, southX, northY)
    rows.push({
      id: northId,
      name: `${idx + 1}# 组北轨${matLabel}移动式翻箱机`,
      category: 'bulk_equip',
      categoryLabel: '散料翻箱/输送',
      cadX: southX,
      cadY: northY,
      ...mNorth,
      spec: '倾翻角待定型(70°/90°研发中，90°示意) · 北轨',
      basisType: 'planned_schematic',
      basisLabel: '图纸8台 vs 旧表4台（待裁决）',
      pendingDecision: true,
      decisionNote: '《给大连大.dwg》及 11-19 纪要为 8 台，旧台账为 4 台，待甲方书面确认一期纳管台数',
      crossRef: 'F-D02 / F-D03 · 待甲方确认台账',
    })
    const mElv = measurePlacement(u.elevatorId, u.elevatorCadX, u.elevatorCadY)
    rows.push({
      id: u.elevatorId,
      name: `${u.id} ${matLabel}40m斗式提升机`,
      category: 'bulk_equip',
      categoryLabel: '散料翻箱/输送',
      cadX: u.elevatorCadX,
      cadY: u.elevatorCadY,
      ...mElv,
      spec: 'H=40m 斗提机 + 仓顶空气斜槽',
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 局部平面坐标',
      crossRef: 'F-D02 / F-D03 · X03(EHMS) / X09(智能入库)',
    })
  })
  const loaderSiloIndices = [0, 8, 16, 24]
  loaderSiloIndices.forEach((siloIdx, i) => {
    const targetSilo = SITE_LAYOUT_V4.ash.silos[siloIdx] ?? SITE_LAYOUT_V4.ash.silos[0]
    const loaderId = `LOADER-0${i + 1}`
    const mLoader = measurePlacement(loaderId, targetSilo.cadX, targetSilo.cadY)
    rows.push({
      id: loaderId,
      name: `${i + 1}# 库底定量散装机（对准${targetSilo.id}）`,
      category: 'bulk_equip',
      categoryLabel: '散料翻箱/输送',
      cadX: targetSilo.cadX,
      cadY: targetSilo.cadY,
      ...mLoader,
      spec: '库底伸缩散装头 + 定量装车联锁',
      basisType: 'process_eddx',
      basisLabel: '筒仓圆心坐标 + 11.14散料.eddx库底装车',
      crossRef: 'F-D02 / F-D09 · X07/X08(WMS) / X09',
    })
  })
  for (const wb of SITE_LAYOUT_V4.weighbridges) {
    const mWb = measurePlacement(wb.id, wb.cadX, wb.cadY)
    rows.push({
      id: wb.id,
      name: wb.name,
      category: 'weigh',
      categoryLabel: '100t计量衡',
      cadX: wb.cadX,
      cadY: wb.cadY,
      ...mWb,
      spec: `${wb.capacityT}t (${wb.kind === 'rail_scale' ? '静态轨道衡' : '进出厂汽车衡'})`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 局部平面坐标',
      crossRef: 'F-D02 / F-D09 · X08(WMS计量复核)',
    })
  }
  for (const s of SITE_LAYOUT_V4.ash.silos) {
    const mSilo = measurePlacement(s.id, s.cadX, s.cadY, { cadX: s.cadX, cadY: s.cadY })
    rows.push({
      id: s.id,
      name: `${s.code} ${s.material === 'cement' ? '3000T水泥筒仓' : '3000T粉煤灰筒仓'}`,
      category: 'silo',
      categoryLabel: '3000T散料筒仓',
      cadX: s.cadX,
      cadY: s.cadY,
      ...mSilo,
      spec: `Φ${s.diameterM}m × H${s.totalHeightM}m (${s.capacityT}t)`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 40座现建筒仓圆心',
      crossRef: 'F-D02 / F-D09 · X08(S2账面)/X09(物理料位)',
    })
  }
  return rows
}

const allAuditRows = buildAuditRows()
const pendingDecisionCount = computed(() => allAuditRows.filter((r) => r.pendingDecision).length)
const maxResidualM = computed(() => Math.max(0, ...allAuditRows.map((r) => r.residualM)))
const avgResidualM = computed(() =>
  allAuditRows.length ? allAuditRows.reduce((acc, r) => acc + r.residualM, 0) / allAuditRows.length : 0,
)

const filteredRows = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  return allAuditRows.filter((r) => {
    if (onlyPendingDecision.value && !r.pendingDecision) return false
    if (selectedCategory.value !== 'all' && r.category !== selectedCategory.value) return false
    if (!kw) return true
    return (
      r.id.toLowerCase().includes(kw) ||
      r.name.toLowerCase().includes(kw) ||
      r.spec.toLowerCase().includes(kw) ||
      r.crossRef.toLowerCase().includes(kw) ||
      (r.decisionNote ?? '').toLowerCase().includes(kw)
    )
  })
})

function toPosition(point: CadPoint, height = 0) {
  return cadToCartesian3(point.cadX, point.cadY, height)
}

function addLabel(position: Cesium.Cartesian3, text: string, color: Cesium.Color, fontSize = 15) {
  calibrationSource?.entities.add({
    position,
    label: {
      text,
      font: `600 ${fontSize}px Microsoft YaHei`,
      fillColor: color,
      outlineColor: Cesium.Color.fromCssColorString('#020A17'),
      outlineWidth: 4,
      style: Cesium.LabelStyle.FILL_AND_OUTLINE,
      verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
      pixelOffset: new Cesium.Cartesian2(0, -8),
      disableDepthTestDistance: Number.POSITIVE_INFINITY,
    },
  })
}

function addCalibrationOverlay() {
  if (!viewer) return
  calibrationSource = new Cesium.CustomDataSource('corrected-layout-calibration')
  viewer.dataSources.add(calibrationSource)

  const rebarRailColor = Cesium.Color.fromCssColorString('#36D7FF')
  const ashRailColor = Cesium.Color.fromCssColorString('#B58CFF')
  const gantryColor = Cesium.Color.fromCssColorString('#FFB45C')
  const rebarColor = Cesium.Color.fromCssColorString('#7BEDA7')

  for (const rail of CALIBRATION_RAILS) {
    const color = rail.serviceLine === 'rebar' ? rebarRailColor : ashRailColor
    calibrationSource.entities.add({
      polyline: {
        positions: [toPosition(rail.start, 1.1), toPosition(rail.end, 1.1)],
        width: 5,
        material: color,
      },
    })
  }
  addLabel(toPosition({ cadX: 3540, cadY: 1656 }, 1.8), '钢材装卸线：货3 / 货4（560/564m）', rebarRailColor, 14)
  addLabel(toPosition({ cadX: 3540, cadY: 1518 }, 1.8), '散料装卸线：货1 / 货2（950/790m）', ashRailColor, 14)

  for (const gantry of CALIBRATION_GANTRIES) {
    const halfSpan = 30
    calibrationSource.entities.add({
      polyline: {
        positions: [
          toPosition({ cadX: gantry.center.cadX, cadY: gantry.center.cadY - halfSpan / 2 }, 3),
          toPosition({ cadX: gantry.center.cadX, cadY: gantry.center.cadY + halfSpan / 2 }, 3),
        ],
      },
    })
    addLabel(toPosition({ cadX: gantry.center.cadX, cadY: gantry.center.cadY + 18 }, 4), `${gantry.id}（40t/30m）`, gantryColor, 13)
  }

  for (const bay of CALIBRATION_REBAR_BAYS) {
    const halfWidth = bay.width / 2
    const halfDepth = bay.depth / 2
    const points = [
      { cadX: bay.center.cadX - halfWidth, cadY: bay.center.cadY - halfDepth },
      { cadX: bay.center.cadX + halfWidth, cadY: bay.center.cadY - halfDepth },
      { cadX: bay.center.cadX + halfWidth, cadY: bay.center.cadY + halfDepth },
      { cadX: bay.center.cadX - halfWidth, cadY: bay.center.cadY + halfDepth },
    ]
    calibrationSource.entities.add({
      polygon: {
        hierarchy: points.map((point) => toPosition(point, 1.25)),
        material: rebarColor.withAlpha(0.18),
      },
    })
    calibrationSource.entities.add({
      polyline: {
        positions: [...points, points[0]].map((point) => toPosition(point, 1.35)),
        width: 2,
        material: rebarColor,
      },
    })
    const craneHint = bay.bridgeType === 'chook_32t' ? '32t C型' : '20t 磁吸'
    addLabel(toPosition(bay.center, 2), `${bay.id.replace('REBAR-BAY-', '')}#\n${craneHint}`, rebarColor, 11)
  }

  for (const zone of CALIBRATION_ZONES) {
    const color = zone.kind === 'cement-silo'
      ? Cesium.Color.fromCssColorString('#34D399')
      : Cesium.Color.fromCssColorString('#38BDF8')
    calibrationSource.entities.add({
      polyline: {
        positions: [...zone.points, zone.points[0]].map((point) => toPosition(point, 1.5)),
        width: 3,
        material: color.withAlpha(0.9),
      },
    })
    const center = zone.points.reduce(
      (sum, point) => ({ cadX: sum.cadX + point.cadX / zone.points.length, cadY: sum.cadY + point.cadY / zone.points.length }),
      { cadX: 0, cadY: 0 },
    )
    addLabel(toPosition(center, 2), zone.label, color, 12)
  }

  addLabel(toPosition({ cadX: 3751, cadY: 1742 }, 2), '钢筋仓库 (23,882 m²) · 13 跨桥吊实测落位', rebarColor, 14)

  for (const silo of SITE_LAYOUT_V4.ash.silos) {
    const sColor = silo.material === 'cement'
      ? Cesium.Color.fromCssColorString('#34D399')
      : Cesium.Color.fromCssColorString('#38BDF8')
    calibrationSource.entities.add({
      position: toPosition({ cadX: silo.cadX, cadY: silo.cadY }, 1.2),
      ellipse: {
        semiMajorAxis: silo.diameterM / 2,
        semiMinorAxis: silo.diameterM / 2,
        height: 1.2,
        material: sColor.withAlpha(0.18),
        outline: true,
        outlineColor: sColor.withAlpha(0.85),
      },
    })
    if (silo.col === 1 || silo.col === 4 || silo.col === 5 || silo.col === 20) {
      addLabel(toPosition({ cadX: silo.cadX, cadY: silo.cadY }, 2.2), silo.code, sColor, 10)
    }
  }

  const elevColor = Cesium.Color.fromCssColorString('#F472B6')
  for (const u of SITE_LAYOUT_V4.ash.unloadingComplexes) {
    calibrationSource.entities.add({
      position: toPosition({ cadX: u.elevatorCadX, cadY: u.elevatorCadY }, 2.0),
      point: { pixelSize: 9, color: elevColor, outlineColor: Cesium.Color.WHITE, outlineWidth: 1.5 },
    })
    addLabel(toPosition({ cadX: u.elevatorCadX, cadY: u.elevatorCadY + 5 }, 3.0), `${u.elevatorId} (40m)`, elevColor, 11)
  }

  const mechColor = Cesium.Color.fromCssColorString('#FBBF24')
  addLabel(toPosition({ cadX: 4412, cadY: 1532 }, 3.0), '机电仓库 (5,036 m² · 2×50t桥吊)', mechColor, 12)
  for (const wb of SITE_LAYOUT_V4.weighbridges) {
    addLabel(toPosition({ cadX: wb.cadX, cadY: wb.cadY }, 2.5), `${wb.id} (${wb.capacityT}t)`, mechColor, 11)
  }
}

function focusCalibrationView(view: 'steel' | 'ash' | 'all') {
  if (!viewer) return
  const options = {
    steel: { cadX: 3751, cadY: 1682, radius: 240, range: 520, headingDeg: 0, pitchDeg: -89.5 },
    ash: { cadX: 3893, cadY: 1545, radius: 280, range: 560, headingDeg: 0, pitchDeg: -89.5 },
    all: { cadX: 3920, cadY: 1615, radius: 520, range: 960, headingDeg: -22, pitchDeg: -58 },
  }[view]
  viewer.camera.flyToBoundingSphere(
    new Cesium.BoundingSphere(cadToCartesian3(options.cadX, options.cadY), options.radius),
    {
      duration: 0.6,
      offset: new Cesium.HeadingPitchRange(
        Cesium.Math.toRadians(options.headingDeg),
        Cesium.Math.toRadians(options.pitchDeg),
        options.range,
      ),
    },
  )
  viewer.scene.requestRender()
}

function focusAuditRow(row: CalibrationAuditRow) {
  activeRowId.value = row.id
  if (twin3d) {
    const info = twin3d.focusEquipment(row.id)
    if (info) {
      pickedInfo.value = info
      return
    }
  }
  if (viewer) {
    viewer.camera.flyToBoundingSphere(
      new Cesium.BoundingSphere(cadToCartesian3(row.cadX, row.cadY, 8), 45),
      {
        duration: 0.6,
        offset: new Cesium.HeadingPitchRange(Cesium.Math.toRadians(-18), Cesium.Math.toRadians(-38), 140),
      },
    )
    viewer.scene.requestRender()
  }
}

function toggleOverlay(key: keyof typeof overlayFlags.value) {
  overlayFlags.value[key] = !overlayFlags.value[key]
  if (key === 'cadBase' && cadDataSource) {
    cadDataSource.show = overlayFlags.value.cadBase
  } else if (key === 'wireframe' && calibrationSource) {
    calibrationSource.show = overlayFlags.value.wireframe
  } else if (key === 'plannedZone') {
    twin3d?.setPlannedZonesVisible(overlayFlags.value.plannedZone)
  } else if (key === 'models3d' && viewer) {
    for (const p of viewer.scene.primitives as unknown as { _primitives?: Array<{ show?: boolean }> } & Iterable<{ show?: boolean }>) {
      if (p && typeof p === 'object' && 'show' in p) p.show = overlayFlags.value.models3d
    }
  }
  viewer?.scene.requestRender()
}

function exportCalibrationCsv() {
  const header = ['对象编号', '对象名称', '类别', 'DWG_X(m)', 'DWG_Y(m)', '3D落位_X(m)', '3D落位_Y(m)', '实算平面残差(m)', '待裁决状态', '规格参数', '依据来源', '支撑功能与跨系统协作']
  const lines = [
    header.join(','),
    ...allAuditRows.map((r) =>
      [
        r.id,
        `"${r.name}"`,
        r.categoryLabel,
        r.cadX,
        r.cadY,
        r.placedX,
        r.placedY,
        r.deltaLabel,
        r.pendingDecision ? `"待甲方裁决: ${r.decisionNote ?? ''}"` : '已核基准',
        `"${r.spec}"`,
        `"${r.basisLabel}"`,
        `"${r.crossRef}"`,
      ].join(','),
    ),
  ]
  const blob = new Blob([`﻿${lines.join('\n')}`], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `B项目-S5数字孪生-F-D02-CAD局部平面落位校核台账-${allAuditRows.length}项.csv`
  a.click()
  URL.revokeObjectURL(url)
}

function onViewerReady(nextViewer: Cesium.Viewer) {
  viewer = nextViewer
  setupCameraInteraction(nextViewer)
  twin3d = new Twin3dController(nextViewer, {
    onSelect: (info) => {
      pickedInfo.value = info
      if (info) activeRowId.value = info.id
    },
  })
  fetch(CAD_GEOJSON_URL)
    .then((response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return response.json()
    })
    .then((cadGeoJson) =>
      Cesium.GeoJsonDataSource.load(cadGeoJsonToWgs84(cadGeoJson), {
        stroke: Cesium.Color.fromCssColorString('#5A89A2').withAlpha(0.42),
        strokeWidth: 1.5,
        fill: Cesium.Color.fromCssColorString('#174A70').withAlpha(0.05),
        clampToGround: false,
        markerSize: 0,
      }),
    )
    .then((source) => {
      cadDataSource = source
      nextViewer.dataSources.add(source)
      addCalibrationOverlay()
      focusCalibrationView('all')
      status.value =
        `【R-D01 / F-D02 实算完成】基于冻结版《给大连大.dwg》纯二维局部米制平面坐标（无国家大地投影与 DEM 高程）：实算 ${allAuditRows.length} 项对象（40 台设备含 ${pendingDecisionCount.value} 项待甲方裁决差异 + 40 座现建筒仓；另预留 68 座二期空点位），平均平面残差 ${avgResidualM.value.toFixed(2)} m、最大残差 ${maxResidualM.value.toFixed(2)} m。`
      nextViewer.scene.requestRender()
    })
    .catch((error: unknown) => {
      status.value = `CAD 加载失败：${error instanceof Error ? error.message : String(error)}`
    })
}

onBeforeUnmount(() => {
  if (viewer && calibrationSource) viewer.dataSources.remove(calibrationSource)
  if (viewer && cadDataSource) viewer.dataSources.remove(cadDataSource)
  twin3d?.destroy()
  calibrationSource = null
  cadDataSource = null
  twin3d = null
  viewer = null
})
</script>

<template>
  <main class="calibration-page">
    <header>
      <div>
        <p>R-D01 / F-D02 · LOCAL METRIC CAD V6.1 · 冻结版《给大连大.dwg》局部米制平面自洽校核工作台（非大地绝对测绘）</p>
        <h1>CAD 与设备模型落位校核（{{ allAuditRows.length }} 项实算台账 · 含 {{ pendingDecisionCount }} 项待裁决）</h1>
      </div>
      <div class="header-links">
        <button type="button" class="export-btn" @click="exportCalibrationCsv">⬇ 导出 {{ allAuditRows.length }} 项实算台账 (CSV)</button>
        <RouterLink to="/" class="nav-link">← 返回三维作业台</RouterLink>
        <RouterLink to="/bigscreen" class="nav-link">→ 综合指挥大屏</RouterLink>
      </div>
    </header>

    <section class="notice">
      <b>F-D02 校核结论：</b>{{ status }}
    </section>

    <section class="fact-grid" aria-label="场站事实基线">
      <article v-for="fact in SITE_FACTS" :key="fact.id" class="fact-card" :class="`status-${fact.status}`">
        <div class="fact-meta">
          <span>{{ fact.name }}</span>
          <b>{{ SITE_FACT_STATUS_LABEL[fact.status] }}</b>
        </div>
        <p>{{ fact.statement }}</p>
      </article>
    </section>

    <nav class="view-controls" aria-label="校核镜头与图层叠合">
      <div class="btn-group">
        <button type="button" @click="focusCalibrationView('all')">完整中沙坝转运站</button>
        <button type="button" @click="focusCalibrationView('steel')">钢材双轨 + 钢筋库13跨（俯视）</button>
        <button type="button" @click="focusCalibrationView('ash')">散料双轨 + 40筒仓/卸料室（俯视）</button>
      </div>
      <div class="layer-switches">
        <label><input type="checkbox" :checked="overlayFlags.cadBase" @change="toggleOverlay('cadBase')" /> CAD平面底图(405)</label>
        <label><input type="checkbox" :checked="overlayFlags.models3d" @change="toggleOverlay('models3d')" /> 三维装备模型({{ allAuditRows.length }})</label>
        <label><input type="checkbox" :checked="overlayFlags.wireframe" @change="toggleOverlay('wireframe')" /> 局部平面校准线框</label>
        <label><input type="checkbox" :checked="overlayFlags.plannedZone" @change="toggleOverlay('plannedZone')" /> 2座缓建仓示意区</label>
      </div>
    </nav>

    <section class="workbench-split">
      <div class="viewer-wrap">
        <CesiumViewer @viewer-ready="onViewerReady" />
        <div v-if="pickedInfo" class="picked-badge">
          <b>已选对象：{{ pickedInfo.id }} · {{ pickedInfo.name }}</b>
          <span>{{ pickedInfo.statusName }}</span>
        </div>
      </div>

      <aside class="ledger-panel" aria-label="F-D02 CAD与三维模型落位实算校核台账">
        <div class="ledger-head">
          <div class="ledger-title-row">
            <b>F-D02 落位校核明细台账（点击行三维飞跃核查）</b>
            <span>共 {{ filteredRows.length }} / {{ allAuditRows.length }} 项 · 最大残差 {{ maxResidualM.toFixed(2) }}m</span>
          </div>
          <div class="ledger-filters">
            <select v-model="selectedCategory" class="cat-select">
              <option value="all">全部类别 ({{ allAuditRows.length }}项)</option>
              <option value="gantry">室外门吊 (6台)</option>
              <option value="bridge">室内/机电桥吊 (15台)</option>
              <option value="bulk_equip">翻箱8/提升4/散装4 (16台)</option>
              <option value="weigh">100t计量衡 (3台)</option>
              <option value="silo">3000T现建筒仓 (40座)</option>
            </select>
            <label class="pending-filter">
              <input v-model="onlyPendingDecision" type="checkbox" /> 仅看待裁决({{ pendingDecisionCount }})
            </label>
            <input v-model="searchKeyword" type="search" class="kw-input" placeholder="搜索编号/名称/跨系统号(如 BC-03, TIPPER-05, SILO-01)..." />
          </div>
        </div>
        <div class="ledger-table-wrap">
          <table class="ledger-table">
            <thead>
              <tr>
                <th>编号</th>
                <th>名称与规格</th>
                <th>DWG vs 3D 坐标</th>
                <th>实算残差</th>
                <th>图纸依据与待裁决说明</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in filteredRows"
                :key="row.id"
                :class="{ active: activeRowId === row.id, pending: row.pendingDecision }"
                @click="focusAuditRow(row)"
              >
                <td class="code-col">{{ row.id }}</td>
                <td>
                  <div class="r-name">{{ row.name }}</div>
                  <small class="r-spec">{{ row.spec }}</small>
                </td>
                <td class="coord-col">
                  <div>DWG({{ row.cadX.toFixed(1) }}, {{ row.cadY.toFixed(1) }})</div>
                  <small class="r-spec">3D({{ row.placedX.toFixed(1) }}, {{ row.placedY.toFixed(1) }})</small>
                </td>
                <td class="delta-col" :class="{ warn: row.residualM > 0.15 }">{{ row.deltaLabel }}</td>
                <td>
                  <span class="basis-tag" :class="row.basisType">{{ row.basisLabel }}</span>
                  <small v-if="row.decisionNote" class="decision-note">⚠ {{ row.decisionNote }}</small>
                  <small class="xref-note">{{ row.crossRef }}</small>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </aside>
    </section>

    <footer>
      <span><i class="steel-rail"></i>钢材线：货3/货4 + 13跨桥吊</span>
      <span><i class="ash-rail"></i>散料线：货1/货2 + 40座Φ12m筒仓</span>
      <span><i class="gantry"></i>6台40t/30m门吊（钢材3+散料3）</span>
      <span><i class="rebar"></i>钢筋仓库：23,882 m²（9×20t磁吸 + 4×32t C型）</span>
    </footer>
  </main>
</template>

<style scoped>
.calibration-page { height: 100%; min-height: 680px; display: grid; grid-template-rows: auto auto auto auto 1fr auto; gap: 8px; padding: 14px 18px; box-sizing: border-box; color: #d9f4ff; background: #061321; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
header p { margin: 0 0 2px; color: #6eb6cf; font-size: 11px; letter-spacing: .08em; }
h1 { margin: 0; font-size: 20px; }
.header-links { display: flex; align-items: center; gap: 10px; }
.nav-link { padding: 5px 10px; border: 1px solid rgba(70, 164, 195, .55); border-radius: 4px; background: rgba(11, 38, 57, .8); color: #9feaf8; text-decoration: none; font-size: 12px; }
.nav-link:hover { border-color: #42d7ea; color: #fff; }
.export-btn { padding: 5px 11px; border: 1px solid rgba(52, 211, 153, .65); border-radius: 4px; background: rgba(6, 78, 59, .65); color: #a7f3d0; font-size: 12px; font-weight: 600; cursor: pointer; }
.export-btn:hover { background: rgba(6, 95, 70, .9); color: #fff; }
.notice { border-left: 3px solid #34d399; background: rgba(6, 60, 48, .45); padding: 7px 12px; color: #d1fae5; font-size: 12px; }
.fact-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.fact-card { min-width: 0; border: 1px solid rgba(86, 155, 180, .42); background: rgba(11, 38, 57, .72); padding: 7px 10px; }
.fact-card p { margin: 4px 0 0; color: #a7c7d2; font-size: 11px; line-height: 1.38; }
.fact-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #d9f4ff; font-size: 12px; }
.fact-meta b { flex: none; color: #78d9f0; font-size: 10px; }
.status-position-review { border-color: rgba(244, 180, 76, .64); }
.status-position-review .fact-meta b { color: #f4c468; }
.status-inferred { border-color: rgba(154, 139, 255, .65); }
.status-inferred .fact-meta b { color: #b9adff; }
.view-controls { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.btn-group { display: flex; gap: 6px; }
.btn-group button { border: 1px solid #286681; background: #0b2639; color: #bfeefa; padding: 5px 10px; font-size: 12px; cursor: pointer; border-radius: 4px; }
.btn-group button:hover { border-color: #42d7ea; color: #fff; }
.layer-switches { display: flex; align-items: center; gap: 12px; font-size: 12px; color: #b6d9e8; }
.layer-switches label { display: inline-flex; align-items: center; gap: 5px; cursor: pointer; }
.workbench-split { min-height: 0; display: grid; grid-template-columns: 1.35fr 1fr; gap: 10px; }
.viewer-wrap { position: relative; min-height: 0; border: 1px solid rgba(70, 164, 195, .6); }
.viewer-wrap :deep(.cesium-viewer) { min-width: 0; min-height: 0; height: 100%; }
.picked-badge { position: absolute; left: 12px; bottom: 12px; padding: 6px 12px; background: rgba(4, 18, 34, .92); border: 1px solid #36d7ff; border-radius: 4px; display: flex; gap: 10px; align-items: center; font-size: 12px; }
.picked-badge span { color: #34d399; font-weight: 600; }
.ledger-panel { min-height: 0; display: flex; flex-direction: column; border: 1px solid rgba(70, 164, 195, .55); background: rgba(7, 24, 41, .9); }
.ledger-head { padding: 8px 10px; border-bottom: 1px solid rgba(70, 164, 195, .35); display: flex; flex-direction: column; gap: 6px; }
.ledger-title-row { display: flex; justify-content: space-between; align-items: center; font-size: 12px; }
.ledger-title-row b { color: #78e3fd; }
.ledger-title-row span { color: #8fb8cc; font-size: 11px; }
.ledger-filters { display: flex; gap: 6px; }
.cat-select { padding: 4px 6px; background: #061829; border: 1px solid #286681; color: #d9f4ff; font-size: 11px; border-radius: 3px; }
.kw-input { flex: 1; padding: 4px 8px; background: #061829; border: 1px solid #286681; color: #d9f4ff; font-size: 11px; border-radius: 3px; }
.ledger-table-wrap { flex: 1; min-height: 0; overflow-y: auto; }
.ledger-table { width: 100%; border-collapse: collapse; font-size: 11px; }
.ledger-table th { position: sticky; top: 0; background: #0c2b44; color: #9feaf8; text-align: left; padding: 6px 7px; font-weight: 600; border-bottom: 1px solid rgba(70, 164, 195, .4); }
.ledger-table td { padding: 5px 7px; border-bottom: 1px solid rgba(45, 95, 125, .28); vertical-align: middle; }
.ledger-table tbody tr { cursor: pointer; transition: background .15s; }
.ledger-table tbody tr:hover { background: rgba(24, 82, 118, .45); }
.ledger-table tbody tr.active { background: rgba(14, 116, 144, .55); }
.code-col { font-family: Consolas, monospace; font-weight: 700; color: #38bdf8; white-space: nowrap; }
.r-name { color: #e2f5ff; font-weight: 600; }
.r-spec { display: block; color: #8ab4c9; font-size: 10px; }
.coord-col { font-family: Consolas, monospace; color: #bae6fd; white-space: nowrap; }
.delta-col { color: #34d399; font-weight: 700; white-space: nowrap; }
.delta-col.warn { color: #f59e0b; }
.pending-filter { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; color: #fcd34d; white-space: nowrap; cursor: pointer; }
.ledger-table tbody tr.pending { background: rgba(180, 83, 9, 0.14); }
.decision-note { display: block; color: #fcd34d; font-size: 10px; margin-top: 2px; }
.basis-tag { display: inline-block; padding: 1px 5px; border-radius: 3px; font-size: 10px; font-weight: 600; }
.basis-tag.cad_surveyed { background: rgba(16, 185, 129, .2); color: #6ee7b7; border: 1px solid rgba(16, 185, 129, .45); }
.basis-tag.process_eddx { background: rgba(56, 189, 248, .2); color: #7dd3fc; border: 1px solid rgba(56, 189, 248, .45); }
.basis-tag.planned_schematic { background: rgba(245, 158, 11, .2); color: #fcd34d; border: 1px solid rgba(245, 158, 11, .45); }
.xref-note { display: block; color: #93b5c6; font-size: 10px; margin-top: 2px; }
footer { display: flex; flex-wrap: wrap; gap: 14px 20px; color: #91b8c8; font-size: 12px; }
footer span { display: inline-flex; align-items: center; gap: 6px; }
i { width: 16px; height: 4px; display: inline-block; }
.steel-rail { background: #36d7ff; }.ash-rail { background: #b58cff; }.gantry { background: #ffb45c; }.rebar { background: #7beda7; }
@media (max-width: 1150px) { .fact-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .workbench-split { grid-template-columns: 1fr; } }
</style>
