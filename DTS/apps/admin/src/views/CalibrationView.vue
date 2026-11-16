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
import { SITE_LAYOUT_V4 } from '@/twin3d/siteLayoutV4'

let viewer: Cesium.Viewer | null = null
let calibrationSource: Cesium.CustomDataSource | null = null
const status = ref('正在加载《给大连大.dwg》V4 实测 CAD 校准底图与 19 项装备台账…')

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
        width: 7,
        material: new Cesium.PolylineDashMaterialProperty({ color: gantryColor, dashLength: 16 }),
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

  // 40 Silos (8 Cement + 32 Fly-Ash) & 4 Unloading Complexes + 40m Bucket Elevators
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

  // Electromechanical Warehouse (2×50t Bridge Cranes) & Weighbridges
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
      status.value = '已切换至《给大连大.dwg》实测 V4 坐标基准：40 座 Φ12m×33.38m 筒仓、4 座卸料室/提升机、6 台 40t/30m 门吊及 13 跨室内桥吊均与中铁二院施工图 1:1 对齐。'
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
        <p>SURVEYED CAD V4 · 给大连大.dwg 实测基准</p>
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
      <button @click="focusCalibrationView('steel')">钢材双轨 + 钢筋库13跨（俯视）</button>
      <button @click="focusCalibrationView('ash')">散料双轨 + 40筒仓/卸料室（俯视）</button>
      <button @click="focusCalibrationView('all')">完整中沙坝转运站</button>
    </nav>
    <section class="viewer-wrap"><CesiumViewer @viewer-ready="onViewerReady" /></section>
    <footer>
      <span><i class="steel-rail"></i>钢材线：货3/货4 + 13跨桥吊</span>
      <span><i class="ash-rail"></i>散料线：货1/货2 + 40座Φ12m筒仓</span>
      <span><i class="gantry"></i>6台40t/30m门吊（钢材3+散料3）</span>
      <span><i class="rebar"></i>钢筋仓库：23,882 m²（9×20t磁吸 + 4×32t C型）</span>
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
