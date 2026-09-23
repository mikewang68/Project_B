import io
from pathlib import Path
p = Path(__file__).resolve().parent / 'frontend/src/composables/useEChart.ts'
s = io.open(p, encoding='utf-8').read()
old = """  onMounted(() => {
    if (!el.value) return
    chart.value = init(el.value)
    render(source.value)
    window.addEventListener('resize', resize)
  })"""
new = """  onMounted(() => {
    if (!el.value) return
    chart.value = init(el.value)
    ;(window as unknown as { __debugCharts?: unknown[] }).__debugCharts ||= []
    ;(window as unknown as { __debugCharts: unknown[] }).__debugCharts.push(chart.value)
    render(source.value)
    window.addEventListener('resize', resize)
  })"""
assert old in s
io.open(p, 'w', encoding='utf-8', newline='').write(s.replace(old, new, 1))
print('debug hook added')
