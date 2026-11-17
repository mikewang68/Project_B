<script setup lang="ts">
/**
 * CesiumViewer 组件（手动封装，不依赖第三方封装库）
 * - onMounted 创建 Viewer / onUnmounted 销毁（生命周期组件内管理）
 * - 通过 defineExpose 暴露 viewer 实例供父组件/图层管理使用
 * - 支持加载 GeoJSON 图层 + 相机控制
 */
import { onMounted, onUnmounted, ref, shallowRef, watch, type Ref } from 'vue'
import * as Cesium from 'cesium'
import { applyLightingPreset, createOfflineViewer, flyToYard, type LightingPreset } from './cesiumInit'
import { cadGeoJsonToWgs84, getYardCenterLonLat } from '@/twin3d/geo'
import { withBase } from '@/config/runtime'

const activeLighting = ref<LightingPreset>('daylight')
const showRenderGallery = ref(false)
const activeRenderIdx = ref(0)

const SHOWCASE_RENDERS = [
  {
    id: 'panorama',
    title: '① 1.27km 全站鸟瞰全景图（AutoCAD 给大连大.dwg + Blender 5.2 精模）',
    desc: '完整还原中铁二院《给大连大.dwg》40座3000T散料筒仓、23,882m²钢筋库、6台40t/30m轨行门吊、35/10kV变电所与轮胎吊集装箱区。',
    url: withBase('renders/render-01-panorama.png?v=20261117-v5'),
  },
  {
    id: 'silos-tipper',
    title: '② 散料区 40 座 Φ12m×33.38m 筒仓群、40m 斗提机塔架与移动翻箱机特写',
    desc: '48段平滑法线筒仓、5道环向加劲肋、8根Φ650钢管支柱与柱间抗风撑、仓顶脉冲布袋除尘器、40m钢桁架斗提机及35°倾翻作业中的移动翻箱机。',
    url: withBase('renders/render-02-silos-tipper.png?v=20261117-v5'),
  },
  {
    id: 'rebar-warehouse',
    title: '③ 23,882m² 钢筋仓库剖切双坡华伦式钢屋架、13 跨桥吊与库存钢材特写',
    desc: '14轴H型钢排架柱、双坡华伦式钢屋架桁架、采光天窗、9跨成捆螺纹钢垛（带枕木垫方与橙色钢带）、4跨V型鞍座热轧钢卷及A字腿双梁门吊。',
    url: withBase('renders/render-03-rebar-warehouse.png?v=20261117-v5'),
  },
  {
    id: 'train-tanker',
    title: '④ DF7G 调车机车牵引 35t 散料集装箱专列进站与库底散装通道特写',
    desc: '铁路货1/货4装卸线碎石道砟路基、DF7G内燃调车机车头、NX70平车、波纹板散料集装箱（带3顶盖与ISO角件）及预留68座缓建筒仓基础环。',
    url: withBase('renders/render-04-train-tanker.png?v=20261117-v5'),
  },
]

function switchLighting(preset: LightingPreset) {
  activeLighting.value = preset
  if (viewerRef.value) {
    applyLightingPreset(viewerRef.value, preset)
  }
}

// 场站设备区域中心（由 给大连大.dwg CGCS2000 基准实时换算）
const YARD_CENTER: [number, number] = getYardCenterLonLat()

interface LayerConfig {
  /** 图层标识 */
  id: string
  /** GeoJSON 数据 URL */
  url?: string
  /** 是否显示 */
  visible: boolean
  /** 样式（颜色/宽度） */
  style?: {
    color: string
    width?: number
  }
}

const props = defineProps<{
  /** 图层配置 */
  layers?: LayerConfig[]
  /** 初始场景中心 [经度, 纬度] */
  center?: [number, number]
  /** 初始视角高度（米） */
  height?: number
}>()

const emit = defineEmits<{
  (e: 'viewer-ready', viewer: Cesium.Viewer): void
}>()

const containerRef = ref<HTMLElement | null>(null)
const viewerRef = shallowRef<Cesium.Viewer | null>(null)
const dataSourcesRef = shallowRef<Map<string, Cesium.GeoJsonDataSource>>(new Map())
/** WebGL 不可用时显示提示 */
const webglError = ref(false)
let resizeObserver: ResizeObserver | null = null
let viewerCreating = false

