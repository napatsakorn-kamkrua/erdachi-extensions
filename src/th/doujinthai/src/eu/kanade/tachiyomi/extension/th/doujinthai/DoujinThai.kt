package eu.kanade.tachiyomi.extension.th.doujinthai

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PAGE_SIZE = 24
private const val POPULAR_TAG = 3
private const val SEARCH_BASE = "https://html.duckduckgo.com/html/"
private val ZONE = ZoneId.of("Asia/Bangkok")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Source
abstract class DoujinThai : KeiSource() {

    // ========================= Browse =========================

    // The site is a plain SMF forum rendered as a gallery: board pages are
    // offset-paginated (?board=1.<start>) and the pager buttons expose the
    // offsets, so a next page exists when any link points past the current one.
    override suspend fun getLatestUpdates(page: Int): MangasPage = getListingPage(page, popular = false)

    override suspend fun getPopularManga(page: Int): MangasPage = getListingPage(page, popular = true)

    private suspend fun getListingPage(page: Int, popular: Boolean): MangasPage {
        val start = (page - 1) * PAGE_SIZE
        val tag = if (popular) "&tag=$POPULAR_TAG" else ""
        val document = client.get("$baseUrl/forum/index.php?board=1.$start$tag").asJsoup()
        return parseMangasPage(document, hasNextPage = document.hasNextStart(start))
    }

    private fun Document.hasNextStart(currentStart: Int): Boolean = select("a[href]").any { anchor ->
        val start = boardStartRegex.find(anchor.attr("abs:href"))?.groupValues?.get(1)?.toIntOrNull()
        start != null && start > currentStart
    }

    private fun parseMangasPage(document: Document, hasNextPage: Boolean): MangasPage {
        val mangas = document.select("#messageindex div.pic_topic").mapNotNull { card ->
            val link = card.selectFirst(".pic_name a[href]") ?: return@mapNotNull null
            val topicId = topicIdRegex.find(link.absUrl("href"))?.groupValues?.get(1) ?: return@mapNotNull null

            SManga.create().apply {
                this.url = "/t$topicId"
                title = link.text().trim()
                thumbnail_url = card.selectFirst(".pic_img img")?.attr("abs:src")
            }
        }

        return MangasPage(mangas, hasNextPage)
    }

    // ========================= Search =========================

    // The site's own search is a JavaScript-only Google CSE widget (the SMF
    // search requires login), so search runs through DuckDuckGo's HTML endpoint
    // and keeps results that point at forum topics (1:1 with /t<id> pages).
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val searchUrl = SEARCH_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("q", "site:doujin-thai.com ${query.trim()}")
            .addQueryParameter("s", (10 * (page - 1)).toString())
            .build()

        val document = client.get(searchUrl).asJsoup()
        val mangas = document.select("a.result__a")
            .mapNotNull { element ->
                val link = element.absUrl("href").let { href ->
                    if (href.contains("/l/?")) href.toHttpUrlOrNull()?.queryParameter("uddg") else href
                } ?: return@mapNotNull null

                topicIdRegex.find(link)?.groupValues?.get(1)?.let { topicId ->
                    SManga.create().apply {
                        this.url = "/t$topicId"
                        title = element.text().substringBefore(" - [").substringBefore(" | [").trim()
                    }
                }
            }
            .distinctBy { it.url }

        val hasNextPage = mangas.isNotEmpty() && document.selectFirst("input[name=s]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        val topicId = when {
            path.startsWith("/t") && path.drop(2).toIntOrNull() != null -> path.drop(2)
            else -> url.queryParameter("topic")
                ?.substringBefore(".")
                ?.takeIf { it.toIntOrNull() != null }
        } ?: return null

        val manga = SManga.create().apply { this.url = "/t$topicId" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { this.url = manga.url }
    }

    // ========================= Details =========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(parseMangaDetails(document), parseChapterList(document))
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        thumbnail_url = pageImageUrls(document).firstOrNull()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // ========================= Chapters =========================

    private fun parseChapterList(document: Document): List<SChapter> {
        // The upload date only exists in the image CDN path (/image/2026/2026-09-01/...).
        val dateUpload = pageImageUrls(document).firstOrNull()
            ?.let { datePathRegex.find(it)?.groupValues?.get(1) }
            ?.let { DATE_FORMAT.tryParseDate(it, ZONE) }
            ?: 0L

        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(document.location())
                name = "Gallery"
                chapter_number = 1f
                date_upload = dateUpload
            },
        )
    }

    // ========================= Pages =========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val pages = pageImageUrls(document).mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        if (pages.isEmpty()) throw Exception("No pages found")
        return pages
    }

    // Only the dated CDN paths are actual manga pages; the sidebar thumbnails
    // use img-rounded, and non-dated images are site banners.
    private fun pageImageUrls(document: Document): List<String> = document.select("img.img-responsive")
        .map { it.attr("abs:src") }
        .filter { pageImageRegex.containsMatchIn(it) }
}

private val topicIdRegex = Regex("""topic=(\d+)""")
private val boardStartRegex = Regex("""board=1\.(\d+)""")
private val pageImageRegex = Regex("""/image/\d{4}/\d{4}-\d{2}-\d{2}/""")
private val datePathRegex = Regex("""/image/\d{4}/(\d{4}-\d{2}-\d{2})/""")
