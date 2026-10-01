import { test, expect } from '@playwright/test';

test.beforeAll(async ({ request }) => {
  for (const url of ['http://server:8081/health', 'http://hosted-server:8081/health', 'http://web/api/config', 'http://hosted-web/api/config']) {
    await expect.poll(async () => {
      try { return (await request.get(url)).status(); } catch { return 0; }
    }, { timeout: 60_000 }).toBe(200);
  }
});

test('self-hosted image boots the app and completes onboarding', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://web');
  await expect(page.getByRole('button', { name: 'Continue', exact: true })).toBeVisible();
  expect(await page.evaluate(() => crossOriginIsolated)).toBe(true);
  // Compose's accessibility elements sit beneath the canvas. Click their
  // coordinates through that canvas, which handles the real pointer event.
  await page.getByRole('button', { name: 'Continue', exact: true }).click({ force: true });
  await expect(page.getByRole('button', { name: /^This week / })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: /^This week / })).toBeVisible();
  expect(errors).toEqual([]);
});

test('hosted web bundle renders welcome and sign-in form', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  // The shipped hosted bundle uses the public API URL. Keep this smoke run isolated.
  await page.route('https://api.shilling.finance/**', async route => {
    const url = new URL(route.request().url());
    const response = await page.request.fetch(`http://hosted-server:8081${url.pathname}${url.search}`, {
      method: route.request().method(), headers: route.request().headers(),
    });
    await route.fulfill({ response });
  });
  await page.goto('http://hosted-web');
  await expect(page.getByText('Welcome to Shilling', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: /^Continue with email/ }).click({ force: true });
  await expect(page.getByText('Sign in or create an account to continue.', { exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test('packaged server enforces hosted authentication', async ({ request, page }) => {
  const config = await (await request.get('http://hosted-web/api/config')).json();
  expect(config.authMode).toBe('SUPABASE');
  expect(JSON.stringify(config)).not.toContain('smoke-service-key');
  expect((await request.get('http://hosted-web/api/household')).status()).toBe(401);
  const { token } = await (await request.get('http://auth-fixture:9000/test-token')).json();
  const household = await request.get('http://hosted-web/api/household', { headers: { Authorization: `Bearer ${token}` } });
  expect(household.status()).toBe(200);
  expect(await household.json()).toEqual({ householdId: 'smoke-household' });
  expect((await request.get('http://hosted-web/api/spaces')).status()).toBe(401);
  const spaces = await request.get('http://hosted-web/api/spaces', { headers: { Authorization: `Bearer ${token}` } });
  expect(spaces.status()).toBe(200);
  expect((await spaces.json()).spaces).toEqual([{ id: 'smoke-household', name: 'Home', kind: 'home', role: 'owner' }]);
  await page.goto('http://hosted-web/api/config');
  for (const [accessToken, householdId] of [[null, 'smoke-household'], ['invalid', 'smoke-household'], [token, 'another-household']]) {
    const code = await page.evaluate(({ accessToken, householdId }) => new Promise((resolve, reject) => {
      const ws = new WebSocket('ws://hosted-web/ws/signal');
      const timer = setTimeout(() => { ws.close(); reject(new Error('Unauthorized join was not rejected')); }, 10_000);
      ws.onopen = () => ws.send(JSON.stringify({ type: 'finance.shilling.core.sync.SignalingMessage.Join', deviceId: 'rejected', householdId, accessToken }));
      ws.onclose = event => { clearTimeout(timer); resolve(event.code); };
    }), { accessToken, householdId });
    expect(code).toBe(1008);
  }
});

test('two authenticated browsers exchange data over WebRTC through packaged signaling', async ({ browser, request }) => {
  const { token } = await (await request.get('http://auth-fixture:9000/test-token')).json();
  const contexts = await Promise.all([browser.newContext(), browser.newContext()]);
  try {
    const pages = await Promise.all(contexts.map(context => context.newPage()));
    for (let i = 0; i < pages.length; i++) {
      // Use a real response from the web origin without loading application state.
      await pages[i].goto('http://hosted-web/api/config');
      await pages[i].evaluate(async ({ id, token }) => {
        const prefix = 'finance.shilling.core.sync.SignalingMessage.';
        const ws = new WebSocket('ws://hosted-web/ws/signal');
        const pc = new RTCPeerConnection({ iceServers: [] });
        const target = id === 'smoke-a' ? 'smoke-b' : 'smoke-a';
        window.smoke = { pc, ws, received: null, errors: [], peers: [] };
        const send = (type, body) => ws.send(JSON.stringify({ type: prefix + type, fromDeviceId: id, toDeviceId: target, ...body }));
        const pending = [];
        const channel = dc => { window.smoke.channel = dc; dc.onmessage = event => { window.smoke.received = event.data; }; };
        pc.ondatachannel = event => channel(event.channel);
        pc.onicecandidate = event => {
          if (event.candidate) send('IceCandidate', event.candidate.toJSON());
        };
        ws.onmessage = async event => {
          try {
            const message = JSON.parse(event.data);
            if (message.type === prefix + 'PeerList') window.smoke.peers.push(...message.deviceIds);
            if (message.type === prefix + 'Offer' || message.type === prefix + 'Answer') {
              await pc.setRemoteDescription({ type: message.type.endsWith('Offer') ? 'offer' : 'answer', sdp: message.sdp });
              for (const candidate of pending.splice(0)) await pc.addIceCandidate(candidate);
              if (message.type.endsWith('Offer')) {
                await pc.setLocalDescription(await pc.createAnswer());
                send('Answer', { sdp: pc.localDescription.sdp });
              }
            } else if (message.type === prefix + 'IceCandidate') {
              const candidate = { candidate: message.candidate, sdpMid: message.sdpMid, sdpMLineIndex: message.sdpMLineIndex };
              if (pc.remoteDescription) await pc.addIceCandidate(candidate); else pending.push(candidate);
            }
          } catch (error) { window.smoke.errors.push(error.message); }
        };
        await new Promise((resolve, reject) => {
          ws.onopen = resolve;
          ws.onerror = () => reject(new Error('Could not connect to packaged signaling server'));
        });
        ws.send(JSON.stringify({ type: prefix + 'Join', deviceId: id, householdId: 'smoke-household', accessToken: token }));
        window.smoke.offer = async () => {
          channel(pc.createDataChannel('smoke'));
          await pc.setLocalDescription(await pc.createOffer());
          send('Offer', { sdp: pc.localDescription.sdp });
        };
      }, { id: i === 0 ? 'smoke-a' : 'smoke-b', token });
    }
    await expect.poll(() => pages[0].evaluate(() => window.smoke.peers)).toContain('smoke-b');
    await pages[0].evaluate(() => window.smoke.offer());
    for (const page of pages) await expect.poll(() => page.evaluate(() => window.smoke.channel?.readyState)).toBe('open');
    // Closing signaling before sending proves the payload travels on the data channel.
    for (const page of pages) await page.evaluate(() => window.smoke.ws.close());
    await pages[0].evaluate(() => window.smoke.channel.send('shilling-p2p-smoke'));
    await expect.poll(() => pages[1].evaluate(() => window.smoke.received)).toBe('shilling-p2p-smoke');
    for (const page of pages) expect(await page.evaluate(() => window.smoke.errors)).toEqual([]);
  } finally {
    await Promise.all(contexts.map(context => context.close()));
  }
});
