<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import * as Cesium from 'cesium'
import CesiumViewer from '@/components/cesium/CesiumViewer.vue'
import { setupCameraInteraction } from '@/components/cesium/cameraControl'
import { cadGeoJsonToWgs84, cadToCartesian3 } from '@/twin3d/geo'
import { CAD_GEOJSON_URL } from '@/config/runtime'
import {
  CALIBRATION_GANTRIES,
  CALIBRATION_RAILS,
  CALIBRATION_REBAR_BAYS,
  CALIBRATION_ZONES,
  type CadPoint,
} from '@/twin3d/siteCalibration'
import { SITE_FACTS, SITE_FACT_STATUS_LABEL } from '@/twin3d/siteFacts'

let viewer: Cesium.Viewer | null = null
let calibrationSource: Cesium.CustomDataSource | null = null
const status = ref('正在加载第二次校准图：钢材两轨与筒仓组下方的煤灰两轨…')

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
  addLabel(toPosition({ cadX: 3560, cadY: 1689.5 }, 1.8), '钢材作业线：2 条轨道', rebarRailColor, 14)
  addLabel(toPosition({ cadX: 3698, cadY: 1517 }, 1.8), '煤灰作业线：2 条轨道', ashRailColor, 14)

  for (const gantry of CALIBRATION_GANTRIES) {
    const halfSpan = 18
    calibrationSource.entities.add({
      polyline: {
        positions: [
          toPosition({ cadX: gantry.center.cadX, cadY: gantry.center.cadY - halfSpan / 2 }, 3),
          toPosition({ cadX: gantry.center.cadX, cadY: gantry.center.cadY + halfSpan / 2 }, 3),
        ],
        width: 7,
        material: new Cesium.PolylineDashMaterialProperty({ color: gantryColor, dashLength: 16 }),
      },
    })
    addLabel(toPosition({ cadX: gantry.center.cadX, cadY: 1691 }, 4), `${gantry.id}（候选）`, gantryColor, 13)
  }

  // This remains intentionally empty until the 13 individual warehouse bays
  // have been numbered against the plan; it prevents false precision.
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
    addLabel(toPosition(bay.center, 2), bay.id.replace('REBAR-BAY-', ''), rebarColor, 12)
  }

  for (const zone of CALIBRATION_ZONES) {
    const color = zone.kind === 'line-silo'
      ? Cesium.Color.fromCssColorString('#66E6A7')
      : Cesium.Color.fromCssColorString('#CC7DFF')
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
    addLabel(toPosition(center, 2), zone.label, color)
  }

  addLabel(toPosition({ cadX: 3740, cadY: 1720.5 }, 2), '13 个钢筋仓位：CAD 矩形编号', rebarColor, 14)
}

function focusCalibrationView(view: 'steel' | 'ash' | 'all') {
  if (!viewer) return
  const options = {
    steel: { cadX: 3740, cadY: 1685, radius: 260, range: 600, headingDeg: 0, pitchDeg: -89.5 },
    ash: { cadX: 3860, cadY: 1530, radius: 330, range: 650, headingDeg: 0, pitchDeg: -89.5 },
    all: { cadX: 3760, cadY: 1600, radius: 500, range: 940, headingDeg: -28, pitchDeg: -62 },
  }[view]
  viewer.camera.flyToBoundingSphere(
    new Cesium.BoundingSphere(cadToCartesian3(options.cadX, options.cadY), options.radius),
    {
      duration: 0,
      offset: new Cesium.HeadingPitchRange(
        Cesium.Math.toRadians(options.headingDeg),
        Cesium.Math.toRadians(options.pitchDeg),
        options.range,
      ),
    },
  )
  viewer.scene.requestRender()
}

function onViewerReady(nextViewer: Cesium.Viewer) {
  viewer = nextViewer
  setupCameraInteraction(nextViewer)
  fetch(CAD_GEOJSON_URL)
    .then((response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return response.json()
    })
    .then((cadGeoJson) => Cesium.GeoJsonDataSource.load(cadGeoJsonToWgs84(cadGeoJson), {
      stroke: Cesium.Color.fromCssColorString('#5A89A2').withAlpha(0.36),
      strokeWidth: 1.5,
      fill: Cesium.Color.fromCssColorString('#174A70').withAlpha(0.04),
      clampToGround: false,
      markerSize: 0,
    }))
    .then((source) => {
      nextViewer.dataSources.add(source)
      addCalibrationOverlay()
      focusCalibrationView('all')
      status.value = '第二次校准已应用：钢材两轨位于钢筋仓位一侧；煤灰两轨位于绿色/紫色筒仓组下方 B 线束。13 个钢筋仓位来自 CAD 重复矩形；三台龙门吊仅服务钢材线，纵向位置仍待最终确认。'
      nextViewer.scene.requestRender()
    })
    .catch((error: unknown) => {
      status.value = `CAD 加载失败：${error instanceof Error ? error.message : String(error)}`
    })
}

