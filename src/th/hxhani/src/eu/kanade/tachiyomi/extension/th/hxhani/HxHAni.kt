package eu.kanade.tachiyomi.extension.th.hxhani

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import kotlin.time.Instant

private const val TITLE_SUFFIX = " Doujin โดจิน แปลไทย"
private val rankPrefixRegex = Regex("""^อันดับที่ \d+:\s*""")

@Source
abstract class HxHAni : KeiSource() {

    // ========================= Browse =========================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/doujin-thai" else "$baseUrl/doujin-thai?pages=$page"
        val document = client.get(url).asJsoup()
        return parseMangasPage(document, hasNextPage = document.hasNextPages(page))
    }

    // Curated top-10 page: horizontal cards ("อันดับที่ N: <title>"); a single
    // unpaginated listing.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/top-10-doujin").asJsoup()
        val mangas = document.select("div.card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=doujin-]") ?: return@mapNotNull null
            val title = card.selectFirst("h5.card-title")?.text()
                ?.replace(rankPrefixRegex, "")
                ?.trim()
                ?: return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                this.title = title
                thumbnail_url = card.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("abs:src") } }
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    private fun Document.hasNextPages(page: Int): Boolean = select("a[href]").any { anchor ->
        anchor.absUrl("href").toHttpUrlOrNull()?.queryParameter("pages")?.toIntOrNull() == page + 1
    }

    private fun parseMangasPage(document: Document, hasNextPage: Boolean): MangasPage {
        val mangas = document.select("a.hentaisubthai").mapNotNull { anchor ->
            if (anchor.selectFirst(".item-title") == null) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(anchor.absUrl("href"))
                title = anchor.selectFirst(".item-title")?.text()?.trim()
                    ?: anchor.attr("title").trim()
                thumbnail_url = anchor.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("abs:src") } }
            }
        }

        return MangasPage(mangas, hasNextPage)
    }

    // ========================= Search =========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val searchUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("doujin-thai")
            .addQueryParameter("doujin", query.trim())
            .apply { if (page > 1) addQueryParameter("pages", page.toString()) }
            .build()

        val document = client.get(searchUrl).asJsoup()
        return parseMangasPage(document, hasNextPage = document.hasNextPages(page))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.startsWith("/doujin-") || path == "/doujin-thai") return null

        val manga = SManga.create().apply { this.url = path }
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
        val meta = document.selectFirst("script[type=application/ld+json]")?.data()?.parseAs<DoujinMeta>()
        return SMangaUpdate(parseMangaDetails(document, meta), parseChapterList(document, meta))
    }

    private fun parseMangaDetails(document: Document, meta: DoujinMeta?): SManga = SManga.create().apply {
        // <title> format: "<Thai title> | <Original title> Doujin โดจิน แปลไทย"
        val fullTitle = document.title()
        title = fullTitle.substringBefore(" | ").trim()

        val original = fullTitle.substringAfter(" | ", "").removeSuffix(TITLE_SUFFIX).trim()
        if (original.isNotEmpty()) description = original

        author = meta?.author?.name
        thumbnail_url = pageImageUrls(document).firstOrNull()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // ========================= Chapters =========================

    private fun parseChapterList(document: Document, meta: DoujinMeta?): List<SChapter> = listOf(
        SChapter.create().apply {
            setUrlWithoutDomain(document.location())
            name = "Gallery"
            chapter_number = 1f
            date_upload = Instant.tryParse(meta?.datePublished)
        },
    )

    // ========================= Pages =========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val pages = pageImageUrls(document).mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        if (pages.isEmpty()) throw Exception("No pages found")
        return pages
    }

    // All pages are present in the initial HTML: the first one in src, the rest
    // lazy-loaded via data-src.
    private fun pageImageUrls(document: Document): List<String> = document.select("img.doujinz")
        .map { it.attr("data-src").ifBlank { it.attr("abs:src") } }
}

@Serializable
private class DoujinMeta(
    val author: DoujinAuthor? = null,
    val datePublished: String? = null,
)

@Serializable
private class DoujinAuthor(
    val name: String? = null,
)
