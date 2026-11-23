<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import Hls from 'hls.js'
import type { VendorControlSystem } from '@/services/dtsDataService'
import { formatProvenanceBadge, type SurveillanceCameraStateV6 } from '@/services/twinIntegrationContracts'

const props = defineProps<{
  camera: SurveillanceCameraStateV6 | null
  cameras?: SurveillanceCameraStateV6[]
  controlSystem?: VendorControlSystem | null
  scsUrl: string
  psmsUrl?: string
  safetyDistanceM?: number
}>()

const emit = defineEmits<{
  close: []
  'focus-camera': [camera: SurveillanceCameraStateV6]
  'select-camera': [camera: SurveillanceCameraStateV6]
}>()

const videoEl = ref<HTMLVideoElement | null>(null)
const streamError = ref('')
let hlsInstance: Hls | null = null

function destroyHls() {
  if (hlsInstance) {
    hlsInstance.destroy()
    hlsInstance = null
  }
}

watch(
  () => [props.camera?.streamUrl, props.camera?.streamProtocol, videoEl.value] as const,
  ([url, proto, el]) => {
    destroyHls()
    streamError.value = ''
    if (!el || !url) return

    if (proto === 'HLS') {
      if (Hls.isSupported()) {
        hlsInstance = new Hls({ lowLatencyMode: true, enableWorker: true })
        hlsInstance.loadSource(url)
        hlsInstance.attachMedia(el)
        hlsInstance.on(Hls.Events.ERROR, (_evt, data) => {
          if (data.fatal) {
            streamError.value = `HLS 视频流加载失败（${data.details}），请核查 S4 流媒体网关或转码服务`
          }
        })
      } else if (el.canPlayType('application/vnd.apple.mpegurl')) {
        el.src = url
      } else {
        streamError.value = '当前浏览器不支持 HLS 播放，请使用支持 MediaSource 的现代 Chromium/Edge'
      }
    } else if (proto === 'MP4') {
      el.src = url
    }
  },
  { flush: 'post', immediate: true },
)

onBeforeUnmount(() => {
  destroyHls()
})
</script>

