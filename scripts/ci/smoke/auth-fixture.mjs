// Ephemeral Supabase protocol fixture. No production accounts or credentials.
import { createServer } from 'node:http';
import { generateKeyPairSync, sign } from 'node:crypto';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'smoke', alg: 'RS256', use: 'sig' };
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');

createServer((req, res) => {
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
  } else {
    res.statusCode = 404;
    res.end('{}');
  }
}).listen(9000, '0.0.0.0');
