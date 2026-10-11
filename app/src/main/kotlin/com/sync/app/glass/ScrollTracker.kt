package com.sync.app.glass

/**
 * Gap filler for the scroll position (CSS px). It does NOT smooth or low-pass the position:
 *
 *   output = latest real sample  +  velocity * (time since that sample)      (extrapolation capped at EXT_MS)
 *
 * so the output is always at least as current as the newest sample (never behind it). Frames drawn between two
 * samples continue along the last measured velocity instead of repeating the old value, which removes stepping
 * without adding any latency. Velocity is taken from the last two samples only; a direction change takes effect
 * immediately. Once samples stop, the extrapolation eases out and the output lands exactly on the last sample.
 */
internal class ScrollTracker {
    private var inited = false
    private var t1 = 0L
    private var z1 = 0f
    private var vel = 0f // css px / ms

    fun addSample(tNs: Long, z: Float) {
        if (!inited) { z1 = z; t1 = tNs; vel = 0f; inited = true; return }
        if (tNs <= t1) return                                   // out-of-order sample
        val dt = (tNs - t1) / 1_000_000f
        vel = if (dt > GAP_MS || kotlin.math.abs(z - z1) > SNAP_PX) {
            0f                                                  // new gesture / programmatic jump
        } else {
            val inst = (z - z1) / dt
            if (inst * vel < 0f) inst else 0.5f * vel + 0.5f * inst
        }.coerceIn(-MAX_VEL, MAX_VEL)
        z1 = z; t1 = tNs
    }

    fun valueAt(nowNs: Long): Float {
        if (!inited) return 0f
        val dt = ((nowNs - t1) / 1_000_000f).coerceAtLeast(0f)
        val ext = if (dt <= EXT_MS) vel * dt else {
            val s = smooth(((dt - EXT_MS) / SETTLE_MS).coerceIn(0f, 1f))
            vel * EXT_MS * (1f - s)
        }
        return (z1 + ext).coerceAtLeast(0f)
    }

    fun isMoving(nowNs: Long): Boolean = inited && (nowNs - t1) / 1_000_000f < EXT_MS + SETTLE_MS + 40f

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    private companion object {
        const val EXT_MS = 24f      // ~1.5 frames: only fills the gap up to the next sample
        const val SETTLE_MS = 80f
        const val GAP_MS = 250f
        const val SNAP_PX = 160f
        const val MAX_VEL = 8f
    }
}
