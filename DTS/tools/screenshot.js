/**
 * 截图脚本：用 Edge 打开 DT 页面，等待 Cesium 场景渲染后截图
 * 用法：node screenshot.js <url> <输出png> [等待秒数]
 */
const puppeteer = require('puppeteer-core')

const url = process.argv[2] || 'http://localhost:5174/'
const out = process.argv[3] || 'dt-screenshot.png'
const waitSec = Number(process.argv[4] || 10)

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'

async function main() {
  const browser = await puppeteer.launch({
    executablePath: EDGE,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--use-gl=swiftshader', '--enable-webgl'],
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 900 })

  // 收集控制台错误
  const errors = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text())
  })
  page.on('pageerror', (err) => errors.push('PAGEERROR: ' + err.message))

  console.log(`打开 ${url} ...`)
  await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 })

  // 等待场景渲染
  await new Promise((r) => setTimeout(r, waitSec * 1000))

  await page.screenshot({ path: out, fullPage: false })
  console.log(`截图已保存: ${out}`)

  if (errors.length > 0) {
    console.log('\n=== 控制台错误 ===')
    errors.slice(0, 10).forEach((e) => console.log(' -', e.slice(0, 200)))
  } else {
    console.log('\n无控制台错误')
  }

  await browser.close()
}

main().catch((err) => {
  console.error('截图失败:', err.message)
  process.exit(1)
})
