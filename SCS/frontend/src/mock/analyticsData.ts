/** 统计分析 Mock 数据：默认场景 + 装卸区 A 风险突增场景 */
import type { AnalyticsDataset, AnalyticsEventItem } from '@/types/analytics'

function eventsDefault(): AnalyticsEventItem[] {
  return [
    { id: 'ALM-20260904-001', time: '09-04 13:21', type: '人员越界', area: '装卸区 A', target: '赵磊（P-1003）', level: '紧急', status: '处理中', durationSec: undefined, team: '装卸一班', deviceId: undefined },
    { id: 'ALM-20260904-002', time: '09-04 13:18', type: '未佩戴安全帽', area: '装卸区 B', target: '作业人员 P-1042', level: '严重', status: '已关闭', durationSec: 742, team: '装卸二班', deviceId: undefined },
    { id: 'ALM-20260904-003', time: '09-04 13:15', type: '设备距离风险', area: '箱区 B', target: 'G-CRANE-01 / 转运车 VEH-08', level: '严重', status: '待确认', durationSec: undefined, team: '设备保障班', deviceId: 'G-CRANE-01' },
    { id: 'ALM-20260904-004', time: '09-04 13:09', type: '人员异常', area: '车辆通道', target: '孙倩（P-1021）', level: '预警', status: '待派单', durationSec: undefined, team: '装卸一班', deviceId: undefined },
    { id: 'ALM-20260904-005', time: '09-04 12:58', type: '翻越护栏', area: '铁路装卸线 B', target: '外协人员 P-1077', level: '预警', status: '待处理', durationSec: undefined, team: '外协单位', deviceId: undefined },
    { id: 'ALM-20260904-006', time: '09-04 12:46', type: '视频设备异常', area: '装卸区 A', target: '摄像头 CAM-02', level: '一般', status: '处理中', durationSec: undefined, team: '设备保障班', deviceId: 'CAM-02' },
    { id: 'ALM-20260904-007', time: '09-04 12:31', type: '人员闯入', area: '翻箱机作业区', target: '外协人员 P-1068 / TIP-03', level: '严重', status: '待复核', durationSec: undefined, team: '外协单位', deviceId: 'TIP-02' },
    { id: 'ALM-20260904-008', time: '09-04 11:42', type: '设备异常', area: '机房', target: '边缘节点 EDGE-03', level: '一般', status: '已关闭', durationSec: 1188, team: '设备保障班', deviceId: undefined },
    { id: 'ALM-20260904-009', time: '09-04 11:36', type: '人员异常', area: '维修通道', target: '吴凯（P-1034）', level: '预警', status: '待确认', durationSec: undefined, team: '装卸二班', deviceId: undefined },
    { id: 'ALM-20260904-010', time: '09-04 11:20', type: '设备距离风险', area: '装卸区 A', target: 'G-CRANE-01 / G-CRANE-02', level: '严重', status: '已升级', durationSec: undefined, team: '设备保障班', deviceId: 'G-CRANE-01' },
    { id: 'ALM-20260903-014', time: '09-03 17:42', type: '人员越界', area: '装卸区 A', target: '王强（P-1008）', level: '预警', status: '已关闭', durationSec: 866, team: '装卸一班', deviceId: undefined },
    { id: 'ALM-20260903-011', time: '09-03 16:08', type: '设备距离风险', area: '车辆通道', target: '转运车辆 07 / 转运车 VEH-11', level: '严重', status: '已关闭', durationSec: 624, team: '装卸二班', deviceId: 'VEH-07' },
    { id: 'ALM-20260903-009', time: '09-03 15:22', type: '未佩戴安全帽', area: '装卸区 A', target: '李伟（P-1015）', level: '一般', status: '已关闭', durationSec: 540, team: '装卸一班', deviceId: undefined },
    { id: 'ALM-20260903-006', time: '09-03 14:05', type: '人员越界', area: '龙门吊作业区', target: '赵磊（P-1003）', level: '严重', status: '已关闭', durationSec: 912, team: '装卸一班', deviceId: undefined },
    { id: 'ALM-20260903-003', time: '09-03 10:48', type: '翻越护栏', area: '铁路装卸线 B', target: '外协人员 P-1077', level: '一般', status: '已关闭', durationSec: 1320, team: '外协单位', deviceId: undefined },
  ]
}

