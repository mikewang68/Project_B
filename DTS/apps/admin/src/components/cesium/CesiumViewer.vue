<script setup lang="ts">
/**
 * CesiumViewer 组件（手动封装，不依赖第三方封装库）
 * - onMounted 创建 Viewer / onUnmounted 销毁（生命周期组件内管理）
 * - 通过 defineExpose 暴露 viewer 实例供父组件/图层管理使用
 * - 支持加载 GeoJSON 图层 + 相机控制
 */
import { onMounted, onUnmounted, ref, shallowRef, watch, type Ref } from 'vue'
import * as Cesium from 'cesium'
import { createOfflineViewer, flyToYard } from './cesiumInit'
import { cadGeoJsonToWgs84 } from '@/twin3d/geo'

// 场站设备区域中心（由 CAD 坐标范围 X 3294~4554 / Y 1485~1853 换算）
// CAD(3924,1669) → lon 91.0403, lat 29.0151
const YARD_CENTER: [number, number] = [91.0403, 29.0151]

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

/** 加载单个 GeoJSON 图层 */
async function loadGeoJsonLayer(viewer: Cesium.Viewer, layer: LayerConfig) {
  if (!layer.url) return
  try {
    const response = await fetch(layer.url)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const dataSource = await Cesium.GeoJsonDataSource.load(cadGeoJsonToWgs84(await response.json()), {
      stroke: Cesium.Color.fromCssColorString(layer.style?.color ?? '#00B4D8'),
      strokeWidth: layer.style?.width ?? 2,
      fill: Cesium.Color.fromCssColorString('#F4A261').withAlpha(0.25),
      clampToGround: true,
    })
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
