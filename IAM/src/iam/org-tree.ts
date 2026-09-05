/**
 * 组织架构数据源 —— 区域(Zone) → 公司(Company) → 部门(Dept) → 组(Group)
 *
 * 设计说明：
 *  - 用户的所属组织必须从该列表中**逐级选择**（el-cascader），不允许手动输入，保证口径统一。
 *  - 每个节点 code 全局唯一且稳定（后续对接后端组织表 /organization 时，直接以 code 映射 ID）。
 *  - 当前为内置 mock；后端就绪后把 ORG_TREE 换成接口返回即可，字段结构保持不变。
 */

export type OrgLevel = 'zone' | 'company' | 'dept' | 'group'

export interface OrgNode {
  /** 稳定编码，全局唯一，如 z-ne */
  code: string
  /** 显示名称 */
  name: string
  level: OrgLevel
  children?: OrgNode[]
}

/** 组织层级中文名（用于表单/表格文案） */
export const ORG_LEVEL_LABEL: Record<OrgLevel, string> = {
  zone: '区域',
  company: '公司',
  dept: '部门',
  group: '组',
}

/**
 * 组织树：区域 → 公司 → 部门 → 组（固定四级，叶子均为组）。
 * 结合 B 项目业务（生产调度/仓储/设备/安全/能源）与实际驻地（大连、绵阳）设置。
 */
export const ORG_TREE: OrgNode[] = [
  {
    code: 'z-ne',
    name: '东北区域',
    level: 'zone',
    children: [
      {
        code: 'c-dl-port',
        name: '大连智慧港务有限公司',
        level: 'company',
        children: [
          {
            code: 'd-pps',
            name: '生产调度部',
            level: 'dept',
            children: [
              { code: 'g-pps-1', name: '调度一班', level: 'group' },
              { code: 'g-pps-2', name: '调度二班', level: 'group' },
            ],
          },
          {
            code: 'd-wms',
            name: '仓储管理部',
            level: 'dept',
            children: [
              { code: 'g-wms-1', name: '入库班组', level: 'group' },
              { code: 'g-wms-2', name: '出库班组', level: 'group' },
              { code: 'g-wms-3', name: '库存管理组', level: 'group' },
            ],
          },
          {
            code: 'd-eh',
            name: '设备运维部',
            level: 'dept',
            children: [
              { code: 'g-eh-1', name: '机械维修班', level: 'group' },
              { code: 'g-eh-2', name: '电气维修班', level: 'group' },
            ],
          },
          {
            code: 'd-safety',
            name: '安全环保部',
            level: 'dept',
            children: [
              { code: 'g-sf-1', name: '安全巡查组', level: 'group' },
              { code: 'g-sf-2', name: '应急管理组', level: 'group' },
            ],
          },
          {
            code: 'd-ems',
            name: '能源管控部',
            level: 'dept',
            children: [{ code: 'g-em-1', name: '能源调度组', level: 'group' }],
          },
          {
            code: 'd-it',
            name: '信息技术部',
            level: 'dept',
            children: [
              { code: 'g-it-1', name: '系统运维组', level: 'group' },
              { code: 'g-it-2', name: '应用开发组', level: 'group' },
            ],
          },
        ],
      },
      {
        code: 'c-dl-steel',
        name: '大连钢材物流园',
        level: 'company',
        children: [
          {
            code: 'ds-pps',
            name: '生产调度部',
            level: 'dept',
            children: [{ code: 'gs-pps-1', name: '加工作业班', level: 'group' }],
          },
          {
            code: 'ds-wms',
            name: '仓储管理部',
            level: 'dept',
            children: [
              { code: 'gs-wms-1', name: '钢材堆场班', level: 'group' },
              { code: 'gs-wms-2', name: '装卸班组', level: 'group' },
            ],
          },
          {
            code: 'ds-eh',
            name: '设备运维部',
            level: 'dept',
            children: [{ code: 'gs-eh-1', name: '设备检修班', level: 'group' }],
          },
        ],
      },
    ],
  },
  {
    code: 'z-sw',
    name: '西南区域',
    level: 'zone',
    children: [
      {
        code: 'c-my',
        name: '绵阳智能制造基地',
        level: 'company',
        children: [
          {
            code: 'm-pps',
            name: '生产调度部',
            level: 'dept',
            children: [{ code: 'gm-pps-1', name: '计划调度组', level: 'group' }],
          },
          {
            code: 'm-wms',
            name: '仓储管理部',
            level: 'dept',
            children: [{ code: 'gm-wms-1', name: '立库运维组', level: 'group' }],
          },
          {
            code: 'm-eh',
            name: '设备运维部',
            level: 'dept',
            children: [{ code: 'gm-eh-1', name: '点检班组', level: 'group' }],
          },
          {
            code: 'm-it',
            name: '信息技术部',
            level: 'dept',
            children: [
              { code: 'gm-it-1', name: '应用开发组', level: 'group' },
              { code: 'gm-it-2', name: '网络安全组', level: 'group' },
            ],
          },
        ],
      },
    ],
  },
  {
    code: 'z-he',
    name: '华东区域',
    level: 'zone',
    children: [
      {
        code: 'c-nb',
        name: '宁波供应链中心',
        level: 'company',
        children: [
          {
            code: 'n-wms',
            name: '仓储管理部',
            level: 'dept',
            children: [{ code: 'gn-wms-1', name: '收发货组', level: 'group' }],
          },
          {
            code: 'n-ems',
            name: '能源管控部',
            level: 'dept',
            children: [{ code: 'gn-em-1', name: '能耗监控组', level: 'group' }],
          },
        ],
      },
    ],
  },
]

/** el-cascader 的字段映射配置（值用 code、显示用 name、子级 children） */
export const ORG_CASCADER_PROPS = {
  value: 'code',
  label: 'name',
  children: 'children',
  expandTrigger: 'click' as const,
}

/** 解析后的四级组织名称（缺失层级为空串） */
export interface ResolvedOrg {
  zone: string
  company: string
  dept: string
  group: string
  /** 完整路径，如 "东北区域 / 大连智慧港务有限公司 / 生产调度部 / 调度一班" */
  orgPath: string
}

const EMPTY_ORG: ResolvedOrg = { zone: '', company: '', dept: '', group: '', orgPath: '' }

/**
 * 根据级联选中的 code 路径解析出各级名称。
 * @param codes el-cascader 绑定的 code 数组，如 ['z-ne','c-dl-port','d-pps','g-pps-1']
 */
export function resolveOrgPath(codes?: string[] | null): ResolvedOrg {
  if (!codes || codes.length === 0) return { ...EMPTY_ORG }
  const names: string[] = []
  let level: OrgNode[] | undefined = ORG_TREE
  const picked: Partial<Record<OrgLevel, string>> = {}
  for (const code of codes) {
    const node: OrgNode | undefined = level ? level.find((n) => n.code === code) : undefined
    if (!node) break
    picked[node.level] = node.name
    names.push(node.name)
    level = node.children
  }
  return {
    zone: picked.zone || '',
    company: picked.company || '',
    dept: picked.dept || '',
    group: picked.group || '',
    orgPath: names.join(' / '),
  }
}

/** 统计组织节点数量（用于页面展示） */
export function countOrgNodes(): { zone: number; company: number; dept: number; group: number } {
  const result = { zone: 0, company: 0, dept: 0, group: 0 }
  const walk = (nodes: OrgNode[]) => {
    for (const n of nodes) {
      result[n.level] += 1
      if (n.children) walk(n.children)
    }
  }
  walk(ORG_TREE)
  return result
}
