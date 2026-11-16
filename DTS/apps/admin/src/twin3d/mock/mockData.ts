import type { EquipmentDef } from '../constants'
import { SITE_LAYOUT_V4 } from '../siteLayoutV4'

/**
 * 给大连大.dwg（siteLayoutV4）实测权威装备台账：
 * 含钢材区 3 台 40t/30m 门吊、散料区 3 台集装箱门吊、4 套移动翻箱机+提升机、
 * 室内代表性桥吊（9×20t磁吸 + 4×32t C型）、库底定量散装机及 100t 计量秤。
 */
export const MOCK_EQUIPMENT: EquipmentDef[] = [
  ...SITE_LAYOUT_V4.rebar.gantries.map((g, idx): EquipmentDef => ({
    id: g.id,
    name: g.name,
    type: 'gantry_crane',
    cadX: g.cadX,
    cadY: g.cadY,
    height: SITE_LAYOUT_V4.rebar.craneRunway.girderHeightM,
    status: idx === 1 ? 'ok' : 'standby',
    params: {
      型号: `Q=${g.capacityT}t, S=${g.spanM}m`,
      额定功率: `${g.powerKw} kW / 380V`,
      所属线别: '钢材装卸线（货3/货4）',
      控制子系统: '门式起重机控制系统',
    },
  })),
  ...SITE_LAYOUT_V4.ash.bulkGantries.map((g, idx): EquipmentDef => ({
    id: g.id,
    name: g.name,
    type: 'gantry_crane',
    cadX: g.cadX,
    cadY: g.cadY,
    height: SITE_LAYOUT_V4.ash.craneRunway.girderHeightM,
    status: idx === 0 ? 'ok' : 'standby',
    params: {
      型号: `Q=${g.capacityT}t, S=${g.spanM}m 集装箱专用`,
      额定功率: `${g.powerKw} kW / 380V`,
      所属线别: '散料装卸线（货1/货2）',
      控制子系统: '翻箱机与门吊协同系统',
    },
  })),
  ...SITE_LAYOUT_V4.ash.unloadingComplexes.map((u, idx): EquipmentDef => ({
    id: u.tipperId,
    name: `${idx + 1}# 移动式翻箱机`,
    type: 'mobile_tipper',
    cadX: u.tipperParkX,
    cadY: u.tipperRailY,
    height: 6.5,
    status: idx === 0 ? 'ok' : 'standby',
    params: {
      卸料室料斗: `${u.hopperCapacityM3} m³ 半封闭`,
      翻转角度: '0° ~ 135°（含振动拍打）',
      物料种类: u.material === 'cement' ? '水泥 (1.3t/m³)' : '粉煤灰 (1.1t/m³)',
      控制子系统: '翻箱机控制系统',
    },
  })),
  ...SITE_LAYOUT_V4.ash.unloadingComplexes.map((u, idx): EquipmentDef => ({
    id: u.elevatorId,
    name: `${idx + 1}# 高原斗式提升机`,
    type: 'elevator',
    cadX: u.elevatorCadX,
    cadY: u.elevatorCadY,
    height: u.elevatorHeightM,
    status: idx === 0 ? 'ok' : 'standby',
    params: {
      提升高度: `${u.elevatorHeightM} m（入仓顶斜槽）`,
      驱动功率: '185~264 kW 高原电机',
      配套输送: '空气斜槽 + 脉冲除尘',
      控制子系统: '智能入库控制系统',
    },
  })),
  ...SITE_LAYOUT_V4.rebar.bays.map((b, idx): EquipmentDef => ({
    id: b.bridgeCraneId,
    name: `${b.bayIndex}# 跨室内桥吊（${b.craneCapacityT}t）`,
    type: 'bridge_crane',
    cadX: b.cadX,
    cadY: b.bridgeParkY,
    height: b.railHeightM,
    status: idx === 0 || idx === 2 || idx === 6 ? 'ok' : 'standby',
    params: {
      型号: `Q=${b.craneCapacityT}t, S=${b.craneSpanM}m`,
      吊具类型: b.bridgeType === 'chook_32t' ? 'C型卷材吊具' : '电磁吸盘吊具',
      轨顶标高: `${b.railHeightM} m`,
      控制子系统: '门吊/桥吊控制系统',
    },
  })),
  {
    id: 'BC-MECH-01',
    name: '机电仓库 1# 50t 桥式起重机',
    type: 'bridge_crane',
    cadX: 4390.0,
    cadY: 1532.3,
    height: 10.5,
    status: 'ok',
    params: {
      型号: 'Q=50t, S=16.5m',
      额定功率: '110 kW / 380V',
      所属线别: '机电设备仓库（5,036 m²）',
      控制子系统: '门吊/桥吊控制系统',
    },
  },
  {
    id: 'BC-MECH-02',
    name: '机电仓库 2# 50t 桥式起重机',
    type: 'bridge_crane',
    cadX: 4435.0,
    cadY: 1532.3,
    height: 10.5,
    status: 'standby',
    params: {
      型号: 'Q=50t, S=16.5m',
      额定功率: '110 kW / 380V',
      所属线别: '机电设备仓库（5,036 m²）',
      控制子系统: '门吊/桥吊控制系统',
    },
  },
  ...[
    { id: 'LOADER-01', siloIdx: 0, label: '水泥仓库底定量散装机 #01', mat: '水泥仓' },
    { id: 'LOADER-02', siloIdx: 8, label: '粉煤灰一区库底散装机 #09', mat: '粉煤灰仓' },
    { id: 'LOADER-03', siloIdx: 16, label: '粉煤灰二区库底散装机 #17', mat: '粉煤灰仓' },
    { id: 'LOADER-04', siloIdx: 24, label: '粉煤灰三区库底散装机 #25', mat: '粉煤灰仓' },
  ].map((item, idx): EquipmentDef => {
    const s = SITE_LAYOUT_V4.ash.silos[item.siloIdx]
    return {
      id: item.id,
      name: item.label,
      type: 'silo_loader',
      cadX: s.cadX,
      cadY: s.cadY,
      height: 5.5,
      status: idx === 0 ? 'ok' : 'standby',
      params: {
        对应筒仓: `${s.id}（3000T ${item.mat}）`,
        单套功率: '10 kW（伸缩溜管+底阀定量）',
        安全联锁: '电子围栏 + 罐车静电接地',
        控制子系统: '散料智能入库/库底定量装车控制系统',
      },
    }
  }),
  ...SITE_LAYOUT_V4.weighbridges.map((w): EquipmentDef => ({
    id: w.id,
    name: w.name,
    type: 'weighbridge',
    cadX: w.cadX,
    cadY: w.cadY,
    height: 1.5,
    status: 'ok',
    params: {
      额定称重: `${w.capacityT} t`,
      作用: w.kind === 'truck_scale' ? '进出场公路车辆毛/皮重计量' : '铁路专用线车辆计量',
    },
  })),
]
