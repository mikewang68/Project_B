/**
 * B 项目六系统统一功能菜单与权限树
 *
 * 覆盖：数字孪生(dt)、生产调度(pps)、仓库管理(wms)、设备健康(ehealth)、
 *       能源管控(ems)、安全卡控(safety)，以及本统一身份与权限管理(iam)。
 *
 * 权限编码规则：<系统前缀>:<二级菜单>:<功能点>:<操作>
 * 操作类型：view / add / edit / delete / execute / export / import / approve
 *
 * 本文件是前端动态菜单渲染、按钮级 v-perm 指令、角色授权页面的唯一数据来源。
 */

export type PermOp = 'view' | 'add' | 'edit' | 'delete' | 'execute' | 'export' | 'import' | 'approve'

export interface PermSet {
  view?: string
  add?: string
  edit?: string
  delete?: string
  execute?: string
  export?: string
  import?: string
  approve?: string
}

export interface MenuNode {
  id: string
  name: string
  system: string
  path?: string
  icon?: string
  children?: MenuNode[]
  perms?: PermSet
}

/** 生成叶子节点的标准权限编码 */
function perms(base: string, ops: PermOp[] = ['view']): PermSet {
  const result: PermSet = {}
  for (const op of ops) {
    result[op] = `${base}:${op}`
  }
  return result
}

/** 快速构造叶子节点 */
function leaf(id: string, name: string, system: string, base: string, ops: PermOp[] = ['view']): MenuNode {
  return { id, name, system, perms: perms(base, ops) }
}

/** 快速构造分组节点 */
function group(id: string, name: string, system: string, children: MenuNode[]): MenuNode {
  return { id, name, system, children }
}

// ============================================================================
// 系统 1：数字孪生可视化系统 (dt)
// ============================================================================
const dtSystem: MenuNode = {
  id: 'dt',
  name: '数字孪生系统',
  system: 'dt',
  icon: 'DataAnalysis',
  children: [
    group('dt-twin', '三维作业台', 'dt', [
      leaf('dt-twin-scene', '三维场景浏览', 'dt', 'dt:twin:scene', ['view', 'execute']),
      leaf('dt-twin-device', '实时设备监控', 'dt', 'dt:twin:device', ['view', 'execute']),
      leaf('dt-twin-stock', '货物库存看板', 'dt', 'dt:twin:stock', ['view']),
      leaf('dt-twin-task', '作业进度与演示', 'dt', 'dt:twin:task', ['view', 'execute']),
      leaf('dt-twin-person', '场内人员定位', 'dt', 'dt:twin:person', ['view']),
    ]),
    group('dt-screen', '综合态势大屏', 'dt', [
      leaf('dt-screen-view', '大屏查看', 'dt', 'dt:screen:view', ['view']),
      leaf('dt-screen-export', '大屏截图导出', 'dt', 'dt:screen:export', ['export']),
    ]),
    group('dt-calib', '落位校核', 'dt', [
      leaf('dt-calib-cad', 'CAD 落位校核', 'dt', 'dt:calib:cad', ['view']),
      leaf('dt-calib-asset', '模型校准与资产替换', 'dt', 'dt:calib:asset', ['view', 'edit', 'execute']),
    ]),
    group('dt-device', '设备管理', 'dt', [
      leaf('dt-device-ledger', '设备台账', 'dt', 'dt:device:ledger', ['view', 'add', 'edit', 'delete', 'import', 'export']),
      leaf('dt-device-type', '设备类型', 'dt', 'dt:device:type', ['view', 'add', 'edit', 'delete']),
      leaf('dt-device-monitor', '实时监控', 'dt', 'dt:device:monitor', ['view']),
      leaf('dt-device-control', '远程控制', 'dt', 'dt:device:control', ['execute']),
    ]),
    group('dt-alert', '告警管理', 'dt', [
      leaf('dt-alert-realtime', '实时告警', 'dt', 'dt:alert:realtime', ['view', 'execute']),
      leaf('dt-alert-history', '历史告警', 'dt', 'dt:alert:history', ['view', 'export']),
      leaf('dt-alert-rule', '告警规则', 'dt', 'dt:alert:rule', ['view', 'add', 'edit', 'delete', 'execute']),
    ]),
    group('dt-task', '作业调度', 'dt', [
      leaf('dt-task-job', '作业任务', 'dt', 'dt:task:job', ['view', 'add', 'edit', 'delete', 'execute']),
      leaf('dt-task-route', '作业路线', 'dt', 'dt:task:route', ['view', 'add', 'edit', 'delete']),
      leaf('dt-task-approve', '调度审批', 'dt', 'dt:task:approve', ['view', 'approve']),
    ]),
    group('dt-stock', '库存管理', 'dt', [
      leaf('dt-stock-bay', '仓位管理', 'dt', 'dt:stock:bay', ['view', 'add', 'edit', 'delete']),
      leaf('dt-stock-batch', '物料批次', 'dt', 'dt:stock:batch', ['view', 'add', 'edit', 'delete', 'export']),
      leaf('dt-stock-io', '出入库与盘点', 'dt', 'dt:stock:io', ['view', 'add', 'execute', 'export']),
    ]),
  ],
}

