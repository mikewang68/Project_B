/* Real UI writes against the independent development application. No API mocks. */
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { spawnSync } = require("node:child_process");
const { createRequire } = require("node:module");
const { chromium, expect: defaultExpect } = createRequire(
  path.join(__dirname, "../frontend/package.json"),
)("@playwright/test");
const expect = defaultExpect.configure({ timeout: 30000 });

const root = path.resolve(__dirname, "..");
function safeError(error) {
  let message = error.stack || String(error);
  const file = path.join(root, ".local/development-accounts.json");
  if (fs.existsSync(file)) {
    for (const account of JSON.parse(fs.readFileSync(file, "utf8"))) {
      if (account.password)
        message = message.replaceAll(account.password, "[REDACTED]");
    }
  }
  return message;
}
const base = process.env.TRUST_BASE_URL || "http://127.0.0.1:18180";
const runId =
  "UI-" +
  new Date().toISOString().replace(/[-:.]/g, "") +
  "-" +
  crypto.randomBytes(3).toString("hex");
const out = path.join(
  process.env.TRUST_TEST_RESULTS || path.join(root, ".local/test-results"),
  runId,
);
fs.mkdirSync(out, { recursive: true });
const report = {
  runId,
  startedAt: new Date().toISOString(),
  mode: "Real UI and real storage/ledger; simulated business data",
  checks: [],
  errors: [],
  records: [],
  exports: [],
};
const sha = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
const fields = [
  "sourceSystem",
  "sourceEventId",
  "eventType",
  "businessObjectId",
  "batchId",
  "occurredAt",
  "quantity",
  "unit",
  "location",
  "supplier",
  "receiver",
  "handoverId",
  "bundleIds",
  "relatedBatchIds",
  "relatedEventRefs",
  "evidenceIds",
  "details",
];
const csvCell = (value) => '"' + String(value).replaceAll('"', '""') + '"';
const csv = (rows) =>
  fields.join(",") +
  "\n" +
  rows
    .map((row) =>
      fields
        .map((key) =>
          csvCell(
            Array.isArray(row[key])
              ? row[key].join("|")
              : key === "details"
                ? JSON.stringify(row[key])
                : (row[key] ?? ""),
          ),
        )
        .join(","),
    )
    .join("\n") +
  "\n";

