# 生产调度 DEMO 中文化、视觉与动态演示升级 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将六个生产调度演示页升级为中文、专业工业科技风页面，为五个特色功能增加确定性自动演示，并输出经验证的 PNG、H.264 MP4 和六页动态演示版 PPTX。

**Architecture:** 保留既有接口、英文枚举、Mock、权限和审计，将中文化限制在新的纯展示映射层；用共享的轻量演示控制器编排页面视觉阶段并调用既有命令服务完成最终业务状态；用 Playwright 统一截屏/录制，再以现有 PPTX 的六个源页为模板原位替换主视觉。

**Tech Stack:** React 19、TypeScript 7、Ant Design 6、Zustand、Vitest、Testing Library、Playwright、Vite、FFmpeg、`@oai/artifact-tool`。

## Global Constraints

- 不修改接口字段、状态机枚举、Mock 数据结构、审计原始值、权限逻辑或既有业务命令语义。
- 不覆盖现有截图、视频或 `output/B项目-生产调度管理模块特色功能介绍-20260724.pptx`。
- 主要状态标签、按钮和业务说明不得直接显示英文枚举。
- PLC、ECS、TOS、AGV、UWB 可保留；首次出现时补中文解释。
- 五段自动演示每段 8～12 秒，开始前复位，完成后停留最终状态，支持 `?autoplay=1` 和 `prefers-reduced-motion`。
- 页面在 1440×900 和 1600×900 下不得有横向滚动、遮挡或内容溢出。
- 继续明确“样机演示/规则样例/模拟状态”，不得暗示真实生产数据、真实算法、实机控制、生产级弱网能力或正式投产。
- 保留工作树中与本任务无关的既有修改，不修改 `src/app/routeCatalog.ts`、`src/pages/monitor/OperationMonitorPage.tsx` 或 `src/pages/yard/RoadAppointmentPage.tsx`。

---

### Task 1: 建立并测试业务展示文案映射层

**Files:**
- Create: `src/presentation/businessCopy.ts`
- Create: `src/presentation/__tests__/businessCopy.test.ts`

**Interfaces:**
- Produces: `businessLabel(value: string | null | undefined): string`
- Produces: `businessTone(value: string | null | undefined): BusinessTone`
- Produces: `businessCode(value: string | null | undefined): string | undefined`
- Produces: `industryTermLabel(value: IndustryTerm): string`

- [ ] **Step 1: 写必需映射的失败测试**

```ts
import { businessLabel } from '../businessCopy';

const required = {
  RECEIVED: '已接收',
  READY: '就绪',
  WAITING: '等待处理',
  PENDING_CONFIRM: '待确认',
  BLOCKED: '已阻断',
  AVAILABLE: '可用',
  BUSY: '忙碌',
  ASSIGNED: '已分配',
  READY_QUEUE: '待派工',
  DISPATCHED: '已派工',
  EXECUTING: '执行中',
  IN_PROGRESS: '处理中',
  ACKNOWLEDGED: '已确认',
  PENDING_REVIEW: '待复核',
  HANDLING: '处置中',
  CLOSED: '已关闭',
  LOCKED: '联锁锁定',
  OVERRIDE_PENDING: '解锁审批中',
  RESET_REQUESTED: '恢复申请中',
  RESTORED: '已恢复',
  PENDING_UPLOAD: '待上传',
  VALIDATING: '校验中',
  CONFLICT: '版本冲突',
  MERGED: '已合并',
  SUCCESS: '成功',
  FAILED: '失败',
  RECOGNITION: '识别',
  INSPECT: '检查',
  UNLOAD: '卸载',
  TRANSFER: '转运',
  STORAGE: '入库',
  LOAD: '装载',
  DEVICE_OFFLINE: '设备离线',
  DATA_CONFLICT: '数据冲突',
  INTERLOCK: '安全联锁',
  TIMEOUT: '任务超时',
  INFO: '提示',
  MINOR: '一般',
  MAJOR: '重大',
  CRITICAL: '紧急',
} as const;

it.each(Object.entries(required))('%s 显示为 %s', (source, expected) => {
  expect(businessLabel(source)).toBe(expected);
});
```

