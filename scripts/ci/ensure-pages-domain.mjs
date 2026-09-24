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

const pagesEndpoint = `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(accountId)}/pages/projects/${encodeURIComponent(project)}/domains`;

async function request(method, endpoint, body) {
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
    const error = new Error(`Cloudflare API ${method} ${new URL(endpoint).pathname} failed (${response.status}): ${messages}`);
    error.status = response.status;
    throw error;
  }
  return payload.result;
}

const domains = await request('GET', pagesEndpoint);
const existing = domains.find((entry) => entry.name === domain);
const configured = existing || (await request('POST', pagesEndpoint, { name: domain }));
console.log(`Cloudflare Pages domain ${configured.name}: ${configured.status}`);

if (!configured.zone_tag) {
  throw new Error(`Cloudflare did not return a zone ID for ${domain}; add its CNAME to ${project}.pages.dev in the zone DNS settings`);
}

const dnsEndpoint = `https://api.cloudflare.com/client/v4/zones/${encodeURIComponent(configured.zone_tag)}/dns_records`;
const target = `${project}.pages.dev`;

try {
  const records = await request('GET', `${dnsEndpoint}?name.exact=${encodeURIComponent(domain)}`);
  const current = records.find((entry) => entry.name === domain);

  if (current) {
    if (current.type !== 'CNAME' || current.content.replace(/\.$/, '') !== target || !current.proxied) {
      throw new Error(`Existing DNS record for ${domain} does not match proxied CNAME ${target}`);
    }
    console.log(`Cloudflare DNS record ${domain} already points to ${target}`);
  } else {
    await request('POST', dnsEndpoint, {
      type: 'CNAME',
      name: domain,
      content: target,
      proxied: true,
      ttl: 1,
    });
    console.log(`Created proxied CNAME ${domain} -> ${target}`);
  }
} catch (error) {
  if (error.status !== 403) throw error;
  console.warn(`TeamCity's Cloudflare token cannot manage DNS for ${domain}. Add a proxied CNAME to ${target} in the shilling.finance zone, or grant this token DNS Read and Edit for that zone.`);
}