/** 检测 WebGL 支持（Cesium 三维渲染的必要条件） */
function isWebGLSupported(): boolean {
  try {
    const canvas = document.createElement('canvas')
    return !!(
      canvas.getContext('webgl2') ||
      canvas.getContext('webgl') ||
      canvas.getContext('experimental-webgl')
    )
  } catch {
    return false
  }
}

async function initializeViewer() {
  const container = containerRef.value
  if (!container || viewerRef.value || viewerCreating) return
  const rect = container.getBoundingClientRect()
  if (rect.width < 2 || rect.height < 2) return
  viewerCreating = true
  const viewer = createOfflineViewer(container)
  viewerRef.value = viewer

  // 监听 GPU 崩溃 → 显示提示
  const onLost = () => {
    webglError.value = true
  }
  viewer.scene.canvas.addEventListener('dt-webgl-lost', onLost)

  // 加载初始图层
  if (props.layers && props.layers.length > 0) {
    for (const layer of props.layers) {
      if (layer.url) {
        await loadGeoJsonLayer(viewer, layer)
      }
    }
  }

  // 飞到场景中心（对准设备区域）
  flyToYard(viewer, props.center ?? YARD_CENTER, props.height ?? 1800)

  emit('viewer-ready', viewer)
  viewerCreating = false
}

onMounted(async () => {
  if (!containerRef.value) return

  // WebGL 检测：不可用则提示，不静默失败
  if (!isWebGLSupported()) {
    webglError.value = true
    console.error('[Cesium] WebGL 不可用，三维场景无法渲染。请开启浏览器硬件加速或更换浏览器。')
    return
  }

  // Do not construct Cesium against a zero-sized panel.  The observer also keeps
  // public resize calls synchronized with later layout changes without touching
  // Cesium internals.
  resizeObserver = new ResizeObserver(() => {
    if (!viewerRef.value) {
      void initializeViewer()
      return
    }
    const rect = containerRef.value?.getBoundingClientRect()
    if (rect && rect.width >= 2 && rect.height >= 2) viewerRef.value.resize()
  })
  resizeObserver.observe(containerRef.value)

  for (let i = 0; i < 30 && !viewerRef.value; i++) {
    await new Promise((resolve) => requestAnimationFrame(resolve))
    await initializeViewer()
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (viewerRef.value) {
    viewerRef.value.destroy()
    viewerRef.value = null
  }
})

/** 加载单个 GeoJSON 图层（支持按 V5 CAD 要素分类着色） */
async function loadGeoJsonLayer(viewer: Cesium.Viewer, layer: LayerConfig) {
  if (!layer.url) return
  try {
    const response = await fetch(layer.url)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const dataSource = await Cesium.GeoJsonDataSource.load(cadGeoJsonToWgs84(await response.json()), {
      stroke: Cesium.Color.fromCssColorString(layer.style?.color ?? '#00B4D8'),
      strokeWidth: layer.style?.width ?? 2,
      fill: Cesium.Color.fromCssColorString('#F4A261').withAlpha(0.18),
      clampToGround: true,
    })
    for (const ent of dataSource.entities.values) {
      const cat = ent.properties?.category?.getValue?.()
      if (cat === 'silo' || cat === 'silo_hopper') {
        const matColor = ent.properties?.material?.getValue?.() === 'cement' ? '#10B981' : '#06B6D4'
        if (ent.polyline) ent.polyline.material = new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString(matColor).withAlpha(0.9))
      } else if (cat === 'silo_reserved') {
        if (ent.polyline) ent.polyline.material = new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#F59E0B').withAlpha(0.55))
      } else if (cat === 'crane_rail' || cat === 'bridge_girder') {
        if (ent.polyline) ent.polyline.material = new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#FBBF24').withAlpha(0.92))
      } else if (cat === 'rebar_bay') {
        if (ent.polygon) ent.polygon.material = new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#38BDF8').withAlpha(0.12))
      } else if (cat === 'unloading_room' || cat === 'elevator') {
        if (ent.polygon) ent.polygon.material = new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#A78BFA').withAlpha(0.22))
      }
    }
    viewer.dataSources.add(dataSource)
    dataSource.show = layer.visible
    dataSourcesRef.value.set(layer.id, dataSource)
  } catch (err) {
    console.error(`加载图层 ${layer.id} 失败:`, err)
  }
}

