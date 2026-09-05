/**
 * 三维建模控制器：管理程序化建模设备、状态模拟、点击交互
 * 需求模块 A（场景）+ B（设备状态）+ C（货物位置）
 */
import * as Cesium from 'cesium'
import { STATUS_COLORS, STATUS_NAMES, type EquipmentDef } from './constants'
import { buildEquipment, type BuiltEquipment } from './models/buildEquipment'
import { GltfModelProvider, type StaticSiteAsset } from './models/gltfModelProvider'
import { MOCK_EQUIPMENT } from './mock/mockData'
import { cadToLonLat } from './geo'
import { VEHICLE_ROUTES } from './vehicleRoutes'
import { VehicleRouteAnimator } from './vehicleAnimator'

export interface SelectedInfo {
  id: string
  name: string
  type: string
  status: string
  statusName: string
  params: Record<string, string>
}

export interface Twin3dControllerOptions {
  /** 点击选中回调 */
  onSelect?: (info: SelectedInfo | null) => void
}

export class Twin3dController {
  private viewer: Cesium.Viewer
  private dataSource: Cesium.CustomDataSource
  private equipmentMap = new Map<string, BuiltEquipment>()
  private mockTimer: ReturnType<typeof setInterval> | null = null
  private options: Twin3dControllerOptions
  private allEquipment: EquipmentDef[]
  private readonly useProceduralFallback: boolean
  private readonly staticAssetMap = new Map<string, StaticSiteAsset>()
  private vehicleAnimator: VehicleRouteAnimator | null = null
  private gltfProvider: GltfModelProvider | null = null

  constructor(viewer: Cesium.Viewer, options: Twin3dControllerOptions = {}) {
    this.viewer = viewer
    this.options = options
    this.dataSource = new Cesium.CustomDataSource('dt-equipment')
    viewer.dataSources.add(this.dataSource)
    this.allEquipment = MOCK_EQUIPMENT
    this.useProceduralFallback = import.meta.env.DEV && import.meta.env.VITE_TWIN_MODEL_MODE === 'procedural'
    this.loadAll()
    this.setupPickHandler()
  }

  /** 加载全部设备（程序化建模） */
  private loadAll() {
    const gltfProvider = new GltfModelProvider(this.dataSource)
    this.gltfProvider = gltfProvider
    if (!this.useProceduralFallback) {
      for (const asset of gltfProvider.loadEnvironment()) this.staticAssetMap.set(asset.placement.id, asset)
      this.vehicleAnimator = new VehicleRouteAnimator(
        VEHICLE_ROUTES,
        (vehicleId, pose) => gltfProvider.updateStaticPose(vehicleId, pose.cadX, pose.cadY, pose.headingDeg),
        () => this.viewer.scene.requestRender(),
      )
      this.vehicleAnimator.start()
    }
    for (const def of this.allEquipment) {
      const built = this.useProceduralFallback
        ? buildEquipment(this.dataSource, def)
        : gltfProvider.buildEquipment(def)
      if (built) this.equipmentMap.set(def.id, built)
    }
  }

