/* Navigation UI checks; existing business systems are not modified or logged into. */
const path = require('node:path'), fs = require('node:fs'), assert = require('node:assert/strict');
const {chromium} = require('node:module').createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const root = path.resolve(__dirname, '..'), base = process.argv[2] || process.env.TRUST_PORTAL_URL || 'http://127.0.0.1:18280';
const audience = process.argv[3] || process.env.TRUST_PORTAL_AUDIENCE || 'internal';
const trustBase = process.argv[4] || process.env.TRUST_BASE_URL;
const out = path.join(root, '.local/test-results/portal');
fs.mkdirSync(out, {recursive: true});
(async () => {
  const context = await chromium.launchPersistentContext(path.join(root, `.local/portal-browser-${audience}-profile`), {
    channel: 'chrome', headless: true, viewport: {width: 1440, height: 1120}
  });
  const report = {mode: audience + ' navigation browser', checks: [], errors: []};
  try {
    const page = await context.newPage();
    page.on('pageerror', error => report.errors.push(error.message));
    await page.goto(base, {waitUntil: 'networkidle'});
    await page.evaluate(() => localStorage.setItem('b-project-appearance', JSON.stringify({theme: 'tech-blue', layout: 'side'})));
    await page.reload({waitUntil: 'networkidle'});
    await page.locator('.card').first().waitFor();
    const catalogue = await (await page.request.get(base + '/portal-assets/services.json')).json();
    assert.equal(catalogue.audience, audience);
    assert.equal(await page.locator('.card').count(), catalogue.services.length);
    assert.equal(await page.title(), 'B项目 · 应用导航');
    for (const item of catalogue.services) {
      const link = page.getByRole('link', {name: `进入${item.title}（新标签页）`, exact: true});
      assert.equal(await link.getAttribute('href'), item.url);
      assert.equal(await link.getAttribute('target'), '_blank');
    }
    report.checks.push('All configured entries, exact URLs and separate tabs');
    await page.screenshot({path: path.join(out, `portal-${audience}-desktop.png`), fullPage: true});
    await page.getByRole('button', {name: '外观设置', exact: true}).click();
    const themes = [['科技蓝', 'tech-blue'], ['护眼墨绿', 'forest-green'], ['雅致深灰', 'purple-elegant'], ['暗夜黑', 'dark-pro']];
    const layouts = [['左侧菜单', 'side'], ['顶部导航', 'top'], ['图标窄栏', 'compact']];
    for (const [themeName, themeId] of themes) {
      await page.getByRole('button', {name: new RegExp('^' + themeName)}).click();
      for (const [layoutName, layoutId] of layouts) {
        await page.getByRole('button', {name: new RegExp('^' + layoutName)}).click();
        assert.equal(await page.locator('html').getAttribute('data-theme'), themeId);
        assert.equal(await page.locator('#portal-frame').getAttribute('data-layout'), layoutId);
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
      }
    }
    const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('b-project-appearance')));
    assert.deepEqual(saved, {theme: 'dark-pro', layout: 'compact'});
    await page.screenshot({path: path.join(out, `portal-${audience}-dark-compact.png`), fullPage: true});
    await page.getByRole('button', {name: /^护眼墨绿/}).click();
    await page.getByRole('button', {name: /^顶部导航/}).click();
    await page.screenshot({path: path.join(out, `portal-${audience}-green-top.png`), fullPage: true});
    await page.getByRole('button', {name: '关闭外观设置', exact: true}).click();
    await page.reload({waitUntil: 'networkidle'});
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'forest-green');
    assert.equal(await page.locator('#portal-frame').getAttribute('data-layout'), 'top');
    await page.setViewportSize({width: 1024, height: 900});
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.screenshot({path: path.join(out, `portal-${audience}-tablet.png`), fullPage: true});
    await page.setViewportSize({width: 1440, height: 1120});
    report.checks.push('All 12 appearance combinations, persistence and desktop overflow');
    for (const category of catalogue.categories) {
      await page.getByRole('button', {name: category, exact: true}).click();
      assert.equal(await page.locator('.card').count(), catalogue.services.filter(item => item.category === category).length);
    }
    await page.getByRole('button', {name: '全部应用', exact: true}).click();
    const search = page.getByRole('searchbox', {name: '搜索应用'});
    await search.fill(audience === 'public' ? '生产' : '仓储'); assert.equal(await page.locator('.card').count(), 1);
    await search.fill('17000'); assert.equal(await page.locator('.card').count(), audience === 'public' ? 0 : 1);
    await search.fill('not-present'); assert.ok(await page.locator('#empty').isVisible());
    await page.getByRole('button', {name: '清除筛选'}).click();
    assert.equal(await page.locator('.card').count(), catalogue.services.length);
    report.checks.push('Chinese search, port search, categories and empty-result reset');
    await page.setViewportSize({width: 390, height: 844});
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile overflow');
    assert.equal(await page.locator('.portal-main').evaluate(node => getComputedStyle(node).marginLeft), '0px');
    await page.screenshot({path: path.join(out, `portal-${audience}-mobile.png`), fullPage: true});
    report.checks.push('Mobile layout without horizontal overflow');
    if (audience === 'public') {
      const visibleText = await page.locator('body').innerText();
      for (const excluded of ['TRUST', 'IAM', 'SYS', 'Nightingale', 'Cockpit', '开发联调', '端口 184']) assert.ok(!visibleText.includes(excluded));
      assert.equal(await page.locator('.badge').count(), 0);
      report.checks.push('Public page omits internal systems, ports and development labels');
    } else {
      const popupPromise = page.waitForEvent('popup');
      await page.getByRole('link', {name: '进入可信存证与溯源（新标签页）', exact: true}).click();
      const trust = await popupPromise;
      await trust.waitForLoadState('networkidle');
      assert.equal(new URL(trust.url()).pathname, '/trust/');
      assert.ok((await trust.locator('body').innerText()).includes('登录'));
      assert.equal(await trust.locator('.quick-account').count(), 4);
      assert.equal(await trust.locator('html').getAttribute('data-theme'), 'forest-green');
      assert.deepEqual(await trust.evaluate(() => JSON.parse(localStorage.getItem('b-project-appearance'))), {theme: 'forest-green', layout: 'top'});
      report.checks.push('TRUST opens from the internal portal on its dedicated preview route');
      if (trustBase) {
        const dedicatedTrust = await context.newPage();
        await dedicatedTrust.goto(trustBase, {waitUntil: 'networkidle'});
        assert.equal(await dedicatedTrust.locator('html').getAttribute('data-theme'), 'forest-green');
        report.checks.push('Appearance preference carries from the internal portal to the TRUST port');
      }
    }
    assert.deepEqual(report.errors, []);
    report.status = 'PASS';
  } catch (error) {
    report.status = 'FAIL'; report.failure = error.stack; throw error;
  } finally {
    fs.writeFileSync(path.join(out, `portal-${audience}-browser.json`), JSON.stringify(report, null, 2));
    await context.close();
  }
  console.log(report.status, report.checks.length, 'navigation checks');
})().catch(error => {console.error(error); process.exitCode = 1;});