/** 显示/隐藏图层 */
function setLayerVisible(id: string, visible: boolean) {
  const ds = dataSourcesRef.value.get(id)
  if (ds) ds.show = visible
}

// 监听图层可见性变化
watch(
  () => props.layers,
  (newLayers) => {
    newLayers?.forEach((layer) => setLayerVisible(layer.id, layer.visible))
  },
  { deep: true },
)

defineExpose({
  viewer: viewerRef as Ref<Cesium.Viewer | null>,
  loadGeoJsonLayer,
  setLayerVisible,
})
</script>

<template>
  <div class="cesium-viewer" ref="containerRef">
    <!-- V5 光影模式与 Blender 1080P 工业渲染图集悬浮切换栏 -->
    <div class="v5-visual-bar" @mousedown.stop @click.stop>
      <span class="v5-visual-tag">V5 精模光影</span>
      <button
        type="button"
        class="v5-light-btn"
        :class="{ active: activeLighting === 'daylight' }"
        title="高原晴昼明亮工业光（推荐汇报）"
        @click="switchLighting('daylight')"
      >
        ☀ 高原晴昼
      </button>
      <button
        type="button"
        class="v5-light-btn"
        :class="{ active: activeLighting === 'dusk' }"
        title="暮光金辉暖阳侧逆光"
        @click="switchLighting('dusk')"
      >
        🌅 暮光金辉
      </button>
      <button
        type="button"
        class="v5-light-btn"
        :class="{ active: activeLighting === 'night' }"
        title="深色科技夜航光效"
        @click="switchLighting('night')"
      >
        🌙 科技夜航
      </button>
      <span class="v5-divider">|</span>
      <button
        type="button"
        class="v5-gallery-btn"
        title="查看 AutoCAD + Blender 5.2 离线渲染 1080P 工业宣传效果图"
        @click="showRenderGallery = true"
      >
        🎨 1080P 渲染大图 (4)
      </button>
    </div>

    <!-- Blender 5.2 1080P 精模渲染画廊弹窗 -->
    <div v-if="showRenderGallery" class="v5-gallery-backdrop" @click.self="showRenderGallery = false">
      <div class="v5-gallery-modal">
        <div class="v5-gallery-header">
          <div>
            <div class="v5-gallery-title">
              AutoCAD《给大连大.dwg》+ Blender 5.2 LTS 工业级精细化建模与 1080P 渲染图集
            </div>
            <div class="v5-gallery-sub">
              405 项实测 CAD 矢量要素 · 18 组高精平滑 PBR 资产 · 40座3000T筒仓 · 23,882m²钢屋架厂房 · 6台40t门吊 · DF7G调车机车
            </div>
          </div>
          <button type="button" class="v5-gallery-close" @click="showRenderGallery = false">✕ 关闭</button>
        </div>

        <div class="v5-gallery-tabs">
          <button
            v-for="(item, idx) in SHOWCASE_RENDERS"
            :key="item.id"
            type="button"
            class="v5-gallery-tab"
            :class="{ active: activeRenderIdx === idx }"
            @click="activeRenderIdx = idx"
          >
            {{ item.title }}
          </button>
        </div>

        <div class="v5-gallery-stage" v-if="SHOWCASE_RENDERS[activeRenderIdx]">
          <img
            :src="SHOWCASE_RENDERS[activeRenderIdx].url"
            :alt="SHOWCASE_RENDERS[activeRenderIdx].title"
            class="v5-gallery-img"
          />
          <div class="v5-gallery-caption">
            <strong>{{ SHOWCASE_RENDERS[activeRenderIdx].title }}</strong>
            <span>{{ SHOWCASE_RENDERS[activeRenderIdx].desc }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- WebGL 不可用提示 -->
    <div v-if="webglError" class="webgl-error">
      <div class="webgl-error-title">⚠ 三维场景无法渲染</div>
      <div class="webgl-error-text">
        当前浏览器不支持 WebGL（三维渲染的必要能力）。
        请尝试：
        <ul>
          <li>使用 Chrome 或 Edge 浏览器</li>
          <li>开启浏览器"硬件加速"设置</li>
          <li>检查显卡驱动是否正常</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cesium-viewer {
  width: 100%;
  height: 100%;
  min-width: 320px;
  min-height: 300px;
  position: relative;
}

.v5-visual-bar {
  position: absolute;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  z-index: 16;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(8, 20, 38, 0.86);
  border: 1px solid rgba(56, 189, 248, 0.38);
  backdrop-filter: blur(10px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  pointer-events: auto;
}

.v5-visual-tag {
  font-size: 11px;
  font-weight: 700;
  color: #38bdf8;
  padding: 0 4px;
  letter-spacing: 0.5px;
}

.v5-light-btn,
.v5-gallery-btn {
  border: 1px solid rgba(148, 163, 184, 0.28);
  background: rgba(15, 23, 42, 0.78);
  color: #e2e8f0;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.16s ease;
}

.v5-light-btn:hover,
.v5-gallery-btn:hover {
  border-color: #38bdf8;
  color: #ffffff;
}

.v5-light-btn.active {
  background: linear-gradient(135deg, #0284c7, #0ea5e9);
  border-color: #7dd3fc;
  color: #ffffff;
  box-shadow: 0 0 10px rgba(14, 165, 233, 0.45);
}

.v5-gallery-btn {
  background: linear-gradient(135deg, rgba(234, 88, 12, 0.88), rgba(245, 158, 11, 0.88));
  border-color: #fbbf24;
  color: #fff;
}

.v5-divider {
  color: rgba(148, 163, 184, 0.4);
  font-size: 11px;
}

.v5-gallery-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(3, 10, 24, 0.84);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.v5-gallery-modal {
  width: min(1240px, 95vw);
  max-height: 92vh;
  background: #0b192e;
  border: 1px solid rgba(56, 189, 248, 0.45);
  border-radius: 14px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.72);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: #f1f5f9;
}

.v5-gallery-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  background: rgba(15, 30, 56, 0.95);
  border-bottom: 1px solid rgba(56, 189, 248, 0.25);
}

.v5-gallery-title {
  font-size: 16px;
  font-weight: 700;
  color: #38bdf8;
}

.v5-gallery-sub {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 3px;
}

.v5-gallery-close {
  border: 1px solid rgba(248, 113, 113, 0.45);
  background: rgba(127, 29, 29, 0.55);
  color: #fecaca;
  padding: 6px 14px;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
}

.v5-gallery-tabs {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  padding: 10px 16px;
  background: rgba(8, 20, 38, 0.9);
}

.v5-gallery-tab {
  border: 1px solid rgba(148, 163, 184, 0.25);
  background: rgba(15, 29, 52, 0.8);
  color: #cbd5e1;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
}

.v5-gallery-tab.active {
  background: linear-gradient(135deg, rgba(2, 132, 199, 0.45), rgba(14, 165, 233, 0.25));
  border-color: #38bdf8;
  color: #ffffff;
}

.v5-gallery-stage {
  padding: 12px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: auto;
}

.v5-gallery-img {
  width: 100%;
  max-height: 68vh;
  object-fit: contain;
  border-radius: 10px;
  border: 1px solid rgba(148, 163, 184, 0.25);
  background: #0f1e36;
}

.v5-gallery-caption {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: #cbd5e1;
  background: rgba(15, 29, 52, 0.75);
  padding: 10px 14px;
  border-radius: 8px;
  border-left: 3px solid #f59e0b;
}

.webgl-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
  color: var(--text-primary);
  text-align: center;
  padding: var(--space-5);
  z-index: 20;

  .webgl-error-title {
    font-size: 18px;
    font-weight: 700;
    color: var(--status-serious);
    margin-bottom: var(--space-3);
  }

  .webgl-error-text {
    font-size: 14px;
    line-height: 1.6;

    ul {
      text-align: left;
      margin-top: var(--space-2);
      padding-left: var(--space-5);
    }
  }
}
</style>
