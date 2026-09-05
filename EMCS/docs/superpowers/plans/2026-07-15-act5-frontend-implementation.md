# 第五幕前端成本与报表两页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. The steps below are ordered implementation instructions.

**Goal:** 交付成本核算与报表导出两页，完整演示真实默认态发现异常、R10 带参下钻、三步反查、重算差异、转建议和五段式月报导出/归档。

**Architecture:** 页面只消费后端冻结契约，不保存第二套金额、环比、峰平谷或权限算法。成本页按“月度总览 → 峰平谷证据 → trace 抽屉 → 版本/差异”组织；报表页按模板选择、canonical preview、Excel 导出和冻结归档组织。复杂交互拆成纯 helper + 小组件，Node `node:test` 验证参数/权限/错误策略，Vite build 验证 Vue 集成。

**Tech Stack:** Vue 3、JavaScript、Element Plus、ECharts 5.6、Axios、file-saver、Node `node:test`、Vite 6。

## Global Constraints

- 事实来源：`docs/mock-contracts.md` §6；后端 API 绿灯后开工。
- 不新增 mock 文件，不硬编码金额、日期、月份、对象、R10 占比、Top5 或版本号。
- 默认筛选只从接口 filters/period 和 route query 恢复；`DEMO_NOW` 不在前端计算。
- 环比、Top5、anomalyEvidence、diffSummary、签名和 allowed actions 全部由后端返回；前端只格式化与展示。
- 单档水/压缩空气不伪造峰平谷图；只展示后端 flatOnly。
- 写失败遵循 403 拦截、409 刷新、422 保留输入；下载错误必须解析 blob 错误包。
- `ops_user/dispatch_user` 依赖菜单和后端 403 双重防线；前端不以隐藏按钮替代鉴权。
- frontend-dev 只改 `web/`；执行 agent 不提交，由主控创建检查点提交。
- 页面和 API 处标注 REQ-051～062、073/074/076 与 PRD §5.9。

---

## File Structure

### 新建

- `web/src/api/cost.js`：月度视图、trace、tariff、allocation、recompute/review API。
- `web/src/api/reports.js`：templates、preview、export、archive API 与 blob 下载。
- `web/src/views/energy/shared/act5.js`：纯参数恢复、深链、展示映射、写失败策略、下载文件名。
- `web/src/views/energy/cost/record.vue`：成本核算页容器。
- `web/src/views/energy/cost/report.vue`：报表导出页容器。
- `web/src/views/energy/cost/components/CostMonthTrend.vue`
- `web/src/views/energy/cost/components/TouCompositionChart.vue`
- `web/src/views/energy/cost/components/CostTraceDrawer.vue`
- `web/src/views/energy/cost/components/TariffVersionTable.vue`
- `web/src/views/energy/cost/components/AllocationRuleTable.vue`
- `web/src/views/energy/cost/components/RecomputeDiffTable.vue`
- `web/src/views/energy/cost/components/ReportPreview.vue`
- `web/src/views/energy/cost/components/ReportArchiveTable.vue`
- `web/test/act5-frontend.test.js`

### 修改

- `web/src/views/energy/alert/list.vue`：R10 详情使用 backend costDeepLink。
- `web/src/views/energy/alert/suggestion.vue`：成本来源建议显示返回成本页入口。
- `web/src/api/suggestions.js`：人工建议请求透传可信 sourceContext。
- `web/src/plugins/download.js`：若现有 helper 无 POST blob 支持，增加带认证的通用 `postBlob`，不复制 token/错误解析逻辑。

---

### Task 1: API 层与纯交互契约

**Owner:** frontend-dev

**Files:**
- Create: `web/src/api/cost.js`
- Create: `web/src/api/reports.js`
- Create: `web/src/views/energy/shared/act5.js`
- Create: `web/test/act5-frontend.test.js`

**Interfaces:**
- Produces:
  - `costFiltersFromRoute(query, serverDefaults) -> filters`
  - `costDrillTarget(drillParams) -> RouteLocationRaw`
  - `costTraceTarget(row, version?) -> query`
  - `costWriteFailurePolicy(status) -> policy`
  - `reportFileName(headers, fallback) -> string`

**Step 1: 写失败测试**

```javascript
test('真实默认值来自服务端，route query 只覆盖显式字段', () => {
  assert.deepEqual(costFiltersFromRoute({}, {
    statMonth: '2026-07', zone: 'ALL', energyType: 'electricity'
  }), { statMonth: '2026-07', zone: 'ALL', energyType: 'electricity' })
})
```

