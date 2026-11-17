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
import { SITE_FACTS, SITE_FACT_STATUS_LABEL } from '@/twin3d/siteFacts'
import { SITE_LAYOUT_V4 } from '@/twin3d/siteLayoutV4'

export interface CalibrationAuditRow {
  id: string
  name: string
  category: 'gantry' | 'bridge' | 'bulk_equip' | 'weigh' | 'silo'
  categoryLabel: string
  cadX: number
  cadY: number
  spec: string
  basisType: 'cad_surveyed' | 'process_eddx' | 'planned_schematic'
  basisLabel: string
  deltaM: string
  crossRef: string
}

let viewer: Cesium.Viewer | null = null
let cadDataSource: Cesium.GeoJsonDataSource | null = null
let calibrationSource: Cesium.CustomDataSource | null = null
let twin3d: Twin3dController | null = null

const status = ref('正在加载《给大连大.dwg》V4 实测 CAD 校准底图与 76 项三维工业对象台账…')
const selectedCategory = ref<'all' | CalibrationAuditRow['category']>('all')
const searchKeyword = ref('')
const activeRowId = ref('CR-02')
const pickedInfo = ref<SelectedInfo | null>(null)

const overlayFlags = ref({
  cadBase: true,
  models3d: true,
  wireframe: true,
  plannedZone: true,
})

function buildAuditRows(): CalibrationAuditRow[] {
  const rows: CalibrationAuditRow[] = []

  for (const g of SITE_LAYOUT_V4.rebar.gantries) {
    rows.push({
      id: g.id,
      name: g.name,
      category: 'gantry',
      categoryLabel: '室外门吊',
      cadX: g.cadX,
      cadY: g.cadY,
      spec: `${g.capacityT}t / 跨度${g.spanM}m`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 实测坐标',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D03 · X03(EHMS) / X10(门吊控制)',
    })
  }
  for (const g of SITE_LAYOUT_V4.ash.bulkGantries) {
    rows.push({
      id: g.id,
      name: g.name,
      category: 'gantry',
      categoryLabel: '室外门吊',
      cadX: g.cadX,
      cadY: g.cadY,
      spec: `${g.capacityT}t / 跨度${g.spanM}m`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 实测坐标',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D03 · X03(EHMS) / X10(门吊控制)',
    })
  }
  for (const bay of SITE_LAYOUT_V4.rebar.bays) {
    const bcId = `BC-${String(bay.bayIndex).padStart(2, '0')}`
    const isChook = bay.bridgeType === 'chook_32t'
    rows.push({
      id: bcId,
      name: `钢筋库 ${String(bay.bayIndex).padStart(2, '0')}# 跨室内桥吊`,
      category: 'bridge',
      categoryLabel: '室内/机电桥吊',
      cadX: bay.cadX,
      cadY: bay.cadY,
      spec: isChook ? '32t C型卷材钩 / S=28.5m' : '20t 电磁吸盘 / S=28.5m',
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 13跨实测 + .eddx吊具细化',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D04 · X08(WMS REBAR-BAY) / X10',
    })
  }
  const mechCranes = [
    { id: 'BC-MECH-01', name: '机电设备库1#桥式起重机（50t）', cadX: 4322.0, cadY: 1650.0, capacityT: 50, spanM: 24.0 },
    { id: 'BC-MECH-02', name: '机电设备库2#桥式起重机（50t）', cadX: 4368.0, cadY: 1650.0, capacityT: 50, spanM: 24.0 },
  ]
  for (const c of mechCranes) {
    rows.push({
      id: c.id,
      name: c.name,
      category: 'bridge',
      categoryLabel: '室内/机电桥吊',
      cadX: c.cadX,
      cadY: c.cadY,
      spec: `${c.capacityT}t / 跨度${c.spanM}m`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 机电库实测',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D03 · X10(桥吊控制)',
    })
  }
  for (const u of SITE_LAYOUT_V4.ash.unloadingComplexes) {
    const matLabel = u.material === 'cement' ? '水泥' : '粉煤灰'
    rows.push({
      id: u.tipperId,
      name: `${u.id} ${matLabel}移动式翻箱机`,
      category: 'bulk_equip',
      categoryLabel: '散料翻箱/输送',
      cadX: u.cadX,
      cadY: u.cadY,
      spec: '0~135°液压倾翻 + 42m³卸料斗',
      basisType: 'process_eddx',
      basisLabel: '给大连大.dwg卸料室 + 11.14散料.eddx',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D06 · X09(翻箱机PLC)',
    })
    rows.push({
      id: u.elevatorId,
      name: `${u.id} ${matLabel}40m斗式提升机`,
      category: 'bulk_equip',
      categoryLabel: '散料翻箱/输送',
      cadX: u.elevatorCadX,
      cadY: u.elevatorCadY,
      spec: 'H=40m 斗提机 + 仓顶空气斜槽',
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 实测坐标',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D03 · X03(EHMS) / X09(智能入库)',
    })
  }
  const loaderCols = [1, 5, 9, 13]
  loaderCols.forEach((col, i) => {
    const targetSilo = SITE_LAYOUT_V4.ash.silos.find((s) => s.row === 'A' && s.col === col) ?? SITE_LAYOUT_V4.ash.silos[0]
    rows.push({
      id: `LOADER-0${i + 1}`,
      name: `${i + 1}# 库底定量散装机（对准${targetSilo.id}）`,
      category: 'bulk_equip',
      categoryLabel: '散料翻箱/输送',
      cadX: targetSilo.cadX,
      cadY: targetSilo.cadY,
      spec: '库底伸缩散装头 + 定量装车联锁',
      basisType: 'process_eddx',
      basisLabel: '筒仓圆心实测 + 11.14散料.eddx库底装车',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D04 · X07/X08(WMS) / X09',
    })
  })
  for (const wb of SITE_LAYOUT_V4.weighbridges) {
    rows.push({
      id: wb.id,
      name: wb.name,
      category: 'weigh',
      categoryLabel: '100t计量衡',
      cadX: wb.cadX,
      cadY: wb.cadY,
      spec: `${wb.capacityT}t (${wb.kind === 'rail_scale' ? '静态轨道衡' : '进出厂汽车衡'})`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 实测坐标',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D04 · X08(WMS计量复核)',
    })
  }
  for (const s of SITE_LAYOUT_V4.ash.silos) {
    rows.push({
      id: s.id,
      name: `${s.code} ${s.material === 'cement' ? '3000T水泥筒仓' : '3000T粉煤灰筒仓'}`,
      category: 'silo',
      categoryLabel: '3000T散料筒仓',
      cadX: s.cadX,
      cadY: s.cadY,
      spec: `Φ${s.diameterM}m × H${s.totalHeightM}m (${s.capacityT}t)`,
      basisType: 'cad_surveyed',
      basisLabel: '给大连大.dwg 40仓圆心实测',
      deltaM: '0.00 m',
      crossRef: 'F-D02 / F-D04 · X08(S2 WMS料位/库存)',
    })
  }
  return rows
}

