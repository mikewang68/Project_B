export function displayReading(value) {
  if (value === 'null' || value == null) return '未知（未提供）'
  try { const parsed = JSON.parse(value); return parsed === null ? '未知（未提供）' : String(parsed) }
  catch { return '无效读数' }
}
