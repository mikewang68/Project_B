/**
 * v-perm 按钮级权限指令
 *
 * 用法：
 *   <el-button v-perm="'iam:user:add:add'">新增用户</el-button>
 *   <el-button v-perm="['dt:device:control:execute', 'pps:task:dispatch:execute']">执行</el-button>
 *   <el-button v-perm:any="['code1', 'code2']">任意一个即可</el-button>
 *   <el-button v-perm:all="['code1', 'code2']">必须全部拥有</el-button>
 *
 * 无权限时移除 DOM 元素（不只是禁用），避免用户通过开发者工具启用。
 * 同时在 updated 阶段重新评估，以响应权限被撤销等变化。
 */

import type { Directive } from 'vue'
import { hasPerm, hasAnyPerm, hasAllPerm } from './index'

function normalize(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter(Boolean) as string[]
  if (typeof value === 'string' && value) return [value]
  return []
}

function isAllowed(value: unknown, arg?: string): boolean {
  const codes = normalize(value)
  const mode = arg || 'single' // single | any | all
  if (mode === 'any') return hasAnyPerm(codes)
  if (mode === 'all') return hasAllPerm(codes)
  return codes.length === 0 ? true : hasPerm(codes[0])
}

export const vPerm: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    if (!isAllowed(binding.value, binding.arg)) {
      el.parentNode?.removeChild(el)
    }
  },
  updated(el, binding) {
    // 权限或绑定值变化时重新评估（元素仍在 DOM 中才可能被移除）
    if (!isAllowed(binding.value, binding.arg) && el.parentNode) {
      el.parentNode.removeChild(el)
    }
  },
}