- [ ] **Step 2: 运行测试并确认因模块缺失而失败**

Run: `pnpm exec vitest run src/presentation/__tests__/businessCopy.test.ts`

Expected: FAIL，提示无法解析 `../businessCopy`。

- [ ] **Step 3: 实现纯展示映射和语气映射**

实现不可变映射对象；`businessLabel` 对未知的全大写枚举使用中文“未识别状态”，不得直接返回原值；`businessCode` 保留原始值供技术详情；行业缩写映射为“生产调度管理系统（TOS）”等首次出现文案。

- [ ] **Step 4: 运行映射专项与全量单元测试**

Run: `pnpm exec vitest run src/presentation/__tests__/businessCopy.test.ts`

Expected: PASS。

Run: `pnpm test`

Expected: 既有测试继续通过。

### Task 2: 在总览及共享组件接入中文展示

**Files:**
- Modify: `src/pages/dispatch/OverviewPage.tsx`
- Modify: `src/features/plan-entry/selectors.ts`
- Modify: `src/features/plan-entry/components/DemoScenarioBar.tsx`
- Modify: `src/features/plan-entry/components/OverviewKpiGrid.tsx`
- Modify: `src/features/plan-entry/components/PageIdentity.tsx`
- Modify: `src/features/plan-entry/components/PlanDetailDrawer.tsx`
- Modify: `src/features/plan-entry/components/PlanFilterBar.tsx`
- Modify: `src/features/plan-entry/components/RiskInterfacePanel.tsx`
- Modify: `src/features/plan-entry/components/YardSchematic.tsx`
- Modify: `src/pages/__tests__/OverviewPage.render.test.tsx`

**Interfaces:**
- Consumes: `businessLabel`, `businessCode`, `businessTone`
- Produces: 中文总览首屏，无主要英文枚举泄漏

- [ ] **Step 1: 扩展总览页面测试，断言中文状态且英文枚举不可见**

```tsx
expect(await screen.findByText('待确认')).toBeVisible();
expect(screen.getByText('已阻断')).toBeVisible();
expect(screen.queryByText('PENDING_CONFIRM', { exact: true })).not.toBeInTheDocument();
expect(screen.queryByText('BLOCKED', { exact: true })).not.toBeInTheDocument();
```

- [ ] **Step 2: 运行总览页面测试并确认英文断言失败**

Run: `pnpm exec vitest run src/pages/__tests__/OverviewPage.render.test.tsx`

Expected: FAIL，中文状态尚未渲染。

- [ ] **Step 3: 在 selector/view 组件接入映射**

主标签使用 `businessLabel`；技术编号放入 `.technical-code` 或 `title`；筛选项的 `value` 继续使用原枚举，`label` 使用中文。

- [ ] **Step 4: 运行总览测试和权限/动作回归**

Run: `pnpm exec vitest run src/pages/__tests__/OverviewPage*.test.tsx`

Expected: PASS。

### Task 3: 实现并测试共享自动演示控制器

**Files:**
- Create: `src/features/demo-presentation/demoSequence.ts`
- Create: `src/features/demo-presentation/useDemoSequence.ts`
- Create: `src/features/demo-presentation/DemoPlaybackControls.tsx`
- Create: `src/features/demo-presentation/demo-presentation.css`
- Create: `src/features/demo-presentation/index.ts`
- Create: `src/features/demo-presentation/__tests__/demoSequence.test.ts`
- Create: `src/features/demo-presentation/__tests__/DemoPlaybackControls.test.tsx`

**Interfaces:**
- Produces: `DemoSequenceStep`
- Produces: `useDemoSequence({ steps, onReset, onStep, onComplete, autoplay })`
- Produces: `<DemoPlaybackControls state={...} onStart={...} onReplay={...} />`

- [ ] **Step 1: 写顺序、总时长、重播和清理的失败测试**

```ts
vi.useFakeTimers();
const sequence = createDemoSequence([
  { id: 'idle', durationMs: 500 },
  { id: 'scan', durationMs: 900 },
  { id: 'done', durationMs: 1500 },
]);
expect(sequence.totalDurationMs).toBe(2900);
```

