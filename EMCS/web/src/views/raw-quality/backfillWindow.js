// REQ-014：从当前连续缺测槽派生后端 [cacheStart, cacheEnd) 补传窗口。
export function deriveBackfillWindow(readings = [], samplingInterval = '') {
  const startIndex = readings.findIndex((reading) => reading.quality === 'miss')
  const intervalMatch = /^(\d+)min$/.exec(samplingInterval)
  if (startIndex === -1 || !intervalMatch) return null

  let endIndex = startIndex
  while (readings[endIndex + 1]?.quality === 'miss') endIndex++

  const parts = readings[endIndex].ts.match(
    /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/
  )
  if (!parts) return null

  const [, year, month, day, hour, minute, second] = parts
  const cacheEnd = new Date(Date.UTC(year, month - 1, day, hour, minute, second))
  cacheEnd.setUTCMinutes(cacheEnd.getUTCMinutes() + Number(intervalMatch[1]))

  return {
    cacheStart: readings[startIndex].ts,
    cacheEnd: cacheEnd.toISOString().slice(0, 19).replace('T', ' ')
  }
}
