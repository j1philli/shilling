// Ephemeral Supabase protocol fixture. No production accounts or credentials.
import { createServer } from 'node:http';
import { generateKeyPairSync, sign } from 'node:crypto';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'smoke', alg: 'RS256', use: 'sig' };
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');

const devices = new Map();
createServer(async (req, res) => {
  const url = new URL(req.url, 'http://auth-fixture:9000');
  res.setHeader('Content-Type', 'application/json');
  if (url.pathname === '/auth/v1/.well-known/jwks.json') {
    res.end(JSON.stringify({ keys: [jwk] }));
  } else if (url.pathname === '/test-token') {
    const payload = `${encode({ alg: 'RS256', kid: 'smoke' })}.${encode({
      sub: 'smoke-user', aud: 'authenticated', role: 'authenticated',
      iss: 'http://auth-fixture:9000/auth/v1', exp: Math.floor(Date.now() / 1000) + 300,
    })}`;
    res.end(JSON.stringify({ token: `${payload}.${sign('RSA-SHA256', Buffer.from(payload), privateKey).toString('base64url')}` }));
  } else if (url.pathname === '/rest/v1/user_profiles' && req.headers.apikey === 'smoke-service-key') {
    res.end(JSON.stringify(url.searchParams.get('user_id') === 'eq.smoke-user' ? [{ household_id: 'smoke-household' }] : []));
  } else if (url.pathname === '/rest/v1/hosted_space_memberships' && req.headers.apikey === 'smoke-service-key') {
    res.end(JSON.stringify([{ user_id: 'smoke-user', space_id: 'smoke-household', role: 'owner' }]));
  } else if (url.pathname === '/rest/v1/rpc/manage_hosted_space' && req.headers.apikey === 'smoke-service-key') {
    let body = ''; for await (const chunk of req) body += chunk;
    const command = JSON.parse(body);
    if (command.p_actor !== 'smoke-user' || command.p_action !== 'list') {
      res.statusCode = 400; res.end(JSON.stringify({ code: '22023', message: 'Fixture supports list only' }));
    } else res.end(JSON.stringify({ activeSpaceId: 'smoke-household', requiresSpaceSelection: false,
      spaces: [{ id: 'smoke-household', name: 'Home', kind: 'home', role: 'owner' }],
      members: [{ userId: 'smoke-user', email: 'smoke@example.test', role: 'owner' }], invitations: [] }));
  } else if (url.pathname === '/rest/v1/rpc/register_hosted_device' && req.headers.apikey === 'smoke-service-key') {
    let body = ''; for await (const chunk of req) body += chunk;
    const command = JSON.parse(body);
    const permitted = command.p_user_id === 'smoke-user' && command.p_household_id === 'smoke-household' &&
      (devices.has(command.p_device_id) || command.p_device_limit == null || devices.size < command.p_device_limit);
    if (permitted) devices.set(command.p_device_id, { device_id: command.p_device_id, owner_user_id: 'smoke-user', registered_at: new Date().toISOString(), last_seen_at: new Date().toISOString() });
    res.end(JSON.stringify(permitted));
  } else if (url.pathname === '/rest/v1/hosted_devices' && req.headers.apikey === 'smoke-service-key') {
    if (req.method === 'DELETE') { devices.delete(url.searchParams.get('device_id')?.replace(/^eq\./, '')); res.end(''); }
    else res.end(JSON.stringify([...devices.values()]));
  } else {
    res.statusCode = 404;
    res.end('{}');
  }
}).listen(9000, '0.0.0.0');
