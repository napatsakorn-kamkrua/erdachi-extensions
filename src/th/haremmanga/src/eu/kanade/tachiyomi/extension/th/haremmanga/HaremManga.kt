package eu.kanade.tachiyomi.extension.th.haremmanga

import eu.kanade.tachiyomi.multisrc.zmanga.ZManga
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import okhttp3.FormBody
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class HaremManga : ZManga() {
    // Thai full month names on Gregorian years, e.g. "14 กันยายน 2026".
    override val dateFormatter = SimpleDateFormat("d MMMM yyyy", Locale("th")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Bangkok")
    }

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
