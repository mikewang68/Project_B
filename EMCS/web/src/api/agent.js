/**
 * AI Agent API｜REQ-AGENT-TBD（PRD 增补待办）
 * 契约：docs/agent-ai-design.md §5（chat SSE）· §6（inspection）
 * 说明：chat 走 fetch 手写 SSE 读流（禁用 EventSource——不能带 Authorization 头，见设计文档 §5）；
 * 巡检列表/详情/手动触发走标准 axios 封装（复用传输加密与统一错误提示）。
 */
import request from '@/utils/request'
import { getToken } from '@/utils/auth'

const BASE = import.meta.env.VITE_APP_BASE_API

/**
 * SSE 对话流。前端逐事件回调，事件契约见 docs/agent-ai-design.md §5：
 *   tool_call            {name, arguments?, description?}
 *   tool_result_summary  {name, ok}
 *   delta                {text}
 *   done                 {elapsed_ms, tool_calls}
 *   error                {message}
 *
 * @param {Object}   body            {session_id, message}
 * @param {Object}   handlers        {onEvent, onDone, onError}
 * @param {AbortSignal} [signal]     用于取消（面板关闭/组件卸载）
 */
export async function streamChat(body, handlers, signal) {
  const { onEvent, onDone, onError } = handlers || {}
  let response
  try {
    response = await fetch(`${BASE}/agent/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json;charset=utf-8',
        'Accept': 'text/event-stream',
        'Authorization': getToken() ? `Bearer ${getToken()}` : '',
        // 显式声明明文——后端 transport_crypto 处于 optional 模式，SSE 流不参与信封化。
        'encrypt': 'false',
        'encryptResponse': 'false'
      },
      body: JSON.stringify(body),
      signal
    })
  } catch (err) {
    onError?.(err)
    return
  }

  if (!response.ok || !response.body) {
    onError?.(new Error(`AI 助手暂时不可用（HTTP ${response.status}）`))
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // 按 SSE 规范以空行分块
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const rawEvent = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        dispatchEvent(rawEvent, onEvent)
      }
    }
    if (buffer.trim()) dispatchEvent(buffer, onEvent)
    onDone?.()
  } catch (err) {
    if (err?.name === 'AbortError') return
    onError?.(err)
  }
}

function dispatchEvent(rawEvent, onEvent) {
  if (!rawEvent || !onEvent) return
  let event = 'message'
  const dataLines = []
  for (const line of rawEvent.split('\n')) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (!dataLines.length) return
  const raw = dataLines.join('\n')
  let data = raw
  try { data = JSON.parse(raw) } catch { /* 保留原文本 */ }
  onEvent(event, data)
}

// ===== 巡检报告 =====

export function listInspections(params = {}) {
  return request({ url: '/agent/inspections', method: 'get', params })
}

export function getInspection(id) {
  return request({ url: `/agent/inspections/${id}`, method: 'get' })
}

export function runInspection(body = {}) {
  // 巡检 workflow 同步执行约 80-120s（5 步 LLM 判读+汇总），必须覆盖全局
  // axios timeout，否则前端先弹"系统接口请求超时"而后端仍在跑（FX-40）
  return request({ url: '/agent/inspections/run', method: 'post', data: body, timeout: 300000 })
}
