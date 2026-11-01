package eu.kanade.tachiyomi.extension.th.bkkmanga

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter

@Source
abstract class Bkkmanga : MadaraNoAjax() {
    override val chapterMode = ChapterMode.MangaAjax

    override val chapterDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // The reader only renders the first page into the DOM and keeps the rest in
    // `var chapter_preloaded_images`, while `?style=list` returns a truncated list.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        val images = preloadedImages(document)
        if (images.isEmpty()) return super.getPageList(chapter)
        return images.mapIndexed { index, image -> Page(index, document.location(), image) }
    }

    private fun preloadedImages(document: Document): List<String> = document
        .selectFirst("script#chapter_preloaded_images")
        ?.data()
        ?.substringAfter("chapter_preloaded_images")
        ?.substringAfter('=')
        ?.substringBefore(';')
        ?.trim()
        ?.parseAs<List<String>>()
        .orEmpty()
}
