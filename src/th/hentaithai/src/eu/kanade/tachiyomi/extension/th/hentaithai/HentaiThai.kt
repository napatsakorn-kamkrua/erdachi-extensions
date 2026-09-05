package eu.kanade.tachiyomi.extension.th.hentaithai

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

private const val POPULAR_TAG = "tag-3"
private const val CLAMP_PAGE = 999999
private const val SEARCH_BASE = "https://html.duckduckgo.com/html/"
private val ZONE = ZoneId.of("Asia/Bangkok")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Source
abstract class HentaiThai : KeiSource() {

    // ========================= Browse =========================

    // Listing pages count from the oldest (page-1), so the newest page number
    // changes as content is added. Requesting an out-of-range page clamps to
    // the newest page, whose pager select reveals the current max page number.
    private var latestMaxPage = 0
    private var popularMaxPage = 0

    override suspend fun getLatestUpdates(page: Int): MangasPage = getBrowsingPage(page, path = "page", maxPage = { latestMaxPage }, setMaxPage = { latestMaxPage = it })

    override suspend fun getPopularManga(page: Int): MangasPage = getBrowsingPage(page, path = "$POPULAR_TAG-page", maxPage = { popularMaxPage }, setMaxPage = { popularMaxPage = it })

    private suspend fun getBrowsingPage(
        page: Int,
        path: String,
        maxPage: () -> Int,
        setMaxPage: (Int) -> Unit,
    ): MangasPage {
        if (page == 1 || maxPage() == 0) {
            val document = client.get("$baseUrl/$path-$CLAMP_PAGE").asJsoup()
            setMaxPage(document.currentMaxPage())
            if (page == 1) {
                return parseMangasPage(document, hasNextPage = maxPage() > 1)
            }
        }

        val sitePage = maxPage() - (page - 1)
        if (sitePage < 1) return MangasPage(emptyList(), false)

        val document = client.get("$baseUrl/$path-$sitePage").asJsoup()
        return parseMangasPage(document, hasNextPage = sitePage > 1)
    }

    private fun Document.currentMaxPage(): Int = select("option[selected]")
        .firstOrNull { it.attr("value").contains("page-", ignoreCase = true) }
        ?.text()?.toIntOrNull() ?: 1

    private fun parseMangasPage(document: Document, hasNextPage: Boolean): MangasPage {
        val thumbnails = document.select("style")
            .flatMap { thumbnailStyleRegex.findAll(it.data()) }
            .associate { it.groupValues[1].toInt() to it.groupValues[2] }

        val mangas = document.select("a[href]:has(div.doujin_post)")
            .mapNotNull { anchor ->
                val card = anchor.selectFirst("div.doujin_post") ?: return@mapNotNull null
                if (card.className().contains("post_rand")) return@mapNotNull null

                SManga.create().apply {
                    setUrlWithoutDomain(anchor.absUrl("href"))
                    title = card.selectFirst("h3")?.text()?.trim()
                        ?: anchor.attr("title").substringBefore(" - [").trim()
                    thumbnail_url = card.className()
                        .let { postIndexRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
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

        val url = SEARCH_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("q", "site:hentaithai.net ${query.trim()}")
            .addQueryParameter("s", (10 * (page - 1)).toString())
            .build()

        val document = client.get(url).asJsoup()
        val mangas = document.select("a.result__a")
            .mapNotNull { element ->
                val link = element.absUrl("href").let { href ->
                    if (href.contains("/l/?")) href.toHttpUrlOrNull()?.queryParameter("uddg") else href
                } ?: return@mapNotNull null

                topicIdRegex.find(link)?.groupValues?.get(1)?.let { topicId ->
                    SManga.create().apply {
                        url = "/t$topicId"
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
        val fullTitle = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val parts = fullTitle.split(" | ", limit = 2)

        title = parts[0].trim().ifBlank { fullTitle }

        // h1 format: "<Thai title> | [<circle/artist>] <original title>"
        val original = parts.getOrNull(1)?.trim()
        if (!original.isNullOrBlank()) {
            author = original.substringAfter("[", "").substringBefore("]", "").ifBlank { null }
            description = original
        }

        genre = document.select("a.badge[href]")
            .filter { it.absUrl("href").toHttpUrlOrNull()?.encodedPath?.startsWith("/tag-") == true }
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

    // Only the dated CDN paths are actual manga pages; other img-fluid images
    // are site banners (e.g. /image/other/, /image/sticker/).
    private fun pageImageUrls(document: Document): List<String> = document.select("img.img-fluid")
        .map { it.attr("abs:src") }
        .filter { pageImageRegex.containsMatchIn(it) }
}

private val thumbnailStyleRegex = Regex("""\.post_(\d+)\s*\{[^}]*?url\('([^']+)'\)""")
private val postIndexRegex = Regex("""post_(\d+)""")
private val topicIdRegex = Regex("""topic=(\d+)""")
private val pageImageRegex = Regex("""/image/\d{4}/\d{4}-\d{2}-\d{2}/""")
private val datePathRegex = Regex("""/image/\d{4}/(\d{4}-\d{2}-\d{2})/""")
