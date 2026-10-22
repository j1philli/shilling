const { spawnSync } = require('node:child_process');
const path = require('node:path');
const minimums = { 21: [0, 13], 25: [0, 5] };

function isSupportedJava(version) {
  if (!/^\d+(?:\.\d+)*$/.test(version)) return false;
  const [major, minor = 0, patch = 0] = version.split('.').map(Number);
  const minimum = minimums[major];
  return Boolean(minimum && (minor > minimum[0] || (minor === minimum[0] && patch >= minimum[1])));
}

if (require.main === module) {
  try {
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
    const result = spawnSync(java, ['-version'], { encoding: 'utf8' });
    if (result.error || result.status !== 0) throw result.error || new Error(result.stderr);
    const match = /version "([^"]+)"/.exec(result.stderr + result.stdout);
    if (!match || !isSupportedJava(match[1])) {
      console.error(`FAIL: Java ${match ? match[1] : 'unknown'}; require 21.0.13+ or 25.0.5+ on those LTS lines (October 2026 security fixes).`);
      process.exitCode = 1;
    } else {
      console.log(`OK: Java ${match[1]}`);
    }
  } catch (error) {
    console.error(`FAIL: Could not execute Java: ${error.message}`);
    process.exitCode = 1;
  }
}

module.exports = { isSupportedJava };
