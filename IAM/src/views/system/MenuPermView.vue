<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { MENU_TREE, collectAllPerms, type MenuNode, type PermOp } from '@/iam/menu-tree'
import { useAuthStore } from '@/stores/auth'
import { iamApi } from '@/api/iam'

const auth = useAuthStore()

/** 权限目录树：优先从后端加载，接口不可用时回退到内置静态菜单源 */
const serverTree = ref<MenuNode[]>(MENU_TREE)

const expandedKeys = ref<string[]>(MENU_TREE.map((n) => n.id))
const searchKeyword = ref('')
const showOnlyMine = ref(false)
const treeRef = ref()

onMounted(() => {
  void syncTree(false)
})

async function syncTree(showMessage = true) {
  try {
    const tree = await iamApi.permissionTree()
    if (Array.isArray(tree) && tree.length > 0) serverTree.value = tree
    if (showMessage) ElMessage.success('菜单权限已从后端同步')
  } catch (e) {
    serverTree.value = MENU_TREE
    if (showMessage) ElMessage.warning(e instanceof Error ? e.message : '同步失败，已使用内置菜单源')
  }
}

const allPerms = computed(() => collectAllPerms(serverTree.value))
const totalCount = computed(() => allPerms.value.length)
const myCount = computed(() => auth.permCodes.size)

const OP_LABELS: Record<PermOp, string> = {
  view: '查看', add: '新增', edit: '编辑', delete: '删除',
  execute: '执行', export: '导出', import: '导入', approve: '审批',
}

const OP_COLORS: Record<PermOp, string> = {
  view: 'var(--app-text-secondary)', add: '#67c23a', edit: '#e6a23c', delete: '#f56c6c',
  execute: '#409eff', export: 'var(--app-text-secondary)', import: 'var(--app-text-secondary)', approve: '#409eff',
}

interface MenuTreeNode {
  id: string
  label: string
  system: string
  perms?: Record<string, string>
  hasPerm?: boolean
  children?: MenuTreeNode[]
}

function transform(nodes: MenuNode[], kw: string, onlyMine: boolean): MenuTreeNode[] {
  const result: MenuTreeNode[] = []
  for (const node of nodes) {
    const children = node.children ? transform(node.children, kw, onlyMine) : []
    const nodeMatch = !kw || node.name.toLowerCase().includes(kw.toLowerCase())
    const permMatch = node.perms && Object.values(node.perms).some((c) => c.toLowerCase().includes(kw.toLowerCase()))
    const hasPerm = node.perms ? Object.values(node.perms).some((c) => auth.permCodes.has(c)) : children.some((c) => c.hasPerm)

    if (onlyMine && !hasPerm && children.length === 0) continue
    if (!kw && !onlyMine) {
      result.push({ id: node.id, label: node.name, system: node.system, perms: node.perms as Record<string, string>, hasPerm, children })
      continue
    }
    if (nodeMatch || permMatch || children.length > 0) {
      result.push({ id: node.id, label: node.name, system: node.system, perms: node.perms as Record<string, string>, hasPerm, children })
    }
  }
  return result
}

const treeData = computed(() => transform(serverTree.value, searchKeyword.value, showOnlyMine.value))

function handleSync() {
  void syncTree(true)
}

async function copyCode(code: string) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(code)
    } else {
      // 非安全上下文（如 http 部署）兜底方案
      const ta = document.createElement('textarea')
      ta.value = code
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success(`已复制：${code}`)
  } catch {
    ElMessage.warning('复制失败，请手动选中权限编码复制')
  }
}

function collectNodeIds(nodes: { id: string; children?: unknown[] }[], acc: string[] = []): string[] {
  for (const n of nodes) {
    acc.push(n.id)
    if (n.children) collectNodeIds(n.children as { id: string; children?: unknown[] }[], acc)
  }
  return acc
}

// 搜索时自动展开全部节点，清空搜索后恢复仅展开顶级系统
watch(searchKeyword, async (kw) => {
  await nextTick()
  const nodesMap = treeRef.value?.store?.nodesMap as Record<string, { expanded: boolean }> | undefined
  if (!nodesMap) return
  if (kw) {
    collectNodeIds(treeData.value).forEach((id) => {
      if (nodesMap[id]) nodesMap[id].expanded = true
    })
  } else {
    Object.keys(nodesMap).forEach((id) => {
      nodesMap[id].expanded = serverTree.value.some((n) => n.id === id)
    })
  }
})