另测 R10 drillParams 原样带入、不把 sourceEventId 丢失；403/409/422 策略；API endpoint/method；export 使用 POST blob。

**Step 2: 运行并确认红灯**

```bash
cd web
node --test test/act5-frontend.test.js
```

Expected: FAIL，act5 helper/API 尚不存在。

**Step 3: 实现 API 与 helper**

所有写请求只发送表单拥有的字段；operator、金额、diff、签名结果不从客户端伪造。report export/archive 使用同形 `{templateCode,period,filters}`。

**Step 4: 增加源码负向测试**

扫描 `web/src/views/energy/cost`，禁止出现 `59501.99/161920.03/138014.50/12.368/2026-07-12` 和 `mock` import；允许这些值只存在测试断言中。

**Step 5: 验证与主控提交**

```bash
cd web
node --test test/act5-frontend.test.js
cd ..
git add web/src/api web/src/views/energy/shared/act5.js web/test/act5-frontend.test.js
git commit -m "feat(act5): add cost and report client contracts"
```

Expected: all PASS。

---

### Task 2: 成本页真实默认、异常自显与三步反查

**Owner:** frontend-dev

**Files:**
- Create: `web/src/views/energy/cost/record.vue`
- Create: `web/src/views/energy/cost/components/CostMonthTrend.vue`
- Create: `web/src/views/energy/cost/components/TouCompositionChart.vue`
- Create: `web/src/views/energy/cost/components/CostTraceDrawer.vue`
- Modify: `web/test/act5-frontend.test.js`

**Interfaces:**
- Consumes: `GET /cost/month-view`、`GET /cost/trace`。

**Step 1: 写页面结构失败测试**

源码断言必须消费 `period.state/monthTrend/anomalyEvidence/touComposition/groups/topCostObjects/costWarnings`；没有按 momPct 推导异常的表达式；常驻“管理核算口径，不替代财务结算”。

**Step 2: 实现筛选与真实默认**

首次无 route query 时先请求后端默认；接口返回 filters 后同步页面。05 partial、07 inProgress 使用 periodState 标签；切换月份/区域/介质/groupBy 重新请求。

**Step 3: 实现发现与带参下钻**

月趋势使用后端 totalCost/momPct/anomaly；点击 anomaly.drillParams 更新 route query 并请求 2026-06/B/electricity/focus=R10。页面展示 backend anomalyEvidence 的基线占比、报告占比、diffPp、thresholdPp。

**Step 4: 实现峰平谷与单档介质**

电力 ECharts 使用 backend peak/flat/valley；flatOnly 时销毁/隐藏三段图，展示单档价格卡。组件不得从 usageQty×price 重算 cost。

**Step 5: 实现 trace 抽屉**

固定三步标签：用量证据、单价版本、分摊规则；历史版本切换重新请求 backend。展示 recomputeChain、relatedAlert 和“转建议”入口，不在抽屉内拼 diff。

**Step 6: 验证与主控提交**

```bash
cd web
node --test test/act5-frontend.test.js
npm run build:prod
cd ..
git add web/src/views/energy/cost/record.vue web/src/views/energy/cost/components web/test/act5-frontend.test.js
git commit -m "feat(act5): build monthly cost investigation"
```

Expected: tests PASS；Vite build 成功。

---

### Task 3: 单价、分摊、重算差异与复核

**Owner:** frontend-dev

**Files:**
- Create: `web/src/views/energy/cost/components/TariffVersionTable.vue`
- Create: `web/src/views/energy/cost/components/AllocationRuleTable.vue`
- Create: `web/src/views/energy/cost/components/RecomputeDiffTable.vue`
- Modify: `web/src/views/energy/cost/record.vue`
- Modify: `web/test/act5-frontend.test.js`

**Step 1: 写角色无推断和写失败测试**

按钮可见性使用骨架标准 `v-hasRole` 对齐 `energy_mgr/finance`，禁止按 username 或页面自造权限常量；backend 403 始终是最终判据。409 后刷新 current 与 diff；422 保留表单。

**Step 2: 实现单价表**

历史版本只读；新增版本表单按 energyType 切换三档/flatOnly。成功后显示 recomputeRequired，不自动调用重算。

**Step 3: 实现分摊规则表**

展示 method/config/effective range/version/affectedMeters；只有 backend 允许时显示新增动作。历史 trace 始终展示冻结 snapshot，不用当前表行覆盖。

**Step 4: 实现重算与 diff**

发起表单只提交 statMonth/energyType/reason/版本选择；diffSummary 按 backend 顺序展示 old/new/delta/deltaPct，点击对象进入精确 old/new trace。pendingRecompute 时显示“正式导出不可用”。

