#!/usr/bin/env python3
"""Read macOS kernel physical footprint without inspecting custom malloc zones."""

import ctypes
import json
import sys

if sys.platform != "darwin":
    raise SystemExit("This probe requires macOS")


class RUsageInfoV4(ctypes.Structure):
    # rusage_info_v4 in the macOS SDK's sys/resource.h has a 16-byte UUID
    # followed by 35 uint64_t fields. The selected indices are
    # ri_phys_footprint and ri_lifetime_max_phys_footprint, respectively.
    _fields_ = [("uuid", ctypes.c_uint8 * 16), ("values", ctypes.c_uint64 * 35)]


lib = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
lib.proc_pid_rusage.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
lib.proc_pid_rusage.restype = ctypes.c_int


def snapshot(pid):
    info = RUsageInfoV4()
    if lib.proc_pid_rusage(int(pid), 4, ctypes.byref(info)):
        raise OSError(ctypes.get_errno(), "proc_pid_rusage failed")
    return {
        "footprintMiB": info.values[7] / 1048576,
        "lifetimePeakFootprintMiB": info.values[28] / 1048576,
    }


if __name__ == "__main__":
    result = {}
    for pid in sys.argv[1:]:
        try:
            result[pid] = snapshot(pid)
        except OSError as error:
            result[pid] = {"error": str(error)}
    print(json.dumps(result))
