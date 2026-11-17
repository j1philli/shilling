#!/usr/bin/env python3
"""Summarize an xctrace hitches-summary XML export without device identifiers.

Hitch rows may overlap across rendering contexts. Their durations must not be
added to claim a percentage of wall time spent hitching.
"""
import argparse
import collections
import json
import xml.etree.ElementTree as ET


def summarize(path):
    root = ET.parse(path).getroot()
    ids = {e.get("id"): e for e in root.iter() if e.get("id")}

    def resolve(element):
        while element.get("ref"):
            element = ids[element.get("ref")]
        return element

    samples = []
    for node in root.iter("node"):
        schema = node.find("schema")
        if schema is None or schema.get("name") != "hitches-summary":
            continue
        columns = [c.findtext("mnemonic") for c in schema]
        for row in node.findall("row"):
            values = dict(zip(columns, [resolve(e) for e in row]))
            samples.append({
                "startSeconds": round(int(values["start"].text) / 1e9, 4),
                "durationMs": round(int(values["duration"].text) / 1e6, 3),
                "type": values["type-label"].text,
                "severity": values["severity"].get("fmt"),
            })
    return {"hitches": len(samples), "maxHitchMs": max((r["durationMs"] for r in samples), default=0),
            "types": dict(collections.Counter(r["type"] for r in samples)), "samples": samples,
            "note": "Rendering-context intervals can overlap; durations are not a wall-time ratio."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xml")
    args = parser.parse_args()
    print(json.dumps(summarize(args.xml), indent=2))
