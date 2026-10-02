// Synthetic hosted metadata and signaling only. Entity/file payloads use the devices' WebRTC channels.
// This is a local test fixture, not an application server or a fallback transport.
const http = require('node:http');
const { WebSocketServer, WebSocket } = require('ws');
const port = Number(process.env.PORT || 18083);
const latency = Number(process.env.LATENCY_MS || 100);
const prefix = 'finance.shilling.core.sync.SignalingMessage.';
const peers = new Map();
const removed = new Set();
const held = new Set();
const requests = [];
const report = record => console.log(JSON.stringify({ wallClockSeconds: Date.now() / 1000, ...record }));
const send = (socket, message) => { if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message)); };
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://fixture');
  const json = (status, value) => {
    response.writeHead(status, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(value));
  };
  if (url.pathname.startsWith('/__fixture/')) {
    if (!['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(request.socket.remoteAddress)) return json(403, {});
    if (url.pathname === '/__fixture/requests') return json(200, requests);
    const device = url.searchParams.get('device');
    if (!['z-pixel', 'z-iphone'].includes(device) || request.method !== 'POST') return json(400, {});
    if (url.pathname === '/__fixture/disconnect') {
      // Leave the P2P channel alive while this device is absent from signaling.
      held.add(device);
      peers.get(device)?.close(1000, 'Synthetic signaling interruption');
      peers.delete(device);
      report({ event: 'signaling-interrupted', device });
    } else if (url.pathname === '/__fixture/remove') {
      removed.add(device);
      peers.get(device)?.close(1008, 'Device removed');
      peers.delete(device);
      for (const socket of peers.values()) send(socket, { type: prefix + 'PeerList', deviceIds: [], removedDeviceIds: [device] });
      report({ event: 'removed', device, notified: [...peers.keys()] });
    } else if (url.pathname === '/__fixture/readmit') {
      removed.delete(device);
      held.delete(device);
      report({ event: 'readmitted', device });
    } else return json(404, {});
    return json(200, { peers: [...peers.keys()], removed: [...removed] });
  }
  const record = { event: 'metadata-request', path: url.pathname, wallClockSeconds: Date.now() / 1000 };
  requests.push(record);
  report(record);
  await new Promise(resolve => setTimeout(resolve, latency));
  switch (url.pathname) {
    case '/api/tier': return json(200, { accountPlan: 'FREE', householdPlan: 'FREE', deviceLimit: 2,
      registeredDevices: 2, turnEnabled: false, bankReadingEnabled: false, householdLimit: 1, canManageSpace: true });
    case '/api/devices/details': return json(200, [
      { deviceId: 'synthetic-device', ownerUserId: 'synthetic-user', registeredAt: '2026-10-01' },
      { deviceId: 'synthetic-peer', ownerUserId: 'synthetic-other-user', registeredAt: '2026-10-01' }
    ]);
    case '/api/billing/config': return json(200, {});
    default: return json(404, {});
  }
});
const sockets = new WebSocketServer({ server, path: '/ws/signal', maxPayload: 1024 * 1024 });
sockets.on('connection', socket => {
  let device;
  socket.on('message', (data, binary) => {
    if (binary) return socket.close(1008, 'Signaling text only');
    let message;
    try { message = JSON.parse(data.toString()); } catch { return socket.close(1008, 'Invalid signaling'); }
    const type = message.type?.startsWith(prefix) ? message.type.slice(prefix.length) : '';
    if (type === 'Join' && !device) {
      const allowed = ['z-pixel', 'z-iphone'];
      if (process.env.BROWSER_PROBE === '1') allowed.push('a-browser');
      if (message.householdId !== 'synthetic-live-perf' || !allowed.includes(message.deviceId))
        return socket.close(1008, 'Synthetic devices only');
      device = message.deviceId;
      if (removed.has(device)) return socket.close(1008, 'Device removed');
      if (held.has(device)) return socket.close(1000, 'Synthetic signaling interruption');
      peers.get(device)?.close(1000, 'Reconnected');
      const existing = [...peers.keys()].filter(id => id !== device);
      peers.set(device, socket);
      send(socket, { type: prefix + 'PeerList', deviceIds: existing });
      for (const id of existing) send(peers.get(id), { type: prefix + 'PeerList', deviceIds: [device] });
      report({ event: 'joined', device, peers: [...peers.keys()] });
    } else if (device && ['Offer', 'Answer', 'IceCandidate'].includes(type) && message.fromDeviceId === device) {
      const destination = peers.get(message.toDeviceId);
      if (destination) send(destination, message);
      report({ event: 'signal', type, from: device, to: message.toDeviceId, delivered: !!destination });
    } else socket.close(1008, 'Control-plane signaling only');
  });
  socket.on('close', () => {
    if (peers.get(device) === socket) peers.delete(device);
    report({ event: 'closed', device });
  });
});
server.listen(port, '0.0.0.0', () => report({ event: 'listening', port, latencyMs: latency }));
process.on('SIGTERM', () => { for (const socket of sockets.clients) socket.terminate(); server.close(); });
