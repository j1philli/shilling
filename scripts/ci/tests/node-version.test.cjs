const assert = require('node:assert/strict');
const test = require('node:test');
const { isSupportedVersion } = require('../check-node-version.cjs');

test('rejects versions before each security release, EOL lines, and prereleases', () => {
  for (const version of ['18.20.8', '20.20.0', '22.22.2', '22.23.3', '24.22.0', '26.12.0', '23.11.0', '25.9.0', '27.0.0', '22.23.4-rc.1', 'garbage']) {
    assert.equal(isSupportedVersion(version), false, version);
  }
});

test('accepts fixed releases and subsequent patches on supported lines', () => {
  for (const version of ['v22.23.4', '22.23.5', '22.24.0', '24.22.1', '24.23.0', '26.12.1', '26.13.0']) {
    assert.equal(isSupportedVersion(version), true, version);
  }
});

const { isSupportedJava } = require('../check-java-version.cjs');
test('Java security floor rejects outdated, EOL and early access releases', () => {
  for (const version of ['21.0.9', '21.0.10', '21.0.12.1', '25.0.4.1', '25.0.5-ea', '24.0.2', '17.0.21', 'garbage']) {
    assert.equal(isSupportedJava(version), false, version);
  }
  for (const version of ['21.0.13', '21.0.13.1', '21.0.14', '25.0.5', '25.0.5.0.2', '25.0.6']) {
    assert.equal(isSupportedJava(version), true, version);
  }
});

const { runtimeMessages } = require('../export-teamcity-runtime.cjs');
test('TeamCity retains the selected toolchain between steps with escaped paths', () => {
  assert.deepEqual(runtimeMessages({ PATH: '/local/bin' }), []);
  assert.deepEqual(runtimeMessages({ TEAMCITY_VERSION: '2026.2', PATH: "/tools/[jdk]/it's|bin\n/bin", JAVA_HOME: '/tools/jdk' }), [
    "##teamcity[setParameter name='env.PATH' value='/tools/|[jdk|]/it|'s||bin|n/bin']",
    "##teamcity[setParameter name='env.JAVA_HOME' value='/tools/jdk']",
  ]);
});
