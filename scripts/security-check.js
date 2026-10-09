import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = path => readFile(path, 'utf8');
const html = await read('app/src/main/assets/web/index.html');
const app = await read('app/src/main/assets/web/app.js');
const bridge = await read('app/src/main/assets/web/link-bridge.js');
const gateway = await read('app/src/main/java/com/lanbrowserrelay/gateway/HtmlGateway.kt');
const uiServer = await read('app/src/main/java/com/lanbrowserrelay/server/LanHttpServer.kt');
const contentServer = await read('app/src/main/java/com/lanbrowserrelay/server/RelayContentServer.kt');
const gradle = await read('app/build.gradle.kts');
const release = await read('.github/workflows/release.yml');

const sandbox = html.match(/<iframe[^>]*id="viewer"[^>]*sandbox="([^"]+)"/i)?.[1];
assert.ok(sandbox, 'viewer iframe must declare sandbox flags');
assert.ok(sandbox.split(/\s+/).includes('allow-scripts'), 'page link bridge requires scripts');
for (const forbidden of ['allow-same-origin', 'allow-top-navigation', 'allow-top-navigation-by-user-activation', 'allow-popups-to-escape-sandbox', 'allow-downloads']) {
  assert.ok(!sandbox.split(/\s+/).includes(forbidden), `unsafe iframe sandbox flag: ${forbidden}`);
}
assert.match(app, /event\.source !== viewer\.contentWindow \|\| event\.origin !== 'null'/);
assert.match(app, /Object\.keys\(message\)\.length !== 2/);
assert.doesNotMatch(app, /message\.download/);
assert.match(app, /contentPort[\s\S]*relayContentOrigin/);
assert.match(bridge, /type: 'lanbrowserrelay:navigate', url: target\.href/);
assert.doesNotMatch(bridge, /download:|filename:|api\/|cancel/);
assert.match(gateway, /link-bridge\.js/);
assert.match(contentServer, /NanoHTTPD\(bindAddress, port\)/);
assert.match(contentServer, /frame-ancestors http:\/\//);
assert.match(contentServer, /form-action 'none'/);
assert.doesNotMatch(contentServer, /api\/status|api\/cancel|api\/download/);
assert.match(uiServer, /HttpOnly; SameSite=Strict/);
assert.match(uiServer, /RequestOriginPolicy\.isExpectedOrigin/);
assert.match(uiServer, /x-relay-csrf/);
assert.match(uiServer, /"\/api\/cancel" -> if \(session\.method == Method\.POST\)/);
assert.match(uiServer, /"\/api\/download" -> if \(session\.method == Method\.POST\)/);
assert.doesNotMatch(uiServer, /apiToken|queryToken|__RELAY_API_TOKEN__/);
assert.doesNotMatch(uiServer, /put\("url", transfer\.url\)/);
assert.doesNotMatch(gradle, /versionCode\s*=\s*1\b|versionName\s*=\s*"1\.0\.0"/);
assert.match(gradle, /versionCode = derivedVersionCode/);
assert.match(gradle, /release packaging requires persistent LBR_KEYSTORE_/i);
assert.match(release, /LBR_RELEASE_KEYSTORE_BASE64/);
assert.match(release, /LBR_RELEASE_KEYSTORE_PASSWORD/);
assert.match(release, /dump badging/);
assert.match(release, /versionCode/);
assert.doesNotMatch(release, /keytool\s+-genkeypair/);
console.log('PASS: static origin-isolation, API, and release-security contracts');