// ============================================================================
// 系统 2：生产调度管理系统 (pps)
// ============================================================================
const ppsSystem: MenuNode = {
  id: 'pps',
  name: '生产调度系统',
  system: 'pps',
  icon: 'Schedule',
  children: [
    group('pps-monitor', '调度监控', 'pps', [
      leaf('pps-monitor-overview', '调度总览', 'pps', 'pps:monitor:overview', ['view']),
      leaf('pps-monitor-flow', '全流程监控', 'pps', 'pps:monitor:flow', ['view']),
    ]),
    group('pps-prepare', '外部信息与生产准备', 'pps', [
      leaf('pps-prepare-arrival', '外部到发信息台账', 'pps', 'pps:prepare:arrival', ['view', 'import']),
      leaf('pps-prepare-suggest', '生产准备建议', 'pps', 'pps:prepare:suggest', ['view', 'edit', 'execute']),
    ]),
    group('pps-task', '任务调度', 'pps', [
      leaf('pps-task-split', '任务拆解', 'pps', 'pps:task:split', ['view', 'add', 'edit', 'execute']),
      leaf('pps-task-dispatch', '派工看板', 'pps', 'pps:task:dispatch', ['view', 'execute']),
    ]),
    group('pps-road', '公路集疏运', 'pps', [
      leaf('pps-road-appoint', '公路预约与叫号', 'pps', 'pps:road:appoint', ['view', 'add', 'edit', 'execute']),
    ]),
    group('pps-safety', '异常与安全', 'pps', [
      leaf('pps-safety-handle', '异常处置', 'pps', 'pps:safety:handle', ['view', 'execute']),
      leaf('pps-safety-interlock', '安全联锁', 'pps', 'pps:safety:interlock', ['view', 'execute', 'approve']),
    ]),
    group('pps-report', '协同与报表', 'pps', [
      leaf('pps-report-sync', '离线同步', 'pps', 'pps:report:sync', ['view', 'execute']),
      leaf('pps-report-stat', '统计报表', 'pps', 'pps:report:stat', ['view', 'export']),
    ]),
    group('pps-system', '系统管理', 'pps', [
      leaf('pps-system-config', '系统配置', 'pps', 'pps:system:config', ['view', 'edit', 'approve']),
      leaf('pps-system-audit', '审计日志', 'pps', 'pps:system:audit', ['view', 'export']),
    ]),
  ],
}

// ============================================================================
// 系统 3：仓库管理系统 (wms)
// ============================================================================
const wmsSystem: MenuNode = {
  id: 'wms',
  name: '仓库管理系统',
  system: 'wms',
  icon: 'Box',
  children: [
    group('wms-dashboard', '仓储工作台', 'wms', [
      leaf('wms-dashboard-view', '工作台概览', 'wms', 'wms:dashboard:view', ['view']),
    ]),
    group('wms-inventory', '库存管理', 'wms', [
      leaf('wms-inventory-balance', '库存余额', 'wms', 'wms:inventory:balance', ['view', 'export']),
      leaf('wms-inventory-trans', '操作流水', 'wms', 'wms:inventory:trans', ['view', 'export']),
      leaf('wms-inventory-warn', '库存预警', 'wms', 'wms:inventory:warn', ['view']),
      leaf('wms-inventory-rule', '补货规则', 'wms', 'wms:inventory:rule', ['view', 'add', 'edit', 'delete', 'execute']),
      leaf('wms-inventory-op', '库存操作(调整/移库/冻结/盘点)', 'wms', 'wms:inventory:op', ['view', 'execute']),
    ]),
    group('wms-master', '基础资料', 'wms', [
      leaf('wms-master-warehouse', '仓库/库区/工作区/库位', 'wms', 'wms:master:warehouse', ['view', 'add', 'edit', 'delete']),
      leaf('wms-master-owner', '货主/合作伙伴', 'wms', 'wms:master:owner', ['view', 'add', 'edit', 'delete']),
      leaf('wms-master-goods', '货类/货品/组合件', 'wms', 'wms:master:goods', ['view', 'add', 'edit', 'delete', 'import']),
    ]),
    group('wms-stockin', '入库管理', 'wms', [
      leaf('wms-stockin-order', '入库单管理', 'wms', 'wms:stockin:order', ['view', 'add', 'edit', 'delete', 'execute', 'import']),
      leaf('wms-stockin-receive', '收货作业', 'wms', 'wms:stockin:receive', ['view', 'execute']),
    ]),
    group('wms-stockout', '出库管理', 'wms', [
      leaf('wms-stockout-order', '出库单管理', 'wms', 'wms:stockout:order', ['view', 'add', 'edit', 'delete', 'execute', 'import']),
      leaf('wms-stockout-pick', '分配/拣货/装箱/发运', 'wms', 'wms:stockout:pick', ['view', 'execute']),
      leaf('wms-stockout-wave', '波次出库', 'wms', 'wms:stockout:wave', ['view', 'execute']),
    ]),
    group('wms-finance', '财务统计', 'wms', [
      leaf('wms-finance-money', '费用单与收付款', 'wms', 'wms:finance:money', ['view', 'add', 'edit', 'execute']),
      leaf('wms-finance-account', '收付款账户', 'wms', 'wms:finance:account', ['view', 'add', 'edit']),
      leaf('wms-finance-report', '经营统计', 'wms', 'wms:finance:report', ['view', 'export']),
    ]),
    group('wms-integration', '导入导出与集成', 'wms', [
      leaf('wms-integration-task', 'Excel 导入导出任务', 'wms', 'wms:integration:task', ['view', 'import', 'export']),
      leaf('wms-integration-qimen', '奇门接口配置与日志', 'wms', 'wms:integration:qimen', ['view', 'edit', 'execute']),
    ]),
  ],
}

