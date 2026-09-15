<!--
  全站 AI 助手悬浮球 + 对话抽屉
  REQ-AGENT-TBD（PRD 增补待办）· 契约：docs/agent-ai-design.md §5、§8
  规则：
    - fetch + ReadableStream 手写 SSE（EventSource 带不了 token 头，见设计文档 §5）
    - 会话 session_id 前端生成，面板收起不清空上下文
    - 深浅双主题走 cockpit-tokens 变量
    - 文案全中文；工具过程条与业务名称显示由 tool_call.description / delta 直接透传
-->
<template>
  <div class="ai-assistant">
    <button class="ai-fab" :class="{ 'is-open': open }" @click="toggle" aria-label="AI 助手">
      <span class="ai-fab__label">AI</span>
      <span class="ai-fab__pulse" v-if="streaming" />
    </button>

    <el-drawer
      v-model="open"
      :size="drawerSize"
      :with-header="false"
      :append-to-body="true"
      :destroy-on-close="false"
      class="cockpit-modal ai-drawer"
      direction="rtl"
    >
      <section class="ai-panel cockpit-page">
        <header class="ai-head">
          <div class="ai-head__title">
            <span class="eyebrow">AI ASSISTANT · 智能问数</span>
            <b>能源管控助手</b>
          </div>
          <div class="ai-head__actions">
            <el-tooltip content="新建对话（清空上下文）" placement="bottom">
              <el-button link @click="resetSession" :disabled="streaming">
                <el-icon><Refresh /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="收起" placement="bottom">
              <el-button link @click="open = false">
                <el-icon><Close /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </header>

        <div class="ai-stream" ref="streamRef">
          <div v-if="!messages.length" class="ai-empty">
            <p>你可以试着问：</p>
            <ul>
              <li @click="quickAsk('上周 A 区能耗为什么偏高？')">上周 A 区能耗为什么偏高？</li>
              <li @click="quickAsk('哪些计量点最近缺数？')">哪些计量点最近缺数？</li>
              <li @click="quickAsk('成本第 3 版和第 4 版差在哪？')">成本第 3 版和第 4 版差在哪？</li>
              <li @click="quickAsk('3 号装卸机今天状态怎么样？')">3 号装卸机今天状态怎么样？</li>
            </ul>
          </div>

          <template v-for="msg in messages" :key="msg.id">
            <!-- 用户气泡（右） -->
            <div v-if="msg.role === 'user'" class="ai-msg ai-msg--user">
              <div class="ai-bubble">{{ msg.text }}</div>
            </div>

            <!-- AI 气泡（左）：blocks 按事件到达顺序渲染，思考块/过程条/正文可穿插 -->
            <div v-else class="ai-msg ai-msg--bot">
              <div class="ai-avatar">AI</div>
              <div class="ai-msg__body">
                <template v-for="(block, bIdx) in msg.blocks" :key="bIdx">
                  <!-- 思考块 -->
                  <div
                    v-if="block.type === 'thinking'"
                    class="ai-think"
                    :class="{ 'is-active': block.active, 'is-done': !block.active, 'is-expanded': block.expanded }"
                  >
                    <div class="ai-think__head" @click="toggleThink(block)">
                      <span class="ai-think__icon">
                        <span v-if="block.active" class="ai-think__pulse" />
                        <span v-else>›</span>
                      </span>
                      <span class="ai-think__label">
                        {{ block.active ? '思考中…' : `已思考 ${(block.elapsedMs / 1000).toFixed(1)} 秒` }}
                      </span>
                      <span v-if="!block.active" class="ai-think__toggle">
                        {{ block.expanded ? '收起' : '展开' }}
                      </span>
                    </div>
                    <div
                      v-show="block.expanded"
                      class="ai-think__body"
                      :ref="(el) => registerThinkBody(el, block)"
                    >{{ block.text }}</div>
                  </div>

                  <!-- 工具过程条 -->
                  <div
                    v-else-if="block.type === 'tool'"
                    class="ai-tool"
                    :class="{ 'is-ok': block.status === 'ok', 'is-fail': block.status === 'fail' }"
                  >
                    <span class="ai-tool__dot" />
                    <span class="ai-tool__text">
                      {{ block.status === 'running' ? '正在查询' : (block.status === 'ok' ? '已完成' : '查询失败') }}·
                      {{ block.description || toolLabel(block.name) }}
                    </span>
                    <el-icon v-if="block.status === 'ok'" class="ai-tool__icon"><Check /></el-icon>
                    <el-icon v-else-if="block.status === 'fail'" class="ai-tool__icon"><Close /></el-icon>
                    <span v-else class="ai-tool__spinner" />
                  </div>

                  <!-- 正文文本气泡 -->
                  <div
                    v-else-if="block.type === 'text'"
                    class="ai-bubble"
                    v-html="renderMarkdown(block.text)"
                  />
                </template>

                <!-- 错误气泡 -->
                <div v-if="msg.error" class="ai-bubble is-error" v-html="renderMarkdown(msg.error)" />
                <!-- 等待首个事件时的打字点 -->
                <div v-if="msg.pending && !msg.blocks.length && !msg.error" class="ai-bubble ai-bubble--typing">
                  <span /><span /><span />
                </div>
                <div v-if="msg.meta" class="ai-meta">
                  用时 {{ (msg.meta.elapsed_ms / 1000).toFixed(1) }} 秒 · 调用工具 {{ msg.meta.tool_calls }} 次
                </div>
              </div>
            </div>
          </template>
        </div>

        <footer class="ai-input">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            resize="none"
            :maxlength="500"
            placeholder="向能源管控助手提问，按 Enter 发送 / Shift+Enter 换行"
            @keydown.enter.exact.prevent="send"
          />
          <div class="ai-input__row">
            <span class="ai-hint">
              演示环境 · 数据口径以 DEMO_NOW 为准
            </span>
            <el-button
              type="primary"
              :loading="streaming"
              :disabled="!draft.trim() || streaming"
              @click="send"
            >发送</el-button>
          </div>
        </footer>
      </section>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { Check, Close, Refresh } from '@element-plus/icons-vue'
