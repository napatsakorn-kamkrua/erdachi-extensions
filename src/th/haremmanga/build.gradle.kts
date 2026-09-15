import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HaremManga"
    theme = "zmanga"
    versionCode = 0
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://www.haremmanga.net"
        lang = "th"
    }
}
