package eu.kanade.tachiyomi.extension.th.doujinth

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
private const val POPULAR_TAG_ID = 3
private const val SEARCH_BASE = "https://html.duckduckgo.com/html/"
private val ZONE = ZoneId.of("Asia/Bangkok")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Source
abstract class DoujinTH : KeiSource() {

    // ========================= Browse =========================

    // Both listings are the forum backend rendered in the gallery skin, paginated
    // by offset. The pager select lists every page, so a next page exists when any
    // option's start offset is beyond the current one.
    override suspend fun getLatestUpdates(page: Int): MangasPage = getListingPage(page, { "$baseUrl/forum/index.php/board,1.${it * PAGE_SIZE}.html" }, boardStartRegex)

    override suspend fun getPopularManga(page: Int): MangasPage = getListingPage(page, { "$baseUrl/forum/index.php?action=tags&tagid=$POPULAR_TAG_ID&start=${it * PAGE_SIZE}" }, tagStartRegex)

    private suspend fun getListingPage(page: Int, url: (Int) -> String, startRegex: Regex): MangasPage {
        val start = page - 1
        val document = client.get(url(start)).asJsoup()
        return parseMangasPage(document, hasNextPage = document.hasNextStart(start, startRegex))
    }

    private fun Document.hasNextStart(currentStart: Int, startRegex: Regex): Boolean = select("option").any { option ->
        val start = startRegex.find(option.attr("value"))?.groupValues?.get(1)?.toIntOrNull()
        start != null && start > currentStart
    }

    private fun parseMangasPage(document: Document, hasNextPage: Boolean): MangasPage {
        val thumbnails = document.select("style")
            .flatMap { thumbnailStyleRegex.findAll(it.data()) }
            .associate { it.groupValues[1].toInt() to it.groupValues[2] }

        val mangas = document.select("a:has(div.topic_new_name)")
            .mapNotNull { anchor ->
                val topicId = topicIdRegex.find(anchor.absUrl("href"))?.groupValues?.get(1)
                    ?: return@mapNotNull null

                SManga.create().apply {
                    this.url = "/t$topicId"
                    title = anchor.selectFirst(".well")?.text()?.trim()
                        ?: anchor.attr("title").substringBefore(" - [").trim()
                    thumbnail_url = anchor.selectFirst("div[id^=post_doujin]")
                        ?.let { postIndexRegex.find(it.id())?.groupValues?.get(1)?.toIntOrNull() }
                        ?.let { thumbnails[it] }
                }
            }

        return MangasPage(mangas, hasNextPage)
    }

    // ========================= Search =========================

    // The site only offers a JavaScript-only Google CSE widget, so search runs
    // through DuckDuckGo's HTML endpoint and keeps results that point at forum
    // topics (which map 1:1 to the /t<id> gallery pages).
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val searchUrl = SEARCH_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("q", "site:doujin-th.com ${query.trim()}")
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
        val topicId = when {
            url.encodedPath.startsWith("/t") && url.encodedPath.drop(2).toIntOrNull() != null -> url.encodedPath.drop(2)
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

        // <title> holds the full "Thai title - [Circle] Original title" string
        fullTitleRegex.find(document.title())?.let { match ->
            author = match.groupValues[2].ifBlank { null }
            description = match.groupValues[3].ifBlank { null }
        }

        genre = document.select("a.tag")
            .joinToString(", ") { it.text() }
            .ifBlank { null }

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

    // Only the dated CDN paths are actual manga pages; other img-responsive images
    // are site banners (e.g. /image/other/).
    private fun pageImageUrls(document: Document): List<String> = document.select("img.img-responsive")
        .map { it.attr("abs:src") }
        .filter { pageImageRegex.containsMatchIn(it) }
}

private val thumbnailStyleRegex = Regex("""#post_doujin_(\d+)\s*\{[^}]*?url\('([^']+)'\)""")
private val postIndexRegex = Regex("""post_doujin_(\d+)""")
private val topicIdRegex = Regex("""topic=(\d+)""")
private val boardStartRegex = Regex("""board,1\.(\d+)\.html""")
private val tagStartRegex = Regex("""start=(\d+)""")
private val fullTitleRegex = Regex("""^(.*?) - \[(.*?)] (.*)$""")
private val pageImageRegex = Regex("""/image/\d{4}/\d{4}-\d{2}-\d{2}/""")
private val datePathRegex = Regex("""/image/\d{4}/(\d{4}-\d{2}-\d{2})/""")