import { streamChat } from '@/api/agent'

const open = ref(false)
const draft = ref('')
const streaming = ref(false)
const messages = ref([])
const streamRef = ref(null)
let currentAbort = null
let msgSeq = 0
const sessionId = ref(newSessionId())

const drawerSize = computed(() => (window.innerWidth < 640 ? '100%' : 400))

function toggle() { open.value = !open.value }

function newSessionId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return `sess-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

function resetSession() {
  if (streaming.value) return
  messages.value = []
  sessionId.value = newSessionId()
}

function quickAsk(text) {
  if (streaming.value) return
  draft.value = text
  send()
}

async function send() {
  const text = draft.value.trim()
  if (!text || streaming.value) return
  draft.value = ''
  messages.value.push({ id: ++msgSeq, role: 'user', text })
  // 必须 reactive 包裹：裸对象 push 进响应式数组后,闭包里的裸引用 mutation
  // 不走代理 set 陷阱 → 流式期间 UI 不更新,直到 streaming.value=false 才
  // 一次性重渲染（"一下子全出来"的根因,P-27 补录）
  const bot = reactive({ id: ++msgSeq, role: 'bot', blocks: [], pending: true })
  messages.value.push(bot)
  streaming.value = true
  scrollToBottom()

  const ctrl = new AbortController()
  currentAbort = ctrl

  // 便捷取上一个 block
  const lastBlock = () => bot.blocks[bot.blocks.length - 1]
  // 记录当前活动的思考块（thinking_start → thinking_end 之间）
  let activeThinking = null

  await streamChat(
    { session_id: sessionId.value, message: text },
    {
      onEvent: (event, data) => {
        if (event === 'thinking_start') {
          bot.pending = false
          bot.blocks.push({ type: 'thinking', text: '', elapsedMs: 0, active: true, expanded: true, _bodyEl: null })
          // 从响应式数组回取代理引用——留裸 block 引用会同样绕过响应式（见上）
          activeThinking = bot.blocks[bot.blocks.length - 1]
        } else if (event === 'thinking_delta') {
          const target = activeThinking || [...bot.blocks].reverse().find((b) => b.type === 'thinking' && b.active)
          if (target) {
            target.text += typeof data === 'string' ? data : (data?.text || '')
            scrollThinkBody(target)
          }
        } else if (event === 'thinking_end') {
          const target = activeThinking || [...bot.blocks].reverse().find((b) => b.type === 'thinking' && b.active)
          if (target) {
            target.active = false
            target.elapsedMs = Number(data?.elapsed_ms) || 0
            target.expanded = false
          }
          activeThinking = null
        } else if (event === 'tool_call') {
          bot.pending = false
          bot.blocks.push({
            type: 'tool',
            name: data?.name,
            description: data?.description,
            status: 'running'
          })
        } else if (event === 'tool_result_summary') {
          const target = [...bot.blocks].reverse().find(
            (b) => b.type === 'tool' && b.name === data?.name && b.status === 'running'
          )
          if (target) target.status = data?.ok ? 'ok' : 'fail'
        } else if (event === 'delta') {
          bot.pending = false
          const chunk = typeof data === 'string' ? data : (data?.text || '')
          const last = lastBlock()
          if (last && last.type === 'text') last.text += chunk
          else bot.blocks.push({ type: 'text', text: chunk })
        } else if (event === 'done') {
          bot.pending = false
          const toolCalls = data?.tool_calls ?? bot.blocks.filter((b) => b.type === 'tool').length
          bot.meta = { elapsed_ms: data?.elapsed_ms ?? 0, tool_calls: toolCalls }
        } else if (event === 'error') {
          bot.pending = false
          bot.error = data?.message || 'AI 助手暂时不可用，稍后重试'
        }
        scrollToBottom()
      },
      onDone: () => {
        streaming.value = false
        bot.pending = false
        // 收尾兜底：若还有活动思考块（后端没送 thinking_end），补一个折叠
        const stray = bot.blocks.find((b) => b.type === 'thinking' && b.active)
        if (stray) { stray.active = false; stray.expanded = false }
        const hasText = bot.blocks.some((b) => b.type === 'text' && b.text)
        if (!hasText && !bot.error) bot.error = 'AI 助手暂时不可用，稍后重试'
        scrollToBottom()
      },
      onError: (err) => {
        streaming.value = false
        bot.pending = false
        bot.error = err?.message?.includes('HTTP') ? err.message : 'AI 助手暂时不可用，稍后重试'
        scrollToBottom()
      }
    },
    ctrl.signal
  )
  currentAbort = null
}

function toggleThink(block) {
  if (block.active) return  // 进行中不折叠，保持展开跟随流式
  block.expanded = !block.expanded
}

function registerThinkBody(el, block) {
  block._bodyEl = el || null
  if (el && block.active) el.scrollTop = el.scrollHeight
}

function scrollThinkBody(block) {
  nextTick(() => {
    const el = block._bodyEl
    if (el) el.scrollTop = el.scrollHeight
  })
}

function scrollToBottom() {
  nextTick(() => {
    const el = streamRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// 演示用工具名回退中文；后端 tool_call.description 会直接给业务化文案，此处仅兜底
function toolLabel(name) {
  const map = {
    query_overview: '能耗与成本总览',
    query_alerts: '异常告警列表',
    get_alert_detail: '告警证据链',
    query_cost_month: '月度成本视图',
    query_cost_trace: '成本口径追溯',
    query_data_quality: '数据质量汇总',
    query_suggestions: '节能建议工单',
    query_equipment_profile: '设备画像'
  }
  return map[name] || name || '数据查询'
}

// 极简 Markdown 渲染：加粗 / 斜体 / 无序列表 / 换行；只处理纯文本 + 常见标记，无需外部库。
function renderMarkdown(text) {
  if (!text) return ''
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  const lines = escaped.split('\n')
  const out = []
  let inList = false
  for (const raw of lines) {
    const line = raw
    if (/^\s*[-*]\s+/.test(line)) {
      if (!inList) { out.push('<ul>'); inList = true }
      out.push(`<li>${inline(line.replace(/^\s*[-*]\s+/, ''))}</li>`)
    } else {
      if (inList) { out.push('</ul>'); inList = false }
      if (line.trim() === '') out.push('<br/>')
      else out.push(`<p>${inline(line)}</p>`)
    }
  }
  if (inList) out.push('</ul>')
  return out.join('')
}
function inline(s) {
  return s
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/`([^`]+?)`/g, '<code>$1</code>')
}