onBeforeUnmount(() => {
  if (viewer && calibrationSource) viewer.dataSources.remove(calibrationSource)
  calibrationSource = null
  viewer = null
})
</script>

<template>
  <main class="calibration-page">
    <header>
      <div>
        <p>FACT-CHECK MODE · 未替换正式模型</p>
        <h1>核心区 CAD 落位校核</h1>
      </div>
      <RouterLink to="/">返回当前作业台</RouterLink>
    </header>
    <section class="notice"><b>校核说明：</b>{{ status }}</section>
    <section class="fact-grid" aria-label="场站事实基线">
      <article v-for="fact in SITE_FACTS" :key="fact.id" class="fact-card" :class="`status-${fact.status}`">
        <div class="fact-meta"><span>{{ fact.name }}</span><b>{{ SITE_FACT_STATUS_LABEL[fact.status] }}</b></div>
        <p>{{ fact.statement }}</p>
      </article>
    </section>
    <nav class="view-controls" aria-label="校核镜头">
      <button @click="focusCalibrationView('steel')">钢材两轨与龙门吊（俯视）</button>
      <button @click="focusCalibrationView('ash')">煤灰两轨、筒仓与输送带（俯视）</button>
      <button @click="focusCalibrationView('all')">完整核心区</button>
    </nav>
    <section class="viewer-wrap"><CesiumViewer @viewer-ready="onViewerReady" /></section>
    <footer>
      <span><i class="steel-rail"></i>钢材/钢筋仓库前：2 条轨道</span>
      <span><i class="ash-rail"></i>煤灰筒仓前：2 条轨道</span>
      <span><i class="gantry"></i>3 台龙门吊：仅钢材线候选</span>
      <span><i class="rebar"></i>13 个钢筋仓位：CAD 矩形已编号</span>
    </footer>
  </main>
</template>

<style scoped>
.calibration-page { height: 100%; min-height: 680px; display: grid; grid-template-rows: auto auto auto auto 1fr auto; gap: 10px; padding: 18px; box-sizing: border-box; color: #d9f4ff; background: #061321; }
header { display: flex; align-items: center; justify-content: space-between; } header p { margin: 0 0 3px; color: #6eb6cf; font-size: 11px; letter-spacing: .12em; } h1 { margin: 0; font-size: 24px; } a { color: #9feaf8; text-decoration: none; }
.notice { border-left: 3px solid #f4b44c; background: rgba(77, 50, 10, .42); padding: 9px 12px; color: #e9d7a9; font-size: 13px; }
.fact-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }.fact-card { min-width: 0; border: 1px solid rgba(86, 155, 180, .42); background: rgba(11, 38, 57, .72); padding: 8px 10px; }.fact-card p { margin: 5px 0 0; color: #a7c7d2; font-size: 11px; line-height: 1.42; }.fact-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #d9f4ff; font-size: 12px; }.fact-meta b { flex: none; color: #78d9f0; font-size: 10px; }.status-position-review { border-color: rgba(244, 180, 76, .64); }.status-position-review .fact-meta b { color: #f4c468; }.status-inferred { border-color: rgba(154, 139, 255, .65); }.status-inferred .fact-meta b { color: #b9adff; }
.view-controls { display: flex; gap: 8px; }.view-controls button { border: 1px solid #286681; background: #0b2639; color: #bfeefa; padding: 7px 11px; cursor: pointer; }.view-controls button:hover { border-color: #42d7ea; }
.viewer-wrap { min-height: 0; border: 1px solid rgba(70, 164, 195, .6); }.viewer-wrap :deep(.cesium-viewer) { min-width: 0; min-height: 0; height: 100%; }
footer { display: flex; flex-wrap: wrap; gap: 14px 20px; color: #91b8c8; font-size: 12px; } footer span { display: inline-flex; align-items: center; gap: 6px; } i { width: 16px; height: 4px; display: inline-block; } .steel-rail { background: #36d7ff; }.ash-rail { background: #b58cff; }.gantry { background: #ffb45c; }.rebar { background: #7beda7; }
@media (max-width: 1100px) { .fact-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
