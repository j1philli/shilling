package finance.shilling.perf

import android.os.Debug
import org.json.JSONObject
import java.io.File

/** Process memory counters for the isolated fixture; VmHWM is lifetime peak RSS. */
internal fun memoryProbe(): JSONObject {
    val status = File("/proc/self/status").readLines()
    fun kib(name: String): Long = status.first { it.startsWith("$name:") }
        .substringAfter(':').trim().substringBefore(' ').toLong()
    val runtime = Runtime.getRuntime()
    return JSONObject()
        .put("vmHwmKiB", kib("VmHWM"))
        .put("vmRssKiB", kib("VmRSS"))
        .put("pssKiB", Debug.getPss())
        .put("javaHeapBytes", runtime.totalMemory() - runtime.freeMemory())
        .put("javaHeapLimitBytes", runtime.maxMemory())
        .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
}
