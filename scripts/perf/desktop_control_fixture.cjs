// Loopback control plane for an isolated desktop UI benchmark. No entity or file payloads.
const http = require('node:http');
const { WebSocketServer } = require('ws');

const port = Number(process.env.PORT || 18091);
const prefix = 'finance.shilling.core.sync.SignalingMessage.';
const headers = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
};
const server = http.createServer((request, response) => {
  if (request.method === 'OPTIONS') {
    response.writeHead(204, { ...headers, 'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': '*' });
    return response.end();
  }
  const path = new URL(request.url, 'http://localhost').pathname;
  const data = request.method === 'GET' && path === '/api/config' ? { authMode: 'NONE' }
    : request.method === 'GET' && path === '/api/ice-servers' ? { iceServers: [], ttlSeconds: 3600 }
    : null;
  response.writeHead(data ? 200 : 404, headers);
  response.end(JSON.stringify(data || {}));
});
const sockets = new WebSocketServer({ server, path: '/ws/signal', maxPayload: 4096 });
sockets.on('connection', socket => socket.on('message', (data, binary) => {
  if (binary) return socket.close(1008, 'Control plane only');
  let message;
  try { message = JSON.parse(data.toString()); }
  catch { return socket.close(1008, 'Invalid control message'); }
  if (message.type === prefix + 'Join') {
    socket.send(JSON.stringify({ type: prefix + 'PeerList', deviceIds: [] }));
  } else {
    socket.close(1008, 'Control plane only');
  }
}));
server.listen(port, '127.0.0.1', () => console.log(`desktop control fixture ready on ${port}`));
process.on('SIGTERM', () => {
  sockets.clients.forEach(socket => socket.terminate());
  server.close();
});
