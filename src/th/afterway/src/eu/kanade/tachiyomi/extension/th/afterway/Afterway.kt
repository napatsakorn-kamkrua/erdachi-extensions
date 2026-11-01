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
import keiyoushi.network.Fetch
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
            .addPathSegments("api/project")
            .addQueryParameter("type", "manga")
            .addQueryParameter("sort", "views")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("skip", skip.toString())
            .build()

        val response = client.get(url, Fetch.XHR).parseAs<ProjectListResponse>()
        val mangas = response.data.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, response.pagination?.hasMore ?: (mangas.size >= limit))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val limit = 20
        val skip = (page - 1) * limit
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/project")
            .addQueryParameter("type", "manga")
            .addQueryParameter("sort", "updatedAt")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("skip", skip.toString())
            .build()

        val response = client.get(url, Fetch.XHR).parseAs<ProjectListResponse>()
        val mangas = response.data.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, response.pagination?.hasMore ?: (mangas.size >= limit))
    }

    // ========================= Search =========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val limit = 20
        val skip = (page - 1) * limit
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/project")
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

        val response = client.get(url.build(), Fetch.XHR).parseAs<ProjectListResponse>()
        val mangas = response.data.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, response.pagination?.hasMore ?: (mangas.size >= limit))
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
        val project = if (fetchDetails) {
            client.get("$baseUrl/api/project/$projectId", Fetch.XHR).parseAs<ProjectResponse>().data
                ?: throw Exception("Project not found")
        } else {
            null
        }
        val chapterList = if (fetchChapters) fetchChapters(projectId) else chapters
        return SMangaUpdate(project?.toSManga(baseUrl) ?: manga, chapterList)
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
        val response = client.get("$baseUrl/api/chapters/$chapterId", Fetch.XHR).parseAs<ChapterResponse>()
        val chapterData = response.data ?: throw Exception("Chapter not found")
        val pages = chapterData.pages

        return pages.sortedBy(PageItem::pageNumber).mapIndexed { index, page ->
            Page(index, imageUrl = page.imageUrl(baseUrl))
        }
    }
}

private suspend fun Afterway.fetchChapters(projectId: String): List<SChapter> {
    val result = mutableListOf<SChapter>()
    var page = 1
    do {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/chapters")
            .addQueryParameter("projectId", projectId)
            .addQueryParameter("sort", "number")
            .addQueryParameter("order", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "100")
            .build()
        val response = client.get(url, Fetch.XHR).parseAs<ChapterListResponse>()
        result += response.data.map { it.toSChapter(projectId) }
        page++
    } while (response.pagination?.let { it.page < it.totalPages } == true)
    return result
}

// ========================= CDN Helpers =========================

private fun encodeCdn(path: String): String = Base64.encodeToString(
    path.toByteArray(Charsets.UTF_8),
    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
)

// ========================= DTOs =========================

@Serializable
private class ProjectListResponse(
    val data: List<ProjectItem> = emptyList(),
    val pagination: Pagination? = null,
)

@Serializable
private class Pagination(
    val page: Int = 1,
    val totalPages: Int = 1,
    val hasMore: Boolean? = null,
)

@Serializable
private class ProjectResponse(
    val data: ProjectItem? = null,
)

@Serializable
private class ProjectItem(
    val id: String,
    val title: String,
    val coverId: String? = null,
    val coverExt: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val isOriginal: Boolean? = null,
    val isNSFW: Boolean? = null,
    val chapters: List<ChapterItem> = emptyList(),
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = "/project/$id"
        title = this@ProjectItem.title
        coverId?.let { cid ->
            val ext = coverExt ?: "webp"
            val encoded = encodeCdn("$cid.$ext")
            thumbnail_url = "$baseUrl/cdn/covers/$encoded.html"
        }
        author = this@ProjectItem.author
        artist = this@ProjectItem.artist
        description = this@ProjectItem.description
        genre = genres.joinToString()
        status = parseStatus(this@ProjectItem.status)
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
private class ChapterListResponse(
    val data: List<ChapterItem> = emptyList(),
    val pagination: ChapterPagination? = null,
)

@Serializable
private class ChapterPagination(
    val page: Int,
    val totalPages: Int,
)

@Serializable
private class ChapterResponse(
    val data: ChapterData? = null,
)

@Serializable
private class ChapterData(
    val id: String,
    val projectId: String,
    val pages: List<PageItem> = emptyList(),
)

@Serializable
private class PageItem(
    val id: String,
    val pageNumber: Int,
    val extension: String,
    val folder: String,
) {
    fun imageUrl(baseUrl: String): String {
        val encodedFileName = encodeCdn("$id.$extension")
        return "$baseUrl/cdn/$folder/$encodedFileName.html"
    }
}

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
