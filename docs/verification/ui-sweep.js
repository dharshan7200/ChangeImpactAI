// Browser sweep of every page and tab: console errors, failed API calls, horizontal overflow; JSON result on stdout.
// node ui-sweep.js <base> <email> <password> <outdir>
const { chromium } = require('playwright');
const [base, email, password, out] = process.argv.slice(2);

(async () => {
  const results = [];
  const b = await chromium.launch();
  for (const [vpName, viewport] of [['desktop', { width: 1440, height: 1000 }], ['phone', { width: 390, height: 844 }]]) {
    const ctx = await b.newContext({ viewport });
    const p = await ctx.newPage();
    let errors = [], failed = [];
    p.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 160)); });
    p.on('pageerror', (e) => errors.push('pageerror ' + e.message.slice(0, 160)));
    p.on('response', (r) => { if (r.url().includes('/api/') && r.status() >= 500) failed.push(r.status() + ' ' + r.url().replace(/.*api.v1/, '')); });
    const record = async (name, fn) => {
      errors = []; failed = [];
      let ok = true, note = '';
      try { await fn(); } catch (e) { ok = false; note = String(e.message).split('\n')[0].slice(0, 160); }
      await p.waitForTimeout(800);
      const overflow = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      const pass = ok && errors.length === 0 && failed.length === 0 && overflow <= 1;
      results.push({ viewport: vpName, page: name, result: pass ? 'PASS' : 'FAIL', errors, failed, overflow, note });
      if (vpName === 'desktop') await p.screenshot({ path: `${out}/${name.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.png` });
    };

    await record('Login', async () => {
      await p.goto(base + '/login');
      await p.fill('input[type="email"]', email);
      await p.fill('input[type="password"]', password);
      await p.click('button[type="submit"]');
      await p.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 });
    });
    const token = await p.evaluate(() => localStorage.getItem('access_token'));
    const api = async (path) => (await (await p.request.get(base + '/api/v1' + path, { headers: { Authorization: 'Bearer ' + token } })).json()).data;
    const repos = (await api('/repositories?size=50')).content;
    const byName = (n) => repos.find((r) => r.fullName === n);
    const pc = byName('spring-projects/spring-petclinic'), py = byName('psf/requests'), rest = byName('spring-petclinic/spring-petclinic-rest'), cl = byName('apache/commons-lang');
    const analyses = (await api(`/analyses?repositoryId=${pc.id}&size=20`)).content.filter((a) => a.status === 'COMPLETED');
    const pyAnalyses = (await api(`/analyses?repositoryId=${py.id}&size=20`)).content.filter((a) => a.status === 'COMPLETED');

    await record('Overview dashboard', async () => { await p.goto(base + '/'); await p.waitForLoadState('networkidle'); });
    await record('Repositories', async () => { await p.goto(base + '/repositories'); await p.getByText('spring-petclinic').first().waitFor(); });
    await record('GitHub page', async () => { await p.goto(base + '/github'); await p.waitForLoadState('networkidle'); });

    const tabs = [['graph', /Dependency Graph/], ['impact', /Change Impact/], ['prs', /Pull Requests/], ['reports', /Quality Reports/],
      ['history', /^History$/], ['architecture', /Architecture/], ['code', /^Code$/], ['assistant', /AI Assistant/], ['jobs', /Ingestion Jobs/]];
    for (const [repo, label] of [[pc, 'petclinic'], [py, 'requests (Python)']]) {
      for (const [key, re] of tabs) {
        await record(`Repository ${label} · ${key}`, async () => {
          await p.goto(`${base}/repositories/${repo.id}`, { waitUntil: 'networkidle' });
          if (key !== 'graph') await p.getByRole('tab', { name: re }).first().click();
          await p.waitForLoadState('networkidle');
          await p.waitForTimeout(1500);
        });
      }
    }
    // Graph nodes are selected by a click even when the hand moves a few pixels (it used to start a drag instead)
    if (vpName === 'desktop') for (const view of ['Packages', 'Classes']) {
      await record(`Graph click with 3 px movement selects · ${view}`, async () => {
        await p.goto(`${base}/repositories/${pc.id}`, { waitUntil: 'networkidle' });
        await p.getByRole('button', { name: view, exact: true }).click();
        await p.locator('.react-flow__node').first().waitFor();
        await p.locator('.react-flow').scrollIntoViewIfNeeded(); await p.evaluate(() => window.scrollBy(0, 300)); await p.waitForTimeout(1200);
        const box = await p.locator('.react-flow__node').first().boundingBox();
        const x = box.x + box.width / 2, y = box.y + box.height / 2;
        await p.mouse.move(x, y); await p.mouse.down(); await p.mouse.move(x + 3, y + 1, { steps: 3 }); await p.mouse.up();
        await p.waitForTimeout(600);
        const inspector = await p.getByText('Inspector').locator('xpath=ancestor::div[2]').innerText();
        if (/Select a (package|class)/.test(inspector)) throw new Error('click did not select the node');
      });
    }
    // Other languages: a React/TypeScript repository's graph and a TSX file in the code browser
    const react = byName('alan2207/bulletproof-react');
    if (react) for (const [key, re] of [['graph', /Dependency Graph/], ['code', /^Code$/]]) {
      await record(`Repository bulletproof-react (TypeScript/React) · ${key}`, async () => {
        const q = key === 'code' ? `?tab=code&path=${encodeURIComponent('apps/react-vite/src/features/comments/components/delete-comment.tsx')}&line=44` : '';
        await p.goto(`${base}/repositories/${react.id}${q}`, { waitUntil: 'networkidle' });
        if (key === 'code') await p.locator('.monaco-editor').first().waitFor({ timeout: 30000 });
        else await p.locator('.react-flow__node').first().waitFor();
        await p.waitForTimeout(1500);
      });
    }
    for (const t of ['Code city', 'Hotspots', 'Dependency matrix', 'Main sequence', 'Centrality', 'Trends', 'Compare', 'Rules & coupling']) {
      await record(`Architecture twin · ${t}`, async () => {
        await p.goto(`${base}/repositories/${rest.id}`, { waitUntil: 'networkidle' });
        await p.getByRole('tab', { name: /Architecture/ }).first().click();
        await p.getByRole('button', { name: t, exact: true }).first().click();
        await p.waitForLoadState('networkidle');
        await p.waitForTimeout(1500);
      });
    }
    await record('Defect model panel (commons-lang)', async () => {
      await p.goto(`${base}/repositories/${cl.id}`, { waitUntil: 'networkidle' });
      await p.getByRole('button', { name: /^History$/ }).first().click();
      await p.getByText('Accepted — used in analyses').waitFor({ timeout: 20000 });
    });
    if (analyses[0]) await record('Analysis page (Java)', async () => {
      await p.goto(`${base}/analyses/${analyses[0].id}`, { waitUntil: 'networkidle' });
      await p.getByText('Pre-merge checklist', { exact: true }).waitFor({ timeout: 20000 });
      await p.locator('svg[aria-label="Blast radius sunburst"]').waitFor({ timeout: 20000 });
      await p.locator('h2', { hasText: 'AI review' }).first().waitFor({ timeout: 20000 });
    });
    if (pyAnalyses[0]) await record('Analysis page (Python)', async () => {
      await p.goto(`${base}/analyses/${pyAnalyses[0].id}`, { waitUntil: 'networkidle' });
      await p.getByText('Optional parameters added').first().waitFor({ timeout: 20000 });
    });
    await record('Markdown report download', async () => {
      const r = await p.request.get(`${base}/api/v1/analyses/${analyses[0].id}/report.md`, { headers: { Authorization: 'Bearer ' + token } });
      if (r.status() !== 200) throw new Error('report.md ' + r.status());
    });
    await ctx.close();
  }
  await b.close();
  console.log(JSON.stringify(results));
})();
