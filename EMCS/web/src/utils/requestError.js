// 统一请求错误的最小结构：只保留消息、业务码和 HTTP 状态，不复制响应载荷。

function numericCode(value) {
  if (value == null || value === '') return null
  const normalized = Number(value)
  return Number.isFinite(normalized) ? normalized : null
}

export function createBusinessError(message, businessCode, httpStatus) {
  const error = new Error(String(message || '请求失败'))
  const code = numericCode(businessCode)
  const status = numericCode(httpStatus)
  if (code != null) error.code = code
  if (status != null) error.status = status
  return error
}

export function getErrorStatus(error) {
  const candidates = [
    error?.code,
    error?.response?.data?.code,
    error?.status,
    error?.response?.status
  ]
  for (const candidate of candidates) {
    const normalized = numericCode(candidate)
    if (normalized != null) return normalized
  }
  return null
}
