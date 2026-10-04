const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test } = require('node:test');
const { optimizeWasm } = require('../../../scripts/optimize-wasm.cjs');

test('release optimization preserves GC references through JS, string imports, numeric behavior and traps', async () => {
  const { default: binaryen } = await import('binaryen');
  const source = binaryen.parseText(`(module
    (type $box (struct (field (mut i32))))
    (import "host" "echo" (func $echo (param externref) (result externref)))
    (import "'" "hello" (global $text externref))
    (import "wasm:js-string" "length" (func $length (param externref) (result i32)))
    (func $roundTrip (export "roundTrip") (param $value i32) (result i32)
      (i32.add
        (struct.get $box 0 (ref.cast (ref $box)
          (any.convert_extern (call $echo (extern.convert_any (struct.new $box (local.get $value)))))))
        (call $length (global.get $text))))
    (func $divide (export "divide") (param $a i32) (param $b i32) (result i32)
      (i32.div_s (local.get $a) (local.get $b)))
    (func $multiply (export "multiply") (param $a f64) (param $b f64) (result f64)
      (f64.mul (local.get $a) (local.get $b)))
  )`);
  source.setFeatures(binaryen.Features.All);
  binaryen.setDebugInfo(true);
  const bytes = Buffer.from(source.emitBinary());
  source.dispose();
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shilling-wasm-'));
  try {
    const file = path.join(directory, 'app with spaces.wasm');
    fs.writeFileSync(file, bytes);
    optimizeWasm(file);
    const options = { builtins: ['js-string'], importedStringConstants: "'" };
    for (const data of [bytes, fs.readFileSync(file)]) {
      const module = new WebAssembly.Module(data, options);
      // Like Kotlin's generated loader, supply JS implementations for engines
      // that do not yet honor the optional string-builtin compilation options.
      const { exports } = new WebAssembly.Instance(module, {
        host: { echo: value => value },
        "'": { hello: 'hello' },
        'wasm:js-string': { length: value => value.length },
      });
      for (const value of [0, -10, 2147483647]) assert.equal(exports.roundTrip(value), (value + 5) | 0);
      assert.equal(exports.divide(21, 3), 7);
      assert.throws(() => exports.divide(1, 0), WebAssembly.RuntimeError);
      assert.throws(() => exports.divide(-2147483648, -1), WebAssembly.RuntimeError);
      assert.ok(Object.is(exports.multiply(-0, 2), -0));
      assert.ok(Number.isNaN(exports.multiply(Infinity, 0)));
      assert.equal(exports.multiply(1.1, 1.1), 1.1 * 1.1);
      assert.ok(WebAssembly.Module.customSections(module, 'name').length, 'stack-trace names are present');
    }
    assert.deepEqual(fs.readdirSync(directory), ['app with spaces.wasm']);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('invalid Wasm cannot replace the input package', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shilling-wasm-invalid-'));
  try {
    const file = path.join(directory, 'app.wasm');
    const bytes = Buffer.from('not a Wasm module');
    fs.writeFileSync(file, bytes);
    assert.throws(() => optimizeWasm(file), WebAssembly.CompileError);
    assert.deepEqual(fs.readFileSync(file), bytes);
    assert.deepEqual(fs.readdirSync(directory), ['app.wasm']);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