<template>
  <div v-if="camera" class="vsm-backdrop" role="dialog" aria-label="S4 监控视频与只读联查弹窗" @click.self="emit('close')">
    <div class="vsm-card" :class="{ 'has-plc': Boolean(controlSystem) }">
      <div class="vsm-head">
        <div>
          <b>{{ camera.cameraId }} · {{ camera.name }}（S4 监控核查）</b>
          <small>
            状态：{{ camera.status }} · 协议：{{ camera.streamProtocol }} · 溯源：{{ formatProvenanceBadge(camera.dataSource).label }}
            · 严守 S5 只监不控红线
          </small>
        </div>
        <button type="button" class="vsm-close" @click="emit('close')">✕</button>
      </div>

      <div v-if="cameras && cameras.length > 1" class="vsm-switcher">
        <button
          v-for="cam in cameras"
          :key="cam.cameraId"
          type="button"
          class="vsm-cam-chip"
          :class="{ active: cam.cameraId === camera.cameraId, alert: cam.status === 'ALERT' }"
          @click="emit('select-camera', cam)"
        >
          {{ cam.cameraId }}
        </button>
      </div>

      <div class="vsm-body">
        <div class="vsm-left">
          <div class="vsm-viewport">
            <video
              v-if="camera.streamUrl && (camera.streamProtocol === 'HLS' || camera.streamProtocol === 'MP4')"
              ref="videoEl"
              controls
              autoplay
              muted
              playsinline
              class="vsm-media"
            />
            <img
              v-else-if="camera.streamUrl && (camera.streamProtocol === 'MJPEG' || camera.streamProtocol === 'SNAPSHOT')"
              :src="camera.streamUrl"
              :alt="camera.name"
              class="vsm-media"
            />
            <iframe
              v-else-if="camera.streamUrl && camera.streamProtocol === 'IFRAME'"
              :src="camera.streamUrl"
              class="vsm-media"
              title="S4 实时监控视频流"
              sandbox="allow-scripts allow-same-origin"
            />
            <div v-else class="vsm-unbound">
              <div class="vsm-unbound-badge">UNBOUND · 未绑定现场流媒体地址</div>
              <div class="vsm-unbound-title">已预留 S4-SCS 浏览器可播流直连契约（HLS / MP4 / MJPEG / IFRAME）</div>
              <div class="vsm-unbound-meta">
                <span>CAD 机位：({{ camera.cadX.toFixed(1) }}, {{ camera.cadY.toFixed(1) }}, H={{ camera.cadZ }}m)</span>
                <span>视场角：{{ camera.fovDeg }}° · 俯仰：{{ camera.pitchDeg }}° · 覆盖半径：{{ camera.rangeM }}m</span>
                <span v-if="safetyDistanceM != null">当前防碰撞测距参考：{{ safetyDistanceM.toFixed(1) }} m</span>
              </div>
              <div class="vsm-unbound-hint">
                说明：DTS 仅消费 S4-SCS / 弱电专业开放的浏览器标准流地址，不承担视频转码、录像存储，亦不以三维画布截帧冒充监控视频。
                当 S4 返回有效 <code>streamUrl</code> 时，本窗口无需改前端代码即可自动切换真实播放。
              </div>
            </div>
            <div v-if="streamError" class="vsm-err-bar">{{ streamError }}</div>
          </div>

          <div v-if="camera.latestAiEvent" class="vsm-ai-banner">
            <b>S4 AI 识别摘要（{{ camera.latestAiEvent.riskCode }}）</b>：
            {{ camera.latestAiEvent.type }} · 置信度 {{ camera.latestAiEvent.confidence }}% ·
            {{ camera.latestAiEvent.detectedAt }}
          </div>

          <div class="vsm-actions">
            <button type="button" class="vsm-btn" @click="emit('focus-camera', camera)">飞至该机位三维视角</button>
            <a :href="scsUrl" target="_blank" rel="noopener noreferrer" class="vsm-btn primary">进入 S4 安全监控系统复核 →</a>
            <a v-if="psmsUrl" :href="psmsUrl" target="_blank" rel="noopener noreferrer" class="vsm-btn">进入 S1 工艺系统处置 →</a>
          </div>
        </div>

        <div v-if="controlSystem" class="vsm-plc">
          <div class="vsm-plc-head">
            <b>{{ controlSystem.name }}</b>
            <em>{{ controlSystem.category }} · 只读镜像</em>
          </div>
          <p class="vsm-plc-sub">{{ controlSystem.interlockSummary }}</p>
          <div class="vsm-sig-list">
            <div v-for="sig in controlSystem.plcSignals" :key="sig.tag" class="vsm-sig-row">
              <span>{{ sig.label }}</span>
              <b>{{ sig.value }}</b>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.vsm-backdrop {
  position: fixed;
  inset: 0;
  z-index: 95;
  background: rgba(2, 10, 22, 0.72);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.vsm-card {
  width: min(740px, 95vw);
  max-height: 90vh;
  overflow-y: auto;
  background: rgba(6, 22, 40, 0.97);
  border: 1px solid rgba(56, 189, 248, 0.55);
  border-radius: 10px;
  color: #dff4ff;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.6);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  &.has-plc {
    width: min(1040px, 96vw);
  }
}

.vsm-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid rgba(56, 189, 248, 0.24);
  padding-bottom: 10px;

  b {
    display: block;
    font-size: 15px;
    color: #f0f9ff;
  }

  small {
    display: block;
    margin-top: 3px;
    font-size: 12px;
    color: #8cc9e8;
  }
}

.vsm-close {
  background: rgba(15, 42, 68, 0.9);
  border: 1px solid rgba(56, 189, 248, 0.45);
  color: #e0f2fe;
  border-radius: 6px;
  padding: 4px 10px;
  cursor: pointer;
}