onBeforeUnmount(() => { currentAbort?.abort() })
</script>

<style scoped>
.ai-fab {
  position: fixed;
  right: 22px;
  bottom: 28px;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  border: 1px solid rgba(64, 158, 255, 0.55);
  background: var(--app-color-primary);
  color: #fff;
  font-family: var(--app-font);
  font-weight: 600;
  letter-spacing: 0.08em;
  box-shadow: var(--app-card-shadow);
  cursor: pointer;
  z-index: 2000;
  transition: background-color 180ms, box-shadow 180ms;
}
.ai-fab:hover { background: var(--app-color-primary-hover); box-shadow: var(--app-card-hover-shadow); }
.ai-fab.is-open { background: var(--app-color-primary-active); }
.ai-fab__label { font-size: 16px; }
.ai-fab__pulse {
  position: absolute; inset: -4px;
  border-radius: 50%;
  border: 2px solid rgba(64, 158, 255, 0.55);
  animation: aiPulse 1.4s ease-out infinite;
}
@keyframes aiPulse {
  0% { transform: scale(0.9); opacity: 0.9; }
  100% { transform: scale(1.35); opacity: 0; }
}
</style>

<style>
/* 抽屉 teleport 到 body，样式脱 scoped，通过 .ai-drawer 命名收窄 */
.ai-drawer.cockpit-modal .el-drawer__body {
  padding: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ai-drawer .ai-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--panel);
  color: var(--ink);
}