// ============================================================================
// 系统 4：设备健康管理系统 (ehealth) —— 10 组 65 功能点
// ============================================================================
const ehealthSystem: MenuNode = {
  id: 'ehealth',
  name: '设备健康管理系统',
  system: 'ehealth',
  icon: 'Cpu',
  children: [
    group('eh-workbench', '工作台', 'ehealth', [
      leaf('eh-wb-cockpit', '综合驾驶舱', 'ehealth', 'ehealth:wb:cockpit', ['view']),
      leaf('eh-wb-todo', '我的待办', 'ehealth', 'ehealth:wb:todo', ['view', 'execute']),
      leaf('eh-wb-shift', '班组交接', 'ehealth', 'ehealth:wb:shift', ['view', 'add', 'edit']),
    ]),
    group('eh-runtime', '运行态势', 'ehealth', [
      leaf('eh-rt-group', '全场设备群态势', 'ehealth', 'ehealth:rt:group', ['view']),
      leaf('eh-rt-dist', '区域/设备分布', 'ehealth', 'ehealth:rt:dist', ['view']),
      leaf('eh-rt-realtime', '实时监测', 'ehealth', 'ehealth:rt:realtime', ['view', 'export']),
      leaf('eh-rt-diagnosis', '诊断分析工作台', 'ehealth', 'ehealth:rt:diagnosis', ['view', 'execute']),
    ]),
    group('eh-asset', '资产中心', 'ehealth', [
      leaf('eh-as-tree', '资产树与设备台账', 'ehealth', 'ehealth:asset:tree', ['view', 'add', 'edit', 'delete', 'import']),
      leaf('eh-as-archive', '设备档案', 'ehealth', 'ehealth:asset:archive', ['view', 'add', 'edit', 'delete']),
      leaf('eh-as-bom', '部件 BOM', 'ehealth', 'ehealth:asset:bom', ['view', 'add', 'edit', 'delete']),
      leaf('eh-as-point', '测点管理', 'ehealth', 'ehealth:asset:point', ['view', 'add', 'edit', 'delete', 'import']),
      leaf('eh-as-template', '设备模板', 'ehealth', 'ehealth:asset:template', ['view', 'add', 'edit', 'delete']),
      leaf('eh-as-sensor', '传感器与计量校准', 'ehealth', 'ehealth:asset:sensor', ['view', 'add', 'edit', 'delete', 'execute']),
      leaf('eh-as-fmeca', 'FMECA/风险登记册', 'ehealth', 'ehealth:asset:fmeca', ['view', 'add', 'edit', 'delete']),
      leaf('eh-as-history', '配置变更与数字履历', 'ehealth', 'ehealth:asset:history', ['view', 'add', 'edit']),
    ]),
    group('eh-edge', '数据与边缘', 'ehealth', [
      leaf('eh-ed-accept', '接入发现与验收', 'ehealth', 'ehealth:edge:accept', ['view', 'execute']),
      leaf('eh-ed-gateway', '边缘网关', 'ehealth', 'ehealth:edge:gateway', ['view', 'edit', 'execute']),
      leaf('eh-ed-protocol', '协议/驱动与点表', 'ehealth', 'ehealth:edge:protocol', ['view', 'add', 'edit', 'delete']),
      leaf('eh-ed-quality', '数据质量', 'ehealth', 'ehealth:edge:quality', ['view', 'execute']),
      leaf('eh-ed-collect', '采集与波形策略', 'ehealth', 'ehealth:edge:collect', ['view', 'edit', 'execute']),
      leaf('eh-ed-lineage', '数据血缘与分层存储', 'ehealth', 'ehealth:edge:lineage', ['view']),
      leaf('eh-ed-sim', '仿真与数据回放', 'ehealth', 'ehealth:edge:sim', ['view', 'execute']),
    ]),
    group('eh-alert', '告警与诊断', 'ehealth', [
      leaf('eh-al-center', '实时告警中心', 'ehealth', 'ehealth:alert:center', ['view', 'execute']),
      leaf('eh-al-rule', '告警规则', 'ehealth', 'ehealth:alert:rule', ['view', 'add', 'edit', 'delete', 'execute']),
      leaf('eh-al-evidence', '告警证据包', 'ehealth', 'ehealth:alert:evidence', ['view', 'export']),
      leaf('eh-al-rca', '根因分析', 'ehealth', 'ehealth:alert:rca', ['view', 'edit', 'approve']),
      leaf('eh-al-kb', '故障知识库/相似案例', 'ehealth', 'ehealth:alert:kb', ['view', 'add', 'edit', 'delete']),
      leaf('eh-al-code', '故障编码体系', 'ehealth', 'ehealth:alert:code', ['view', 'add', 'edit', 'delete']),
      leaf('eh-al-sla', 'SLA 与升级策略', 'ehealth', 'ehealth:alert:sla', ['view', 'add', 'edit', 'delete']),
    ]),
    group('eh-health', '健康与预测', 'ehealth', [
      leaf('eh-hl-score', '健康评估', 'ehealth', 'ehealth:health:score', ['view', 'execute']),
      leaf('eh-hl-baseline', '健康基线', 'ehealth', 'ehealth:health:baseline', ['view', 'add', 'edit', 'delete']),
      leaf('eh-hl-trend', '劣化趋势', 'ehealth', 'ehealth:health:trend', ['view', 'export']),
      leaf('eh-hl-rul', 'RUL 预测', 'ehealth', 'ehealth:health:rul', ['view', 'execute']),
      leaf('eh-hl-suggest', '维护建议与风险排序', 'ehealth', 'ehealth:health:suggest', ['view', 'execute']),
    ]),
    group('eh-maint', '维保管理', 'ehealth', [
      leaf('eh-mt-strategy', '维保策略', 'ehealth', 'ehealth:maint:strategy', ['view', 'add', 'edit', 'delete']),
      leaf('eh-mt-plan', '维保计划/日历', 'ehealth', 'ehealth:maint:plan', ['view', 'add', 'edit', 'delete', 'approve']),
      leaf('eh-mt-order', '工单中心', 'ehealth', 'ehealth:maint:order', ['view', 'add', 'edit', 'execute', 'approve']),
      leaf('eh-mt-check', '点检管理', 'ehealth', 'ehealth:maint:check', ['view', 'add', 'edit', 'execute']),
      leaf('eh-mt-record', '维修记录与复测', 'ehealth', 'ehealth:maint:record', ['view', 'add', 'edit', 'execute']),
      leaf('eh-mt-opportunity', '机会维护与资源负荷', 'ehealth', 'ehealth:maint:opportunity', ['view', 'execute']),
      leaf('eh-mt-spare', '备件需求建议', 'ehealth', 'ehealth:maint:spare', ['view', 'execute', 'export']),
      leaf('eh-mt-outsource', '外委服务与质保', 'ehealth', 'ehealth:maint:outsource', ['view', 'add', 'edit', 'delete']),
    ]),
    group('eh-report', '分析报表', 'ehealth', [
      leaf('eh-rp-kpi', 'KPI 总览', 'ehealth', 'ehealth:report:kpi', ['view', 'export']),
      leaf('eh-rp-reliability', '可靠性分析', 'ehealth', 'ehealth:report:reliability', ['view', 'export']),
      leaf('eh-rp-oee', 'OEE 分析', 'ehealth', 'ehealth:report:oee', ['view', 'export']),
      leaf('eh-rp-fault', '故障分析', 'ehealth', 'ehealth:report:fault', ['view', 'export']),
      leaf('eh-rp-cost', '维保成本', 'ehealth', 'ehealth:report:cost', ['view', 'export']),
      leaf('eh-rp-coverage', '监测覆盖报告', 'ehealth', 'ehealth:report:coverage', ['view', 'export']),
      leaf('eh-rp-forecast', '风险与资源预测', 'ehealth', 'ehealth:report:forecast', ['view', 'export']),
      leaf('eh-rp-energy', '能耗—负载—健康关联', 'ehealth', 'ehealth:report:energy', ['view', 'export']),
      leaf('eh-rp-period', '日报/周报/月报', 'ehealth', 'ehealth:report:period', ['view', 'export']),
    ]),
    group('eh-model', '算法与模型', 'ehealth', [
      leaf('eh-md-rule', '规则与阈值版本', 'ehealth', 'ehealth:model:rule', ['view', 'add', 'edit', 'delete', 'execute', 'approve']),
      leaf('eh-md-registry', '模型注册表', 'ehealth', 'ehealth:model:registry', ['view', 'add', 'edit', 'delete']),
      leaf('eh-md-dataset', '数据集与标签', 'ehealth', 'ehealth:model:dataset', ['view', 'add', 'edit', 'delete', 'import']),
      leaf('eh-md-lineage', '推理血缘', 'ehealth', 'ehealth:model:lineage', ['view']),
      leaf('eh-md-shadow', '影子评估', 'ehealth', 'ehealth:model:shadow', ['view', 'execute']),
      leaf('eh-md-release', '发布/灰度/回滚', 'ehealth', 'ehealth:model:release', ['view', 'execute', 'approve']),
      leaf('eh-md-drift', '模型绩效与漂移', 'ehealth', 'ehealth:model:drift', ['view', 'export']),
    ]),
    group('eh-system', '集成与系统', 'ehealth', [
      leaf('eh-sys-api', '外部接口/API/事件订阅', 'ehealth', 'ehealth:system:api', ['view', 'add', 'edit', 'delete']),
      leaf('eh-sys-user', '用户、角色与数据权限', 'ehealth', 'ehealth:system:user', ['view', 'add', 'edit', 'delete']),
      leaf('eh-sys-dict', '参数与字典配置', 'ehealth', 'ehealth:system:dict', ['view', 'add', 'edit', 'delete']),
      leaf('eh-sys-approve', '审批中心', 'ehealth', 'ehealth:system:approve', ['view', 'approve']),
      leaf('eh-sys-log', '操作/登录/接口/异常日志', 'ehealth', 'ehealth:system:log', ['view', 'export']),
      leaf('eh-sys-evidence', '审计证据包', 'ehealth', 'ehealth:system:evidence', ['view', 'export']),
      leaf('eh-sys-monitor', '服务监控、备份与韧性演练', 'ehealth', 'ehealth:system:monitor', ['view', 'execute']),
    ]),
  ],
}