  /** 点击交互（射线检测 → 选中 + 回调；pickPosition 后备提升容错） */
  private setupPickHandler() {
    const handler = new Cesium.ScreenSpaceEventHandler(this.viewer.scene.canvas)
    handler.setInputAction((click: Cesium.ScreenSpaceEventHandler.PositionedEvent) => {
      this.clearSelection()

      let rootId: string | null = null
      const picked = this.viewer.scene.pick(click.position)
      if (Cesium.defined(picked)) {
        rootId = this.resolveRootId(picked.id)
      } else {
        // 后备：pickPosition 获取点击位置 3D 坐标，匹配附近设备（提升容错）
        const pos = this.viewer.scene.pickPosition(click.position)
        if (Cesium.defined(pos)) {
          rootId = this.findNearbyEquipment(pos as Cesium.Cartesian3)
        }
      }

      if (rootId && this.equipmentMap.has(rootId)) {
        this.selectEquipment(rootId)
      } else if (rootId && this.staticAssetMap.has(rootId)) {
        this.selectStaticAsset(rootId)
      } else {
        this.options.onSelect?.(null)
      }
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK)
  }

  /** 找出距离点击位置最近的设备（阈值内） */
  private findNearbyEquipment(position: Cesium.Cartesian3, thresholdM = 40): string | null {
    let nearest: string | null = null
    let minDist = Infinity
    for (const [id, built] of this.equipmentMap) {
      const [lon, lat] = cadToLonLat(built.def.cadX, built.def.cadY)
      const pos = Cesium.Cartesian3.fromDegrees(lon, lat)
      const dist = Cesium.Cartesian3.distance(position, pos)
      if (dist < minDist) {
        minDist = dist
        nearest = id
      }
    }
    return minDist <= thresholdM ? nearest : null
  }

  /** 解析实体 → 设备根 id（龙门吊子实体 parent 指向根） */
  private resolveRootId(entity: Cesium.Entity | undefined): string {
    const parent = entity?.properties?.parent?.getValue?.()
    if (parent) return String(parent)
    return entity?.id ?? ''
  }

  /** 选中设备：高亮 + 回调 */
  private selectEquipment(id: string) {
    const built = this.equipmentMap.get(id)
    if (!built) return
    const { def } = built
    this.options.onSelect?.({
      id: def.id,
      name: def.name,
      type: def.type,
      status: def.status,
      statusName: STATUS_NAMES[def.status] ?? def.status,
      params: def.params,
    })
  }

  private selectStaticAsset(id: string) {
    const asset = this.staticAssetMap.get(id)
    if (!asset) return
    const { placement } = asset
    this.options.onSelect?.({
      id: placement.id,
      name: placement.name,
      type: placement.type,
      status: placement.state === 'planned' ? 'planned' : 'ok',
      statusName: placement.state === 'planned' ? '规划中' : '在场',
      params: {
        分区: placement.zone,
        数据来源: placement.source,
        置信度: placement.confidence,
      },
    })
  }

  /** 清除选中高亮 */
  private clearSelection() {
    // 高亮逻辑：恢复原状态色（简单实现）
  }

  /** 更新单个设备状态 */
  setStatus(id: string, status: EquipmentDef['status']) {
    this.equipmentMap.get(id)?.setStatus(status)
    this.viewer.scene.requestRender() // requestRenderMode 下手动触发重绘
  }

  /** 驱动龙门吊三轴动画（供 useAnimation 输出映射） */
  setCranePose(id: string, pose: { gantryX: number; gantryY: number; trolleyOffset: number; hoistHeight: number }) {
    this.equipmentMap.get(id)?.updatePose?.(pose)
    this.viewer.scene.requestRender()
  }

  /** Demo mode can take deterministic control of a vehicle without changing its source route. */
  setVehiclePose(id: string, pose: { cadX: number; cadY: number; headingDeg: number }) {
    this.gltfProvider?.updateStaticPose(id, pose.cadX, pose.cadY, pose.headingDeg)
    this.viewer.scene.requestRender()
  }

  setVehicleRouteAnimation(enabled: boolean) {
    if (enabled) this.vehicleAnimator?.start()
    else this.vehicleAnimator?.stop()
  }

  /** 调试：返回所有设备当前状态摘要 */
  getStatusSummary(): { id: string; name: string; status: string }[] {
    return [...this.equipmentMap.values()].map((b) => ({
      id: b.def.id,
      name: b.def.name,
      status: b.def.status,
    }))
  }

  /** 状态模拟：每隔 N 秒随机切换设备状态 */
  startMockSimulation(intervalMs = 5000) {
    if (this.mockTimer) return
    const statuses: EquipmentDef['status'][] = ['ok', 'standby', 'fault', 'offline', 'maintenance']
    this.mockTimer = setInterval(() => {
      const ids = [...this.equipmentMap.keys()]
      const randomId = ids[Math.floor(Math.random() * ids.length)]
      const randomStatus = statuses[Math.floor(Math.random() * statuses.length)]
      this.setStatus(randomId, randomStatus)
    }, intervalMs)
  }

  stopMockSimulation() {
    if (this.mockTimer) {
      clearInterval(this.mockTimer)
      this.mockTimer = null
    }
  }

  destroy() {
    this.stopMockSimulation()
    this.vehicleAnimator?.stop()
    this.viewer.dataSources.remove(this.dataSource)
  }
}

export { STATUS_COLORS, STATUS_NAMES }
