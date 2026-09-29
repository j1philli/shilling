import { copyFileSync, readFileSync, writeFileSync } from 'node:fs';

const outputDir = process.argv[2] || 'web-app-dist';
const indexPath = `${outputDir}/index.html`;
const index = readFileSync(indexPath, 'utf8');
const marker = '<script type="module">';
if (!index.includes(marker)) throw new Error(`${indexPath} is missing the module script`);
if (index.includes('SHILLING_SELF_HOSTED_ONLY')) throw new Error(`${indexPath} is already prepared`);
writeFileSync(indexPath, index.replace(marker, '<script>window.SHILLING_SELF_HOSTED_ONLY = true;</script>\n' + marker));

copyFileSync('deploy/self-host/Caddyfile', `${outputDir}/Caddyfile`);
copyFileSync('deploy/self-host/web.Dockerfile', `${outputDir}/Dockerfile`);
