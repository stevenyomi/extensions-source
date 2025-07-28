package eu.kanade.tachiyomi.extension.zh.onemanhua

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.dataimage.DataImageInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

abstract class ColaManga(
    final override val name: String,
    final override val baseUrl: String,
    final override val lang: String,
) : ParsedHttpSource(), ConfigurableSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = network.cloudflareClient.newBuilder()
        .rateLimitHost(
            baseUrl.toHttpUrl(),
            preferences.getString(RATE_LIMIT_PREF_KEY, RATE_LIMIT_PREF_DEFAULT)!!.toInt(),
            preferences.getString(RATE_LIMIT_PERIOD_PREF_KEY, RATE_LIMIT_PERIOD_PREF_DEFAULT)!!.toLong(),
            TimeUnit.MILLISECONDS,
        )
        .addInterceptor(DataImageInterceptor())
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/show?orderBy=dailyCount&page=$page", headers)

    override fun popularMangaSelector() = "li.fed-list-item"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        element.selectFirst("a.fed-list-title")!!.let {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst("a.fed-list-pics")?.absUrl("data-original")
    }

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/show?orderBy=update&page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/search".toHttpUrl().newBuilder().apply {
                filters.ifEmpty { getFilterList() }
                    .firstOrNull { it is SearchTypeFilter }
                    ?.let { (it as SearchTypeFilter).addToUri(this) }

                addQueryParameter("searchString", query)
                addQueryParameter("page", page.toString())
            }.build()
        } else {
            "$baseUrl/show".toHttpUrl().newBuilder().apply {
                filters.ifEmpty { getFilterList() }
                    .filterIsInstance<UriFilter>()
                    .filterNot { it is SearchTypeFilter }
                    .forEach { it.addToUri(this) }

                addQueryParameter("page", page.toString())
            }.build()
        }

        return GET(url, headers)
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SLUG_SEARCH)
            val url = "/$slug/"

            fetchMangaDetails(SManga.create().apply { this.url = url })
                .map { MangasPage(listOf(it.apply { this.url = url }), false) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaSelector() = "dl.fed-deta-info, ${popularMangaSelector()}"

    override fun searchMangaFromElement(element: Element): SManga {
        if (element.tagName() == "li") {
            return popularMangaFromElement(element)
        }

        return SManga.create().apply {
            element.selectFirst("h1.fed-part-eone a")!!.let {
                setUrlWithoutDomain(it.attr("href"))
                title = it.text()
            }

            thumbnail_url = element.selectFirst("a.fed-list-pics")?.absUrl("data-original")
        }
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    protected abstract val statusTitle: String
    protected abstract val authorTitle: String
    protected abstract val genreTitle: String
    protected abstract val statusOngoing: String
    protected abstract val statusCompleted: String
    protected abstract val lastUpdated: String
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd")

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1.fed-part-eone")!!.text()
        thumbnail_url = document.selectFirst("a.fed-list-pics")?.absUrl("data-original")
        author = document.selectFirst("span.fed-text-muted:contains($authorTitle) + a")?.text()
        genre = document.select("span.fed-text-muted:contains($genreTitle) ~ a").joinToString { it.text() }
        description = document
            .selectFirst("ul.fed-part-rows li.fed-col-xs12.fed-show-md-block .fed-part-esan")
            ?.ownText()
        status = when (document.selectFirst("span.fed-text-muted:contains($statusTitle) + a")?.text()) {
            statusOngoing -> SManga.ONGOING
            statusCompleted -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListSelector(): String = "div:not(.fed-hidden) > div.all_data_list > ul.fed-part-rows a"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).map { chapterFromElement(it) }.apply {
            if (isNotEmpty()) {
                this[0].date_upload = dateFormat.tryParse(document.selectFirst("span.fed-text-muted:contains($lastUpdated) + a")?.text())
            }
        }
    }

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.attr("title")
    }

    override fun pageListParse(document: Document) = throw UnsupportedOperationException()
    private val interfaceName = randomString()

    private val chapterCache = ChapterCache()
    private val webViewScript by lazy {
        javaClass.getResource("/assets/webview-script.js")?.readText() ?: throw Exception("WebView 脚本不存在")
    }
    private val baseUrlTopPrivateDomain = baseUrl.toHttpUrl().topPrivateDomain()!!

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val url = chapter.url
        return Observable.fromCallable { fetchPageList(url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchPageList(chapterUrl: String): List<Page> {
        val latch = CountDownLatch(1)
        val jsInterface = JsInterface(latch, chapterUrl)
        chapterCache[chapterUrl] = jsInterface

        HANDLER.post {
            val innerWv = WebView(Injekt.get<Application>())
            jsInterface.webView = innerWv
            innerWv.settings.javaScriptEnabled = true
            innerWv.settings.domStorageEnabled = true
            innerWv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            innerWv.addJavascriptInterface(jsInterface, interfaceName)
            innerWv.webViewClient = MyWebViewClient(chapterUrl)

            innerWv.loadUrl(baseUrl + chapterUrl)
        }

        latch.await(30L, TimeUnit.SECONDS)

        if (latch.count == 1L) {
            chapterCache.remove(chapterUrl)
            throw Exception("加载章节超时")
        }
        if (jsInterface.getPageCount() <= 0) {
            chapterCache.remove(chapterUrl)
            throw Exception("加载章节失败：页面数量为 0")
        }

        return List(jsInterface.getPageCount()) { index ->
            Page(index, url = chapterUrl)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun fetchImageUrl(page: Page): Observable<String> = rxObservable {
        val chapterUrl = page.url
        val pageIndex = page.index
        Log.i(LOG_TAG, "fetchImageUrl(${chapterUrl.takeLast(8)}, #${pageIndex + 1})")
        // FIXME: image errors not surfaced in Stable. need toast
        val jsInterface = chapterCache[chapterUrl] ?: run {
            Log.i(LOG_TAG, "Spawning WebView from #${pageIndex + 1}") // FIXME: race?
            fetchPageList(chapterUrl) // webview not found, load webview
            chapterCache[chapterUrl]!!
        }
        jsInterface.takePage(pageIndex)?.let {
            return@rxObservable it
        }
        val webView = jsInterface.webView ?: throw Exception("WebView 已被销毁")
        HANDLER.post { // run this later
            Log.i(LOG_TAG, "calling loadPic(${pageIndex + 1}) from Kotlin side")
            webView.evaluateJavascript("loadPic($pageIndex)", null) // FIXME: do something here?
        }
        if (jsInterface.lastPageIndex + 8 < pageIndex) {
            val message = "$LOG_TAG: 请从头开始顺序阅读，目前加载到第 ${jsInterface.lastPageIndex + 1} 页"
            HANDLER.post {
                Toast.makeText(Injekt.get<Application>(), message, Toast.LENGTH_SHORT).show()
            }
            throw Exception(message)
        }
        try {
            withTimeout(30.seconds) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { jsInterface.removeCallback(pageIndex) }
                    jsInterface.setCallback(pageIndex, continuation::resume)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw Exception("加载图片超时")
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        SearchTypeFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        ListPreference(context).apply {
            key = RATE_LIMIT_PREF_KEY
            title = "主站连接限制"
            summary = "此值影响主站的连接请求量。降低此值可以减少获得HTTP 403错误的几率，但加载速度也会变慢。需要重启软件以生效。\n默认值：${RATE_LIMIT_PREF_DEFAULT}\n当前值：%s"
            entries = RATE_LIMIT_PREF_ENTRIES
            entryValues = RATE_LIMIT_PREF_ENTRIES

            setDefaultValue(RATE_LIMIT_PREF_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(context).apply {
            key = RATE_LIMIT_PERIOD_PREF_KEY
            title = "主站连接限制期"
            summary = "此值影响主站点连接限制时的延迟（毫秒）。增加这个值可能会减少出现HTTP 403错误的机会，但加载速度也会变慢。需要重启软件以生效。\n默认值：${RATE_LIMIT_PERIOD_PREF_DEFAULT}\n当前值：%s"
            entries = RATE_LIMIT_PERIOD_PREF_ENTRIES
            entryValues = RATE_LIMIT_PERIOD_PREF_ENTRIES

            setDefaultValue(RATE_LIMIT_PERIOD_PREF_DEFAULT)
        }.also(screen::addPreference)
    }

    private inner class MyWebViewClient(private val chapterUrl: String) : WebViewClient() {
        override fun onLoadResource(view: WebView, url: String) {
            if (url == "$baseUrl/counting") { // the time when document.body is ready
                val script = webViewScript.replace("__interface__", interfaceName)
                view.evaluateJavascript(script, null)
            }
            super.onLoadResource(view, url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            request.url.host?.run {
                if (!endsWith(baseUrlTopPrivateDomain)) {
                    return true // prevent redirects to external sites, some ad scheme is intent and break the webview
                }
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        private val emptyResourceResponse = WebResourceResponse(null, null, 204, "No Content", null, null)
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            request.url.host?.run {
                if (!endsWith(baseUrlTopPrivateDomain)) {
                    return emptyResourceResponse // prevent loading resources from external sites, all of them are ads
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            chapterCache.remove(chapterUrl)
            return super.onRenderProcessGone(view, detail)
        }
    }

    private fun randomString() = buildString(15) {
        val charPool = ('a'..'z') + ('A'..'Z')

        for (i in 0 until 15) {
            append(charPool.random())
        }
    }
}

const val LOG_TAG = "ColaManga"
const val PREFIX_SLUG_SEARCH = "slug:"
val HANDLER = Handler(Looper.getMainLooper())

private const val RATE_LIMIT_PREF_KEY = "mainSiteRatePermitsPreference"
private const val RATE_LIMIT_PREF_DEFAULT = "1"
private val RATE_LIMIT_PREF_ENTRIES = (1..10).map { i -> i.toString() }.toTypedArray()

private const val RATE_LIMIT_PERIOD_PREF_KEY = "mainSiteRatePeriodMillisPreference"
private const val RATE_LIMIT_PERIOD_PREF_DEFAULT = "2500"
private val RATE_LIMIT_PERIOD_PREF_ENTRIES = (2000..6000 step 500).map { i -> i.toString() }.toTypedArray()
