import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const web = path.join(root, 'app/src/main/assets/web');
const readWeb = name => readFile(path.join(web, name));
const csrf = 'test-only-csrf-capability';
const session = 'a'.repeat(64);
const counters = { authorizedStatus: 0, statusWithoutOrigin: 0, deniedStatus: 0, authorizedControl: 0, deniedControl: 0 };

function makeServer(handler) {
  const server = createServer((req, res) => { Promise.resolve(handler(req, res)).catch(error => {
    res.writeHead(500); res.end(String(error));
  }); });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}
function portOf(server) { return server.address().port; }
function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...headers });
  res.end(body);
}
async function bodyOf(req) {
  let data = '';
  for await (const chunk of req) data += chunk.toString();
  return data;
}

const contentServer = await makeServer(async (req, res) => {
  const host = `127.0.0.1:${portOf(contentServer)}`;
  if (req.headers.host !== host) return send(res, 400, 'Bad Host');
  if (req.url === '/link-bridge.js') {
    return send(res, 200, await readWeb('link-bridge.js'), { 'Content-Type': 'application/javascript; charset=utf-8' });
  }
  if (req.url.startsWith('/browse?')) {
    const hostile = `<!doctype html><html><head><meta charset="utf-8"><title>Hostile page</title><script src="/link-bridge.js"></script></head><body>
      <script>
        window.attackResults = {};
        try { window.attackResults.frameCookie = document.cookie; }
        catch (_) { window.attackResults.frameCookie = 'blocked'; }
        try { window.attackResults.parentCsrf = parent.document.querySelector('meta[name="relay-csrf-token"]').content; }
        catch (_) { window.attackResults.parentCsrf = 'blocked'; }
        try { parent.document.body.dataset.pwned = 'yes'; window.attackResults.parentRead = 'allowed'; }
        catch (_) { window.attackResults.parentRead = 'blocked'; }
        try { parent.fetch('/api/status'); window.attackResults.parentApi = 'allowed'; }
        catch (_) { window.attackResults.parentApi = 'blocked'; }
        parent.postMessage({type:'lanbrowserrelay:cancel', id:'victim'}, '*');
        parent.postMessage({type:'lanbrowserrelay:navigate', url:'http://127.0.0.1:${portOf(uiServer)}/api/status', download:true, id:'victim'}, '*');
        fetch('http://127.0.0.1:${portOf(uiServer)}/api/status', {credentials:'include'})
          .then(r => window.attackResults.statusApi = 'HTTP ' + r.status)
          .catch(() => window.attackResults.statusApi = 'blocked');
        fetch('http://127.0.0.1:${portOf(uiServer)}/api/cancel', {method:'POST', mode:'no-cors', credentials:'include',
          headers:{'Content-Type':'text/plain'}, body:'id=victim'})
          .then(() => window.attackResults.cancelAttempt = 'sent')
          .catch(() => window.attackResults.cancelAttempt = 'blocked');
        const form = document.createElement('form'); form.method='POST';
        form.action='http://127.0.0.1:${portOf(uiServer)}/api/download';
        form.innerHTML='<input name="url" value="https://example.com/file.zip">'; document.body.appendChild(form);
        form.submit();
      </script><p>hostile content</p></body></html>`;
    return send(res, 200, hostile, {
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Security-Policy': `default-src 'self' https: data: blob:; script-src 'self' 'unsafe-inline' https:; style-src 'self' 'unsafe-inline' https:; connect-src 'self' http://127.0.0.1:${portOf(uiServer)}; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors http://127.0.0.1:${portOf(uiServer)}`
    });
  }
  return send(res, 404, 'Not found');
});
const contentPort = portOf(contentServer);
let uiServer;
uiServer = await makeServer(async (req, res) => {
  const host = `127.0.0.1:${portOf(uiServer)}`;
  const origin = `http://${host}`;
  if (req.headers.host !== host) return send(res, 400, 'Bad Host');
  const url = new URL(req.url, origin);
  if (url.pathname === '/' || url.pathname === '/index.html') {
    let html = (await readWeb('index.html')).toString()
      .replace('__RELAY_CSRF_TOKEN__', csrf)
      .replace('__RELAY_CONTENT_PORT__', String(contentPort));
    return send(res, 200, html, {
      'Content-Type': 'text/html; charset=utf-8',
      'Set-Cookie': `lbr_session=${session}; Path=/; HttpOnly; SameSite=Strict`,
      'Content-Security-Policy': `default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-src 'self' http://127.0.0.1:${contentPort}; form-action 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'self'`
    });
  }
  if (['/styles.css', '/app.js'].includes(url.pathname)) {
    const name = url.pathname.slice(1);
    return send(res, 200, await readWeb(name), { 'Content-Type': name.endsWith('.js') ? 'application/javascript; charset=utf-8' : 'text/css; charset=utf-8' });
  }
  if (url.pathname.startsWith('/api/')) {
    const sameOriginFetch = req.headers['sec-fetch-site'] === 'same-origin';
    // Same-origin fetch GETs are allowed to omit Origin; CSRF and Fetch Metadata still apply.
    const originAllowed = req.method === 'GET'
      ? (!req.headers.origin || req.headers.origin === origin)
      : req.headers.origin === origin;
    const hasSession = req.headers.cookie?.includes(`lbr_session=${session}`);
    const csrfOk = req.headers['x-relay-csrf'] === csrf;
    const allowed = sameOriginFetch && originAllowed && hasSession && csrfOk;
    if (url.pathname === '/api/status' && req.method === 'GET') {
      if (allowed) {
        counters.authorizedStatus++;
        if (!req.headers.origin) counters.statusWithoutOrigin++;
        return send(res, 200, JSON.stringify({running:true,activeCount:0,downloads:[]} ), {'Content-Type':'application/json'});
      }
      counters.deniedStatus++; return send(res, 403, '{"error":"Forbidden"}', {'Content-Type':'application/json'});
    }
    if (url.pathname === '/api/cancel' || url.pathname === '/api/download') {
      if (allowed && req.method === 'POST') { counters.authorizedControl++; await bodyOf(req); return send(res, 200, '{"ok":true}'); }
      counters.deniedControl++; await bodyOf(req); return send(res, 403, '{"error":"Forbidden"}');
    }
  }
  return send(res, 404, 'Not found');
});

