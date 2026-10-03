#!/usr/bin/env python3
"""Summarize an xctrace time-profile XML export without device IDs or paths."""
import argparse
import collections
import json
import xml.etree.ElementTree as ET


def summarize(path, start, end):
    root = ET.parse(path).getroot()
    ids = {element.get("id"): element for element in root.iter() if element.get("id")}

    def resolve(element):
        while element is not None and element.get("ref"):
            element = ids[element.get("ref")]
        return element

    threads = collections.Counter()
    frames = collections.Counter()
    samples = weight_ns = 0
    for row in root.iter("row"):
        timestamp = int(resolve(row.find("sample-time")).text) / 1e9
        if not start <= timestamp < end:
            continue
        samples += 1
        weight_ns += int(resolve(row.find("weight")).text)
        thread = resolve(row.find("thread")).get("fmt", "")
        threads["main" if "Main Thread" in thread else "gc" if "GC" in thread else "other"] += 1
        backtrace = resolve(row.find("tagged-backtrace"))
        if backtrace is not None:
            names = {resolve(frame).get("name", "") for frame in backtrace.iter("frame")}
            frames.update(name for name in names if "finance.shilling" in name)
    return {
        "windowSeconds": [start, end],
        "samples": samples,
        "sampledCpuMilliseconds": weight_ns / 1e6,
        "threads": dict(threads),
        "inclusiveAppFrames": dict(frames.most_common()),
        "note": "Statistical CPU samples, not battery measurements. Inclusive frame counts overlap.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xml")
    parser.add_argument("--start", type=float, default=15)
    parser.add_argument("--end", type=float, default=20)
    args = parser.parse_args()
    if not 0 <= args.start < args.end:
        parser.error("Require 0 <= start < end")
    print(json.dumps(summarize(args.xml, args.start, args.end), indent=2))
