// Self-hosted loopback signaling smoke benchmark. Start `just server`, then run:
// node scripts/perf/bench_signaling.cjs [ws://127.0.0.1:8081/ws/signal]
const WebSocket = require('ws');
const { performance } = require('node:perf_hooks');

const url = process.argv[2] || 'ws://127.0.0.1:8081/ws/signal';
const count = 200;
const householdId = `perf-${process.pid}`;
const type = kind => `finance.shilling.core.sync.SignalingMessage.${kind}`;

function connect(deviceId) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.once('error', reject);
    socket.once('open', () => {
      socket.send(JSON.stringify({ type: type('Join'), deviceId, householdId, accessToken: null }));
      resolve(socket);
    });
  });
}

(async () => {
  const receiver = await connect('receiver');
  const joined = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('PeerList handshake timed out')), 5000);
    receiver.on('message', data => {
      const msg = JSON.parse(data);
      if (msg.type === type('PeerList') && msg.deviceIds.includes('sender')) {
        clearTimeout(timeout);
        resolve();
      }
    });
  });
  const sender = await connect('sender');
  await joined;
  const sent = new Map();
  const latencies = [];
  const received = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Received ${latencies.length}/${count} offers`)), 10000);
    receiver.on('message', data => {
      const msg = JSON.parse(data);
      if (msg.type !== type('Offer')) return;
      const started = sent.get(Number(msg.sdp));
      if (started === undefined) return;
      latencies.push(performance.now() - started);
      if (latencies.length === count) {
        clearTimeout(timeout);
        resolve();
      }
    });
  });
  const start = performance.now();
  for (let i = 0; i < count; i++) {
    sent.set(i, performance.now());
    sender.send(JSON.stringify({ type: type('Offer'), fromDeviceId: 'sender', toDeviceId: 'receiver', sdp: String(i) }));
  }
  await received;
  const elapsed = performance.now() - start;
  latencies.sort((a, b) => a - b);
  console.log(`${count} offers in ${elapsed.toFixed(1)} ms (${(count * 1000 / elapsed).toFixed(0)}/s), p50 ${latencies[Math.floor(count * 0.5)].toFixed(1)} ms, p95 ${latencies[Math.floor(count * 0.95)].toFixed(1)} ms`);
  sender.close();
  receiver.close();
})().catch(error => { console.error(error); process.exitCode = 1; });
