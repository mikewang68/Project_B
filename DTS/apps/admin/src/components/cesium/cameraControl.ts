/**
 * 相机控制：6-DOF 相机操控（需求模块 A：视角控制）
 * 基于 Cesium 默认相机交互（左键旋转/中键缩放/右键平移）
 * 提供程序化控制接口：飞行聚焦、重置视角、量算辅助
 */
import * as Cesium from 'cesium'

/**
 * 初始化相机交互灵敏度
 */
export function setupCameraInteraction(viewer: Cesium.Viewer): void {
  const scene = viewer.scene
  // 默认交互已启用（ScreenSpaceCameraController），此处调整灵敏度
  scene.screenSpaceCameraController.zoomEventTypes = [
    Cesium.CameraEventType.WHEEL,
    Cesium.CameraEventType.PINCH,
  ]
  // 限制缩放范围：最小 150m（防止缩到 0m 进入地底下导致渲染崩溃）
  scene.screenSpaceCameraController.minimumZoomDistance = 150
  scene.screenSpaceCameraController.maximumZoomDistance = 20000
}

/**
 * 视角聚焦到指定对象（需求 §13.11 视角自动聚焦）
 * @param viewer Cesium Viewer
 * @param target 目标 [经度, 纬度] 或 Cartesian3
 * @param height 聚焦高度
 * @param duration 飞行时长（秒）
 */
export function focusOn(
  viewer: Cesium.Viewer,
  target: [number, number] | Cesium.Cartesian3,
  height = 2000,
  duration = 1.5,
): void {
  const position = Array.isArray(target)
    ? Cesium.Cartesian3.fromDegrees(target[0], target[1], height)
    : target
  viewer.camera.flyTo({
    destination: position,
    orientation: {
      heading: 0,
      pitch: Cesium.Math.toRadians(-45),
      roll: 0,
    },
    duration,
  })
}

/**
 * 重置到场站全景视角
 */
export function resetToOverview(
  viewer: Cesium.Viewer,
  center: [number, number],
  height = 1800,
): void {
  viewer.camera.flyTo({
    destination: Cesium.Cartesian3.fromDegrees(center[0], center[1], height),
    orientation: {
      heading: 0,
      pitch: Cesium.Math.toRadians(-60),
      roll: 0,
    },
    duration: 1.5,
  })
}
