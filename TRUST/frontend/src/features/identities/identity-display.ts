/** Display-only mapping: unknown values never imply successful verification. */
export function identityLabel(value: unknown): string {
  const labels: Record<string, string> = {
    NOT_PROVISIONED: '尚未供给', PENDING: '等待处理', PREPARING: '准备证书', ISSUED: '证书已签发，待网络核验',
    NETWORK_PENDING: '等待网络核验', RETRY: '等待自动重试', READY: '身份可用', DISABLED: '平台已停用',
    DELETED: '用户已删除', REVOCATION_PENDING: '等待 CA 吊销', CRL_PENDING: 'CA 已吊销，待 CRL 核验',
    REVOKED: '网络吊销已验证', EXPIRED: '证书已过期', RETIRED: '历史退休版本',
    QUARANTINED: '已隔离，需人工审核', ACTIVE: '当前版本', FAILED: '处理失败'
  }
  return labels[String(value)] || '未知状态，需核查'
}
export function networkLabel(value: unknown): string {
  return ({ VERIFIED: '网络认可已核验', PENDING: '等待网络核验', NOT_REQUESTED: '尚未请求核验',
    REVOCATION_UNVERIFIED: '吊销传播尚未核验', REVOKED: '网络吊销已验证', FAILED: '网络核验失败',
    UNVERIFIED: '尚未核验', EXPIRED: '证书已过期' } as Record<string,string>)[String(value)] || '未知网络状态，需核查'
}
export function crlLabel(value: unknown): string {
  return ({ VERIFIED: '网络吊销已验证', PENDING: 'CA 已吊销，待 CRL 核验', NOT_REQUESTED: '尚未请求吊销核验',
    FAILED: 'CRL 核验失败' } as Record<string,string>)[String(value)] || '未知 CRL 状态，需核查'
}
export function identityExpiry(value: unknown): string {
  if (value === null || value === undefined || value === '') return '未提供有效期'
  if (typeof value !== 'string' && typeof value !== 'number') return '无效日期，需核查'
  if (typeof value === 'string' && !/(Z|[+-]\d{2}:?\d{2})$/i.test(value)) return '日期缺少时区，需核查'
  if (typeof value === 'string') {
    const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})$/i.exec(value)
    if (!parts) return '无效日期，需核查'
    const [, year, month, day, hour, minute, second = '0'] = parts
    const y = Number(year), m = Number(month), d = Number(day)
    const days = [31, y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    if (m < 1 || m > 12 || d < 1 || d > days[m - 1]! || Number(hour) > 23 || Number(minute) > 59 || Number(second) > 59) return '无效日期，需核查'
  }
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '无效日期，需核查'
  return new Intl.DateTimeFormat('zh-CN', { timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit',
    hour:'2-digit', minute:'2-digit', second:'2-digit', hourCycle:'h23' }).format(date) + '（UTC+08:00）'
}
export function identityError(state: unknown): string {
  if (state === 'QUARANTINED') return '记录已隔离，需人工审核后才能处理'
  if (state === 'RETRY' || state === 'PENDING') return '供给失败，等待重试；尚未证明身份可用'
  return '处理异常，请核查技术详情'
}