export function buildDefaultDataset(): AnalyticsDataset {
  return {
    kpi: [
      { key: 'total', label: '事件总数', value: '128', deltaPct: 12.4, direction: 'down', goodWhenDown: true, hint: '较上周期下降，整体风险趋稳', variant: 'hero' },
      { key: 'high', label: '高风险事件', value: '12', deltaPct: 3.2, direction: 'up', goodWhenDown: false, hint: '严重 11 · 紧急 1', variant: 'default' },
      { key: 'response', label: '平均响应时间', value: '2m 18s', deltaPct: 8.1, direction: 'down', goodWhenDown: true, hint: '确认速度较上周期变快', variant: 'time' },
      { key: 'close', label: '平均关闭时间', value: '16m 42s', deltaPct: 4.6, direction: 'down', goodWhenDown: true, hint: '处置闭环效率提升', variant: 'time' },
      { key: 'repeat', label: '重复风险事件', value: '8', deltaPct: 1.0, direction: 'flat', goodWhenDown: true, hint: '同对象 24h 内重复触发', variant: 'default' },
    ],
    trend: [
      { date: '08/29', total: 15, high: 2 },
      { date: '08/30', total: 18, high: 3 },
      { date: '08/31', total: 14, high: 1 },
      { date: '09/01', total: 20, high: 2 },
      { date: '09/02', total: 17, high: 1 },
      { date: '09/03', total: 22, high: 2 },
      { date: '09/04', total: 22, high: 1 },
    ],
    riskTypes: [
      { type: '人员越界', count: 32, high: 7 },
      { type: '设备距离风险', count: 26, high: 6 },
      { type: '未佩戴安全帽', count: 21, high: 2 },
      { type: '人员异常', count: 15, high: 1 },
      { type: '设备异常', count: 12, high: 0 },
      { type: '翻越护栏', count: 8, high: 2 },
    ],
    areas: [
      { area: '装卸区 A', total: 28, high: 6 },
      { area: '龙门吊作业区', total: 21, high: 5 },
      { area: '翻箱机作业区', total: 17, high: 3 },
      { area: '车辆通道', total: 12, high: 2 },
      { area: '箱区 B', total: 10, high: 1 },
      { area: '维修通道', total: 8, high: 0 },
    ],
    teams: [
      { team: '装卸一班', confirmSec: 92, arriveSec: 258, closeSec: 940, eventCount: 34 },
      { team: '装卸二班', confirmSec: 128, arriveSec: 381, closeSec: 1274, eventCount: 29 },
      { team: '安全管理班', confirmSec: 46, arriveSec: 174, closeSec: 720, eventCount: 18 },
      { team: '设备保障班', confirmSec: 104, arriveSec: 312, closeSec: 1080, eventCount: 26 },
      { team: '外协单位', confirmSec: 152, arriveSec: 426, closeSec: 1410, eventCount: 21 },
    ],
    devices: [
      {
        deviceId: 'VEH-07', name: '转运车辆 07', count: 12, primaryRisk: '设备距离风险', primaryCount: 9, trend: 'up', recentRisk: '09-04 11:20 间距 4.2m 触发严重预警',
        typeSplit: [{ type: '设备距离风险', count: 9 }, { type: '人员闯入', count: 2 }, { type: '设备异常', count: 1 }],
        recentEvents: [
          { time: '09-04 11:20', type: '设备距离风险', level: '严重' },
          { time: '09-03 16:08', type: '设备距离风险', level: '严重' },
          { time: '09-03 09:41', type: '设备距离风险', level: '预警' },
          { time: '09-02 18:12', type: '人员闯入', level: '预警' },
          { time: '09-02 10:05', type: '设备距离风险', level: '一般' },
        ],
      },
      {
        deviceId: 'G-CRANE-01', name: '龙门吊 01', count: 8, primaryRisk: '人员闯入', primaryCount: 5, trend: 'down', recentRisk: '09-04 13:21 人员进入作业半径',
        typeSplit: [{ type: '人员闯入', count: 5 }, { type: '设备距离风险', count: 3 }],
        recentEvents: [
          { time: '09-04 13:21', type: '人员闯入', level: '紧急' },
          { time: '09-04 11:20', type: '设备距离风险', level: '严重' },
          { time: '09-03 14:05', type: '人员闯入', level: '严重' },
          { time: '09-02 15:30', type: '人员闯入', level: '预警' },
          { time: '09-01 09:18', type: '设备距离风险', level: '预警' },
        ],
      },
      {
        deviceId: 'TIP-02', name: '翻箱机 02', count: 6, primaryRisk: '设备距离风险', primaryCount: 4, trend: 'flat', recentRisk: '09-04 12:31 区域人员闯入',
        typeSplit: [{ type: '设备距离风险', count: 4 }, { type: '人员闯入', count: 2 }],
        recentEvents: [
          { time: '09-04 12:31', type: '人员闯入', level: '严重' },
          { time: '09-03 17:02', type: '设备距离风险', level: '预警' },
          { time: '09-03 11:26', type: '设备距离风险', level: '预警' },
          { time: '09-02 16:44', type: '设备距离风险', level: '一般' },
          { time: '09-01 14:55', type: '设备距离风险', level: '一般' },
        ],
      },
      {
        deviceId: 'VEH-03', name: '转运车辆 03', count: 5, primaryRisk: '设备距离风险', primaryCount: 3, trend: 'up', recentRisk: '09-03 21:14 通道会车距离不足',
        typeSplit: [{ type: '设备距离风险', count: 3 }, { type: '设备异常', count: 2 }],
        recentEvents: [
          { time: '09-03 21:14', type: '设备距离风险', level: '预警' },
          { time: '09-03 08:32', type: '设备异常', level: '一般' },
          { time: '09-02 19:40', type: '设备距离风险', level: '一般' },
          { time: '09-01 17:22', type: '设备距离风险', level: '预警' },
          { time: '08-31 13:08', type: '设备异常', level: '一般' },
        ],
      },
      {
        deviceId: 'CAM-02', name: '摄像头 CAM-02', count: 4, primaryRisk: '视频设备异常', primaryCount: 4, trend: 'flat', recentRisk: '09-04 12:46 画面清晰度下降',
        typeSplit: [{ type: '视频设备异常', count: 4 }],
        recentEvents: [
          { time: '09-04 12:46', type: '视频设备异常', level: '一般' },
          { time: '09-03 10:12', type: '视频设备异常', level: '一般' },
          { time: '09-02 09:03', type: '视频设备异常', level: '一般' },
          { time: '08-31 16:40', type: '视频设备异常', level: '预警' },
          { time: '08-30 11:27', type: '视频设备异常', level: '一般' },
        ],
      },
    ],
    persons: [
      { personId: 'P-1003', name: '赵磊', team: '装卸一班', count: 3, mainType: '人员越界', recent: ['09-04 13:21 进入龙门吊作业区', '09-03 14:05 越过区域边界', '09-01 10:33 接近危险围栏'] },
      { personId: 'P-1008', name: '王强', team: '装卸一班', count: 3, mainType: '人员越界', recent: ['09-03 17:42 穿越车辆通道', '09-02 15:10 滞留装卸区 A', '08-31 09:26 越界提醒'] },
      { personId: 'P-1015', name: '李伟', team: '装卸一班', count: 2, mainType: '未佩戴安全帽', recent: ['09-03 15:22 未佩戴安全帽', '08-30 13:48 安全帽佩戴不规范'] },
      { personId: 'P-1021', name: '孙倩', team: '装卸二班', count: 2, mainType: '人员异常', recent: ['09-04 13:09 通道滞留超时', '09-01 18:37 进入临时管控区'] },
      { personId: 'P-1034', name: '周凯', team: '装卸二班', count: 2, mainType: '人员异常', recent: ['09-04 11:36 手环电量低', '08-30 16:12 维修通道长时间静止'] },
    ],
    levels: [
      { level: '一般', count: 68 },
      { level: '预警', count: 48 },
      { level: '严重', count: 11 },
      { level: '紧急', count: 1 },
    ],
    events: eventsDefault(),
  }
}

