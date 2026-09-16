package eu.kanade.tachiyomi.extension.th.haremmanga

import eu.kanade.tachiyomi.multisrc.zmanga.ZManga
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.asJsoup
import okhttp3.FormBody
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class HaremManga : ZManga() {
    // Thai full month names on Gregorian years, e.g. "14 กันยายน 2026".
    override val dateFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("th"))

    override fun parseDate(dateString: String): Long = try {
        LocalDate.parse(dateString, dateFormatter)
            .atStartOfDay(ZoneId.of("Asia/Bangkok"))
            .toInstant()
            .toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/advance-search/${pagePathSegment(page)}?order=popular").asJsoup()
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = document.select(popularMangaNextPageSelector()).isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/advance-search/${pagePathSegment(page)}?order=update").asJsoup()
        val mangas = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }
        val hasNextPage = document.select(latestUpdatesNextPageSelector()).isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override val supportsFilterFetching get() = false

    // The site's title= search returns an empty list server-side; its own
    // live-search widget POSTs to admin-ajax instead. No result pagination.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val document = client.post(
            "$baseUrl/wp-admin/admin-ajax.php",
            body = FormBody.Builder()
                .add("action", "data_fetch")
                .add("keyword", query.trim())
                .build(),
        ).asJsoup()

        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }
        return MangasPage(mangas, false)
    }

    override fun searchMangaSelector() = "div.searchbox"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
        title = element.selectFirst(".searchbox-title")!!.text().trim()
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }
}