// ============================================================================
// 系统 5：能源管控系统 (ems) —— 7 组
// ============================================================================
const emsSystem: MenuNode = {
  id: 'ems',
  name: '能源管控系统',
  system: 'ems',
  icon: 'Lightning',
  children: [
    group('ems-overview', '能源总览', 'ems', [
      leaf('ems-ov-cockpit', '能源驾驶舱', 'ems', 'ems:overview:cockpit', ['view']),
      leaf('ems-ov-trend', '能耗趋势与基线对比', 'ems', 'ems:overview:trend', ['view']),
      leaf('ems-ov-top', '重点用能对象', 'ems', 'ems:overview:top', ['view']),
      leaf('ems-ov-summary', '告警与质量摘要', 'ems', 'ems:overview:summary', ['view']),
      leaf('ems-ov-filter', '筛选与下钻', 'ems', 'ems:overview:filter', ['view', 'execute']),
    ]),
    group('ems-analysis', '能源分析', 'ems', [
      leaf('ems-an-multi', '多维统计分析', 'ems', 'ems:analysis:multi', ['view', 'export']),
      leaf('ems-an-profile', '设备能耗画像', 'ems', 'ems:analysis:profile', ['view']),
      leaf('ems-an-overlay', '状态叠加与同类对比', 'ems', 'ems:analysis:overlay', ['view']),
      leaf('ems-an-attr', '作业能耗归因', 'ems', 'ems:analysis:attr', ['view', 'export']),
      leaf('ems-an-baseline', '能源基线', 'ems', 'ems:analysis:baseline', ['view', 'add', 'edit', 'delete']),
      leaf('ems-an-forecast', '短期能耗预测', 'ems', 'ems:analysis:forecast', ['view', 'execute']),
    ]),
    group('ems-collect', '采集与质量', 'ems', [
      leaf('ems-co-ledger', '用能对象与采集点台账', 'ems', 'ems:collect:ledger', ['view', 'add', 'edit', 'delete', 'import']),
      leaf('ems-co-raw', '原始数据查询', 'ems', 'ems:collect:raw', ['view', 'export']),
      leaf('ems-co-quality', '数据质量监测', 'ems', 'ems:collect:quality', ['view', 'execute']),
      leaf('ems-co-task', '采集任务监测', 'ems', 'ems:collect:task', ['view']),
      leaf('ems-co-repair', '补传处理', 'ems', 'ems:collect:repair', ['view', 'execute']),
      leaf('ems-co-recalc', '统计重算与差异', 'ems', 'ems:collect:recalc', ['view', 'execute']),
      leaf('ems-co-external', '外置传感器接入配置', 'ems', 'ems:collect:external', ['view', 'add', 'edit', 'delete']),
    ]),
    group('ems-alert', '异常与建议', 'ems', [
      leaf('ems-al-list', '异常告警列表', 'ems', 'ems:alert:list', ['view', 'export']),
      leaf('ems-al-detail', '告警详情与证据', 'ems', 'ems:alert:detail', ['view']),
      leaf('ems-al-flow', '告警处置流转', 'ems', 'ems:alert:flow', ['view', 'execute']),
      leaf('ems-al-rule', '告警规则与通知配置', 'ems', 'ems:alert:rule', ['view', 'add', 'edit', 'delete']),
      leaf('ems-al-suggest', '节能建议看板', 'ems', 'ems:alert:suggest', ['view', 'execute']),
      leaf('ems-al-exec', '建议执行与关闭', 'ems', 'ems:alert:exec', ['view', 'edit', 'execute']),
      leaf('ems-al-verify', '节能效果验证', 'ems', 'ems:alert:verify', ['view', 'export']),
      leaf('ems-al-archive', '归档与月度复盘', 'ems', 'ems:alert:archive', ['view', 'export']),
    ]),
    group('ems-cost', '成本与报表', 'ems', [
      leaf('ems-ct-account', '成本核算', 'ems', 'ems:cost:account', ['view', 'export']),
      leaf('ems-ct-peak', '峰平谷成本分析', 'ems', 'ems:cost:peak', ['view', 'export']),
      leaf('ems-ct-price', '单价版本', 'ems', 'ems:cost:price', ['view', 'add', 'edit', 'delete']),
      leaf('ems-ct-share', '分摊规则', 'ems', 'ems:cost:share', ['view', 'add', 'edit', 'delete']),
      leaf('ems-ct-trace', '成本追溯与重算复核', 'ems', 'ems:cost:trace', ['view', 'execute', 'approve']),
      leaf('ems-ct-convert', '成本异常转建议', 'ems', 'ems:cost:convert', ['view', 'execute']),
      leaf('ems-ct-daily', '能源日报', 'ems', 'ems:cost:daily', ['view', 'export']),
      leaf('ems-ct-monthly', '能源月报', 'ems', 'ems:cost:monthly', ['view', 'export']),
      leaf('ems-ct-special', '专项报表', 'ems', 'ems:cost:special', ['view', 'export']),
      leaf('ems-ct-export', '导出与冻结归档', 'ems', 'ems:cost:export', ['view', 'export', 'execute']),
      leaf('ems-ct-subscribe', '报表订阅', 'ems', 'ems:cost:subscribe', ['view', 'add', 'edit', 'delete']),
    ]),
    group('ems-ai', '智能辅助', 'ems', [
      leaf('ems-ai-chat', '智能问数', 'ems', 'ems:ai:chat', ['view', 'execute']),
      leaf('ems-ai-feedback', '问数查询反馈', 'ems', 'ems:ai:feedback', ['view', 'edit']),
      leaf('ems-ai-inspect', 'AI 巡检', 'ems', 'ems:ai:inspect', ['view', 'execute']),
      leaf('ems-ai-report', '巡检报告', 'ems', 'ems:ai:report', ['view', 'export']),
    ]),
    group('ems-system', '系统管理', 'ems', [
      leaf('ems-sys-user', '用户与角色', 'ems', 'ems:system:user', ['view', 'add', 'edit', 'delete']),
      leaf('ems-sys-scope', '数据范围权限', 'ems', 'ems:system:scope', ['view', 'edit']),
      leaf('ems-sys-approve', '审批管理', 'ems', 'ems:system:approve', ['view', 'approve']),
      leaf('ems-sys-audit', '操作与安全审计', 'ems', 'ems:system:audit', ['view', 'export']),
      leaf('ems-sys-health', '接口健康监控', 'ems', 'ems:system:health', ['view', 'execute']),
      leaf('ems-sys-map', '接口协同与映射', 'ems', 'ems:system:map', ['view', 'add', 'edit', 'delete']),
      leaf('ems-sys-ops', '运行运维', 'ems', 'ems:system:ops', ['view', 'execute']),
      leaf('ems-sys-config', '基础配置', 'ems', 'ems:system:config', ['view', 'edit']),
    ]),
  ],
}

