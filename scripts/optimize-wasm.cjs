const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { trimDebugNames } = require('./trim-wasm-debug-names.cjs');

// Kotlin's single-module application passes opaque GC references through JS,
// but no external Wasm module inspects their layout. Do not use this packaging
// step for separately linked Wasm libraries. Keep IEEE floating-point and trap
// semantics: deliberately omit --fast-math and --traps-never-happen.
const OPTIMIZER_ARGS = [
  '--enable-gc', '--enable-reference-types', '--enable-exception-handling',
  '--enable-bulk-memory', '--enable-sign-ext', '--enable-nontrapping-float-to-int',
  '--enable-multivalue', '--closed-world', '-Oz', '--gufa', '-Oz', '-g',
];
const KOTLIN_OPTIONS = { builtins: ['js-string'], importedStringConstants: "'" };

function optimizeWasm(file) {
  file = path.resolve(file);
  const original = fs.readFileSync(file);
  const originalModule = new WebAssembly.Module(original, KOTLIN_OPTIONS);
  const temporary = fs.mkdtempSync(path.join(path.dirname(file), '.wasm-opt-'));
  try {
    // Normalize the input to the previous release package, keeping interactive
    // debugger metadata out of the optimizer's working set as well.
    const input = path.join(temporary, 'input.wasm');
    fs.writeFileSync(input, trimDebugNames(original));
    const output = path.join(temporary, 'optimized.wasm');
    execFileSync(process.execPath, [
      require.resolve('binaryen/bin/wasm-opt'), input, '-o', output, ...OPTIMIZER_ARGS,
    ], { stdio: 'inherit' });
    // Binaryen updates stack-trace names for changed function indices. Preserve
    // those names while dropping the same debugger-only metadata as before.
    const optimized = trimDebugNames(fs.readFileSync(output));
    const optimizedModule = new WebAssembly.Module(optimized, KOTLIN_OPTIONS);
    const exportsOf = module => WebAssembly.Module.exports(module)
      .map(entry => `${entry.kind}:${entry.name}`).sort();
    if (JSON.stringify(exportsOf(originalModule)) !== JSON.stringify(exportsOf(optimizedModule))) {
      throw new Error('Wasm optimization changed the public exports');
    }
    const imports = new Set(WebAssembly.Module.imports(originalModule).map(entry => JSON.stringify(entry)));
    if (WebAssembly.Module.imports(optimizedModule).some(entry => !imports.has(JSON.stringify(entry)))) {
      throw new Error('Wasm optimization introduced a dependency missing from the generated loader');
    }
    fs.writeFileSync(output, optimized);
    // A failed optimization or validation leaves the original package intact.
    fs.renameSync(output, file);
    console.log(`${file}: ${original.length} -> ${optimized.length} bytes (optimized; function names retained)`);
    return { beforeBytes: original.length, afterBytes: optimized.length };
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

module.exports = { optimizeWasm };
if (require.main === module) {
  const files = process.argv.slice(2);
  if (!files.length) throw new Error('Usage: node scripts/optimize-wasm.cjs KOTLIN_APP.wasm ...');
  for (const file of files) optimizeWasm(file);
}
