/** 第五幕报表预览、导出与冻结归档真实 API｜REQ-030/059/061/062 */
import request from '@/utils/request'

export function getReportTemplates() {
  return request({ url: '/reports/templates', method: 'get' })
}

export function previewReport(data) {
  return request({ url: '/reports/preview', method: 'post', data })
}

export function exportReport(data) {
  return request({ url: '/reports/export', method: 'post', data, responseType: 'blob' })
}

export function archiveReport(data) {
  return request({ url: '/reports/archives', method: 'post', data })
}

export function getReportArchives() {
  return request({ url: '/reports/archives', method: 'get' })
}

export function getReportArchive(archiveId) {
  return request({ url: `/reports/archives/${archiveId}`, method: 'get' })
}

export function exportReportArchive(archiveId) {
  return request({ url: `/reports/archives/${archiveId}/export`, method: 'get', responseType: 'blob' })
}
