package eu.kanade.tachiyomi.extension.th.mangathaiplus

import eu.kanade.tachiyomi.source.model.Filter

class TypeFilter :
    Filter.Select<String>(
        "ประเภท (Type)",
        types.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = types[state].second

    companion object {
        private val types = listOf(
            "ทั้งหมด (All)" to "",
            "มังงะญี่ปุ่น (Manga)" to "manga",
            "มังงะเกาหลี (Manhwa)" to "manhwa",
            "มังงะจีน (Manhua)" to "manhua",
            "เว็บตูน (Webtoon)" to "webtoon",
        )
    }
}

class StatusFilter :
    Filter.Select<String>(
        "สถานะ (Status)",
        statuses.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = statuses[state].second

    companion object {
        private val statuses = listOf(
            "ทั้งหมด (All)" to "",
            "กำลังอัปเดต (Ongoing)" to "ongoing",
            "จบแล้ว (Completed)" to "completed",
        )
    }
}

class GenreFilter :
    Filter.Select<String>(
        "หมวดหมู่ (Genre)",
        genres.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = genres[state].second

    companion object {
        private val genres = listOf(
            "ทั้งหมด (All)" to "",
            "มังงะต่างโลก (Isekai)" to "isekai",
            "พระเอกเทพ (Overpowered MC)" to "op-mc",
            "พระเอกเกิดใหม่ (Reincarnation)" to "reincarnation",
            "ระบบ (System)" to "system",
            "มังงะโรแมนติก (Romance)" to "romance",
            "มังงะต่อสู้ (Action)" to "action",
            "แฟนตาซี (Fantasy)" to "fantasy",
            "ดราม่า (Drama)" to "drama",
            "ตลก (Comedy)" to "comedy",
            "ผจญภัย (Adventure)" to "adventure",
            "ฮาเร็ม (Harem)" to "harem",
            "ชีวิตในโรงเรียน (School Life)" to "school-life",
            "โชเน็ง (Shounen)" to "shounen",
            "เซเน็ง (Seinen)" to "seinen",
            "ย้อนยุค (Historical)" to "historical",
            "แก้แค้น (Revenge)" to "revenge",
            "ดันเจี้ยน (Dungeon)" to "dungeon",
            "ลึกลับ (Mystery)" to "mystery",
            "สยองขวัญ (Horror)" to "horror",
            "กีฬา (Sports)" to "sports",
            "ไซไฟ (Sci-Fi)" to "sci-fi",
            "ศิลปะการต่อสู้ (Martial Arts)" to "martial-arts",
            "ชีวิตประจำวัน (Slice of Life)" to "slice-of-life",
            "โชโจ (Shoujo)" to "shoujo",
            "โจเซย์ (Josei)" to "josei",
            "จิตวิทยา (Psychological)" to "psychological",
            "โศกนาฏกรรม (Tragedy)" to "tragedy",
            "ย้อนเวลา (Time Travel)" to "time-travel",
            "หุ่นยนต์ (Mecha)" to "mecha",
            "เหนือธรรมชาติ (Supernatural)" to "supernatural",
        )
    }
}