// ============================================================================
// 系统 6：装卸作业安全卡控系统 (safety)
// ============================================================================
const safetySystem: MenuNode = {
  id: 'safety',
  name: '安全卡控系统',
  system: 'safety',
  icon: 'Warning',
  children: [
    group('safety-home', '安全态势首页', 'safety', [
      leaf('safety-hm-indicator', '安全指标', 'safety', 'safety:home:indicator', ['view']),
      leaf('safety-hm-map', '站场安全态势', 'safety', 'safety:home:map', ['view']),
      leaf('safety-hm-alert', '实时告警', 'safety', 'safety:home:alert', ['view', 'execute']),
      leaf('safety-hm-analysis', '数据分析', 'safety', 'safety:home:analysis', ['view', 'export']),
      leaf('safety-hm-sim', '风险模拟', 'safety', 'safety:home:sim', ['view', 'execute']),
    ]),
    group('safety-person', '人员定位', 'safety', [
      leaf('safety-ps-search', '查询与筛选', 'safety', 'safety:person:search', ['view']),
      leaf('safety-ps-map', '人员地图', 'safety', 'safety:person:map', ['view']),
      leaf('safety-ps-detail', '人员详情', 'safety', 'safety:person:detail', ['view', 'execute']),
      leaf('safety-ps-track', '轨迹回放', 'safety', 'safety:person:track', ['view', 'execute']),
      leaf('safety-ps-sim', '人员异常模拟', 'safety', 'safety:person:sim', ['view', 'execute']),
    ]),
    group('safety-fence', '电子围栏', 'safety', [
      leaf('safety-fc-list', '围栏列表与状态', 'safety', 'safety:fence:list', ['view']),
      leaf('safety-fc-map', '围栏地图与详情', 'safety', 'safety:fence:map', ['view']),
      leaf('safety-fc-create', '新建围栏', 'safety', 'safety:fence:create', ['view', 'add', 'edit']),
      leaf('safety-fc-publish', '围栏发布与下发', 'safety', 'safety:fence:publish', ['view', 'execute', 'approve']),
      leaf('safety-fc-retry', '发布异常与重新下发', 'safety', 'safety:fence:retry', ['view', 'execute']),
    ]),
    group('safety-module', '扩展模块(规划中)', 'safety', [
      leaf('safety-md-collision', '设备防碰撞', 'safety', 'safety:module:collision', ['view', 'execute']),
      leaf('safety-md-ai', 'AI 违规识别', 'safety', 'safety:module:ai', ['view', 'execute']),
      leaf('safety-md-alert', '告警中心', 'safety', 'safety:module:alert', ['view', 'execute', 'export']),
      leaf('safety-md-stat', '统计分析', 'safety', 'safety:module:stat', ['view', 'export']),
      leaf('safety-md-rule', '规则配置', 'safety', 'safety:module:rule', ['view', 'add', 'edit', 'delete']),
      leaf('safety-md-ops', '运维监控', 'safety', 'safety:module:ops', ['view', 'execute']),
    ]),
  ],
}

