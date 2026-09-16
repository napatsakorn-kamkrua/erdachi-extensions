package eu.kanade.tachiyomi.extension.th.mangathaiplus

import kotlinx.serialization.Serializable

@Serializable
class SearchIndexDto(
    val docs: List<SearchDoc> = emptyList(),
)

@Serializable
class SearchDoc(
    val slug: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val cover: String? = null,
    val synonyms: List<String> = emptyList(),
)

@Serializable
class PageDto(
    val src: String? = null,
    val webp: String? = null,
)
