// Carry the toolchain selected by a health check into subsequent TeamCity steps.
function escapeValue(value) {
  return value.replace(/[|'\n\r\[\]]/g, character => ({
    '|': '||', "'": "|'", '\n': '|n', '\r': '|r', '[': '|[', ']': '|]',
  })[character]);
}

function runtimeMessages(env) {
  if (!env.TEAMCITY_VERSION) return [];
  return ['PATH', 'JAVA_HOME'].filter(name => env[name]).map(name =>
    `##teamcity[setParameter name='env.${name}' value='${escapeValue(env[name])}']`);
}

if (require.main === module) {
  for (const message of runtimeMessages(process.env)) console.log(message);
}

module.exports = { runtimeMessages };