页面测试还需断言重播先调用 `onReset`，再从首阶段开始；卸载后无挂起计时器。

- [ ] **Step 2: 运行测试并确认模块缺失**

Run: `pnpm exec vitest run src/features/demo-presentation`

Expected: FAIL。

- [ ] **Step 3: 实现 AbortController/timeout 驱动的序列**

每次开始先取消前一轮并调用 `onReset`；根据 `matchMedia('(prefers-reduced-motion: reduce)')` 缩短纯视觉过渡，但保留阶段次序；解析 `autoplay=1` 只自动运行一次。

- [ ] **Step 4: 运行共享控制器测试**

Run: `pnpm exec vitest run src/features/demo-presentation`

Expected: PASS 且无 `act(...)` 警告。

### Task 4: 升级任务拆解页面并实现 10 秒演示

**Files:**
- Modify: `src/pages/dispatch/TaskDecompositionPage.tsx`
- Modify: `src/features/task-decomposition/components/CargoSummaryCard.tsx`
- Modify: `src/features/task-decomposition/components/ResourcePreview.tsx`
- Modify: `src/features/task-decomposition/components/RuleExplainPanel.tsx`
- Modify: `src/features/task-decomposition/components/TaskContextHeader.tsx`
- Modify: `src/features/task-decomposition/components/TaskEditDrawer.tsx`
- Modify: `src/features/task-decomposition/components/TaskTreePanel.tsx`
- Modify: `src/features/task-decomposition/task-decomposition.css`
- Modify: `src/pages/__tests__/TaskDecompositionPage.render.test.tsx`
- Modify: `src/pages/__tests__/TaskDecompositionPage.action.test.tsx`
- Modify: `e2e/ui-004-task-decomposition.spec.ts`

**Interfaces:**
- Consumes: mapping layer and shared demo controller
- Produces: `data-demo-step` and `data-demo-state` for capture/verification

- [ ] **Step 1: 写中文和演示状态的失败测试**

断言“开始演示”“重新播放”“拆解完成，可进入派工”，并使用假计时器验证节点按序出现。

- [ ] **Step 2: 运行专项测试确认失败**

Run: `pnpm exec vitest run src/pages/__tests__/TaskDecompositionPage.render.test.tsx src/pages/__tests__/TaskDecompositionPage.action.test.tsx`

- [ ] **Step 3: 接入映射与阶段显现**

`TaskTreePanel` 接收 `visibleNodeCount`、`highlightedDependencyCount`；页面序列调用既有生成/确认命令，最终显示成功条。

- [ ] **Step 4: 运行页面和 Playwright 专项**

Run: `pnpm exec vitest run src/pages/__tests__/TaskDecompositionPage*.test.tsx`

Run: `pnpm exec playwright test e2e/ui-004-task-decomposition.spec.ts`

Expected: PASS。

### Task 5: 升级资源匹配/派工页面并实现约 10 秒演示

**Files:**
- Modify: `src/pages/dispatch/DispatchBoardPage.tsx`
- Modify: `src/features/dispatch-board/selectors.ts`
- Modify: `src/features/dispatch-board/components/DispatchContextHeader.tsx`
- Modify: `src/features/dispatch-board/components/DispatchKpiStrip.tsx`
- Modify: `src/features/dispatch-board/components/ExecutionFeedbackPanel.tsx`
- Modify: `src/features/dispatch-board/components/ResourceAssignmentPanel.tsx`
- Modify: `src/features/dispatch-board/components/WorkOrderDetailPanel.tsx`
- Modify: `src/features/dispatch-board/components/WorkOrderQueue.tsx`
- Modify: `src/features/dispatch-board/dispatch-board.css`
- Modify: `src/pages/__tests__/DispatchBoardPage.render.test.tsx`
- Modify: `src/pages/__tests__/DispatchBoardPage.action.test.tsx`
- Modify: `e2e/ui-005-dispatch-board.spec.ts`

**Interfaces:**
- Consumes: mapping layer and shared demo controller
- Produces: scan/exclude/candidate/recommend/confirm display stages

