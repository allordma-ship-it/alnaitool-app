import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { buildApp } from '../server.js';

const MAC = 'aa-bb-cc-11-22-33';
const MAC_NORM = 'AA:BB:CC:11:22:33';

function multipartBody(fields, file) {
  const boundary = '----aura' + Math.random().toString(16).slice(2);
  const chunks = [];
  for (const [k, v] of Object.entries(fields)) {
    chunks.push(`--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`);
  }
  if (file) {
    chunks.push(
      `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${file.name}"\r\nContent-Type: audio/x-mpegurl\r\n\r\n${file.content}\r\n`,
    );
  }
  chunks.push(`--${boundary}--\r\n`);
  return { payload: chunks.join(''), headers: { 'content-type': `multipart/form-data; boundary=${boundary}` } };
}

describe('AuraStream API', () => {
  let app;
  let device;
  let token;

  before(async () => {
    app = await buildApp({
      sqlitePath: ':memory:',
      logger: false,
      rateLimit: false,
      publicPortalUrl: 'https://portal.example',
      adminToken: 'admin-token-for-tests',
      maxFailedAttempts: 3,
    });
  });
  after(() => app.close());

  test('handshake registers a new device with 6-digit key and 7-day trial', async () => {
    const res = await app.inject({ method: 'POST', url: '/api/v1/device/handshake', payload: { mac: MAC, model: 'Shield TV' } });
    assert.equal(res.statusCode, 201);
    device = res.json();
    assert.equal(device.mac, MAC_NORM);
    assert.match(device.deviceKey, /^\d{6}$/);
    assert.ok(device.deviceSecret.length >= 40);
    assert.equal(device.status, 'trial');
    assert.equal(device.daysLeft, 7);
    assert.equal(device.pairingUrl, `https://portal.example/?mac=AA%3ABB%3ACC%3A11%3A22%3A33&key=${device.deviceKey}`);
  });

  test('handshake with secret returns same key and no new secret', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/device/handshake',
      payload: { mac: MAC_NORM, deviceSecret: device.deviceSecret },
    });
    assert.equal(res.statusCode, 200);
    assert.equal(res.json().deviceKey, device.deviceKey);
    assert.equal(res.json().deviceSecret, undefined);
  });

  test('handshake without secret for a known MAC is rejected', async () => {
    const res = await app.inject({ method: 'POST', url: '/api/v1/device/handshake', payload: { mac: MAC } });
    assert.equal(res.statusCode, 409);
    assert.equal(res.json().error, 'DEVICE_SECRET_MISMATCH');
  });

  test('invalid MAC is rejected', async () => {
    const res = await app.inject({ method: 'POST', url: '/api/v1/device/handshake', payload: { mac: 'nope' } });
    assert.equal(res.statusCode, 400);
  });

  test('portal verify issues a session token', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/device/verify',
      payload: { mac: MAC, deviceKey: device.deviceKey },
    });
    assert.equal(res.statusCode, 200);
    token = res.json().token;
    assert.ok(token);
  });

  test('portal can add an M3U URL and Xtream account; password is never echoed', async () => {
    const auth = { authorization: `Bearer ${token}` };
    let res = await app.inject({
      method: 'POST',
      url: '/api/v1/playlists',
      headers: auth,
      payload: { sourceType: 'm3u_url', name: '<b>My list</b>', sourceUrl: 'http://prov.tv/get.php?username=u&password=secret&type=m3u_plus' },
    });
    assert.equal(res.statusCode, 201);
    assert.equal(res.json().name, 'bMy list/b');
    assert.ok(!res.json().sourceUrl.includes('secret'));

    res = await app.inject({
      method: 'POST',
      url: '/api/v1/playlists',
      headers: auth,
      payload: { sourceType: 'xtream', xtream: { host: 'line.prov.tv:8080/player_api.php', username: 'john', password: 'p@ss' } },
    });
    assert.equal(res.statusCode, 201);
    assert.equal(res.json().xtream.host, 'http://line.prov.tv:8080');
    assert.notEqual(res.json().xtream.password, 'p@ss');

    res = await app.inject({ method: 'POST', url: '/api/v1/playlists', headers: auth, payload: { sourceType: 'm3u_url', sourceUrl: 'javascript:alert(1)' } });
    assert.equal(res.statusCode, 400);
  });

  test('portal file upload validates and stores M3U content', async () => {
    const m3u = '#EXTM3U url-tvg="http://epg.example/guide.xml"\n#EXTINF:-1 tvg-id="a" group-title="News",Channel A\nhttp://s/a.ts\n#EXTINF:-1 group-title="Sport",Channel B\nhttp://s/b.ts\n';
    const { payload, headers } = multipartBody({ name: 'Upload' }, { name: 'list.m3u', content: m3u });
    let res = await app.inject({ method: 'POST', url: '/api/v1/playlists/upload', headers: { ...headers, authorization: `Bearer ${token}` }, payload });
    assert.equal(res.statusCode, 201, res.body);
    assert.equal(res.json().channelCount, 2);
    assert.equal(res.json().epgUrl, 'http://epg.example/guide.xml');

    const bad = multipartBody({}, { name: 'list.m3u', content: 'hello world' });
    res = await app.inject({ method: 'POST', url: '/api/v1/playlists/upload', headers: { ...bad.headers, authorization: `Bearer ${token}` }, payload: bad.payload });
    assert.equal(res.statusCode, 422);
  });

  test('TV sync returns decrypted credentials and file content url', async () => {
    const headers = { 'x-device-mac': MAC_NORM, 'x-device-secret': device.deviceSecret };
    const res = await app.inject({ method: 'GET', url: '/api/v1/playlists/sync', headers });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.playlists.length, 3);
    const xt = body.playlists.find((p) => p.sourceType === 'xtream');
    assert.deepEqual(xt.xtream, { host: 'http://line.prov.tv:8080', username: 'john', password: 'p@ss' });
    const file = body.playlists.find((p) => p.sourceType === 'file');
    assert.ok(file.sourceUrl.startsWith('https://portal.example/api/v1/playlists/'));

    const content = await app.inject({ method: 'GET', url: `/api/v1/playlists/${file.id}/content`, headers });
    assert.equal(content.statusCode, 200);
    assert.ok(content.body.startsWith('#EXTM3U'));

    const unauth = await app.inject({ method: 'GET', url: '/api/v1/playlists/sync', headers: { 'x-device-mac': MAC_NORM, 'x-device-secret': 'wrong' } });
    assert.equal(unauth.statusCode, 401);
  });

  test('portal brute force locks the device', async () => {
    const wrong = device.deviceKey === '000000' ? '111111' : '000000';
    for (let i = 0; i < 3; i++) {
      const r = await app.inject({ method: 'POST', url: '/api/v1/device/verify', payload: { mac: MAC, deviceKey: wrong } });
      assert.equal(r.statusCode, 401);
    }
    const res = await app.inject({ method: 'POST', url: '/api/v1/device/verify', payload: { mac: MAC, deviceKey: device.deviceKey } });
    assert.equal(res.statusCode, 423);
  });

  test('re-pair rotates key, purges playlists and invalidates portal sessions', async () => {
    const res = await app.inject({ method: 'POST', url: '/api/v1/device/handshake', payload: { mac: MAC, reset: true } });
    assert.equal(res.statusCode, 200);
    const fresh = res.json();
    assert.ok(fresh.deviceSecret && fresh.deviceSecret !== device.deviceSecret);

    const old = await app.inject({ method: 'GET', url: '/api/v1/playlists', headers: { authorization: `Bearer ${token}` } });
    assert.equal(old.statusCode, 401);

    const sync = await app.inject({
      method: 'GET',
      url: '/api/v1/playlists/sync',
      headers: { 'x-device-mac': MAC_NORM, 'x-device-secret': fresh.deviceSecret },
    });
    assert.equal(sync.json().playlists.length, 0);
    device = fresh;
  });

  test('expired licence blocks sync; admin can activate', async () => {
    const headers = { 'x-device-mac': MAC_NORM, 'x-device-secret': device.deviceSecret };
    const admin = { authorization: 'Bearer admin-token-for-tests' };
    let res = await app.inject({ method: 'POST', url: `/api/v1/admin/devices/${MAC_NORM}/license`, headers: admin, payload: { status: 'expired' } });
    assert.equal(res.statusCode, 200);
    res = await app.inject({ method: 'GET', url: '/api/v1/playlists/sync', headers });
    assert.equal(res.statusCode, 402);

    res = await app.inject({ method: 'POST', url: `/api/v1/admin/devices/${MAC_NORM}/license`, headers: admin, payload: { status: 'active', days: 365 } });
    assert.equal(res.json().status, 'active');
    res = await app.inject({ method: 'GET', url: '/api/v1/playlists/sync', headers });
    assert.equal(res.statusCode, 200);

    res = await app.inject({ method: 'POST', url: `/api/v1/admin/devices/${MAC_NORM}/license`, headers: { authorization: 'Bearer x' }, payload: { status: 'active' } });
    assert.equal(res.statusCode, 401);
  });

  test('portal page is served with security headers', async () => {
    const res = await app.inject({ method: 'GET', url: '/?mac=AA:BB:CC:11:22:33&key=123456' });
    assert.equal(res.statusCode, 200);
    assert.match(res.headers['content-type'], /text\/html/);
    assert.ok(res.headers['content-security-policy'].includes('cdn.tailwindcss.com'));
  });
});
