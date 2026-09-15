package eu.kanade.tachiyomi.extension.th.afterway

import android.util.Base64
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Instant

@Source
abstract class Afterway : KeiSource() {

    // ========================= Popular / Latest =========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val limit = 20
        val skip = (page - 1) * limit
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/series")
            .addQueryParameter("type", "manga")
            .addQueryParameter("sort", "views")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("skip", skip.toString())
            .build()

        val response = client.get(url).parseAs<SeriesListResponse>()
        val mangas = response.data.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, hasNextPage = mangas.size >= limit)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val limit = 20
        val skip = (page - 1) * limit
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/series")
            .addQueryParameter("type", "manga")
            .addQueryParameter("sort", "updatedAt")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("skip", skip.toString())
            .build()

        val response = client.get(url).parseAs<SeriesListResponse>()
        val mangas = response.data.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, hasNextPage = mangas.size >= limit)
    }

    // ========================= Search =========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val limit = 20
        val skip = (page - 1) * limit
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/series")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("skip", skip.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query.trim())
        }

        var type = "manga"
        for (filter in filters) {
            when (filter) {
                is TypeFilter -> type = filter.selected
                is StatusFilter -> if (filter.selected != "all") url.addQueryParameter("status", filter.selected)
                is GenreFilter -> if (filter.selected != "all") url.addQueryParameter("genre", filter.selected)
                is SortFilter -> {
                    url.addQueryParameter("sort", filter.selected)
                    url.addQueryParameter("order", if (filter.state?.ascending == true) "asc" else "desc")
                }
                else -> {}
            }
        }
        if (type != "both") {
            url.addQueryParameter("type", type)
        }

        val response = client.get(url.build()).parseAs<SeriesListResponse>()
        val mangas = response.data.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, hasNextPage = mangas.size >= limit)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
        SortFilter(),
    )

    // ========================= Details =========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val projectId = manga.url.removePrefix("/project/").substringBefore("/")
        val response = client.get("$baseUrl/api/project/$projectId").parseAs<ProjectDetailsResponse>()
        val project = response.data ?: throw Exception("Project not found")

        val sManga = if (fetchDetails) {
            project.toSManga(baseUrl)
        } else {
            manga
        }

        val sChapters = if (fetchChapters) {
            project.chapters.map { it.toSChapter(projectId) }
        } else {
            chapters
        }

        return SMangaUpdate(sManga, sChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.size >= 2 && segments[0] == "project") {
            val projectId = segments[1]
            val manga = SManga.create().apply { this.url = "/project/$projectId" }
            return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
                .manga
                .apply { this.url = manga.url }
        }
        return null
    }

    // ========================= Pages =========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("?")
        val response = client.get("$baseUrl/api/chapters/$chapterId").parseAs<ChapterDetailsResponse>()
        val chapterData = response.data ?: throw Exception("Chapter not found")
        val pages = chapterData.pages ?: emptyList()

        return pages.mapIndexed { index, page ->
            val extension = page.extension ?: "webp"
            val encodedFileName = encodeCdn("${page.id}.$extension")
            val folder = page.folder ?: "pages/${chapterData.projectId ?: ""}/${chapterData.id}"
            Page(index, imageUrl = "$baseUrl/cdn/$folder/$encodedFileName.html")
        }
    }
}

// ========================= CDN Helpers =========================

private fun encodeCdn(path: String): String = Base64.encodeToString(
    path.toByteArray(Charsets.UTF_8),
    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
)

// ========================= DTOs =========================

@Serializable
private class SeriesListResponse(
    val data: List<SeriesItem> = emptyList(),
)

@Serializable
private class SeriesItem(
    val id: String,
    val title: String,
    val coverId: String? = null,
    val coverExt: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = "/project/$id"
        title = this@SeriesItem.title
        coverId?.let { cid ->
            val ext = coverExt ?: "webp"
            val encoded = encodeCdn("$cid.$ext")
            thumbnail_url = "$baseUrl/cdn/covers/$encoded.html"
        }
        author = this@SeriesItem.author
        artist = this@SeriesItem.artist
        description = this@SeriesItem.description
        genre = genres.joinToString()
        status = parseStatus(this@SeriesItem.status)
    }
}