- [ ] **Step 1: 写资源中文标签与扫描流程失败测试**

断言“可用”“忙碌”“推荐候选”“待人工确认”，不出现精确文本 `AVAILABLE`、`BUSY`。

- [ ] **Step 2: 运行测试确认失败**

Run: `pnpm exec vitest run src/pages/__tests__/DispatchBoardPage*.test.tsx`

- [ ] **Step 3: 实现资源演示阶段**

资源 selector 继续返回原始枚举；组件使用中文标签并通过 `data-match-state` 控制弱化/高亮；演示止于待人工确认，不自动冒充人工审批。

- [ ] **Step 4: 运行页面与 Playwright 专项**

Run: `pnpm exec vitest run src/pages/__tests__/DispatchBoardPage*.test.tsx`

Run: `pnpm exec playwright test e2e/ui-005-dispatch-board.spec.ts`

Expected: PASS。

### Task 6: 升级异常、安全和离线页面并实现三段演示

**Files:**
- Modify: `src/pages/monitor/ExceptionHandlingPage.tsx`
- Modify: `src/features/exception-handling/components/ExceptionActionPanel.tsx`
- Modify: `src/features/exception-handling/components/ExceptionContextHeader.tsx`
- Modify: `src/features/exception-handling/components/ExceptionDetailPanel.tsx`
- Modify: `src/features/exception-handling/components/ExceptionEvidencePanel.tsx`
- Modify: `src/features/exception-handling/components/ExceptionKpiStrip.tsx`
- Modify: `src/features/exception-handling/components/ExceptionLedger.tsx`
- Modify: `src/features/exception-handling/exception-handling.css`
- Modify: `src/pages/safety/SafetyInterlockPage.tsx`
- Modify: `src/features/safety-interlock/components/InterlockActionPanel.tsx`
- Modify: `src/features/safety-interlock/components/InterlockContextHeader.tsx`
- Modify: `src/features/safety-interlock/components/InterlockDetailPanel.tsx`
- Modify: `src/features/safety-interlock/components/InterlockKpiStrip.tsx`
- Modify: `src/features/safety-interlock/components/InterlockLedger.tsx`
- Modify: `src/features/safety-interlock/safety-interlock.css`
- Modify: `src/pages/operations/OfflineSyncPage.tsx`
- Modify: `src/features/offline-sync/components/OfflineCommandFeedback.tsx`
- Modify: `src/features/offline-sync/components/OfflineContextHeader.tsx`
- Modify: `src/features/offline-sync/components/OfflineKpiStrip.tsx`
- Modify: `src/features/offline-sync/components/OfflinePacketActionPanel.tsx`
- Modify: `src/features/offline-sync/components/OfflinePacketDetail.tsx`
- Modify: `src/features/offline-sync/components/OfflinePacketLedger.tsx`
- Modify: `src/features/offline-sync/offline-sync.css`
- Modify: `src/pages/__tests__/ExceptionHandlingPage*.test.tsx`
- Modify: `src/pages/__tests__/SafetyInterlockPage*.test.tsx`
- Modify: `src/pages/__tests__/OfflineSyncPage*.test.tsx`
- Modify: `e2e/ui-008-exception-handling.spec.ts`
- Modify: `e2e/ui-009-safety-interlock.spec.ts`
- Modify: `e2e/ui-010-offline-sync.spec.ts`

**Interfaces:**
- Consumes: mapping layer and shared demo controller
- Produces: three deterministic final states `CLOSED`、`RESTORED`、`MERGED` displayed as Chinese

- [ ] **Step 1: 分别写三页的中文和演示完成失败测试**

异常断言“待处理 → 处置中 → 待复核 → 已关闭”；安全断言“联锁锁定 → 恢复申请中 → 已恢复”；离线断言“待上传 → 校验中 → 版本冲突 → 已合并”。

- [ ] **Step 2: 运行三组测试确认失败**

Run: `pnpm exec vitest run src/pages/__tests__/ExceptionHandlingPage*.test.tsx src/pages/__tests__/SafetyInterlockPage*.test.tsx src/pages/__tests__/OfflineSyncPage*.test.tsx`

