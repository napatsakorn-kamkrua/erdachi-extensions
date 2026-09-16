package eu.kanade.tachiyomi.extension.th.mangathaiplus

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Source
abstract class MangaThaiPlus : KeiSource() {

    private var cachedDocs: List<SearchDoc>? = null

    // ========================= Popular / Latest =========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val path = if (page > 1) "series/page/$page/" else "series/"
        val document = client.get("$baseUrl/$path").asJsoup()
        val mangas = document.select("article.card").mapNotNull { parseCard(it) }
        val hasNextPage = document.select("nav.pagination a")
            .any { it.text().contains("ถัดไป") || it.attr("href").contains("/page/${page + 1}/") }
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get("$baseUrl/updates/").asJsoup()
        val mangas = document.select("article.card").mapNotNull { parseCard(it) }
        return MangasPage(mangas, false)
    }

    private fun parseCard(card: Element): SManga? {
        val link = card.selectFirst("a.card-cover") ?: card.selectFirst("a") ?: return null
        val title = card.selectFirst(".card-title a")?.text()?.trim()
            ?: link.attr("aria-label").trim().ifEmpty { link.text().trim() }
        val thumb = card.selectFirst("picture.cover-pic img, img")?.let {
            it.attr("abs:src").ifBlank { it.attr("src") }
        }

        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            this.title = title
            thumbnail_url = thumb
        }
    }

    // ========================= Search =========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selected.orEmpty()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected.orEmpty()
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected.orEmpty()

        if (query.isBlank() && typeFilter.isEmpty() && statusFilter.isEmpty() && genreFilter.isEmpty()) {
            return getPopularManga(page)
        }

        if (cachedDocs == null) {
            val response = client.get("$baseUrl/search-index.json").parseAs<SearchIndexDto>()
            cachedDocs = response.docs
        }
        val docs = cachedDocs ?: emptyList()

        val trimmed = query.trim().lowercase()
        val filtered = docs.filter { doc ->
            val matchesQuery = if (trimmed.isNotEmpty()) {
                doc.title.lowercase().contains(trimmed) ||
                    doc.altTitles.any { it.lowercase().contains(trimmed) } ||
                    doc.synonyms.any { it.lowercase().contains(trimmed) } ||
                    doc.slug.contains(trimmed)
            } else {
                true
            }

            val matchesType = if (typeFilter.isNotEmpty()) {
                doc.type.equals(typeFilter, ignoreCase = true)
            } else {
                true
            }

            val matchesStatus = if (statusFilter.isNotEmpty()) {
                doc.status.equals(statusFilter, ignoreCase = true)
            } else {
                true
            }

            val matchesGenre = if (genreFilter.isNotEmpty()) {
                doc.genres.any { it.equals(genreFilter, ignoreCase = true) }
            } else {
                true
            }

            matchesQuery && matchesType && matchesStatus && matchesGenre
        }

        val limit = 30
        val offset = (page - 1) * limit
        val pageItems = filtered.drop(offset).take(limit)
        val hasNextPage = offset + limit < filtered.size

        val mangas = pageItems.map { doc ->
            SManga.create().apply {
                url = "/series/${doc.slug}/"
                title = doc.title
                thumbnail_url = doc.cover?.let { "https://img.mangathaiplus.com/$it-480.jpg" }
                status = parseStatus(doc.status)
            }
        }

        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    // ========================= Details =========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        val sManga = if (fetchDetails) {
            val h1 = document.selectFirst("h1")?.text().orEmpty()
            val parsedTitle = h1.substringBefore("(").trim()
            val altTitle = if (h1.contains("(") && h1.contains(")")) {
                h1.substringAfter("(").substringBeforeLast(")").trim()
            } else null

            val byline = document.selectFirst("p.byline")?.text().orEmpty()
            val authorName = if (byline.contains("ผู้แต่ง")) {
                byline.substringAfter("ผู้แต่ง").substringBefore("นักวาด").trim()
            } else null
            val artistName = if (byline.contains("นักวาด")) {
                byline.substringAfter("นักวาด").trim()
            } else null

            val meta = document.selectFirst("p.meta")?.text().orEmpty()
            val mangaStatus = when {
                meta.contains("จบแล้ว") -> SManga.COMPLETED
                meta.contains("รอตอนถัดไป") || meta.contains("กำลังอัปเดต") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            val genres = document.select("ul.genre-chips a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .joinToString()

            val synopsis = document.selectFirst("p.synopsis")?.text()?.trim()
            val fullDescription = buildString {
                synopsis?.let { append(it) }
                if (!altTitle.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("ชื่อทางเลือก: ").append(altTitle)
                }
            }.trim().ifEmpty { null }

            val thumb = document.selectFirst("picture.cover-pic img, .hero-cover img, .series-hero img, main img")?.let {
                it.attr("abs:src").ifBlank { it.attr("src") }
            }

            SManga.create().apply {
                url = manga.url
                title = parsedTitle.ifEmpty { manga.title }
                author = authorName
                artist = artistName
                status = mangaStatus
                genre = genres.ifEmpty { null }
                description = fullDescription
                thumbnail_url = thumb ?: manga.thumbnail_url
            }
        } else {
            manga
        }

        val sChapters = if (fetchChapters) {
            document.select("a[href*=\"/chapter-\"]")
                .filter { it.selectFirst("time") != null }
                .mapNotNull { element ->
                    val href = element.attr("href").ifBlank { return@mapNotNull null }
                    val label = element.selectFirst(".ch-label")?.text()?.trim()
                        ?: element.text().trim()
                    val timeElem = element.selectFirst("time")
                    val dateStr = timeElem?.attr("datetime")
                    val date = if (!dateStr.isNullOrBlank()) {
                        Instant.tryParse(dateStr)
                    } else 0L

                    SChapter.create().apply {
                        setUrlWithoutDomain(element.absUrl("href"))
                        name = label
                        date_upload = date
                    }
                }
                .distinctBy { it.url }
        } else {
            chapters
        }

        return SMangaUpdate(sManga, sChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.size >= 2 && segments[0] == "series" && segments[1].isNotBlank()) {
            val slug = segments[1]
            val manga = SManga.create().apply { this.url = "/series/$slug/" }
            return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
                .manga
                .apply { this.url = manga.url }
        }
        return null
    }

    // ========================= Pages =========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        val pageUrls = mutableListOf<String>()

        // 1. Static images in HTML (first few pages)
        document.select("main picture img, main img").forEach { img ->
            val src = img.attr("abs:src").ifBlank { img.attr("src") }
            if (src.contains("img.mangathaiplus.com") && !pageUrls.contains(src)) {
                pageUrls.add(src)
            }
        }

        // 2. Remaining pages in JSON inside <script> tags
        for (script in document.select("script")) {
            val text = script.data().trim()
            if (text.contains("\"src\"") && (text.contains("\"webp\"") || text.startsWith("[{"))) {
                val jsonArray = runCatching { text.parseAs<List<PageDto>>() }.getOrNull()
                if (jsonArray != null) {
                    for (item in jsonArray) {
                        val src = item.src ?: item.webp?.substringBefore(" ")
                        if (!src.isNullOrBlank() && !pageUrls.contains(src)) {
                            pageUrls.add(src)
                        }
                    }
                } else {
                    srcRegex.findAll(text).forEach { match ->
                        val src = match.groupValues[1]
                        if (!pageUrls.contains(src)) {
                            pageUrls.add(src)
                        }
                    }
                }
            }
        }

        return pageUrls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    companion object {
        private val srcRegex = """\"src\"\s*:\s*\"([^\"]+)\"""".toRegex()
    }
}
