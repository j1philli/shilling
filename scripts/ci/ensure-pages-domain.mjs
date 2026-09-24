const accountId = process.env.CLOUDFLARE_ACCOUNT_ID;
const project = process.env.CLOUDFLARE_PAGES_PROJECT;
const domain = process.env.CLOUDFLARE_PAGES_DOMAIN;
const token = process.env.CLOUDFLARE_API_TOKEN || process.env.CF_API_TOKEN;

for (const [name, value] of Object.entries({
  CLOUDFLARE_ACCOUNT_ID: accountId,
  CLOUDFLARE_PAGES_PROJECT: project,
  CLOUDFLARE_PAGES_DOMAIN: domain,
  CLOUDFLARE_API_TOKEN: token,
})) {
  if (!value) throw new Error(`${name} is required to configure the Pages domain`);
}

const endpoint = `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(accountId)}/pages/projects/${encodeURIComponent(project)}/domains`;

async function request(method, body) {
  const response = await fetch(endpoint, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await response.json();
  if (!response.ok || !payload.success) {
    const messages = payload.errors?.map((error) => error.message).join('; ') || 'unknown error';
    throw new Error(`Cloudflare Pages domain ${method} failed (${response.status}): ${messages}`);
  }
  return payload.result;
}

const domains = await request('GET');
const existing = domains.find((entry) => entry.name === domain);
const configured = existing || (await request('POST', { name: domain }));
console.log(`Cloudflare Pages domain ${configured.name}: ${configured.status}`);