.ai-drawer .ai-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
}
.ai-drawer .ai-head__title { display: flex; flex-direction: column; gap: 4px; }
.ai-drawer .ai-head__title .eyebrow {
  font-family: var(--mono);
  font-size: 10px;
  color: var(--cyan);
  letter-spacing: 0.14em;
}
.ai-drawer .ai-head__title b {
  font-family: var(--serif);
  font-size: 16px;
  letter-spacing: 0.08em;
  color: var(--ink);
}
.ai-drawer .ai-head__actions { display: flex; gap: 4px; }
.ai-drawer .ai-head__actions .el-button { color: var(--ink-3); }
.ai-drawer .ai-head__actions .el-button:hover { color: var(--cyan); }

.ai-drawer .ai-stream {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px 8px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ai-drawer .ai-empty {
  color: var(--ink-3);
  font-size: 12px;
  padding: 32px 4px;
}
.ai-drawer .ai-empty p { margin: 0 0 10px; font-family: var(--serif); color: var(--ink-2); }
.ai-drawer .ai-empty ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.ai-drawer .ai-empty li {
  padding: 10px 12px;
  border: 1px dashed var(--line-strong);
  background: var(--panel-2);
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;
}
.ai-drawer .ai-empty li:hover { border-color: var(--cyan); color: var(--cyan); }

.ai-drawer .ai-msg { display: flex; gap: 8px; }
.ai-drawer .ai-msg--user { justify-content: flex-end; }
.ai-drawer .ai-msg--bot { justify-content: flex-start; }
.ai-drawer .ai-msg__body { display: flex; flex-direction: column; gap: 6px; max-width: 82%; }

.ai-drawer .ai-avatar {
  width: 30px; height: 30px; border-radius: 50%;
  background: var(--cyan-tint);
  color: var(--cyan);
  border: 1px solid var(--cyan);
  display: flex; align-items: center; justify-content: center;
  font: 11px var(--mono);
  flex-shrink: 0;
}

.ai-drawer .ai-bubble {
  padding: 10px 13px;
  border: 1px solid var(--line);
  background: var(--panel-2);
  color: var(--ink);
  line-height: 1.65;
  font-size: 13px;
  word-break: break-word;
  border-radius: 10px;
}
.ai-drawer .ai-msg--user .ai-bubble {
  background: color-mix(in srgb, var(--cyan) 22%, var(--panel-2));
  border-color: color-mix(in srgb, var(--cyan) 45%, var(--line));
  color: var(--ink);
  max-width: 78%;
}
.ai-drawer .ai-bubble.is-error {
  background: var(--red-tint);
  border-color: var(--red);
  color: var(--red);
}
.ai-drawer .ai-bubble :deep(p),
.ai-drawer .ai-bubble p { margin: 0 0 6px; }
.ai-drawer .ai-bubble p:last-child { margin-bottom: 0; }
.ai-drawer .ai-bubble ul { margin: 4px 0 4px 18px; padding: 0; }
.ai-drawer .ai-bubble code {
  padding: 1px 6px;
  background: var(--panel);
  border: 1px solid var(--line-strong);
  color: var(--cyan);
  font-family: var(--mono);
  font-size: 12px;
}
.ai-drawer .ai-bubble strong { color: var(--ink); font-weight: 600; }

.ai-drawer .ai-bubble--typing { display: flex; gap: 4px; align-items: center; padding: 12px; }
.ai-drawer .ai-bubble--typing span {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--ink-3);
  animation: aiBlink 1.2s infinite ease-in-out;
}
.ai-drawer .ai-bubble--typing span:nth-child(2) { animation-delay: 0.2s; }
.ai-drawer .ai-bubble--typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes aiBlink { 0%, 60%, 100% { opacity: 0.3; } 30% { opacity: 1; } }