// ============================================================================
// 系统 7：统一身份与权限管理 (iam) —— 本系统自身
// ============================================================================
const iamSystem: MenuNode = {
  id: 'iam',
  name: '统一身份与权限管理',
  system: 'iam',
  icon: 'User',
  children: [
    group('iam-auth', '认证管理', 'iam', [
      leaf('iam-auth-login', '登录认证', 'iam', 'iam:auth:login', ['view', 'execute']),
      leaf('iam-auth-password', '密码修改与重置', 'iam', 'iam:auth:password', ['view', 'edit', 'execute']),
    ]),
    group('iam-user', '用户管理', 'iam', [
      leaf('iam-user-list', '用户列表', 'iam', 'iam:user:list', ['view', 'export']),
      leaf('iam-user-add', '新增用户', 'iam', 'iam:user:add', ['add']),
      leaf('iam-user-edit', '编辑用户', 'iam', 'iam:user:edit', ['edit']),
      leaf('iam-user-delete', '删除/停用用户', 'iam', 'iam:user:delete', ['delete', 'execute']),
      leaf('iam-user-role', '分配角色', 'iam', 'iam:user:role', ['edit', 'execute']),
    ]),
    group('iam-role', '角色管理', 'iam', [
      leaf('iam-role-list', '角色列表', 'iam', 'iam:role:list', ['view']),
      leaf('iam-role-add', '新增角色', 'iam', 'iam:role:add', ['add']),
      leaf('iam-role-edit', '编辑角色', 'iam', 'iam:role:edit', ['edit']),
      leaf('iam-role-delete', '删除角色', 'iam', 'iam:role:delete', ['delete']),
      leaf('iam-role-perm', '分配权限', 'iam', 'iam:role:perm', ['edit', 'execute']),
    ]),
    group('iam-menu', '菜单与权限管理', 'iam', [
      leaf('iam-menu-tree', '六系统菜单树', 'iam', 'iam:menu:tree', ['view']),
      leaf('iam-menu-perm', '权限点查看', 'iam', 'iam:menu:perm', ['view']),
      leaf('iam-menu-sync', '菜单同步与刷新', 'iam', 'iam:menu:sync', ['execute']),
    ]),
    group('iam-log', '审计日志', 'iam', [
      leaf('iam-log-login', '登录日志', 'iam', 'iam:log:login', ['view', 'export']),
      leaf('iam-log-op', '操作日志', 'iam', 'iam:log:op', ['view', 'export']),
    ]),
  ],
}

