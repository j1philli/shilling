import { copyFileSync, readFileSync, writeFileSync } from 'node:fs';

const indexPath = 'web-app-dist/index.html';
const index = readFileSync(indexPath, 'utf8');
const marker = '<script type="module">';
if (!index.includes(marker)) throw new Error(`${indexPath} is missing the module script`);
if (index.includes('SHILLING_SELF_HOSTED_ONLY')) throw new Error(`${indexPath} is already prepared`);
writeFileSync(indexPath, index.replace(marker, '<script>window.SHILLING_SELF_HOSTED_ONLY = true;</script>\n' + marker));

copyFileSync('deploy/self-host/Caddyfile', 'web-app-dist/Caddyfile');
copyFileSync('deploy/self-host/web.Dockerfile', 'web-app-dist/Dockerfile');
