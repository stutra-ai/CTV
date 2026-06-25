package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Suppress("DEPRECATION")
class PinayCum(private val context: Context) : MainAPI() {
    override var mainUrl = "https://pinaycum.tv"
    override var name = "PinayCum"
    override val hasMainPage = true
    override var lang = "tl"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Recent",
        "$mainUrl/category/pinay-scandal/" to "Pinay Scandal",
        "$mainUrl/category/amateur/" to "Amateur",
        "$mainUrl/category/alter/" to "Alter"
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2, .title, .video-title")?.text()?.trim() ?: this.text().trim()
        if (title.isBlank()) return null

        val href = fixUrl(this.attr("href"))
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}&p=$page", interceptor = interceptor).document
        val searchResults = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }

        return newSearchResponseList(searchResults, hasNext = true)
    }

    private fun cleanupWebView(wv: WebView) {
        try {
            wv.stopLoading()
            wv.setWebChromeClient(null)
            wv.webViewClient = object : WebViewClient() {}
            wv.removeAllViews()
            wv.clearHistory()
            wv.loadUrl("about:blank")
            wv.destroy()
        } catch (ignored: Throwable) {}
    }

    suspend fun createWebViewAndExtract(
        context: Context,
        baseUrl: String,
        html: String,
        onResult: (String?) -> Unit
    ): WebView = withContext(Dispatchers.Main) {

        val modifiedHtml = html.replace(
            Regex("""jwplayer\s*\(\s*["']player["']\s*\)\s*\.setup\s*\(\s*configs\s*\)\s*;"""),
            """
            window.configs = configs;
            console.log('jwplayer configs set:', JSON.stringify(configs));
            jwplayer("player").setup(configs);
            """.trimIndent()
        )

        val wv = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    extractWithDelay(view, { result ->
                        onResult(result)
                        Handler(Looper.getMainLooper()).post {
                            Log.d("PinayCum_WV", "Webview cleared successfully")
                            cleanupWebView(this@apply)
                        }
                    }, 0)
                }
            }
            loadDataWithBaseURL(baseUrl, modifiedHtml, "text/html", "UTF-8", null)
        }
        return@withContext wv
    }

    private fun extractWithDelay(webView: WebView?, onResult: (String?) -> Unit, attempt: Int) {
        if (webView == null || attempt > 15) {
            onResult(null)
            return
        }

        val extractScript = """
            (function() {
                var result = {};
                if (typeof window.configs !== 'undefined' && window.configs) {
                    result.configs = window.configs;
                    result.type = 'jwplayer_configs';
                    return JSON.stringify(result);
                }
                if (typeof jwplayer !== 'undefined') {
                    try {
                        var instances = jwplayer().getPlaylist();
                        if (instances && instances.length > 0) {
                            result.playlist = instances;
                            result.type = 'jwplayer';
                            return JSON.stringify(result);
                        }
                    } catch(e) {}
                }
                var videos = document.querySelectorAll('video source, video');
                if (videos.length > 0) {
                    var sources = [];
                    videos.forEach(function(v) {
                        if (v.src) sources.push({src: v.src, type: v.type});
                    });
                    if (sources.length > 0) {
                        result.sources = sources;
                        result.type = 'html5';
                        return JSON.stringify(result);
                    }
                }
                return null;
            })();
        """.trimIndent()

        webView.evaluateJavascript(extractScript) { resultJson ->
            val cleanResult = resultJson?.let { raw ->
                if (raw == "null" || raw == "\"null\"") null
                else raw.removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            }

            if (cleanResult.isNullOrEmpty() || cleanResult == "null") {
                if (attempt < 15) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        extractWithDelay(webView, onResult, attempt + 1)
                    }, 200)
                } else {
                    onResult(null)
                }
            } else {
                onResult(cleanResult)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1, title")?.text()?.trim() ?: "PinayCum Video"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    @SuppressLint("SuspiciousIndentation")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val document = app.get(data, interceptor = interceptor).document
        
        // Find iframe targeting video content
        val iframeUrl = fixUrlNull(document.selectFirst("iframe")?.attr("src")) ?: ""
        if (iframeUrl.isEmpty()) return@withContext false

        val iframeText = app.get(iframeUrl, interceptor = interceptor).text

        val configJson = suspendCoroutine<String?> { continuation ->
            runBlocking {
                createWebViewAndExtract(context = context, iframeUrl, iframeText) { result ->
                    continuation.resume(result)
                }
            }
        }

        configJson?.let { configStr ->
            val configObj = JSONObject(configStr)
            if (configObj.has("sources")) {
                val sources = configObj.getJSONArray("sources")
                for (i in 0 until sources.length()) {
                    val sourceObj = sources.getJSONObject(i)
                    val videoUrl = sourceObj.optString("file", "").ifEmpty {
                        sourceObj.optString("src", "")
                    }
                    val quality = sourceObj.optString("src", "").substringAfterLast("/")

                    if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "PinayCum",
                                name = "PinayCum",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = getQualityFromName(quality)
                            }
                        )
                    }
                }
            } else {
                loadExtractor(iframeUrl, subtitleCallback, callback)
            }
        }
        return@withContext true
    }
}