async function main() {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 980 },
    acceptDownloads: true,
  });
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  page.on("pageerror", (error) => report.errors.push(error.message));
  async function check(name, action) {
    const start = Date.now();
    try {
      const detail = await action();
      report.checks.push({
        name,
        status: "PASS",
        seconds: (Date.now() - start) / 1000,
        detail,
      });
      console.log("PASS:", name);
      return detail;
    } catch (error) {
      report.checks.push({
        name,
        status: "FAIL",
        seconds: (Date.now() - start) / 1000,
      });
      throw error;
    }
  }
  async function actionResponse(method, suffix, action, status = 200) {
    const [response] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === method &&
          new URL(r.url()).pathname === "/api/v1" + suffix,
      ),
      action(),
    ]);
    assert.equal(
      response.status(),
      status,
      method + " " + suffix + ": " + (await response.text()),
    );
    return response.json();
  }
  async function get(suffix) {
    const response = await context.request.get(base + "/api/v1" + suffix);
    assert.equal(response.status(), 200);
    return response.json();
  }
  async function committed(id) {
    let row;
    await expect
      .poll(
        async () => {
          row = await get("/events/" + id);
          return row.file_state === "STORED" && row.chain_state === "COMMITTED";
        },
        { timeout: 120000, intervals: [500, 1000, 2000] },
      )
      .toBe(true);
    assert.ok(row.tx_id && row.manifest_cid && row.manifest_sha256);
    assert.ok(row.evidence.every((e) => e.cid && e.storage_state === "STORED"));
    return row;
  }
  async function nav(name) {
    await page.locator("nav").getByRole("button", { name }).click();
  }
  async function openEvent(sourceId) {
    await nav("事件台账");
    await page.getByLabel("搜索事件").fill(sourceId);
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === "GET" &&
          new URL(r.url()).pathname === "/api/v1/events" &&
          new URL(r.url()).searchParams.get("q") === sourceId,
      ),
      page.getByRole("button", { name: "查询", exact: true }).click(),
    ]);
    await page
      .locator("tbody tr")
      .filter({ has: page.getByText(sourceId, { exact: true }) })
      .getByRole("button", { name: "详情 →", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { level: 2 }).filter({ hasText: sourceId }),
    ).toBeVisible();
  }
  async function verifyCurrent(id) {
    const value = await actionResponse(
      "POST",
      "/events/" + id + "/verify",
      () => page.getByRole("button", { name: "核验证据", exact: true }).click(),
    );
    assert.equal(value.ok, true, JSON.stringify(value.checks));
    assert.ok(value.checks.some((c) => c.item === "Fabric 登记" && c.ok));
    await expect(
      page.getByRole("heading", { name: "核验通过", exact: true }),
    ).toBeVisible();
    return value;
  }
  async function importFile(name, content, count) {
    const value = await actionResponse("POST", "/imports", () =>
      page.locator(".import-panel input[type=file]").setInputFiles({
        name,
        mimeType: name.endsWith(".json") ? "application/json" : "text/csv",
        buffer: Buffer.from(content),
      }),
    );
    assert.equal(value.accepted, count);
    assert.equal(value.results.length, count);
    assert.ok(
      value.results.every((row) => row.ok),
      JSON.stringify(value),
    );
    await expect(
      page.getByRole("heading", {
        name: "已接收 " + count + " 条",
        exact: true,
      }),
    ).toBeVisible();
    return value.results.map((row) => row.id);
  }
  try {
    await check("录入员登录真实应用", async () => {
      const account = JSON.parse(
        fs.readFileSync(
          path.join(root, ".local/development-accounts.json"),
          "utf8",
        ),
      ).find((u) => u.username === (process.env.TRUST_UI_ACCOUNT || "editor"));
      assert.ok(account, "Configured UI test account is missing");
      await page.goto(base);
      await page.getByLabel("账号", { exact: true }).fill(account.username);
      await page.getByLabel("密码", { exact: true }).fill(account.password);
      await page
        .getByRole("button", { name: "进入工作台", exact: true })
        .click();
      await expect(
        page.getByRole("heading", { name: "事件台账", exact: true }),
      ).toBeVisible();
      const state = await get("/status");
      assert.equal(state.database, "UP");
      assert.equal(state.fabric, "UP");
      assert.equal(state.ipfs.state, "UP");
      assert.equal(state.ipfs.node, "IPFS 独立节点");
      report.beforeEvents = state.events;
      return { allComponentsUp: true };
    });

    const evidence = await check("页面上传模拟 PDF 与 PNG 附件", async () => {
      await nav("录入与导入");
      const saved = [];
      for (const filename of [
        "quality-simulated.pdf",
        "handover-simulated.png",
      ]) {
        const value = await actionResponse("POST", "/evidence", () =>
          page
            .locator("input[type=file][multiple]")
            .setInputFiles(path.join(root, "samples", filename)),
        );
        assert.equal(
          value.sha256,
          sha(fs.readFileSync(path.join(root, "samples", filename))),
        );
        await expect(page.getByText(filename, { exact: true })).toBeVisible();
        saved.push(value);
      }
      return saved;
    });
    const arrivalSource = runId + "-ARRIVAL";
    const batch = runId + "-STEEL";
    const arrival = await check(
      "页面录入 100 吨到货并完成真实存证",
      async () => {
        const values = {
          来源系统: "UI-ACCEPTANCE",
          来源事件号: arrivalSource,
          批次号: batch,
          业务对象编号: runId + "-ORDER",
          数量: "100",
          单位: "吨",
          作业地点: "模拟验收货场 A 区",
          供应来源: "模拟钢材供应商",
          接收单位: "模拟项目货场",
          备注: "真实页面验收用模拟业务数据，不作为实际库存。",
        };
        for (const [label, value] of Object.entries(values))
          await page.getByLabel(label, { exact: true }).fill(value);
        const result = await actionResponse(
          "POST",
          "/events",
          () =>
            page.getByRole("button", { name: "提交事件", exact: true }).click(),
          202,
        );
        const row = await committed(result.id);
        assert.equal(row.evidence.length, 2);
        const input = JSON.parse(row.canonical_json).event;
        assert.equal(Number(input.quantity), 100);
        await verifyCurrent(row.id);
        await page.screenshot({
          path: path.join(out, "arrival-verified.png"),
          fullPage: true,
        });
        report.records.push(row);
        return row;
      },
    );
    const baseInput = JSON.parse(arrival.canonical_json).event;
    const acceptedInput = {
      ...baseInput,
      sourceEventId: runId + "-ACCEPT",
      eventType: "ACCEPTANCE",
      relatedEventRefs: ["UI-ACCEPTANCE:" + arrivalSource],
      evidenceIds: evidence.map((e) => e.id),
    };
    const accepted = await check(
      "页面 JSON 导入验收事件并完成真实存证",
      async () => {
        await nav("录入与导入");
        const content = JSON.stringify([acceptedInput], null, 2);
        fs.writeFileSync(path.join(out, "acceptance.json"), content);
        const [id] = await importFile("acceptance.json", content, 1);
        const row = await committed(id);
        report.records.push(row);
        assert.deepEqual(
          JSON.parse(row.canonical_json).event.evidenceIds.sort(),
          evidence.map((e) => e.id).sort(),
        );
        return row;
      },
    );
    const dispatchInputs = [60, 40].map((amount) => ({
      ...baseInput,
      sourceEventId: runId + "-DISPATCH-" + amount,
      eventType: "DISPATCH",
      quantity: amount,
      batchId: batch + "-" + amount,
      relatedBatchIds: [batch],
      relatedEventRefs: ["UI-ACCEPTANCE:" + acceptedInput.sourceEventId],
      handoverId: runId + "-HANDOVER-" + amount,
      receiver: "模拟接收单位 " + amount,
      evidenceIds: evidence.map((e) => e.id),
    }));
    const dispatches = await check(
      "页面 CSV 导入 60/40 吨发运并完成真实存证",
      async () => {
        const content = csv(dispatchInputs);
        fs.writeFileSync(path.join(out, "dispatches.csv"), content);
        const ids = await importFile("dispatches.csv", content, 2);
        const saved = [];
        for (const id of ids) {
          const row = await committed(id);
          saved.push(row);
          report.records.push(row);
        }
        assert.deepEqual(
          saved.map((r) => Number(JSON.parse(r.canonical_json).event.quantity)),
          [60, 40],
        );
        return saved;
      },
    );
    const correction = await check(
      "页面追加更正并保留原记录及两次链上登记",
      async () => {
        await openEvent(arrivalSource);
        await page
          .getByRole("button", { name: "追加更正", exact: true })
          .click();
        await expect(
          page.getByRole("heading", { name: "追加更正", exact: true }),
        ).toBeVisible();
        await page
          .getByLabel("来源事件号", { exact: true })
          .fill(runId + "-CORRECTION");
        await page
          .getByLabel("作业地点", { exact: true })
          .fill("模拟验收货场 A-01 区（补充定位）");
        await page
          .getByLabel("备注", { exact: true })
          .fill("模拟更正：补充作业地点，100 吨数量保持原输入。");
        const value = await actionResponse(
          "POST",
          "/events/" + arrival.id + "/corrections",
          () =>
            page.getByRole("button", { name: "提交事件", exact: true }).click(),
          202,
        );
        const row = await committed(value.id);
        assert.equal(row.version, 2);
        assert.equal(row.supersedes_id, arrival.id);
        assert.equal(row.versions.length, 2);
        assert.equal(row.evidence.length, 2);
        assert.equal(
          Number(JSON.parse(row.canonical_json).event.quantity),
          100,
        );
        await verifyCurrent(row.id);
        await page.getByText("存证标识与历史版本", { exact: true }).click();
        await expect(page.locator(".version-list button")).toHaveCount(2);
        await page.screenshot({
          path: path.join(out, "correction-history.png"),
          fullPage: true,
        });
        await page
          .locator(".version-list button")
          .filter({ hasText: /\bv1\s*·/ })
          .click();
        await expect(
          page
            .getByRole("heading", { level: 2 })
            .filter({ hasText: arrivalSource }),
        ).toBeVisible();
        const original = await get("/events/" + arrival.id);
        assert.equal(original.canonical_json, arrival.canonical_json);
        assert.equal(original.tx_id, arrival.tx_id);
        await verifyCurrent(arrival.id);
        report.records.push(row);
        return row;
      },
    );
    await check("页面下载附件与原始模拟文件逐字节一致", async () => {
      for (const file of evidence) {
        const row = page
          .locator(".file-row")
          .filter({ has: page.getByText(file.filename, { exact: true }) });
        const [download] = await Promise.all([
          page.waitForEvent("download"),
          row.getByRole("button", { name: "下载", exact: true }).click(),
        ]);
        const target = path.join(out, file.filename);
        await download.saveAs(target);
        assert.deepEqual(
          fs.readFileSync(target),
          fs.readFileSync(path.join(root, "samples", file.filename)),
        );
      }
      return { files: evidence.length };
    });
    await check("页面按来源批次及两次发运交接单双向溯源", async () => {
      const ids = [
        arrival.id,
        accepted.id,
        ...dispatches.map((r) => r.id),
        correction.id,
      ];
      for (const [kind, value] of [
        ["BATCH", batch],
        ["BATCH", batch + "-60"],
        ["BATCH", batch + "-40"],
        ...dispatchInputs.map((r) => ["HANDOVER", r.handoverId]),
      ]) {
        await nav("批次溯源");
        await page.locator("form.toolbar select").selectOption(kind);
        await page.getByLabel("溯源查询值").fill(value);
        const found = await actionResponse("GET", "/trace", () =>
          page.getByRole("button", { name: "查看溯源", exact: true }).click(),
        );
        assert.ok(ids.every((id) => found.items.some((r) => r.id === id)));
        assert.deepEqual(found.missingReferences, []);
        assert.equal(found.truncated, false);
        await expect(page.locator(".timeline-card")).toHaveCount(5);
      }
      await page.screenshot({
        path: path.join(out, "dispatch-trace.png"),
        fullPage: true,
      });
      return { queries: 5, eventsPerQuery: 5 };
    });
    await check(
      "页面核验两次发运并导出真实证据包，离线摘要检查通过",
      async () => {
        for (const row of dispatches) {
          await openEvent(row.source_event_id);
          const checked = await verifyCurrent(row.id);
          assert.equal(checked.ledger.txId, row.tx_id);
          const [download] = await Promise.all([
            page.waitForEvent("download"),
            page
              .getByRole("button", { name: "导出证据包", exact: true })
              .click(),
          ]);
          const filename = row.source_event_id + ".zip";
          const target = path.join(out, filename);
          await download.saveAs(target);
          const result = spawnSync(
            process.env.TRUST_PYTHON || "python",
            [
              "-X",
              "utf8",
              path.join(root, "scripts/verify-export.py"),
              target,
              "--manifest-sha256",
              checked.ledger.manifestSha256,
            ],
            { encoding: "utf8" },
          );
          assert.equal(
            result.status,
            0,
            result.stderr || result.stdout || String(result.error),
          );
          report.exports.push({
            file: filename,
            sha256: sha(fs.readFileSync(target)),
            eventId: row.id,
            manifestSha256: checked.ledger.manifestSha256,
            reference: "Separate online verification response before export",
            offlineResult: result.stdout.trim(),
          });
        }
        return { exports: 2, offlineChecks: 2 };
      },
    );
    await check("五条新事件全部完成，组件健康且页面无脚本错误", async () => {
      await openEvent(accepted.source_event_id);
      await verifyCurrent(accepted.id);
      await nav("运行状态");
      await expect(
        page.getByRole("heading", { name: "IPFS 文件归档", exact: true }),
      ).toBeVisible();
      const state = await get("/status");
      assert.equal(state.database, "UP");
      assert.equal(state.fabric, "UP");
      assert.equal(state.ipfs.state, "UP");
      assert.equal(state.events, report.beforeEvents + 5);
      const tasks = await get("/tasks");
      assert.ok(
        report.records.every((r) =>
          tasks.some((t) => t.event_id === r.id && t.state === "DONE"),
        ),
      );
      assert.deepEqual(report.errors, []);
      report.afterEvents = state.events;
      await page.screenshot({
        path: path.join(out, "final-status.png"),
        fullPage: true,
      });
      return {
        newEvents: 5,
        newTasksDone: 5,
        componentHealth: "UP",
        pageErrors: 0,
      };
    });
    report.status = "PASS";
  } catch (error) {
    report.status = "FAIL";
    report.failure = safeError(error);
    await page
      .screenshot({ path: path.join(out, "failure.png"), fullPage: true })
      .catch(() => {});
    throw error;
  } finally {
    report.finishedAt = new Date().toISOString();
    report.browser = browser.version();
    fs.writeFileSync(
      path.join(out, "workflow-real.json"),
      JSON.stringify(report, null, 2),
    );
    await browser.close();
    console.log("Results:", path.relative(root, out));
  }
}
main().catch((error) => {
  console.error(safeError(error));
  process.exitCode = 1;
});