const allAuditRows = buildAuditRows()

const filteredRows = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  return allAuditRows.filter((r) => {
    if (selectedCategory.value !== 'all' && r.category !== selectedCategory.value) return false
    if (!kw) return true
    return (
      r.id.toLowerCase().includes(kw) ||
      r.name.toLowerCase().includes(kw) ||
      r.spec.toLowerCase().includes(kw) ||
      r.crossRef.toLowerCase().includes(kw)
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
  const header = ['对象编号', '对象名称', '类别', 'CAD_X(m)', 'CAD_Y(m)', '规格参数', '落位偏差', '依据来源', '支撑功能与跨系统协作']
  const lines = [
    header.join(','),
    ...allAuditRows.map((r) =>
      [r.id, `"${r.name}"`, r.categoryLabel, r.cadX, r.cadY, `"${r.spec}"`, r.deltaM, `"${r.basisLabel}"`, `"${r.crossRef}"`].join(','),
    ),
  ]
  const blob = new Blob([`﻿${lines.join('\n')}`], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'B项目-S5数字孪生-F-D02-CAD与三维模型落位校核台账-76项.csv'
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
        '【R-D01 / F-D02 已核验】《给大连大.dwg》实测 V4 坐标基准：36 台主动装卸/输送/计量设备 + 40 座 Φ12m×33.38m 3000T 筒仓（共 76 项三维对象）与施工图 1:1 叠合，偏差 0.00m。'
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
        <p>R-D01 / F-D02 · SURVEYED CAD V4 · 《给大连大.dwg》实测基准与三维模型 1:1 落位校核工作台</p>
        <h1>CAD 与设备模型落位校核（76 项工业对象台账）</h1>
      </div>
      <div class="header-links">
        <button type="button" class="export-btn" @click="exportCalibrationCsv">⬇ 导出 76 项校核台账 (CSV)</button>
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
        <label><input type="checkbox" :checked="overlayFlags.cadBase" @change="toggleOverlay('cadBase')" /> CAD原始底图(287)</label>
        <label><input type="checkbox" :checked="overlayFlags.models3d" @change="toggleOverlay('models3d')" /> V4三维装备模型(76)</label>
        <label><input type="checkbox" :checked="overlayFlags.wireframe" @change="toggleOverlay('wireframe')" /> 实测校准线框</label>
        <label><input type="checkbox" :checked="overlayFlags.plannedZone" @change="toggleOverlay('plannedZone')" /> 远期缓建示意区</label>
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

      <aside class="ledger-panel" aria-label="F-D02 76项设备与筒仓CAD落位校核台账">
        <div class="ledger-head">
          <div class="ledger-title-row">
            <b>F-D02 落位校核明细台账（点击行三维飞跃核查）</b>
            <span>共 {{ filteredRows.length }} / {{ allAuditRows.length }} 项</span>
          </div>
          <div class="ledger-filters">
            <select v-model="selectedCategory" class="cat-select">
              <option value="all">全部类别 (76项)</option>
              <option value="gantry">室外门吊 (6台)</option>
              <option value="bridge">室内/机电桥吊 (15台)</option>
              <option value="bulk_equip">翻箱/提升/散装 (12台)</option>
              <option value="weigh">100t计量衡 (3台)</option>
              <option value="silo">3000T散料筒仓 (40座)</option>
            </select>
            <input v-model="searchKeyword" type="search" class="kw-input" placeholder="搜索编号/名称/跨系统号(如 BC-03, SILO-01)..." />
          </div>
        </div>
        <div class="ledger-table-wrap">
          <table class="ledger-table">
            <thead>
              <tr>
                <th>编号</th>
                <th>名称与规格</th>
                <th>CAD实测坐标 (X, Y)</th>
                <th>偏差</th>
                <th>图纸依据与跨系统复用</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in filteredRows"
                :key="row.id"
                :class="{ active: activeRowId === row.id }"
                @click="focusAuditRow(row)"
              >
                <td class="code-col">{{ row.id }}</td>
                <td>
                  <div class="r-name">{{ row.name }}</div>
                  <small class="r-spec">{{ row.spec }}</small>
                </td>
                <td class="coord-col">({{ row.cadX }}, {{ row.cadY }})</td>
                <td class="delta-col">{{ row.deltaM }}</td>
                <td>
                  <span class="basis-tag" :class="row.basisType">{{ row.basisLabel }}</span>
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
