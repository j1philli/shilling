const assert = require('node:assert/strict');
const { test } = require('node:test');
const { trimDebugNames } = require('../../../scripts/trim-wasm-debug-names.cjs');

function u32(value) {
  const out = [];
  do { const byte = value & 127; value >>>= 7; out.push(byte | (value ? 128 : 0)); } while (value);
  return Buffer.from(out);
}
function section(id, content) { return Buffer.concat([Buffer.from([id]), u32(content.length), content]); }
function string(text) { const b = Buffer.from(text); return Buffer.concat([u32(b.length), b]); }
function custom(name, data) { return section(0, Buffer.concat([string(name), data])); }

test('release names retain working exports, stack-trace function names and unrelated metadata', async () => {
  // An executable module exporting answer() = 42.
  const executable = Buffer.from('0061736d010000000105016000017f03020100070a0106616e7377657200000a06010400412a0b', 'hex');
  const functionName = section(1, Buffer.concat([u32(1), u32(0), string('answer')]));
  const localNames = section(2, Buffer.concat([u32(1), u32(0), u32(1), u32(0), string('temporary'.repeat(40))]));
  const otherMetadata = custom('build-id', Buffer.from('keep me'));
  const names = custom('name', Buffer.concat([functionName, localNames]));
  const original = Buffer.concat([executable, names, otherMetadata]);
  const trimmed = trimDebugNames(original);
  assert.deepEqual(trimmed, Buffer.concat([executable, custom('name', functionName), otherMetadata]));
  assert.deepEqual(trimDebugNames(trimmed), trimmed, 'repeated packaging must be idempotent');
  const { instance, module } = await WebAssembly.instantiate(trimmed);
  assert.equal(instance.exports.answer(), 42);
  assert.deepEqual(Buffer.from(WebAssembly.Module.customSections(module, 'name')[0]), functionName);
  assert.deepEqual(trimDebugNames(executable), executable, 'modules without debug names stay unchanged');
});

test('only known debugging name subsections are removed', () => {
  const header = Buffer.from('0061736d01000000', 'hex');
  const moduleName = section(0, string('fixture'));
  const unknownFutureName = section(42, Buffer.from([1, 2, 3]));
  const debug = [2, 4, 7, 10].map(id => section(id, Buffer.from([0])));
  const original = Buffer.concat([header, custom('name', Buffer.concat([moduleName, ...debug, unknownFutureName]))]);
  assert.deepEqual(trimDebugNames(original),
    Buffer.concat([header, custom('name', Buffer.concat([moduleName, unknownFutureName]))]));
});

test('malformed framing fails before a rewritten binary can be published', () => {
  assert.throws(() => trimDebugNames(Buffer.from('not wasm')), /Wasm/);
  const header = Buffer.from('0061736d01000000', 'hex');
  for (const suffix of ['00', '008080808010', '000a0161', '000a', '0004016e', '0008046e616d650209']) {
    assert.throws(() => trimDebugNames(Buffer.concat([header, Buffer.from(suffix, 'hex')])), /Wasm|section/);
  }
});