const systemStats = computed(() => {
  return serverTree.value.map((sys) => {
    const perms = allPerms.value.filter((p) => p.system === sys.system)
    const mine = perms.filter((p) => auth.permCodes.has(p.code)).length
    return { name: sys.name, system: sys.system, total: perms.length, mine }
  })
})
</script>

<template>
  <div class="menu-perm">
    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">系统总数</div>
          <div class="stat-value">{{ MENU_TREE.length }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">权限点总数</div>
          <div class="stat-value">{{ totalCount }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card stat-mine">
          <div class="stat-label">当前用户拥有</div>
          <div class="stat-value">{{ myCount }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">权限覆盖率</div>
          <div class="stat-value">{{ totalCount ? Math.round((myCount / totalCount) * 100) : 0 }}%</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 各系统权限统计 -->
    <el-card shadow="never" class="sys-card">
      <template #header>
        <span class="card-title">各系统权限分布</span>
      </template>
      <div class="sys-grid">
        <div v-for="s in systemStats" :key="s.system" class="sys-item">
          <div class="sys-name">{{ s.name }}</div>
          <el-progress :percentage="s.total ? Math.round((s.mine / s.total) * 100) : 0" :stroke-width="8" />
          <div class="sys-count">{{ s.mine }} / {{ s.total }} 项</div>
        </div>
      </div>
    </el-card>

    <!-- 菜单树 -->
    <el-card shadow="never" class="tree-card">
      <template #header>
        <div class="tree-header">
          <span class="card-title">各系统功能菜单与权限点</span>
          <div class="tree-actions">
            <el-input v-model="searchKeyword" placeholder="搜索菜单/权限编码" clearable style="width:220px" size="default" />
            <el-checkbox v-model="showOnlyMine">只看我有权限的</el-checkbox>
            <el-button type="primary" @click="handleSync">
              <el-icon><Refresh /></el-icon>同步菜单
            </el-button>
          </div>
        </div>
      </template>

      <el-tree
        ref="treeRef"
        :data="treeData"
        :default-expanded-keys="expandedKeys"
        :props="{ label: 'label', children: 'children' }"
        node-key="id"
        class="menu-tree"
      >
        <template #default="{ data }">
          <div class="tree-node">
            <span class="node-label" :class="{ 'has-perm': data.hasPerm, 'no-perm': data.hasPerm === false && data.perms }">
              {{ data.label }}
            </span>
            <template v-if="data.perms">
              <span
                v-for="(code, op) in data.perms"
                :key="op"
                class="perm-tag"
                :style="{ borderColor: OP_COLORS[op as PermOp], color: OP_COLORS[op as PermOp] }"
                :class="{ owned: auth.permCodes.has(code) }"
                @click="copyCode(code)"
                :title="`点击复制：${code}`"
              >
                {{ OP_LABELS[op as PermOp] }}
              </span>
            </template>
            <el-tag v-if="!data.perms && !data.children" size="small" type="info" effect="plain">无操作权限</el-tag>
          </div>
        </template>
      </el-tree>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.menu-perm {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.stat-row {
  .stat-card {
    :deep(.el-card__body) { padding: 16px 20px; }
  }
}

.stat-label {
  font-size: 13px;
  color: var(--iam-text-muted);
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--iam-text-strong);
}

.stat-mine .stat-value {
  color: var(--iam-primary);
}

.sys-card {
  :deep(.el-card__body) { padding: 16px 20px; }
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--iam-text-strong);
}

.sys-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.sys-item {
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  border: 1px solid var(--el-border-color);
}

.sys-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--iam-text-strong);
  margin-bottom: 8px;
}

.sys-count {
  font-size: 12px;
  color: var(--iam-text-muted);
  margin-top: 6px;
  text-align: right;
}

.tree-card {
  :deep(.el-card__body) { padding: 8px 20px 16px; }
}

.tree-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.tree-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.menu-tree {
  background: transparent;

  :deep(.el-tree-node__content) {
    height: 36px;
  }
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  width: 100%;
}

.node-label {
  font-size: 13px;
  color: var(--iam-text-strong);

  &.has-perm {
    font-weight: 500;
  }

  &.no-perm {
    color: var(--el-text-color-placeholder);
  }
}

.perm-tag {
  display: inline-block;
  padding: 1px 8px;
  font-size: 11px;
  border: 1px solid;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
  opacity: 0.5;

  &:hover {
    opacity: 1;
    transform: translateY(-1px);
  }

  &.owned {
    opacity: 1;
    font-weight: 500;
  }
}
</style>
