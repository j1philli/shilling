const fs = require('node:fs');

// Keep module/function names for production stack traces. Local, type, global
// and GC field names serve interactive debugging only; the unmodified compiler
// package remains in build/tasks for that purpose. All executable sections and
// other custom metadata are preserved byte for byte.
const DEBUG_NAME_SUBSECTIONS = new Set([2, 4, 7, 10]);
const HEADER = Buffer.from([0, 97, 115, 109, 1, 0, 0, 0]);

function u32(value) {
  const bytes = [];
  do {
    const byte = value & 127;
    value = Math.floor(value / 128);
    bytes.push(byte | (value ? 128 : 0));
  } while (value);
  return Buffer.from(bytes);
}

function trimDebugNames(bytes) {
  if (!bytes.subarray(0, 8).equals(HEADER)) throw new Error('Expected a Wasm v1 binary');
  let offset = 8;
  function readU32(limit) {
    let value = 0;
    for (let n = 0; n < 5; n++) {
      if (offset >= limit) throw new Error('Truncated Wasm length');
      const byte = bytes[offset++];
      if (n === 4 && byte > 15) throw new Error('Invalid Wasm u32');
      value += (byte & 127) * 2 ** (n * 7);
      if (!(byte & 128)) return value;
    }
    throw new Error('Invalid Wasm u32');
  }
  const output = [bytes.subarray(0, 8)];
  while (offset < bytes.length) {
    const start = offset;
    const id = bytes[offset++];
    const length = readU32(bytes.length);
    const payloadStart = offset;
    const end = offset + length;
    if (end > bytes.length) throw new Error('Truncated Wasm section');
    let rewritten = false;
    if (id === 0) {
      const nameLength = readU32(end);
      const nameEnd = offset + nameLength;
      if (nameEnd > end) throw new Error('Truncated custom section name');
      const name = bytes.subarray(offset, nameEnd).toString('utf8');
      offset = nameEnd;
      if (name === 'name') {
        const kept = [bytes.subarray(payloadStart, offset)];
        while (offset < end) {
          const subStart = offset;
          const subId = bytes[offset++];
          const subLength = readU32(end);
          offset += subLength;
          if (offset > end) throw new Error('Truncated name subsection');
          if (DEBUG_NAME_SUBSECTIONS.has(subId)) rewritten = true;
          else kept.push(bytes.subarray(subStart, offset));
        }
        if (rewritten) {
          const payload = Buffer.concat(kept);
          output.push(Buffer.from([0]), u32(payload.length), payload);
        }
      }
    }
    if (!rewritten) output.push(bytes.subarray(start, end));
    offset = end;
  }
  return Buffer.concat(output);
}

module.exports = { trimDebugNames };

if (require.main === module) {
  const paths = process.argv.slice(2);
  if (!paths.length) throw new Error('Usage: node scripts/trim-wasm-debug-names.cjs FILE.wasm ...');
  for (const path of paths) {
    const original = fs.readFileSync(path);
    const trimmed = trimDebugNames(original);
    fs.writeFileSync(path, trimmed);
    console.log(`${path}: ${original.length} -> ${trimmed.length} bytes (function names preserved)`);
  }
}