// 系统设置与维护（SYS）：独立模块，编码需与 SYS 工程 src/sys/sys-menu.ts 保持一致
const sysSystem: MenuNode = {
  id: 'sys',
  name: '系统设置与维护',
  system: 'sys',
  icon: 'Setting',
  children: [
    group('sys-dict', '数据字典', 'sys', [
      leaf('sys-dict-type', '字典分类', 'sys', 'sys:dict:type', ['view', 'add', 'edit', 'delete']),
      leaf('sys-dict-item', '字典项', 'sys', 'sys:dict:item', ['view', 'add', 'edit', 'delete']),
    ]),
    group('sys-log', '操作日志', 'sys', [
      leaf('sys-log-list', '日志查询', 'sys', 'sys:log:list', ['view', 'export']),
    ]),
    group('sys-config', '系统配置', 'sys', [
      leaf('sys-config-list', '参数配置', 'sys', 'sys:config:list', ['view', 'edit']),
    ]),
  ],
}

/** 完整的六业务系统 + IAM + SYS 统一菜单树（顶级节点） */
export const MENU_TREE: MenuNode[] = [
  iamSystem,
  sysSystem,
  dtSystem,
  ppsSystem,
  wmsSystem,
  ehealthSystem,
  emsSystem,
  safetySystem,
]

