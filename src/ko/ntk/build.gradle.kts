import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NTK"
    versionCode = 39
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    // baseUrl here is only the shipped default; the custom(...) block below makes
    // it user-overridable. Source names must stay byte-identical to the published
    // v19's, or existing installs get new source ids and detach libraries.
    source {
        name = "NTK 만화"
        lang = "ko"
        baseUrl {
            custom("https://newtoki1.org")
        }
        id = 7381471216199971485L
    }
    source {
        name = "NTK 웹툰"
        lang = "ko"
        baseUrl {
            custom("https://newtoki1.org")
        }
        id = 2180431219753503027L
    }

    deeplink {
        path("/webtoon/..*")
        path("/manhwa/..*")
    }
}
