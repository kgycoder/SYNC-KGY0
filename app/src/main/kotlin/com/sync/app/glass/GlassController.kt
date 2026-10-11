package com.sync.app.glass

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Frontend(WebView) <-> Native(Compose) channel.
 *
 *  JS  -> Kotlin : window.GlassBridge.push(json)   (DOM state mirror, rects in CSS px)
 *  Kotlin -> JS  : window.__glassCmd({...})        (taps / text input forwarded to the existing app.js functions)
 *
 * The WebView + app.js + AndroidBridge keep running exactly as before; they remain the single source of
 * truth for playback / search / playlists. Compose only renders the glass surfaces and their controls.
 */
class GlassController(private val webView: WebView) {

    var state by mutableStateOf(GlassState())
        private set

    // ---- scroll position: continuous stream -> ScrollTracker (smoothing + prediction) -> per-frame value ----
    private val tracker = ScrollTracker()
    private var nativeWorks = false
    private var nativeNs = 0L

    /** frame time of the last tick while the page is moving; reading it makes placement re-run every frame */
    var frameNs by mutableLongStateOf(0L)
        private set
    /** true while scroll samples are flowing (drives the per-frame ticker in GlassRoot) */
    var moving by mutableStateOf(false)
        private set

    private val density get() = webView.resources.displayMetrics.density

    /** WebView.onScrollChanged — UI thread, fires inside the touch handler / fling tick (freshest source). */
    fun onNativeScroll(y: Int) {
        val now = System.nanoTime()
        nativeWorks = true
        nativeNs = now
        tracker.addSample(now, y / density)
        if (!moving) moving = true
    }

    /** window.scrollY (CSS px) pushed by JS on every scroll event / animation frame; used when native goes silent. */
    @JavascriptInterface
    fun scroll(y: Float) {
        val t = System.nanoTime()
        main.post {
            if (!nativeWorks || t - nativeNs > 40_000_000L) {
                tracker.addSample(t - JS_LATENCY_NS, y)
                if (!moving) moving = true
            }
        }
    }

    /** called once per frame by the ticker; returns false when the page has settled */
    fun tick(frameTimeNs: Long): Boolean {
        frameNs = frameTimeNs
        val m = tracker.isMoving(frameTimeNs)
        if (!m) moving = false
        return m
    }

    /** Document scroll in CSS px (== dp) for the frame being placed. Reads state → re-runs every frame while moving. */
    fun cssScroll(@Suppress("UNUSED_PARAMETER") density: Float): Float {
        val f = frameNs
        return tracker.valueAt(if (moving) f else System.nanoTime())
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastJson = ""

    @JavascriptInterface
    fun push(json: String) {
        if (json == lastJson) return
        lastJson = json
        // parse on the JS bridge thread; only the already-built immutable state hops to the UI thread
        val parsed = try { GlassState.parse(json) } catch (_: Exception) { return }
        main.post { state = parsed }
    }

    fun cmd(build: JSONObject.() -> Unit) {
        val js = JSONObject().apply(build).toString()
        webView.post { webView.evaluateJavascript("window.__glassCmd&&window.__glassCmd($js)", null) }
    }

    private companion object {
        /** JS scroll events describe a position ~1-2 frames old; shifting their timestamp keeps the tracker's timeline honest */
        const val JS_LATENCY_NS = 20_000_000L
    }
}