/**
 * 递归收集整棵树的所有权限编码（用于角色授权时的全量选项、权限校验）。
 * 返回 { code, name, system, nodeId } 列表。
 */
export interface PermItem {
  code: string
  name: string
  system: string
  nodeId: string
  op: PermOp
}

export function collectAllPerms(tree: MenuNode[] = MENU_TREE): PermItem[] {
  const result: PermItem[] = []
  const walk = (node: MenuNode) => {
    if (node.perms) {
      for (const op of Object.keys(node.perms) as PermOp[]) {
        const code = node.perms[op]
        if (code) {
          result.push({ code, name: node.name, system: node.system, nodeId: node.id, op })
        }
      }
    }
    if (node.children) {
      for (const child of node.children) walk(child)
    }
  }
  for (const root of tree) walk(root)
  return result
}

/** 根据权限编码列表过滤菜单树（用于前端动态菜单渲染） */
export function filterMenuByPerms(tree: MenuNode[], perms: Set<string>): MenuNode[] {
  const result: MenuNode[] = []
  for (const node of tree) {
    const hasDirectView = node.perms?.view ? perms.has(node.perms.view) : false
    const children = node.children ? filterMenuByPerms(node.children, perms) : []
    if (hasDirectView || children.length > 0) {
      result.push({ ...node, children: children.length > 0 ? children : undefined })
    }
  }
  return result
}

/**
 * 操作权限 → 必须连带拥有的查看权限。
 * IAM 的增删改是独立功能点（自身无 view），需联动对应列表的查看权限，
 * 避免出现"有新增按钮权限、却因缺少列表查看权限而进不去页面"的孤立授权。
 */
const EXTRA_VIEW_DEPS: Record<string, string[]> = {
  'iam:user:add:add': ['iam:user:list:view'],
  'iam:user:edit:edit': ['iam:user:list:view'],
  'iam:user:delete:delete': ['iam:user:list:view'],
  'iam:user:delete:execute': ['iam:user:list:view'],
  'iam:user:role:edit': ['iam:user:list:view'],
  'iam:user:role:execute': ['iam:user:list:view'],
  'iam:role:add:add': ['iam:role:list:view'],
  'iam:role:edit:edit': ['iam:role:list:view'],
  'iam:role:delete:delete': ['iam:role:list:view'],
  'iam:role:perm:edit': ['iam:role:list:view'],
  'iam:role:perm:execute': ['iam:role:list:view'],
  'iam:menu:sync:execute': ['iam:menu:tree:view'],
  'iam:auth:password:edit': ['iam:auth:login:view'],
  'iam:auth:password:execute': ['iam:auth:login:view'],
}

/**
 * 授权补全：勾选任意操作权限时，自动补上其查看权限，保证"能操作必先能看到"。
 * 规则1：同一功能点下，非 view 操作连带该功能点 view（六业务系统内聚功能点）。
 * 规则2：IAM 独立操作功能点按 EXTRA_VIEW_DEPS 联动对应列表 view。
 */
export function ensureViewPerms(codes: string[]): string[] {
  const allCodes = new Set(collectAllPerms().map((p) => p.code))
  const result = new Set(codes)
  for (const code of result) {
    const parts = code.split(':')
    if (parts.length === 4 && parts[3] !== 'view') {
      const sameBaseView = `${parts[0]}:${parts[1]}:${parts[2]}:view`
      if (allCodes.has(sameBaseView)) result.add(sameBaseView)
    }
    const extra = EXTRA_VIEW_DEPS[code]
    if (extra) extra.forEach((c) => allCodes.has(c) && result.add(c))
  }
  return Array.from(result)
}
