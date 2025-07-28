package eu.kanade.tachiyomi.extension.zh.onemanhua

import android.os.Build
import android.os.SystemClock
import android.util.Log

private const val CHAPTER_CACHE_SIZE = 5
private const val CHAPTER_CACHE_MILLIS = 1800_000L

// Limited callable methods to make sure we destroy WebViews properly.
class ChapterCache {
    private val inner = ChapterCacheInner()

    operator fun get(key: String): JsInterface? = inner[key]?.also { scheduleRemoval(key) }

    operator fun set(key: String, value: JsInterface) {
        remove(key)
        Log.i(LOG_TAG, "Adding WebView for $key")
        inner[key] = value
        scheduleRemoval(key)
    }

    fun remove(key: String) {
        val value = inner.remove(key) ?: return
        value.destroyWebView()
    }

    private fun scheduleRemoval(key: String) {
        HANDLER.removeCallbacksAndMessages(key)
        val r = Runnable {
            Log.i(LOG_TAG, "Cache timeout: $key")
            remove(key)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HANDLER.postDelayed(r, key, CHAPTER_CACHE_MILLIS)
        } else {
            HANDLER.postAtTime(r, key, SystemClock.uptimeMillis() + CHAPTER_CACHE_MILLIS)
        }
    }
}

private class ChapterCacheInner :
    LinkedHashMap<String, JsInterface>(CHAPTER_CACHE_SIZE * 2, 0.75f, true) {

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsInterface>): Boolean {
        if (size <= CHAPTER_CACHE_SIZE) return false
        Log.i(LOG_TAG, "Cache capacity reached, evicting ${eldest.key}")
        eldest.value.destroyWebView()
        return true
    }
}
