#!/usr/bin/env python3
"""Create disposable deterministic 10/50 MiB PNG fixtures without third-party libraries."""
import argparse
import pathlib
import random
import struct
import zlib

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=pathlib.Path)
args = parser.parse_args()
args.directory.mkdir(parents=True, exist_ok=True)

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

for mib, side in [(10, 1800), (50, 4000)]:
    target = args.directory / f'shilling-synthetic-{mib}MiB.png'
    rng = random.Random(mib)
    scanlines = b''.join(b'\0' + rng.randbytes(side * 3) for _ in range(side))
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', side, side, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(scanlines, level=0)) + chunk(b'IEND', b''))
    size = mib * 1024 * 1024
    assert len(png) <= size
    # Trailing padding makes transport/import sizes exact, without changing pixels.
    target.write_bytes(png + bytes(size - len(png)))
    print(target.name)