- [ ] **Step 3: 实现异常演示**

使用固定 Mock 原因、责任人和处置文本调用既有确认、处置、复核、关闭命令；演示按钮保持权限边界，失败进入既有错误反馈。

- [ ] **Step 4: 实现安全演示**

按输入快照、规则命中、触发、回执、申请、审批、恢复顺序调用既有命令；锁定阶段禁用高风险操作；显示“规则样例，不连接真实设备”。

- [ ] **Step 5: 实现离线演示**

按缓存、上传、校验、冲突、差异、合并顺序调用既有命令；显示服务端权威与 Mock 边界。

- [ ] **Step 6: 运行三组页面与 Playwright 专项**

Run: `pnpm exec vitest run src/pages/__tests__/ExceptionHandlingPage*.test.tsx src/pages/__tests__/SafetyInterlockPage*.test.tsx src/pages/__tests__/OfflineSyncPage*.test.tsx`

Run: `pnpm exec playwright test e2e/ui-008-exception-handling.spec.ts e2e/ui-009-safety-interlock.spec.ts e2e/ui-010-offline-sync.spec.ts`

Expected: PASS。

### Task 7: 应用全局工业视觉系统并做英语泄漏/响应式验收

**Files:**
- Modify: `src/styles/global.css`
- Modify: `src/styles/theme.ts`
- Modify: `src/features/plan-entry/plan-entry.css`
- Modify: `src/features/task-decomposition/task-decomposition.css`
- Modify: `src/features/dispatch-board/dispatch-board.css`
- Modify: `src/features/exception-handling/exception-handling.css`
- Modify: `src/features/safety-interlock/safety-interlock.css`
- Modify: `src/features/offline-sync/offline-sync.css`
- Create: `e2e/presentation-demo.spec.ts`
- Create: `e2e/visible-enum-audit.spec.ts`

**Interfaces:**
- Produces: 1440×900、1600×900 下无溢出的统一视觉系统

- [ ] **Step 1: 写响应式和英文枚举审计测试**

```ts
expect(await page.evaluate(() => document.documentElement.scrollWidth))
  .toBeLessThanOrEqual(viewport.width);
expect(await page.locator('main, [role="main"]').innerText())
  .not.toMatch(/\b(?:PENDING_CONFIRM|READY_QUEUE|DISPATCHED|HANDLING|LOCKED|CONFLICT|MERGED)\b/);
```

- [ ] **Step 2: 运行测试并保存失败证据**

Run: `pnpm exec playwright test e2e/presentation-demo.spec.ts e2e/visible-enum-audit.spec.ts`

- [ ] **Step 3: 统一主题与模块 CSS**

使用设计文档中的调色板、12～16px 圆角、克制阴影、技术编号次要色、200～400ms 过渡；为 reduced-motion 关闭位移/缩放动画。

- [ ] **Step 4: 运行类型、单元、端到端和正式构建**

Run: `pnpm exec tsc --noEmit`

Run: `pnpm test`

Run: `pnpm exec playwright test`

Run: `pnpm build`

Expected: 全部 PASS，构建无错误。

### Task 8: 生成 PNG 与五段 H.264 MP4

**Files:**
- Create: `tools/capture-production-dispatch-media.mjs`
- Create: `output/生产调度PPT动态素材-v2/01-生产调度模块总览-中文版.png`
- Create: `output/生产调度PPT动态素材-v2/02-任务自动拆解-封面.png`
- Create: `output/生产调度PPT动态素材-v2/02-任务自动拆解-演示.mp4`
- Create: `output/生产调度PPT动态素材-v2/03-资源状态匹配-封面.png`
- Create: `output/生产调度PPT动态素材-v2/03-资源状态匹配-演示.mp4`
- Create: `output/生产调度PPT动态素材-v2/04-异常处置闭环-封面.png`
- Create: `output/生产调度PPT动态素材-v2/04-异常处置闭环-演示.mp4`
- Create: `output/生产调度PPT动态素材-v2/05-安全联锁-封面.png`
- Create: `output/生产调度PPT动态素材-v2/05-安全联锁-演示.mp4`
- Create: `output/生产调度PPT动态素材-v2/06-离线同步-封面.png`
- Create: `output/生产调度PPT动态素材-v2/06-离线同步-演示.mp4`

