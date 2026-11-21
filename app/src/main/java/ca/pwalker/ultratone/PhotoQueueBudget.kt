package ca.pwalker.ultratone

import android.app.ActivityManager
import android.content.Context

/** Conservative admission estimates, not a promise that all RAM is available to this app. */
internal object PhotoQueueBudget {
    private const val MIB = 1024L * 1024
    @Volatile private var largestJpeg = 8 * MIB
    private var observedJpeg = false
    private var queuedHeapReserve = 0L
    private var queuedNativeReserve = 0L
    @Volatile private var largestPixels = 12_500_000L

    @Synchronized fun observe(bytes: Int, width: Int, height: Int) {
        largestJpeg = maxOf(if (observedJpeg) largestJpeg else 2 * MIB, bytes.toLong())
        observedJpeg = true
        var sample = 1L
        while ((width / sample) * (height / sample) > 16_000_000L) sample *= 2
        largestPixels = maxOf(largestPixels, (width / sample) * (height / sample))
    }

    @Synchronized fun limit(context: Context, framesPerPhoto: Int, longExposure: Boolean, bokeh: Boolean): Int {
        val count = PhotoProcessingService.pendingCount
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val heapFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        // Day/colour effects use row batches, NOT full-frame Java pixel arrays.
        // Android 8+ Bitmap pixels live in native memory, checked separately below.
        val heapReserve = 24 * MIB + when {
            longExposure -> largestPixels * 13
            framesPerPhoto > 1 -> largestPixels * 4 + if (bokeh) 40 * MIB else 0L
            bokeh -> 40 * MIB
            else -> 0L
        }
        val nativeReserve = 128 * MIB + largestPixels * if (bokeh) 20 else 12
        // Preserve the heavier budget while earlier stack/Bokeh jobs are still queued,
        // even if the user switches back to Day. Reset when the queue drains.
        queuedHeapReserve = if (count == 0) heapReserve else maxOf(queuedHeapReserve, heapReserve)
        queuedNativeReserve = if (count == 0) nativeReserve else maxOf(queuedNativeReserve, nativeReserve)
        val perPhoto = largestJpeg * framesPerPhoto.coerceAtLeast(1) + MIB
        val extra = if (info.lowMemory) 0 else (minOf(
            heapFree - queuedHeapReserve - largestJpeg, // Leave room to copy the next camera buffer.
            info.availMem - info.threshold - queuedNativeReserve
        ).coerceAtLeast(0) / perPhoto).coerceAtMost(24).toInt()
        val queueBudget = minOf(runtime.maxMemory() / 3, info.totalMem / 64, 128 * MIB)
        val budgetCount = (queueBudget / perPhoto).coerceIn(1, 24).toInt()
        return minOf(count + extra, budgetCount, 24).coerceAtLeast(1)
    }
}
