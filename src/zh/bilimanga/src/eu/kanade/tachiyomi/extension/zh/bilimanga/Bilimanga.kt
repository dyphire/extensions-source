package eu.kanade.tachiyomi.extension.zh.bilimanga

import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class Bilimanga : HttpSource() {
    override val name = "Bilimanga"
    override val baseUrl = "https://www.bilimanga.net"
    override val lang = "zh"
    override val supportsLatest = true

    private val userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(RateLimitInterceptor(4))
        .addNetworkInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Origin", baseUrl)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Cookie", "night=0")
                .build()
            chain.proceed(request)
        }
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/top/monthvisit/1.html".toHttpUrl().newBuilder()
        return GET(url.build(), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".book-li > a").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.selectFirst(".book-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".book-cover img")?.attr("abs:data-src")
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/top/lastupdate/1.html".toHttpUrl().newBuilder()
        return GET(url.build(), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // =============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/search/$encodedQuery/$page.html".toHttpUrl().newBuilder()
            GET(url.build(), headers)
        } else {
            val params = FilterHandler().getParameters(filters)
            val path = buildString {
                append(params["order"]).append('_')
                append(params["tagid"]).append('_')
                append(params["isfull"]).append('_')
                append(params["anime"]).append('_')
                append(params["rgroupid"]).append('_')
                append(params["sortid"]).append('_')
                append(params["update"]).append('_')
                append(params["quality"]).append('_')
                append(page).append("_0.html")
            }

            val url = "$baseUrl/filter/$path".toHttpUrl().newBuilder()
            GET(url.build(), headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val alternateUrl = document.selectFirst("link[rel='alternate']")?.attr("href")
        if (alternateUrl?.contains("detail") == true) {
            val manga = SManga.create().apply {
                url = alternateUrl
                title = document.selectFirst("h1.book-title")?.text() ?: ""
                thumbnail_url = document.selectFirst(".book-cover img")?.attr("abs:src")
            }
            return MangasPage(listOf(manga), false)
        }

        val mangas = document.select(".book-li > a").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.selectFirst(".book-title")?.text() ?: ""
                thumbnail_url = element.selectFirst(".book-cover img")?.attr("abs:data-src")
            }
        }

        val hasNextPage = if (response.request.url.pathSegments.contains("search")) {
            document.select(".next").attr("href") != "#"
        } else {
            document.select("#pagelink strong").text() !=
                document.select("#pagelink .last").text()
        }

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Filters ===============================
    private class FilterHandler {
        private val filters = listOf(
            Filter.Select(
                "作品主题",
                TAGS,
            ),
            Filter.Select(
                "作品分类",
                CATEGORIES,
            ),
            Filter.Select(
                "文库地区",
                REGIONS,
            ),
            Filter.Select(
                "是否动画",
                ANIME_STATUS,
            ),
            Filter.Select(
                "是否轻改",
                ADAPTATION_STATUS,
            ),
            Filter.Select(
                "连载状态",
                PUBLICATION_STATUS,
            ),
            Filter.Select(
                "更新时间",
                UPDATE_TIMES,
            ),
            SortFilter(),
        )

        fun getParameters(filterList: FilterList): Map<String, String> {
            val params = mutableMapOf(
                "order" to "lastupdate",
                "tagid" to "0",
                "isfull" to "0",
                "anime" to "0",
                "rgroupid" to "0",
                "sortid" to "0",
                "update" to "0",
                "quality" to "0",
            )

            filterList.forEach { filter ->
                when (filter) {
                    is Filter.Select -> {
                        when (filter.name) {
                            "作品主题" -> params["tagid"] = TAGS_IDS[filter.state]
                            "作品分类" -> params["sortid"] = CATEGORIES_IDS[filter.state]
                            "文库地区" -> params["rgroupid"] = REGIONS_IDS[filter.state]
                            "是否动画" -> params["anime"] = ANIME_IDS[filter.state]
                            "是否轻改" -> params["quality"] = ADAPTATION_IDS[filter.state]
                            "连载状态" -> params["isfull"] = PUBLICATION_IDS[filter.state]
                            "更新时间" -> params["update"] = UPDATE_IDS[filter.state]
                        }
                    }
                    is SortFilter -> {
                        params["order"] = SORT_IDS[filter.state?.index ?: 0]
                    }
                }
            }
            return params
        }

        fun getFilters() = FilterList(filters)
    }

    private class SortFilter : Filter.Sort(
        "排序",
        SORT_OPTIONS,
        Selection(0, false),
    )

    companion object {
        private val TAGS = arrayOf(
            "全部",
            "冒险",
            "欢乐向",
            "格斗",
            "悬疑",
            "竞技",
            "爱情",
            "科幻",
            "魔法",
            "恐怖",
            "校园",
            "生活",
            "百合",
            "伪娘",
            "耽美",
            "后宫",
            "萌系",
            "治愈",
            "职场",
            "奇幻",
            "节操",
            "轻小说",
            "武侠",
            "仙侠",
            "性转",
            "宅系",
            "网游",
            "血腥",
            "料理",
            "其他",
        )

        private val TAGS_IDS = arrayOf(
            "0",
            "1",
            "2",
            "3",
            "4",
            "5",
            "6",
            "7",
            "8",
            "9",
            "10",
            "11",
            "12",
            "13",
            "14",
            "15",
            "16",
            "17",
            "18",
            "19",
            "20",
            "21",
            "22",
            "23",
            "24",
            "25",
            "26",
            "27",
            "28",
            "29",
        )

        private val CATEGORIES = arrayOf(
            "全部",
            "日漫",
            "港漫",
            "韩漫",
            "美漫",
            "其他",
        )

        private val CATEGORIES_IDS = arrayOf(
            "0",
            "1",
            "2",
            "3",
            "4",
            "5",
        )

        private val REGIONS = arrayOf(
            "全部",
            "日本",
            "香港",
            "韩国",
            "美国",
            "其他",
        )

        private val REGIONS_IDS = arrayOf(
            "0",
            "1",
            "2",
            "3",
            "4",
            "5",
        )

        private val ANIME_STATUS = arrayOf(
            "全部",
            "已动画化",
            "未动画化",
        )

        private val ANIME_IDS = arrayOf(
            "0",
            "1",
            "2",
        )

        private val ADAPTATION_STATUS = arrayOf(
            "全部",
            "轻小说改编",
            "原创",
        )

        private val ADAPTATION_IDS = arrayOf(
            "0",
            "1",
            "2",
        )

        private val PUBLICATION_STATUS = arrayOf(
            "全部",
            "连载中",
            "已完结",
        )

        private val PUBLICATION_IDS = arrayOf(
            "0",
            "1",
            "2",
        )

        private val UPDATE_TIMES = arrayOf(
            "全部",
            "三天内",
            "七天内",
            "半月内",
            "一月内",
        )

        private val UPDATE_IDS = arrayOf(
            "0",
            "1",
            "2",
            "3",
            "4",
        )

        private val SORT_OPTIONS = arrayOf(
            "最近更新",
            "收藏榜",
            "字数排行",
            "新书榜",
            "周点击",
            "月点击",
            "周推荐",
            "月推荐",
            "周鲜花",
            "月鲜花",
            "周鸡蛋",
            "月鸡蛋",
        )

        private val SORT_IDS = arrayOf(
            "lastupdate",
            "goodnum",
            "words",
            "newhot",
            "weekvisit",
            "monthvisit",
            "weekvote",
            "monthvote",
            "weekflower",
            "monthflower",
            "weekegg",
            "monthegg",
        )
    }

    override fun getFilterList() = FilterHandler().getFilters()

    // =========================== Manga Details ============================
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1.book-title")?.text() ?: ""
            thumbnail_url = document.selectFirst(".book-cover img")?.attr("abs:src")
            author = document.select(".authorname, .illname").joinToString { it.text() }
            description = document.selectFirst(".book-summary content")?.text()
            genre = document.select(".tag-small-group .tag-small").joinToString { it.text() }
            status = when (document.select(".book-layout-inline").text().trim().split("|")[0]) {
                "連載" -> SManga.ONGOING
                "完結" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".catalog-volume .chapter-li-a").mapIndexed { index, element ->
            SChapter.create().apply {
                name = element.selectFirst("span")?.text() ?: ""
                setUrlWithoutDomain(element.attr("href"))
                chapter_number = (index + 1).toFloat()
                date_upload = parseDate(element.selectFirst(".chapter-date")?.text())
            }
        }.reversed()
    }

    private fun parseDate(dateString: String?): Long {
        dateString ?: return 0
        val format = when {
            dateString.contains("秒") || dateString.contains("分钟") -> return System.currentTimeMillis()
            dateString.contains("小时") -> return System.currentTimeMillis() - 3600000
            dateString.contains("昨天") -> "昨天 HH:mm"
            dateString.contains("-") -> "yyyy-MM-dd"
            else -> "MM月dd日"
        }
        return runCatching {
            SimpleDateFormat(format, Locale.getDefault()).parse(dateString)?.time
        }.getOrNull() ?: 0
    }

    // =============================== Pages ================================
    override fun pageListParse(document: Document): List<Page> {
        return document.select("#acontentz > img").mapIndexed { index, element ->
            Page(index, "", element.attr("abs:data-src").trim())
        }
    }

    override fun imageUrlParse(document: Document): String = ""
}
