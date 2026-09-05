package eu.kanade.tachiyomi.extension.th.bkkmanga

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import keiyoushi.annotation.Source
import java.time.format.DateTimeFormatter

@Source
abstract class Bkkmanga : MadaraNoAjax() {
    override val chapterMode = ChapterMode.MangaAjax

    override val chapterDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}