@Serializable
private class ProjectDetailsResponse(
    val data: ProjectDetails? = null,
)

@Serializable
private class ProjectDetails(
    val id: String,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val coverId: String? = null,
    val coverExt: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val chapters: List<ChapterItem> = emptyList(),
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = "/project/$id"
        title = this@ProjectDetails.title
        coverId?.let { cid ->
            val ext = coverExt ?: "webp"
            val encoded = encodeCdn("$cid.$ext")
            thumbnail_url = "$baseUrl/cdn/covers/$encoded.html"
        }
        author = this@ProjectDetails.author
        artist = this@ProjectDetails.artist

        val desc = buildString {
            this@ProjectDetails.description?.let { append(it).append("\n\n") }
            if (alternativeTitles.isNotEmpty()) {
                append("Alternative Titles: ").append(alternativeTitles.joinToString()).append("\n")
            }
        }.trim()
        description = desc.ifEmpty { null }
        genre = genres.joinToString()
        status = parseStatus(this@ProjectDetails.status)
    }
}

@Serializable
private class ChapterItem(
    val id: String,
    val number: Float? = null,
    val title: String? = null,
    val publishedAt: String? = null,
    val createdAt: String? = null,
    val translator: String? = null,
) {
    fun toSChapter(projectId: String): SChapter = SChapter.create().apply {
        url = "/project/$projectId/chapter/$id"
        name = buildString {
            if (this@ChapterItem.number != null) {
                append("Chapter ").append(this@ChapterItem.number.toString().removeSuffix(".0"))
            }
            if (!this@ChapterItem.title.isNullOrBlank()) {
                if (isNotEmpty()) append(" - ")
                append(this@ChapterItem.title)
            }
        }.ifEmpty { "Chapter" }
        chapter_number = this@ChapterItem.number ?: -1f
        date_upload = Instant.tryParse(publishedAt ?: createdAt)
        scanlator = translator
    }
}

@Serializable
private class ChapterDetailsResponse(
    val data: ChapterDetails? = null,
)

@Serializable
private class ChapterDetails(
    val id: String,
    val projectId: String? = null,
    val pages: List<PageItem>? = null,
)

@Serializable
private class PageItem(
    val id: String,
    val pageNumber: Int? = null,
    val extension: String? = null,
    val folder: String? = null,
)

private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

// ========================= Filters =========================

private class TypeFilter :
    Filter.Select<String>(
        "Type",
        arrayOf("Manga", "Novel", "Both"),
    ) {
    val selected: String
        get() = when (state) {
            0 -> "manga"
            1 -> "novel"
            else -> "both"
        }
}

private class StatusFilter :
    Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed", "Hiatus", "Cancelled"),
    ) {
    val selected: String
        get() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            4 -> "cancelled"
            else -> "all"
        }
}

private class GenreFilter :
    Filter.Select<String>(
        "Genre",
        arrayOf(
            "All",
            "Action",
            "Adventure",
            "Comedy",
            "Drama",
            "Fantasy",
            "Harem",
            "Horror",
            "Isekai",
            "Romance",
            "School",
            "Sci-Fi",
            "Sports",
            "Superhero",
        ),
    ) {
    val selected: String
        get() = if (state == 0) "all" else values[state]
}

private class SortFilter :
    Filter.Sort(
        "Sort by",
        arrayOf("Updated at", "Created at", "Views", "Favorites", "Title"),
        Selection(0, false),
    ) {
    val selected: String
        get() = when (state?.index) {
            1 -> "createdAt"
            2 -> "views"
            3 -> "favorites"
            4 -> "title"
            else -> "updatedAt"
        }
}
