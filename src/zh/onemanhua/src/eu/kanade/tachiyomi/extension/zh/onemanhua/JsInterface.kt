package eu.kanade.tachiyomi.extension.zh.onemanhua

import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.concurrent.CountDownLatch

class JsInterface(
    latch: CountDownLatch,
    private val chapterUrl: String,
) {
    var webView: WebView? = null
    private var latch: CountDownLatch? = latch
    private var images: Array<String?> = emptyArray()
    private var callbacks: Array<((String) -> Unit)?> = emptyArray()
    var lastPageIndex: Int = -1
        private set

    fun getPageCount() = images.size

    fun takePage(index: Int): String? {
        val result = images[index] ?: return null
        check(result.isNotEmpty()) { "图片已被读取过" }
        Log.i(LOG_TAG, "Taken image ${index + 1}")
        images[index] = ""
        callbacks[index] = null
        return result
    }

    fun setCallback(index: Int, callback: (String) -> Unit) {
        callbacks[index] = callback
    }

    fun removeCallback(index: Int) {
        callbacks[index] = null
    }

    fun destroyWebView() {
        val webView = this.webView ?: return
        Log.i(LOG_TAG, "Destroying WebView for $chapterUrl")
        if (Looper.myLooper() == null) {
            HANDLER.removeCallbacksAndMessages(chapterUrl)
            HANDLER.post(webView::destroy)
        } else {
            try {
                webView.destroy()
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "destroy", e)
            }
        }
        this.webView = null
    }

    @JavascriptInterface
    fun setPageCount(count: Int) {
        if (count <= 0) {
            latch!!.countDown()
            latch = null
            return
        }
        images = arrayOfNulls(count)
        callbacks = arrayOfNulls(count)
        latch!!.countDown()
        latch = null
    }

    @JavascriptInterface
    fun setPage(index: Int, url: String) {
        require(url.startsWith("data"))
        val image = "https://127.0.0.1/?image${url.substringAfter(":")}" // FIXME 2
//        val callback = callbacks[index]
//        if (callback != null) {
//            try {
//
//            }
//        }
        val images = this.images
        images[index] = image
        if (index > lastPageIndex) lastPageIndex = index
        Log.i(LOG_TAG, "Got page #${index + 1} of ${chapterUrl.takeLast(8)}: ${url.take(50)}; Looper: ${Looper.myLooper()}, main is ${Looper.getMainLooper()}")
        for (i in images.lastIndex downTo 0) {
            if (images[i] == null) return
        }
        Log.i(LOG_TAG, "All page saved, destroying WebView")
        destroyWebView()
    }

    @JavascriptInterface
    fun log(msg: String) {
        Log.i(LOG_TAG, msg)
    }
}