let browser;
try {
  browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${portOf(uiServer)}/`);
  await page.locator('#address').fill('https://example.com/hostile');
  await page.locator('#navform button.go').click();
  const frame = page.frames().find(item => item.url().startsWith(`http://127.0.0.1:${contentPort}/browse`));
  assert.ok(frame, 'proxied page must load from the distinct content port');
  await frame.waitForFunction(() => window.attackResults && window.attackResults.cancelAttempt);
  const results = await frame.evaluate(() => window.attackResults);
  assert.equal(results.parentRead, 'blocked', 'hostile frame must not read or mutate the parent DOM');
  assert.ok(!results.frameCookie.includes('lbr_session'), 'hostile frame must not read the HttpOnly session cookie');
  assert.equal(results.parentCsrf, 'blocked', 'hostile frame must not read the UI CSRF token');
  assert.equal(await page.locator('body').getAttribute('data-pwned'), null, 'parent DOM must remain unchanged');
  assert.equal(results.parentApi, 'blocked', 'same-origin UI API must not be reachable through parent Window');
  assert.equal(await page.locator('#address').inputValue(), 'https://example.com/hostile', 'forged control messages must not alter the UI address');
  assert.equal(await page.locator('#viewer').getAttribute('sandbox'), 'allow-scripts allow-forms');
  assert.ok(counters.authorizedStatus > 0, `trusted UI status failed; observed=${JSON.stringify(counters)}`);
  assert.ok(counters.deniedStatus > 0, 'hostile frame API reads should be denied');
  assert.equal(counters.authorizedControl, 0, 'hostile content must not trigger any control action');
  assert.ok(counters.deniedControl > 0, 'hostile state-changing requests should be rejected');
  console.log('PASS: hostile HTML cannot read/mutate the parent or invoke protected relay controls', JSON.stringify(counters));
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => uiServer.close(resolve));
  await new Promise(resolve => contentServer.close(resolve));
}
