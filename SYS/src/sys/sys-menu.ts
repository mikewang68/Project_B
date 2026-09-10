/**
 * 系统设置与维护（SYS）功能菜单与权限树
 *
 * 权限编码规则：sys:<二级菜单>:<功能点>:<操作>
 * 操作类型：view / add / edit / delete / export
 * 本文件是前端动态菜单过滤、按钮级 v-perm 指令、角色授权的唯一数据来源。
 * 同时需在 IAM 的全平台权限菜单树中登记同套 sys:* 编码，以便统一授权。
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
  icon?: string
  children?: MenuNode[]
  perms?: PermSet
}

export interface PermItem {
  code: string
  name: string
  system: string
  nodeId: string
  op: PermOp
}

/** 生成叶子节点的标准权限编码 */
function perms(base: string, ops: PermOp[] = ['view']): PermSet {
  const result: PermSet = {}
  for (const op of ops) result[op] = `${base}:${op}`
  return result
}

function leaf(id: string, name: string, base: string, ops: PermOp[] = ['view']): MenuNode {
  return { id, name, system: 'sys', perms: perms(base, ops) }
}

function group(id: string, name: string, children: MenuNode[], icon?: string): MenuNode {
  return { id, name, system: 'sys', icon, children }
}

const sysSystem: MenuNode = {
  id: 'sys',
  name: '系统设置与维护',
  system: 'sys',
  children: [
    group('sys-dict', '数据字典', [
      leaf('sys-dict-type', '字典分类', 'sys:dict:type', ['view', 'add', 'edit', 'delete']),
      leaf('sys-dict-item', '字典项', 'sys:dict:item', ['view', 'add', 'edit', 'delete']),
    ], 'Collection'),
    group('sys-log', '操作日志', [
      leaf('sys-log-list', '日志查询', 'sys:log:list', ['view', 'export']),
    ], 'Document'),
    group('sys-config', '系统配置', [
      leaf('sys-config-list', '参数配置', 'sys:config:list', ['view', 'edit']),
    ], 'Setting'),
  ],
}

export const MENU_TREE: MenuNode[] = [sysSystem]

/** 遍历收集全部权限点 */
export function collectAllPerms(tree: MenuNode[] = MENU_TREE): PermItem[] {
  const result: PermItem[] = []
  const walk = (node: MenuNode) => {
    if (node.perms) {
      for (const op of Object.keys(node.perms) as PermOp[]) {
        const code = node.perms[op]
        if (code) result.push({ code, name: node.name, system: node.system, nodeId: node.id, op })
      }
    }
    node.children?.forEach(walk)
  }
  tree.forEach(walk)
  return result
}

/** 按权限集合过滤菜单树（保留有权限的节点） */
export function filterMenuByPerms(nodes: MenuNode[], perms: Set<string>): MenuNode[] {
  const result: MenuNode[] = []
  for (const node of nodes) {
    const hasDirectView = node.perms?.view ? perms.has(node.perms.view) : false
    const children = node.children ? filterMenuByPerms(node.children, perms) : []
    if (hasDirectView || children.length > 0) {
      result.push({ ...node, children: children.length > 0 ? children : undefined })
    }
  }
  return result
}

/** 授权补全：勾选非查看操作时自动带上同功能点 view，保证能操作必先能看到 */
export function ensureViewPerms(codes: string[]): string[] {
  const all = new Set(collectAllPerms().map((p) => p.code))
  const result = new Set(codes)
  for (const code of [...result]) {
    const parts = code.split(':')
    if (parts.length === 4 && parts[3] !== 'view') {
      const viewCode = `${parts[0]}:${parts[1]}:${parts[2]}:view`
      if (all.has(viewCode)) result.add(viewCode)
    }
  }
  return Array.from(result)
}