/* ===== 思考链块 ===== */
.ai-drawer .ai-think {
  border-left: 2px solid var(--line-strong);
  padding: 4px 0 4px 10px;
  color: var(--ink-3);
  font-size: 11px;
  line-height: 1.55;
  transition: border-color 0.2s ease, color 0.2s ease;
}
.ai-drawer .ai-think.is-active { border-left-color: var(--cyan); color: var(--ink-2); }
.ai-drawer .ai-think__head {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: default;
  user-select: none;
  font-family: var(--mono);
  letter-spacing: 0.04em;
}
.ai-drawer .ai-think.is-done .ai-think__head { cursor: pointer; }
.ai-drawer .ai-think.is-done .ai-think__head:hover .ai-think__label,
.ai-drawer .ai-think.is-done .ai-think__head:hover .ai-think__toggle { color: var(--cyan); }
.ai-drawer .ai-think__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 12px;
  height: 12px;
  color: var(--ink-3);
  font: 12px var(--mono);
}
.ai-drawer .ai-think.is-active .ai-think__icon { color: var(--cyan); }
.ai-drawer .ai-think__pulse {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--cyan);
  animation: aiThinkPulse 1.1s ease-in-out infinite;
}
.ai-drawer .ai-think.is-done.is-expanded .ai-think__icon { transform: rotate(90deg); transition: transform 0.15s ease; }
@keyframes aiThinkPulse {
  0%, 100% { opacity: 0.35; transform: scale(0.85); }
  50% { opacity: 1; transform: scale(1.1); }
}
.ai-drawer .ai-think__label { flex: 1; font-size: 11px; }
.ai-drawer .ai-think__toggle { color: var(--ink-3); font-size: 10px; }
.ai-drawer .ai-think__body {
  margin-top: 6px;
  padding: 8px 10px;
  max-height: 120px;
  overflow-y: auto;
  background: var(--panel-2);
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--ink-3);
  font: 11px/1.6 var(--mono);
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-drawer .ai-think.is-active .ai-think__body { border-style: solid; border-color: color-mix(in srgb, var(--cyan) 40%, var(--line-strong)); }

.ai-drawer .ai-tool {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px dashed var(--cyan);
  background: var(--cyan-tint);
  color: var(--ink-2);
  font-family: var(--mono);
  font-size: 11px;
  letter-spacing: 0.04em;
  border-radius: 6px;
}
.ai-drawer .ai-tool.is-ok { border-color: var(--lime); color: var(--lime); background: var(--lime-tint); border-style: solid; }
.ai-drawer .ai-tool.is-fail { border-color: var(--red); color: var(--red); background: var(--red-tint); border-style: solid; }
.ai-drawer .ai-tool__dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
  opacity: 0.85;
}
.ai-drawer .ai-tool__text { flex: 1; line-height: 1.4; }
.ai-drawer .ai-tool__icon { font-size: 12px; }
.ai-drawer .ai-tool__spinner {
  width: 10px; height: 10px;
  border: 1.5px solid var(--line-strong);
  border-top-color: var(--cyan);
  border-radius: 50%;
  animation: aiSpin 0.8s linear infinite;
}
@keyframes aiSpin { to { transform: rotate(360deg); } }

.ai-drawer .ai-meta {
  color: var(--ink-3);
  font: 10px var(--mono);
  letter-spacing: 0.06em;
}

.ai-drawer .ai-input {
  border-top: 1px solid var(--line);
  padding: 12px 18px 16px;
  background: var(--panel);
}
.ai-drawer .ai-input__row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.ai-drawer .ai-hint {
  color: var(--ink-3);
  font: 10px var(--mono);
  letter-spacing: 0.06em;
}
</style>
