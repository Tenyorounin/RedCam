package ca.pwalker.ultratone

import android.app.ActivityManager
import android.content.Context

/** Conservative admission estimates, not a promise that all RAM is available to this app. */
internal object PhotoQueueBudget {
    private const val MIB = 1024L * 1024
    @Volatile private var largestJpeg = 8 * MIB
    @Volatile private var largestPixels = 12_500_000L

    @Synchronized fun observe(bytes: Int, width: Int, height: Int) {
        largestJpeg = maxOf(largestJpeg, bytes.toLong())
        var sample = 1L
        while ((width / sample) * (height / sample) > 16_000_000L) sample *= 2
        largestPixels = maxOf(largestPixels, (width / sample) * (height / sample))
    }

    fun limit(context: Context, framesPerPhoto: Int, longExposure: Boolean): Int {
        val count = PhotoProcessingService.pendingCount
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        if (info.lowMemory) return count
        val runtime = Runtime.getRuntime()
        val heapFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        // Reserve pixel arrays for merging, plus native bitmaps, depth inference and camera buffers.
        val heapReserve = 48 * MIB + largestPixels * if (longExposure) 13 else 8
        val systemReserve = maxOf(info.threshold, info.totalMem / 10) + 192 * MIB + largestPixels * 32
        val perPhoto = largestJpeg * framesPerPhoto.coerceAtLeast(1) + 2 * MIB
        val extra = (minOf(heapFree - heapReserve, info.availMem - systemReserve)
            .coerceAtLeast(0) / perPhoto).coerceAtMost(32).toInt()
        val queueBudget = minOf(runtime.maxMemory() / 3, info.totalMem / 32, 256 * MIB)
        val budgetCount = (queueBudget / perPhoto).coerceIn(1, 32).toInt()
        return minOf(count + extra, budgetCount, 32)
    }
}
