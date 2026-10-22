const minimums = { 22: [23, 4], 24: [22, 1], 26: [12, 1] };

function isSupportedVersion(version) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(version);
  if (!match) return false;
  const [major, minor, patch] = match.slice(1).map(Number);
  const minimum = minimums[major];
  return Boolean(minimum && (minor > minimum[0] || (minor === minimum[0] && patch >= minimum[1])));
}

if (require.main === module) {
  if (!isSupportedVersion(process.version)) {
    console.error(`FAIL: Node.js ${process.version}; install 22.23.4+, 24.22.1+, or 26.12.1+ on the corresponding release line (October 21, 2026 security fixes).`);
    process.exitCode = 1;
  } else {
    console.log(`OK: Node.js ${process.version}`);
  }
}

module.exports = { isSupportedVersion };