/** 风险突增场景：装卸区 A 最近一周风险明显上升 */
export function buildSurgeDataset(): AnalyticsDataset {
  const base = buildDefaultDataset()
  base.kpi = [
    { key: 'total', label: '事件总数', value: '155', deltaPct: 21.1, direction: 'up', goodWhenDown: false, hint: '装卸区 A 风险拉动总量上升', variant: 'hero' },
    { key: 'high', label: '高风险事件', value: '25', deltaPct: 108.3, direction: 'up', goodWhenDown: false, hint: '严重 22 · 紧急 3，需重点关注', variant: 'default' },
    { key: 'response', label: '平均响应时间', value: '3m 02s', deltaPct: 31.9, direction: 'up', goodWhenDown: false, hint: '事件激增导致响应变慢', variant: 'time' },
    { key: 'close', label: '平均关闭时间', value: '21m 10s', deltaPct: 26.7, direction: 'up', goodWhenDown: false, hint: '处置积压，闭环时间拉长', variant: 'time' },
    { key: 'repeat', label: '重复风险事件', value: '14', deltaPct: 75.0, direction: 'up', goodWhenDown: false, hint: '装卸区 A 重复触发集中', variant: 'default' },
  ]
  base.trend = [
    { date: '08/29', total: 15, high: 2 },
    { date: '08/30', total: 18, high: 3 },
    { date: '08/31', total: 14, high: 1 },
    { date: '09/01', total: 20, high: 2 },
    { date: '09/02', total: 26, high: 5 },
    { date: '09/03', total: 34, high: 8 },
    { date: '09/04', total: 28, high: 4 },
  ]
  base.riskTypes = [
    { type: '人员越界', count: 46, high: 14 },
    { type: '设备距离风险', count: 26, high: 6 },
    { type: '未佩戴安全帽', count: 23, high: 3 },
    { type: '人员异常', count: 17, high: 2 },
    { type: '设备异常', count: 12, high: 0 },
    { type: '翻越护栏', count: 9, high: 2 },
  ]
  base.areas = [
    { area: '装卸区 A', total: 46, high: 11 },
    { area: '龙门吊作业区', total: 21, high: 5 },
    { area: '翻箱机作业区', total: 17, high: 3 },
    { area: '车辆通道', total: 12, high: 2 },
    { area: '箱区 B', total: 10, high: 1 },
    { area: '维修通道', total: 8, high: 0 },
  ]
  base.levels = [
    { level: '一般', count: 76 },
    { level: '预警', count: 54 },
    { level: '严重', count: 22 },
    { level: '紧急', count: 3 },
  ]
  // 明细前置 3 条装卸区 A 新增越界事件
  const surgeEvents: AnalyticsEventItem[] = [
    { id: 'ALM-20260904-021', time: '09-04 13:42', type: '人员越界', area: '装卸区 A', target: '王强（P-1008）', level: '严重', status: '待确认', durationSec: undefined, team: '装卸一班', deviceId: undefined },
    { id: 'ALM-20260904-020', time: '09-04 13:36', type: '人员越界', area: '装卸区 A', target: '外协人员 P-1091', level: '紧急', status: '处理中', durationSec: undefined, team: '外协单位', deviceId: undefined },
    { id: 'ALM-20260903-019', time: '09-03 18:02', type: '人员越界', area: '装卸区 A', target: '赵磊（P-1003）', level: '严重', status: '已关闭', durationSec: 688, team: '装卸一班', deviceId: undefined },
  ]
  base.events = [...surgeEvents, ...base.events]
  return base
}
