const { chromium } = require('playwright');
const out = process.argv[2];
(async () => {
  const b = await chromium.launch();
  const p = await (await b.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })).newPage();
  const errors = []; p.on('pageerror', e => errors.push(e.message));
  const steps = [];
  await p.goto('http://localhost:5173/login');
  await p.fill('input[type="email"]', 'e2e27637@example.com');
  await p.fill('input[type="password"]', 'Passw0rd!123');
  await p.click('button[type="submit"]');
  await p.waitForURL(u => !u.pathname.includes('login'));
  await p.goto('http://localhost:5173/github', { waitUntil: 'networkidle' });
  const btn = p.getByRole('button', { name: 'Connect with GitHub' });
  steps.push(['button shown when configured', await btn.count() === 1]);
  await p.screenshot({ path: out + '/1-github-page.png' });
  const nav = [];
  p.on('framenavigated', f => { if (f === p.mainFrame()) nav.push(f.url().replace(/state=[^&]+/, 'state=…')); });
  await btn.click();
  await p.getByText(/Connected GitHub account octo-dev/).waitFor({ timeout: 15000 });
  await p.screenshot({ path: out + '/2-callback.png' });
  steps.push(['redirect chain', nav.slice(0, 3).join(' -> ')]);
  await p.waitForURL(u => u.pathname === '/github', { timeout: 10000 });
  await p.getByText('octo-dev').first().waitFor();
  await p.screenshot({ path: out + '/3-connected.png' });
  steps.push(['account listed after redirect', true]);
  // replay the callback URL (same code + state) -> must be refused
  const cb = nav.find(u => u.includes('/github/oauth/callback'));
  const realCb = (await p.evaluate(() => performance.getEntriesByType('navigation').map(n => n.name)))
  await p.goBack(); await p.goBack();
  const replayUrl = await p.evaluate(() => location.href);
  steps.push(['back to', replayUrl.replace(/state=[^&]+/, 'state=…')]);
  if (replayUrl.includes('/github/oauth/callback')) {
    await p.reload();
    await p.getByText(/reused|Invalid/).waitFor({ timeout: 10000 });
    steps.push(['replayed callback refused', true]);
    await p.screenshot({ path: out + '/4-replay.png' });
  }
  // denied consent
  const token = await p.evaluate(() => localStorage.getItem('access_token'));
  const r = await p.request.get('http://localhost:5173/api/v1/github/oauth/authorize', { headers: { Authorization: 'Bearer ' + token } });
  const url = (await r.json()).data.url + '&deny=1';
  await p.goto(url);
  await p.getByText(/denied your application access/).waitFor({ timeout: 10000 });
  steps.push(['denied consent shows GitHub message', true]);
  await p.screenshot({ path: out + '/5-denied.png' });
  console.log(JSON.stringify({ steps, errors }, null, 1));
  await b.close();
})();