**Step 5: 实现 finance 复核**

approve/reject 都要求 remark；成功刷新月视图、版本和报告可导出状态。前端不假定发起人必须不同，正式分离由 backend 控制。

**Step 6: 验证与主控提交**

```bash
cd web
node --test test/act5-frontend.test.js
npm run build:prod
cd ..
git add web/src/views/energy/cost web/test/act5-frontend.test.js
git commit -m "feat(act5): manage cost versions and differences"
```

Expected: tests/build PASS。

---

### Task 4: 报表模板、预览、Excel 与冻结归档

**Owner:** frontend-dev

**Files:**
- Create: `web/src/views/energy/cost/report.vue`
- Create: `web/src/views/energy/cost/components/ReportPreview.vue`
- Create: `web/src/views/energy/cost/components/ReportArchiveTable.vue`
- Modify: `web/src/plugins/download.js`
- Modify: `web/test/act5-frontend.test.js`

**Step 1: 写模板/灰态/下载失败测试**

断言页面消费 templates；订阅入口 disabled 且显示 backend label；preview 直接渲染 sections；export 读取 blob 和 Content-Disposition；archive detail/export 使用 archiveId。

**Step 2: 实现模板与周期默认**

默认月由 backend 返回上一完整月；进行中月展示 asOf。日报/月报/专项的表单字段只来自 template metadata，不写死成本/建议版本。

**Step 3: 实现五段预览**

月报严格按 usage/cost/alert/suggestion/quality 顺序；签名短码、generatedAt、版本摘要和 partial 说明全部展示 backend 值。页面不重新聚合关闭三档或告警统计。

**Step 4: 实现 POST blob 下载**

复用现有 token、blobValidate、printErrMsg、file-saver；409 blob 错误解析为业务消息，不保存损坏文件。

**Step 5: 实现归档列表和冻结预览**

归档详情只调用 archive endpoint；页面标注 frozen，不用当前 preview 替换。归档重下载使用 archive export。

**Step 6: 验证与主控提交**

```bash
cd web
node --test test/act5-frontend.test.js
npm run build:prod
cd ..
git add web/src/views/energy/cost/report.vue web/src/views/energy/cost/components web/src/plugins/download.js web/test/act5-frontend.test.js
git commit -m "feat(act5): preview and export energy reports"
```

Expected: tests/build PASS。

---

### Task 5: 告警/建议深链与全前端回归

**Owner:** frontend-dev + 主控联调

**Files:**
- Modify: `web/src/views/energy/alert/list.vue`
- Modify: `web/src/views/energy/alert/suggestion.vue`
- Modify: `web/src/api/suggestions.js`
- Modify: `web/test/act5-frontend.test.js`

**Step 1: 实现 R10 成本深链**

告警详情只消费 backend `costDeepLink`；不按 ruleCode 在前端拼 B 区/6 月。无深链时不显示按钮。

**Step 2: 实现成本异常转建议**

成本页把 backend suggestionContext 作为 sourceContext 提交；finance 只能在该上下文显示创建入口。成功后跳第四幕建议详情并保留 relatedSuggestionId。

**Step 3: 实现建议回成本证据**

建议详情只在 backend 返回 cost source deepLink 时显示“返回成本证据”；通用 manual/R06 不显示。

**Step 4: 运行全前端回归**

```bash
cd web
node --test test/*.test.js
npm run build:prod
```

Expected: 全部 node tests PASS；Vite build 成功；第三/四幕页面无回归。

**Step 5: 主控浏览器走查**

finance_user：默认发现 → R10 下钻 → trace → 重算/复核 → 转建议 → 月报 preview/export/archive；ops_user：成本路由 403；R10 告警深链进入同一筛选。检查窄屏、空态、loading、409/422 和 blob 错误。

**Step 6: 主控检查点提交**

```bash
git add web/src/views/energy/alert web/src/api/suggestions.js web/test/act5-frontend.test.js
git commit -m "feat(act5): connect cost alerts and suggestions"
```

---

## Frontend Completion Gate

1. `node --test web/test/*.test.js` 与 `npm run build:prod` 全绿。
2. 成本/报表源码无 mock import、金额/日期/占比硬编码和前端重算。
3. 05 partial、07 inProgress、R10 evidence、flatOnly、pendingRecompute 均有真实 UI 状态。
4. 告警页和成本页两条入口进入同一带参现场。
5. preview/export/archive 展示同一签名；归档页不重放当前查询。
6. finance 窄授权成功，通用建议写权不扩张；ops 成本访问被后端拦截。
