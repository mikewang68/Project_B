import { mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

import { chromium } from '@playwright/test';

const baseUrl = process.env.DEMO_BASE_URL ?? 'http://127.0.0.1:4173';
const outputDir = path.resolve(
  process.cwd(),
  '..',
  'output',
  '生产调度PPT动态素材-v2',
);
const rawVideoDir = path.resolve(
  process.cwd(),
  '..',
  'output',
  '.production-dispatch-raw-video',
);

const viewport = { width: 1600, height: 900 };
const recordingDurationMs = 11_000;
const overviewOnly = process.env.CAPTURE_OVERVIEW_ONLY === '1';

const demos = [
  {
    name: '任务自动拆解',
    route: '/dispatch/plans/PLAN-001/tasks?scenarioId=SCN-01&autoplay=1',
    poster: '02-任务自动拆解-封面.png',
    rawVideo: '02-任务自动拆解-演示.webm',
  },
  {
    name: '资源智能匹配与派工',
    route: '/dispatch/work-orders?planId=PLAN-001&scenarioId=SCN-01&from=task-decomposition&autoplay=1',
    poster: '03-资源状态匹配-封面.png',
    rawVideo: '03-资源状态匹配-演示.webm',
  },
  {
    name: '异常识别与闭环',
    route: '/monitor/exceptions?workOrderId=C06-WO-PLAN-001-G001-02&planId=PLAN-001&scenarioId=SCN-01&from=dispatch-board&autoplay=1',
    poster: '04-异常处置闭环-封面.png',
    rawVideo: '04-异常处置闭环-演示.webm',
  },
  {
    name: '安全联锁闭环',
    route: '/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling&autoplay=1',
    poster: '05-安全联锁-封面.png',
    rawVideo: '05-安全联锁-演示.webm',
  },
  {
    name: '离线同步冲突合并',
    route: '/operations/offline-sync?scenarioId=SCN-01&from=monitor&autoplay=1',
    poster: '06-离线同步-封面.png',
    rawVideo: '06-离线同步-演示.webm',
  },
];

async function waitForStablePage(page) {
  await page.waitForLoadState('domcontentloaded');
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
}

async function captureOverview(browser) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  await page.goto(`${baseUrl}/dispatch/overview`);
  await waitForStablePage(page);
  await page.getByRole('heading', { name: '调度总览' }).waitFor({
    state: 'visible',
    timeout: 12_000,
  });
  await page.waitForTimeout(350);
  await page.screenshot({
    path: path.join(outputDir, '01-生产调度模块总览-中文版.png'),
    fullPage: false,
  });
  await context.close();
}

async function captureDemo(browser, demo) {
  const context = await browser.newContext({
    viewport,
    recordVideo: {
      dir: rawVideoDir,
      size: viewport,
    },
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });

  const captureStartedAt = Date.now();
  await page.goto(`${baseUrl}${demo.route}`);
  await waitForStablePage(page);

  const finalStage = page.locator('.demo-stage-rail li:last-child[aria-current="step"]');
  await finalStage.waitFor({ state: 'visible', timeout: 12_000 });
  await page.waitForTimeout(350);
  await page.screenshot({
    path: path.join(outputDir, demo.poster),
    fullPage: false,
  });

  const reachedFinalStage = await page.evaluate(() => {
    const stage = document.querySelector('.demo-stage-rail li:last-child[aria-current="step"]');
    return stage ? 1 : 0;
  });
  if (reachedFinalStage !== 1) {
    throw new Error(`${demo.name} did not reach its final presentation stage.`);
  }

  await page.waitForTimeout(Math.max(0, recordingDurationMs - (Date.now() - captureStartedAt)));
  const playbackText = await page.locator('.demo-playback-controls').innerText();
  if (playbackText.includes('演示异常')) {
    throw new Error(`${demo.name} failed during capture: ${playbackText}`);
  }
  if (errors.length > 0) {
    throw new Error(`${demo.name} emitted browser errors: ${errors.join(' | ')}`);
  }

  const video = page.video();
  await context.close();
  if (!video) throw new Error(`${demo.name} did not create a video.`);
  await video.saveAs(path.join(rawVideoDir, demo.rawVideo));
}

await mkdir(outputDir, { recursive: true });
if (!overviewOnly) {
  await rm(rawVideoDir, { recursive: true, force: true });
  await mkdir(rawVideoDir, { recursive: true });
}

const browser = await chromium.launch({ headless: true });
try {
  await captureOverview(browser);
  if (!overviewOnly) {
    for (const demo of demos) {
      await captureDemo(browser, demo);
      process.stdout.write(`captured ${demo.name}\n`);
    }
  }
} finally {
  await browser.close();
}

process.stdout.write(JSON.stringify({
  baseUrl,
  outputDir,
  rawVideoDir,
  viewport,
  recordingDurationMs,
  demos: demos.map(({ name, poster, rawVideo }) => ({ name, poster, rawVideo })),
}, null, 2));
