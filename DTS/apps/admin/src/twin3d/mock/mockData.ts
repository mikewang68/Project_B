/** Confirmed first-batch steel-line equipment.  Ash equipment stays out of the production mock until its line is rebuilt. */
import type { EquipmentDef } from '../constants'

export const MOCK_EQUIPMENT: EquipmentDef[] = [
  {
    id: 'CR-01',
    name: '龙门吊 1',
    type: 'gantry_crane',
    cadX: 3600.670,
    cadY: 1673.434,
    height: 24,
    status: 'standby',
    params: { 类型: '铁路钢材装卸龙门吊', 服务线: '钢材两轨作业线', 状态来源: '模拟/推断', 起升高度: '16m' },
  },
  {
    id: 'CR-02',
    name: '龙门吊 2',
    type: 'gantry_crane',
    cadX: 3727.235,
    cadY: 1673.434,
    height: 24,
    status: 'ok',
    params: { 类型: '铁路钢材装卸龙门吊', 服务线: '钢材两轨作业线', 状态来源: '模拟/推断', 起升高度: '16m' },
  },
  {
    id: 'CR-03',
    name: '龙门吊 3',
    type: 'gantry_crane',
    cadX: 3839.751,
    cadY: 1673.434,
    height: 24,
    status: 'standby',
    params: { 类型: '铁路钢材装卸龙门吊', 服务线: '钢材两轨作业线', 状态来源: '模拟/推断', 起升高度: '16m' },
  },
]
