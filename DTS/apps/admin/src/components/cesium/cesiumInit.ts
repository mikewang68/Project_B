/**
 * Cesium 初始化配置
 * 对齐统一基线：浏览器本地渲染、离线运行、不使用 Cesium ion 在线服务
 */
import * as Cesium from 'cesium'
import 'cesium/Build/Cesium/Widgets/widgets.css'
import { CESIUM_BASE_URL } from '@/config/runtime'

// Cesium 静态资源基路径，跟随部署 base（根路径为 /cesium/，子路径为 /dt/cesium/）
declare global {
  interface Window {
    CESIUM_BASE_URL?: string
  }
}
window.CESIUM_BASE_URL = CESIUM_BASE_URL

/**
 * 创建 Cesium Viewer（离线配置）
 * - 不加载任何在线影像/地形（ion、Bing 等）
 * - 使用本地默认底图样式
 * - 关闭不需要的 UI 控件
 */
export function createOfflineViewer(container: HTMLElement): Cesium.Viewer {
  const viewer = new Cesium.Viewer(container, {
    // ── 离线：不加载在线影像/地形 ──
    baseLayer: false,
    baseLayerPicker: false,
    // 关闭 UI 控件
    animation: false,
    timeline: false,
    fullscreenButton: false,
    geocoder: false,
    homeButton: false,
    sceneModePicker: false,
    navigationHelpButton: false,
    infoBox: false,
    selectionIndicator: true,
    orderIndependentTranslucency: false,
    // ── 低负载稳定配置（避免 GPU 崩溃）──
    requestRenderMode: true, // 按需渲染，降低 GPU 持续负载
    maximumRenderTimeChange: Infinity,
  })

  // 移除默认（空的）底图
  viewer.imageryLayers.removeAll()

  // 深色场景风格（对齐基线：深色背景 #071228，不加载天空/太阳等装饰）
  const scene = viewer.scene
  scene.backgroundColor = Cesium.Color.fromCssColorString('#030A17')
  if (scene.skyBox) scene.skyBox.show = false
  if (scene.skyAtmosphere) scene.skyAtmosphere.show = false
  if (scene.sun) scene.sun.show = false
  if (scene.moon) scene.moon.show = false
  scene.light = new Cesium.DirectionalLight({
    // Cool moonlit key light.  Warm practical lights are authored in the GLBs
    // and added as small scene accents, which keeps the operational view legible
    // without pretending this globe-free scene has physically accurate lighting.
    direction: new Cesium.Cartesian3(-0.42, -0.30, -0.86),
    color: Cesium.Color.fromCssColorString('#B7D6FF'),
    intensity: 2.55,
  })
  // 当前 CAD 只有本地米制坐标，尚未完成真实地理配准；保留地球会遮挡场站实体。
  // 后续拿到真实经纬度、地形和影像后，再由场站配置显式开启 globe。
  scene.globe.show = false
  scene.globe.enableLighting = false
  scene.globe.baseColor = Cesium.Color.fromCssColorString('#0a1628')

  // ── 关闭高负载渲染特性，降低 GPU 压力（兼容 1650Ti 等中端显卡）──
  // A modest quality baseline: enough edge definition for steel members and
  // rails, while remaining practical for the medium-LOD station scene.
  scene.postProcessStages.fxaa.enabled = true
  scene.msaaSamples = 2
  scene.highDynamicRange = true
  scene.useDepthPicking = false

  // The target browser can briefly report a 0×0 host during panel/layout changes.
  // GlobeDepth then creates an invalid texture.  Guard Viewer.resize at the public
  // boundary instead of altering Cesium's private render state.
  const baseResize = viewer.resize.bind(viewer)
  const safeResize = () => {
    const rect = container.getBoundingClientRect()
    if (rect.width < 2 || rect.height < 2) return
    baseResize()
  }
  viewer.resize = safeResize
  safeResize()

  // WebGL 上下文丢失（GPU 崩溃）检测 → 发出事件，页面可捕获提示
  const canvas = viewer.scene.canvas
  const onContextLost = (e: Event) => {
    e.preventDefault()
    console.error('[Cesium] WebGL 上下文丢失（GPU 崩溃）。请关闭浏览器硬件加速或更新显卡驱动。')
    const ev = new CustomEvent('dt-webgl-lost', { detail: { message: 'GPU渲染崩溃' } })
    canvas.dispatchEvent(ev)
  }
  const onContextRestored = () => {
    console.log('[Cesium] WebGL 上下文已恢复')
    const ev = new CustomEvent('dt-webgl-restored')
    canvas.dispatchEvent(ev)
  }
  canvas.addEventListener('webglcontextlost', onContextLost, false)
  canvas.addEventListener('webglcontextrestored', onContextRestored, false)

  return viewer
}

/**
 * 将相机飞到场站区域
 * @param viewer Cesium Viewer
 * @param center [经度, 纬度]
 * @param height 视角高度（米）
 */
export function flyToYard(
  viewer: Cesium.Viewer,
  center: [number, number],
  height = 3000,
): void {
  viewer.camera.flyTo({
    destination: Cesium.Cartesian3.fromDegrees(center[0], center[1], height),
    orientation: {
      heading: 0,
      pitch: Cesium.Math.toRadians(-60), // 俯视 30°（-60 俯角）
      roll: 0,
    },
    duration: 2,
  })
}
