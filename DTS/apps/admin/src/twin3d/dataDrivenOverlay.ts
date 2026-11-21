/**
 * DTS V6 数据驱动三维覆盖层控制器（零业务耦合，纯消费 S2 / S3 / S4 标准只读契约）
 *
 * 三大独立图层（可在左侧 F-D01 面板按需勾选）：
 * 1. S2 仓储料位与库容层 (`storageVisible`)：
 *    - 40 座 3000T 筒仓外部半透明料位指示筒 + 顶部液面环（正常青绿 / 偏高琥珀 / ≥85% 高料位预警红）
 *    - 13 跨钢筋仓库动态库容包络体块（高度与颜色随实测 fillRatio 实时联动）
 * 2. S3 设备健康态势光环层 (`healthVisible`)：
 *    - 36 台主动特种设备底座/顶冠健康光环与故障红脉冲标（随 EHMS 健康分/状态实时切换）
 * 3. S4 立体电子围栏与监控视场锥层 (`safetyVisible`)：
 *    - 4 处 CAD 实测轮廓 3D 半透明激光围栏墙（Cesium WallGraphics，ARMED 青绿 / WARN 琥珀 / BREACHED 红闪）
 *    - 6 台关键工位摄像机标牌与地面扇形视场投影锥（FOV Cone），支持点击弹窗查看流状态或播放实时流
 */
import * as Cesium from 'cesium'
import { cadToCartesian3 } from './geo'
import { SITE_LAYOUT_V4 } from './siteLayoutV4'
import { MOCK_EQUIPMENT } from './mock/mockData'
import {
  formatProvenanceBadge,
  type DeviceHealthOverlayItemV6,
  type SafetyTwinSnapshotV6,
  type StorageLocationStateV6,
  type SurveillanceCameraStateV6,
} from '@/services/twinIntegrationContracts'

const EQUIP_POS_MAP = new Map(MOCK_EQUIPMENT.map((e) => [e.id, e]))
const SILO_POS_MAP = new Map(SITE_LAYOUT_V4.ash.silos.map((s) => [s.id, s]))
const BAY_POS_MAP = new Map(SITE_LAYOUT_V4.rebar.bays.map((b) => [b.id, b]))

function storageColor(item: StorageLocationStateV6): Cesium.Color {
  if (item.dataSource === 'REGION_ISOLATED') return Cesium.Color.fromCssColorString('#64748B')
  if (item.highLevelWarning || item.fillRatio >= 0.85) return Cesium.Color.fromCssColorString('#EF4444')
  if (item.fillRatio >= 0.72) return Cesium.Color.fromCssColorString('#F59E0B')
  return item.category === 'CEMENT_SILO'
    ? Cesium.Color.fromCssColorString('#10B981')
    : item.category === 'FLYASH_SILO'
      ? Cesium.Color.fromCssColorString('#38BDF8')
      : Cesium.Color.fromCssColorString('#60A5FA')
}

function healthColor(item: DeviceHealthOverlayItemV6): Cesium.Color {
  if (item.status === 'fault' || item.healthLevel === 'critical') return Cesium.Color.fromCssColorString('#EF4444')
  if (item.healthLevel === 'risk') return Cesium.Color.fromCssColorString('#F97316')
  if (item.status === 'maintenance' || item.healthLevel === 'attention') return Cesium.Color.fromCssColorString('#F59E0B')
  if (item.status === 'standby') return Cesium.Color.fromCssColorString('#38BDF8')
  if (item.status === 'offline') return Cesium.Color.fromCssColorString('#64748B')
  return Cesium.Color.fromCssColorString('#34D399')
}

function fenceColor(status: string): Cesium.Color {
  if (status === 'BREACHED') return Cesium.Color.fromCssColorString('#EF4444')
  if (status === 'WARN') return Cesium.Color.fromCssColorString('#F59E0B')
  if (status === 'BYPASSED') return Cesium.Color.fromCssColorString('#94A3B8')
  return Cesium.Color.fromCssColorString('#10B981')
}

export class DataDrivenOverlayController {
  private readonly viewer: Cesium.Viewer
  private readonly dataSource = new Cesium.CustomDataSource('dt-v6-data-overlay')

  private readonly storageEntities = new Map<string, Cesium.Entity[]>()
  private readonly healthEntities = new Map<string, Cesium.Entity>()
  private readonly safetyEntities = new Map<string, Cesium.Entity[]>()
  private readonly cameraDataById = new Map<string, SurveillanceCameraStateV6>()