.vsm-switcher {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.vsm-cam-chip {
  padding: 3px 10px;
  border-radius: 99px;
  font-size: 11px;
  cursor: pointer;
  color: #bae6fd;
  background: rgba(10, 34, 56, 0.9);
  border: 1px solid rgba(56, 189, 248, 0.35);

  &.active {
    background: rgba(2, 132, 199, 0.9);
    border-color: #7dd3fc;
    color: #fff;
    font-weight: 700;
  }

  &.alert {
    border-color: #ef4444;
    color: #fecaca;
  }
}

.vsm-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;

  .has-plc & {
    grid-template-columns: minmax(0, 1.35fr) minmax(260px, 1fr);
  }
}

@media (max-width: 900px) {
  .vsm-card.has-plc .vsm-body {
    grid-template-columns: minmax(0, 1fr);
  }
}

.vsm-left {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.vsm-viewport {
  position: relative;
  min-height: 290px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid rgba(56, 189, 248, 0.35);
  background: radial-gradient(circle at 50% 35%, rgba(12, 52, 82, 0.45), rgba(3, 12, 24, 0.95));
  display: flex;
  align-items: center;
  justify-content: center;
}

.vsm-media {
  width: 100%;
  height: 320px;
  object-fit: contain;
  border: 0;
  background: #020914;
}

.vsm-unbound {
  padding: 20px 22px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.vsm-unbound-badge {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  padding: 3px 10px;
  border-radius: 99px;
  color: #fde68a;
  background: rgba(245, 158, 11, 0.16);
  border: 1px solid rgba(245, 158, 11, 0.52);
}

.vsm-unbound-title {
  font-size: 14px;
  font-weight: 600;
  color: #e0f2fe;
}

.vsm-unbound-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #7dd3fc;
}

.vsm-unbound-hint {
  max-width: 520px;
  font-size: 12px;
  line-height: 1.55;
  color: #9fbcd0;
  background: rgba(8, 28, 48, 0.8);
  border: 1px dashed rgba(56, 189, 248, 0.35);
  border-radius: 6px;
  padding: 8px 12px;

  code {
    color: #38bdf8;
    font-family: Consolas, monospace;
  }
}

.vsm-err-bar {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 10px;
  background: rgba(127, 29, 29, 0.92);
  border: 1px solid rgba(248, 113, 113, 0.7);
  color: #fee2e2;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 12px;
}

.vsm-ai-banner {
  background: rgba(180, 83, 9, 0.18);
  border: 1px solid rgba(245, 158, 11, 0.45);
  border-radius: 6px;
  padding: 7px 10px;
  font-size: 12px;
  color: #fef3c7;
}

.vsm-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.vsm-btn {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  text-decoration: none;
  cursor: pointer;
  color: #dff4ff;
  background: rgba(12, 46, 74, 0.9);
  border: 1px solid rgba(56, 189, 248, 0.48);

  &.primary {
    background: rgba(2, 132, 199, 0.88);
    border-color: #38bdf8;
    color: #fff;
  }
}

.vsm-plc {
  background: rgba(8, 28, 48, 0.82);
  border: 1px solid rgba(56, 189, 248, 0.28);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.vsm-plc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  b {
    font-size: 13px;
    color: #e0f2fe;
  }

  em {
    font-style: normal;
    font-size: 11px;
    color: #34d399;
    background: rgba(16, 185, 129, 0.14);
    border: 1px solid rgba(16, 185, 129, 0.42);
    border-radius: 99px;
    padding: 2px 7px;
    white-space: nowrap;
  }
}

.vsm-plc-sub {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.45;
  color: #93c5fd;
}

.vsm-sig-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.vsm-sig-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11.5px;
  padding: 5px 7px;
  border-radius: 5px;
  background: rgba(4, 18, 34, 0.72);

  span {
    color: #9fbcd0;
  }

  b {
    color: #7dd3fc;
    text-align: right;
  }
}
</style>