**Interfaces:**
- Consumes: `data-demo-state="completed"` and `?autoplay=1`
- Produces: 1600×900、16:9、无声、8～12 秒 H.264 MP4

- [ ] **Step 1: 检查 FFmpeg 与 Playwright Chromium**

Run: `ffmpeg -version`

Run: `pnpm exec playwright --version`

- [ ] **Step 2: 用 Playwright 截图并录制 WebM**

每页等待稳定的初始状态后写封面；演示页使用 `?autoplay=1`，录制到完成后再等待 1.5 秒；不显示地址栏、开发工具或录制 UI。

- [ ] **Step 3: 转为 H.264 MP4**

Run per file:

```text
ffmpeg -y -i input.webm -an -c:v libx264 -pix_fmt yuv420p -movflags +faststart output.mp4
```

- [ ] **Step 4: 用 ffprobe 验证分辨率、编码、时长和无音轨**

Expected: `codec_name=h264`、`width=1600`、`height=900`、8≤duration≤12、无 audio stream。

### Task 9: 沿用现有六页模板生成动态演示版 PPT

**Files:**
- Create: task-local `template-audit.txt`
- Create: task-local `template-frame-map.json`
- Create: task-local `deviation-log.txt`
- Create: task-local `build_dynamic_deck.mjs`
- Create: `output/B项目-生产调度管理模块特色功能介绍-动态演示版-20260724.pptx`

**Interfaces:**
- Consumes: existing six-slide source deck, six poster images, five MP4 files
- Produces: six-slide template-preserving deck

- [ ] **Step 1: 完整检查六页源 PPT 和元素布局**

记录每个输出页对应同号源页；保留标题、副标题、业务价值和样机边界；标记待替换的截图/对比视觉对象。

- [ ] **Step 2: 准备模板 starter**

运行 `prepare_template_starter_deck.mjs`，六页均使用 `reuseMode: "duplicate-slide"`，`editTargets` 只指向明确的旧截图、截图容器和对比箭头。

- [ ] **Step 3: 用 `@oai/artifact-tool` 原位替换主视觉**

第 1 页替换总览截图；第 2～6 页以封面填充原主视觉区域。若 artifact-tool 的媒体接口能可靠写入并在重新导入后识别 MP4，则嵌入对应视频并设置自动播放一次、不循环；否则保留封面版并在 `deviation-log.txt` 记录视频替换区域。

- [ ] **Step 4: 导出、逐页渲染和检查占位符/溢出**

Run: presentation export renders。

Run: `slides_test.py <final.pptx>`

Run: `check_template_fidelity.mjs ...`

Expected: 6 页、无意外重叠、无空结构占位符、微软雅黑正常、图片不模糊、布局保持源模板层级。

### Task 10: 最终回归与交付记录

**Files:**
- Create: `docs/evidence/C14/verification.md`
- Create: `docs/evidence/C14/visible-copy-audit.txt`
- Create: `docs/evidence/C14/media-audit.txt`
- Create: `docs/evidence/C14/presentation-audit.txt`

**Interfaces:**
- Produces: 可追溯的修改、测试、素材、PPT 与边界报告

- [ ] **Step 1: 重跑最终验证矩阵**

Run: `pnpm exec tsc --noEmit`

Run: `pnpm test`

Run: `pnpm exec playwright test`

Run: `pnpm build`

- [ ] **Step 2: 统计映射数量、修改文件和保留英文**

保留英文分类只允许：行业缩写、路由、业务编号、规则号、版本号、追踪号、审计号以及技术详情中的原始枚举编码。

- [ ] **Step 3: 核对输出文件清单与哈希**

确认 11 个素材文件和 1 个 PPTX 均存在、非空且未覆盖旧素材。

- [ ] **Step 4: 写最终交付说明**

覆盖修改清单、映射数量、保留英文、五段流程/时长、测试与浏览器结果、素材目录、PPT 路径和 Mock/待生产化边界。
