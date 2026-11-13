import { apiRequest, postJson, putJson } from './http'
import type {
  AiInferenceRequest,
  AiInferenceResult,
  AiModelInfo,
  AiProviderConfig,
  EscalateCaseRequest,
  EscalateCaseResult,
  UpdateAiProviderConfigRequest,
  UpdateModelConfigRequest,
} from '@/types/aiModel'

/** AI 模型选用与识别推理 API Client */
export const aiModelApi = {
  /** 获取所有模型 */
  list: async (): Promise<AiModelInfo[]> =>
    apiRequest<AiModelInfo[]>('/ai/models'),

  /** 获取单个模型详情 */
  get: async (id: string): Promise<AiModelInfo> =>
    apiRequest<AiModelInfo>(`/ai/models/${encodeURIComponent(id)}`),

  /** 选用模型为激活状态 */
  select: async (id: string): Promise<AiModelInfo> =>
    postJson<AiModelInfo>(`/ai/models/${encodeURIComponent(id)}/select`, {}),

  /** 更新模型配置 */
  updateConfig: async (id: string, config: UpdateModelConfigRequest): Promise<AiModelInfo> =>
    putJson<AiModelInfo>(`/ai/models/${encodeURIComponent(id)}/config`, config),

  /** 执行实际识别案例推理 */
  infer: async (req: AiInferenceRequest = {}): Promise<AiInferenceResult> =>
    postJson<AiInferenceResult>('/ai/models/infer', req),

  /** 实际识别案例一键上报为安全告警 */
  escalate: async (req: EscalateCaseRequest): Promise<EscalateCaseResult> =>
    postJson<EscalateCaseResult>('/ai/models/escalate', req),

  /** 读取边缘 AI 服务配置（密钥只返回 last4） */
  getProviderConfig: async (): Promise<AiProviderConfig> =>
    apiRequest<AiProviderConfig>('/ai/models/config'),

  /** 保存边缘 AI 服务配置；密钥留空表示保持原密钥 */
  saveProviderConfig: async (config: UpdateAiProviderConfigRequest): Promise<AiProviderConfig> =>
    putJson<AiProviderConfig>('/ai/models/config', config),
}