  private storageVisible = false
  private healthVisible = true
  private safetyVisible = false

  constructor(viewer: Cesium.Viewer) {
    this.viewer = viewer
    viewer.dataSources.add(this.dataSource)
  }

  getCameraById(cameraId: string): SurveillanceCameraStateV6 | null {
    return this.cameraDataById.get(cameraId) ?? null
  }

  getAllCameras(): SurveillanceCameraStateV6[] {
    return Array.from(this.cameraDataById.values())
  }

  setStorageVisible(visible: boolean) {
    this.storageVisible = visible
    for (const list of this.storageEntities.values()) {
      for (const e of list) e.show = visible
    }
    this.viewer.scene.requestRender()
  }

  setHealthVisible(visible: boolean) {
    this.healthVisible = visible
    for (const e of this.healthEntities.values()) e.show = visible
    this.viewer.scene.requestRender()
  }

  setSafetyVisible(visible: boolean) {
    this.safetyVisible = visible
    for (const list of this.safetyEntities.values()) {
      for (const e of list) e.show = visible
    }
    this.viewer.scene.requestRender()
  }

  /** S2 (WMS): Sync all 40 silos + 13 rebar bays live 3D fill columns & stock envelopes. */
  syncStorage(items: StorageLocationStateV6[]) {
    for (const item of items) {
      const col = storageColor(item)
      const pct = Math.round(item.fillRatio * 100)
      const badge = formatProvenanceBadge(item.dataSource).label

      if (item.locationId.startsWith('SILO-')) {
        const silo = SILO_POS_MAP.get(item.locationId)
        if (!silo) continue
        const fillH = Math.max(1.2, item.fillRatio * 23.2)
        const baseZ = 7.6
        const centerZ = baseZ + fillH / 2
        const topZ = baseZ + fillH
        let pair = this.storageEntities.get(item.locationId)
        if (!pair) {
          const shell = this.dataSource.entities.add({
            id: `S2-LEVEL-${item.locationId}`,
            show: this.storageVisible,
            position: cadToCartesian3(silo.cadX, silo.cadY, centerZ),
            cylinder: {
              length: fillH,
              topRadius: 6.45,
              bottomRadius: 6.45,
              material: col.withAlpha(0.30),
              outline: true,
              outlineColor: col.withAlpha(0.92),
              numberOfVerticalLines: 8,
            },
            properties: { parent: item.locationId, type: 'silo_level_overlay' },
          })
          const cap = this.dataSource.entities.add({
            id: `S2-CAP-${item.locationId}`,
            show: this.storageVisible,
            position: cadToCartesian3(silo.cadX, silo.cadY, topZ + 0.35),
            cylinder: {
              length: 0.48,
              topRadius: 6.62,
              bottomRadius: 6.62,
              material: col.withAlpha(0.85),
            },
            label: {
              text: `${item.locationCode} ${pct}%`,
              font: '700 11px Microsoft YaHei',
              fillColor: Cesium.Color.WHITE,
              outlineColor: Cesium.Color.fromCssColorString('#030B18'),
              outlineWidth: 2,
              style: Cesium.LabelStyle.FILL_AND_OUTLINE,
              backgroundColor: Cesium.Color.fromCssColorString('#06192C').withAlpha(0.82),
              showBackground: true,
              backgroundPadding: new Cesium.Cartesian2(5, 2),
              pixelOffset: new Cesium.Cartesian2(0, -10),
              distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 320),
              disableDepthTestDistance: 120,
            },
            properties: { parent: item.locationId, type: 'silo_level_overlay' },
          })
          pair = [shell, cap]
          this.storageEntities.set(item.locationId, pair)
        } else {
          const [shell, cap] = pair
          shell.position = new Cesium.ConstantPositionProperty(cadToCartesian3(silo.cadX, silo.cadY, centerZ))
          if (shell.cylinder) {
            shell.cylinder.length = new Cesium.ConstantProperty(fillH)
            shell.cylinder.material = new Cesium.ColorMaterialProperty(col.withAlpha(item.highLevelWarning ? 0.42 : 0.28))
            shell.cylinder.outlineColor = new Cesium.ConstantProperty(col.withAlpha(0.95))
          }
          cap.position = new Cesium.ConstantPositionProperty(cadToCartesian3(silo.cadX, silo.cadY, topZ + 0.35))
          if (cap.cylinder) {
            cap.cylinder.material = new Cesium.ColorMaterialProperty(col.withAlpha(0.88))
          }
          if (cap.label) {
            cap.label.text = new Cesium.ConstantProperty(
              item.highLevelWarning ? `${item.locationCode} ${pct}% 预警` : `${item.locationCode} ${pct}%`,
            )
          }
        }
      } else if (item.locationId.startsWith('REBAR-BAY-')) {
        const bay = BAY_POS_MAP.get(item.locationId)
        if (!bay) continue
        const boxH = Math.max(0.9, item.fillRatio * 6.4)
        const bayW = Math.max(18, bay.maxX - bay.minX - 5.2)
        const bayD = 36.0
        const bayCy = 1696.0
        let list = this.storageEntities.get(item.locationId)
        if (!list) {
          const vol = this.dataSource.entities.add({
            id: `S2-BAY-${item.locationId}`,
            show: this.storageVisible,
            position: cadToCartesian3(bay.cadX, bayCy, boxH / 2 + 0.12),
            box: {
              dimensions: new Cesium.Cartesian3(bayW, bayD, boxH),
              material: col.withAlpha(0.24),
              outline: true,
              outlineColor: col.withAlpha(0.92),
            },
            label: {
              text: `${String(bay.bayIndex).padStart(2, '0')}跨 ${pct}% (${Math.round(item.availableTon)}t · ${badge})`,
              font: '700 11px Microsoft YaHei',
              fillColor: Cesium.Color.WHITE,
              outlineColor: Cesium.Color.fromCssColorString('#030B18'),
              outlineWidth: 2,
              style: Cesium.LabelStyle.FILL_AND_OUTLINE,
              backgroundColor: Cesium.Color.fromCssColorString('#06192C').withAlpha(0.84),
              showBackground: true,
              backgroundPadding: new Cesium.Cartesian2(5, 2),
              pixelOffset: new Cesium.Cartesian2(0, -14),
              distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 380),
              disableDepthTestDistance: 120,
            },
            properties: { parent: item.locationId, type: 'rebar_stock_overlay' },
          })
          list = [vol]
          this.storageEntities.set(item.locationId, list)
        } else {
          const [vol] = list
          vol.position = new Cesium.ConstantPositionProperty(cadToCartesian3(bay.cadX, bayCy, boxH / 2 + 0.12))
          if (vol.box) {
            vol.box.dimensions = new Cesium.ConstantProperty(new Cesium.Cartesian3(bayW, bayD, boxH))
            vol.box.material = new Cesium.ColorMaterialProperty(col.withAlpha(item.highLevelWarning ? 0.36 : 0.22))
            vol.box.outlineColor = new Cesium.ConstantProperty(col.withAlpha(0.95))
          }
          if (vol.label) {
            vol.label.text = new Cesium.ConstantProperty(
              `${String(bay.bayIndex).padStart(2, '0')}跨 ${pct}% (${Math.round(item.availableTon)}t)`,
            )
          }
        }
      }
    }
    this.viewer.scene.requestRender()
  }

  /** S3 (EHMS): Sync 36-device 3D health halos (subtle green/cyan base rings, bold red beacon beams on fault/risk). */
  syncDeviceHealth(items: DeviceHealthOverlayItemV6[]) {
    for (const item of items) {
      const def = EQUIP_POS_MAP.get(item.deviceId)
      if (!def) continue
      const col = healthColor(item)
      const isRisk = item.status === 'fault' || item.healthLevel === 'critical' || item.healthLevel === 'risk'
      const radius = def.type === 'gantry_crane' ? 8.5 : def.type === 'bridge_crane' ? 5.2 : 4.2
      const ringZ = def.type === 'bridge_crane' ? 13.8 : 0.36
      const ringH = isRisk ? 10.5 : 0.32
      const posZ = isRisk ? ringZ + ringH / 2 : ringZ

      let entity = this.healthEntities.get(item.deviceId)
      if (!entity) {
        entity = this.dataSource.entities.add({
          id: `S3-HALO-${item.deviceId}`,
          show: this.healthVisible,
          position: cadToCartesian3(def.cadX, def.cadY, posZ),
          cylinder: {
            length: ringH,
            topRadius: isRisk ? radius * 0.62 : radius,
            bottomRadius: radius,
            material: col.withAlpha(isRisk ? 0.28 : 0.14),
            outline: true,
            outlineColor: col.withAlpha(isRisk ? 0.96 : 0.55),
            numberOfVerticalLines: isRisk ? 12 : 0,
          },
          properties: { parent: item.deviceId, type: 'ehms_health_halo' },
        })
        this.healthEntities.set(item.deviceId, entity)
      } else {
        entity.position = new Cesium.ConstantPositionProperty(cadToCartesian3(def.cadX, def.cadY, posZ))
        if (entity.cylinder) {
          entity.cylinder.length = new Cesium.ConstantProperty(ringH)
          entity.cylinder.topRadius = new Cesium.ConstantProperty(isRisk ? radius * 0.62 : radius)
          entity.cylinder.bottomRadius = new Cesium.ConstantProperty(radius)
          entity.cylinder.material = new Cesium.ColorMaterialProperty(col.withAlpha(isRisk ? 0.30 : 0.14))
          entity.cylinder.outlineColor = new Cesium.ConstantProperty(col.withAlpha(isRisk ? 0.96 : 0.55))
        }
      }
    }
    this.viewer.scene.requestRender()
  }

  /** S4 (SCS): Sync 3D volumetric electronic fence walls & 6 surveillance camera FOV cones. */
  syncSafety(snapshot: SafetyTwinSnapshotV6) {
    // 1. Electronic Fences (3D translucent laser walls)
    for (const fence of snapshot.fences) {
      const col = fenceColor(fence.status)
      const ring = [...fence.polygonCad, fence.polygonCad[0]]
      const positions = ring.map(([x, y]) => cadToCartesian3(x, y, 0.08))
      const topHeights = ring.map(() => fence.heightM || 3.6)
      const botHeights = ring.map(() => 0.08)
      const cx = fence.polygonCad.reduce((s, p) => s + p[0], 0) / fence.polygonCad.length
      const cy = fence.polygonCad.reduce((s, p) => s + p[1], 0) / fence.polygonCad.length
      const statusTxt =
        fence.status === 'BREACHED'
          ? `越界告警${fence.breachTarget ? ` · ${fence.breachTarget}` : ''}`
          : fence.status === 'WARN'
            ? '防撞预警'
            : fence.status === 'BYPASSED'
              ? '检修旁路'
              : '布防正常'
      const badge = formatProvenanceBadge(fence.dataSource).label

      let pair = this.safetyEntities.get(fence.fenceId)
      if (!pair) {
        const wall = this.dataSource.entities.add({
          id: `S4-WALL-${fence.fenceId}`,
          show: this.safetyVisible,
          wall: {
            positions,
            maximumHeights: topHeights,
            minimumHeights: botHeights,
            material: col.withAlpha(fence.status === 'BREACHED' ? 0.44 : 0.28),
            outline: true,
            outlineColor: col.withAlpha(0.98),
          },
          polyline: {
            positions: ring.map(([x, y]) => cadToCartesian3(x, y, (fence.heightM || 3.6) + 0.05)),
            width: 3,
            material: new Cesium.PolylineGlowMaterialProperty({ glowPower: 0.45, color: col }),
          },
          properties: { parent: fence.fenceId, type: 'scs_fence_wall', name: fence.name },
        })
        const tag = this.dataSource.entities.add({
          id: `S4-FENCE-TAG-${fence.fenceId}`,
          show: this.safetyVisible,
          position: cadToCartesian3(cx, cy, (fence.heightM || 3.6) + 1.6),
          label: {
            text: `${fence.name}\n${statusTxt} · 缓冲 ${fence.bufferDistanceM}m (${badge})`,
            font: '700 11px Microsoft YaHei',
            fillColor: Cesium.Color.WHITE,
            outlineColor: Cesium.Color.fromCssColorString('#030B18'),
            outlineWidth: 2,
            style: Cesium.LabelStyle.FILL_AND_OUTLINE,
            backgroundColor: col.withAlpha(0.32),
            showBackground: true,
            backgroundPadding: new Cesium.Cartesian2(6, 3),
            distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 620),
            disableDepthTestDistance: 140,
          },
          properties: { parent: fence.fenceId, type: 'scs_fence_wall', name: fence.name },
        })
        pair = [wall, tag]
        this.safetyEntities.set(fence.fenceId, pair)
      } else {
        const [wall, tag] = pair
        if (wall.wall) {
          wall.wall.material = new Cesium.ColorMaterialProperty(col.withAlpha(fence.status === 'BREACHED' ? 0.42 : 0.20))
          wall.wall.outlineColor = new Cesium.ConstantProperty(col.withAlpha(0.95))
        }
        if (tag.label) {
          tag.label.text = new Cesium.ConstantProperty(`${fence.name}\n${statusTxt} · 缓冲 ${fence.bufferDistanceM}m (${badge})`)
        }
      }
    }

    // 2. Surveillance Cameras + Ground FOV Frustum Cones (`CAM-01..06`)
    for (const cam of snapshot.cameras) {
      this.cameraDataById.set(cam.cameraId, cam)
      const isAlert = cam.status === 'ALERT'
      const col = isAlert
        ? Cesium.Color.fromCssColorString('#EF4444')
        : cam.streamUrl
          ? Cesium.Color.fromCssColorString('#10B981')
          : Cesium.Color.fromCssColorString('#38BDF8')

      const halfFov = ((cam.fovDeg || 52) / 2) * (Math.PI / 180)
      const hRad = (cam.headingDeg || 0) * (Math.PI / 180)
      const r = Math.min(70, Math.max(24, cam.rangeM || 52))
      const pLeft: [number, number] = [cam.cadX + Math.cos(hRad - halfFov) * r, cam.cadY + Math.sin(hRad - halfFov) * r]
      const pMid: [number, number] = [cam.cadX + Math.cos(hRad) * (r * 1.04), cam.cadY + Math.sin(hRad) * (r * 1.04)]
      const pRight: [number, number] = [cam.cadX + Math.cos(hRad + halfFov) * r, cam.cadY + Math.sin(hRad + halfFov) * r]

      const fovPts = [
        cadToCartesian3(cam.cadX, cam.cadY, 0.35),
        cadToCartesian3(pLeft[0], pLeft[1], 0.35),
        cadToCartesian3(pMid[0], pMid[1], 0.35),
        cadToCartesian3(pRight[0], pRight[1], 0.35),
      ]
      const statusTag = cam.streamUrl ? `${cam.streamProtocol} 实流` : isAlert ? 'AI告警' : '待接流'

      let pair = this.safetyEntities.get(cam.cameraId)
      if (!pair) {
        const marker = this.dataSource.entities.add({
          id: `S4-CAM-${cam.cameraId}`,
          show: this.safetyVisible,
          position: cadToCartesian3(cam.cadX, cam.cadY, cam.cadZ || 10),
          point: {
            pixelSize: 11,
            color: col,
            outlineColor: Cesium.Color.WHITE,
            outlineWidth: 2,
          },
          label: {
            text: `${cam.cameraId} · ${statusTag}`,
            font: '700 11px Microsoft YaHei',
            fillColor: Cesium.Color.fromCssColorString('#E0F2FE'),
            outlineColor: Cesium.Color.fromCssColorString('#030B18'),
            outlineWidth: 2,
            style: Cesium.LabelStyle.FILL_AND_OUTLINE,
            backgroundColor: Cesium.Color.fromCssColorString('#082F49').withAlpha(0.88),
            showBackground: true,
            backgroundPadding: new Cesium.Cartesian2(6, 3),
            pixelOffset: new Cesium.Cartesian2(0, -16),
            distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 680),
            disableDepthTestDistance: 160,
          },
          properties: { parent: cam.cameraId, type: 'scs_camera', name: cam.name },
        })
        const cone = this.dataSource.entities.add({
          id: `S4-FOV-${cam.cameraId}`,
          show: this.safetyVisible,
          polygon: {
            hierarchy: new Cesium.PolygonHierarchy(fovPts),
            height: 0.35,
            material: col.withAlpha(isAlert ? 0.36 : 0.24),
            outline: true,
            outlineColor: col.withAlpha(0.92),
          },
          properties: { parent: cam.cameraId, type: 'scs_camera', name: cam.name },
        })
        pair = [marker, cone]
        this.safetyEntities.set(cam.cameraId, pair)
      } else {
        const [marker, cone] = pair
        if (marker.point) marker.point.color = new Cesium.ConstantProperty(col)
        if (marker.label) marker.label.text = new Cesium.ConstantProperty(`${cam.cameraId} · ${statusTag}`)
        if (cone.polygon) {
          cone.polygon.material = new Cesium.ColorMaterialProperty(col.withAlpha(isAlert ? 0.30 : 0.16))
          cone.polygon.outlineColor = new Cesium.ConstantProperty(col.withAlpha(0.75))
        }
      }
    }

    this.viewer.scene.requestRender()
  }

  destroy() {
    this.viewer.dataSources.remove(this.dataSource)
  }
}
