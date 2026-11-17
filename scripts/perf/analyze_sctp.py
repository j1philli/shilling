#!/usr/bin/env python3
"""Summarize one synthetic transfer from Android's verbose dcSCTP text log.

Reads packet prefixes even when logcat truncates payloads. Counts logged send
attempts, not confirmed wire transmissions. Stops 100 ms after FileComplete to
exclude teardown retries. Use a fresh log containing just one transfer.
"""

import collections
import json
import re
import statistics
import struct
import sys

sent = collections.defaultdict(list)
packets = collections.Counter()
windows = []
missing = set()
sacks = gaps = malformed = truncated = 0
complete_at = None
pattern = re.compile(r": ([OI]) (\d\d):(\d\d):(\d\d\.\d+) 0000 ((?:[0-9a-f]{2} ?)+)")

with open(sys.argv[1]) as trace:
    for line in trace:
        match = pattern.search(line)
        if not match:
            continue
        direction = match[1]
        timestamp = int(match[2]) * 3600 + int(match[3]) * 60 + float(match[4])
        packet = bytes.fromhex(match[5])
        if complete_at is not None and timestamp > complete_at + 0.1:
            continue
        if direction == "O" and b".FileComplete" in packet and complete_at is None:
            complete_at = timestamp
        packets[direction] += 1
        offset = 12  # SCTP common header.
        while offset + 4 <= len(packet):
            kind, flags, size = struct.unpack("!BBH", packet[offset:offset + 4])
            chunk = packet[offset:offset + size]
            if size < 4:
                malformed += 1
                break
            if direction == "O" and kind in (0, 64) and len(chunk) >= 8:
                # DATA and I-DATA both put the TSN immediately after the header.
                sent[int.from_bytes(chunk[4:8], "big")].append(timestamp)
            if direction == "I" and kind == 3 and len(chunk) >= 16:
                sacks += 1
                ack, window, gap_count = struct.unpack("!IIH", chunk[4:14])
                windows.append(window)
                if gap_count and len(chunk) >= 16 + gap_count * 4:
                    gaps += 1
                    previous_end = 0
                    for index in range(gap_count):
                        start, gap_end = struct.unpack("!HH", chunk[16 + index * 4:20 + index * 4])
                        missing.update((ack + delta) & 0xffffffff for delta in range(previous_end + 1, start))
                        previous_end = gap_end
            if len(chunk) < size:
                truncated += 1
                break
            offset += (size + 3) & ~3

repeated = {tsn: times for tsn, times in sent.items() if len(times) > 1}
delays = [(times[1] - times[0]) * 1000 for times in repeated.values()]
print(json.dumps({
    "packets": dict(packets),
    "uniqueOutboundDataTsns": len(sent),
    "repeatedOutboundTsns": len(repeated),
    "extraLoggedSendAttempts": sum(len(times) - 1 for times in repeated.values()),
    "receivedSacks": sacks,
    "sacksWithGaps": gaps,
    "distinctMissingTsnsReportedByPeer": len(missing),
    "repeatedAndReportedMissing": len(set(repeated) & missing),
    "receiverWindowMinBytes": min(windows, default=0),
    "receiverWindowMaxBytes": max(windows, default=0),
    "firstRepeatDelayMsMedian": statistics.median(delays) if delays else None,
    "malformedChunks": malformed,
    "truncatedPayloadsWithHeader": truncated,
    "fileCompleteObserved": complete_at is not None,
}, indent=2))
