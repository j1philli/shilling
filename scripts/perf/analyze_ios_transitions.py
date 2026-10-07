#!/usr/bin/env python3
"""Align native UI signpost intervals with Time Profiler and Animation Hitches exports.

Record Animation Hitches with Time Profiler and Points of Interest added. Export
OSSignpostIntervals, time-profile and hitches-summary tables from the same trace.
"""
import argparse
import json
import xml.etree.ElementTree as ET

from analyze_ios_hitches import summarize as summarize_hitches


def table(path):
    root = ET.parse(path).getroot()
    ids = {e.get("id"): e for e in root.iter() if e.get("id")}

    def resolve(element):
        while element is not None and element.get("ref"):
            element = ids[element.get("ref")]
        return element

    return root, resolve


def summarize_cpu(samples):
    # Inclusive sample counts: stack categories overlap. A sample contributes
    # once per category even when multiple matching frames occur in its stack.
    categories = {
        "menuConstruction": "Coordinator.makeMenu()",
        "viewGraphUpdate": "ViewRendererHost.updateViewGraph<A>(body:)",
        "collectionLayout": "-[UICollectionView layoutSubviews]",
        "cellCreation": "-[UICollectionView _createPreparedCellForItemAtIndexPath:withLayoutAttributes:applyAttributes:isFocused:notify:]",
        "cellSizing": "PlatformListViewBase<>.hostSizeThatFits(width:)",
        "navigationLayout": "-[UINavigationController __viewWillLayoutSubviews]",
        "toolbarUpdate": "ToolbarBridge.preferencesDidChange(_:context:)",
        "hostingViewRelease": "_UIHostingView.__deallocating_deinit",
        "hostingLayout": "_UIHostingView.layoutSubviews()",
        "windowAttachment": "-[UIView(Internal) _didMoveFromWindow:toWindow:]",
        "layerLayout": "CA::Layer::layout_if_needed(CA::Transaction*)",
    }
    main_stacks = [s[3] for s in samples if s[2]]
    counts = {key: sum(frame in stack for stack in main_stacks) for key, frame in categories.items()}
    counts["tabTransition"] = sum(
        any("-[UITabBarController transitionFromViewController:" in frame for frame in stack)
        for stack in main_stacks
    )
    return {"sampledCpuMs": sum(s[1] for s in samples), "mainThreadSamples": len(main_stacks),
            "inclusiveMainThreadSamples": counts}


def summarize(intervals_path, cpu_path, hitches_path):
    root, resolve = table(intervals_path)
    intervals = []
    for node in root.iter("node"):
        columns = [c.findtext("mnemonic") for c in node.find("schema")]
        for row in node.findall("row"):
            values = dict(zip(columns, map(resolve, row)))
            if values["name"].text != "NativeUIWorkload":
                continue
            start = int(values["start"].text) / 1e9
            duration = int(values["duration"].text) / 1e9
            intervals.append((start, start + duration, values["start-message"].get("fmt")))
    if not intervals:
        raise ValueError("No NativeUIWorkload intervals; add Points of Interest when recording")

    root, resolve = table(cpu_path)
    samples = []
    for row in root.iter("row"):
        timestamp = int(resolve(row.find("sample-time")).text) / 1e9
        weight = int(resolve(row.find("weight")).text) / 1e6
        main = "Main Thread" in resolve(row.find("thread")).get("fmt", "")
        backtrace = resolve(row.find("tagged-backtrace"))
        frames = {resolve(f).get("name", "") for f in backtrace.iter("frame")} if backtrace is not None else set()
        samples.append((timestamp, weight, main, frames))

    hitches = summarize_hitches(hitches_path)["samples"]
    result = []
    for start, end, name in intervals:
        selected = [s for s in samples if start <= s[0] < end]
        hits = [h for h in hitches if start <= h["startSeconds"] < end]
        # Include the full hitch window even if it ends after the phase. CPU
        # stacks locate overlapping work, not proof that a frame caused a hitch.
        hits = [{**h, "cpuWindow": summarize_cpu([
            s for s in samples if h["startSeconds"] <= s[0] < h["startSeconds"] + h["durationMs"] / 1000
        ])} for h in hits]
        result.append({
            "phase": name, "startSeconds": start, "endSeconds": end,
            **summarize_cpu(selected),
            "hitches": len(hits), "maxHitchMs": max((h["durationMs"] for h in hits), default=0),
            "hitchSamples": hits,
        })
    return {
        "phases": result,
        "note": "Hitches are assigned by interval start. Rendering contexts and inclusive CPU stacks can overlap; do not add their durations or counts into wall-time percentages. Push intervals end at field readiness, not animation completion. Launch/seed work outside the signposts is excluded.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--intervals", required=True)
    parser.add_argument("--cpu", required=True)
    parser.add_argument("--hitches", required=True)
    args = parser.parse_args()
    print(json.dumps(summarize(args.intervals, args.cpu, args.hitches), indent=2))
