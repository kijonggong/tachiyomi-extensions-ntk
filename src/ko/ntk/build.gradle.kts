import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NTK"
    versionCode = 20
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    // Both sources share one user-overridable domain (see Ntk.DOMAIN_PREF), so the
    // baseUrl here is only the shipped default. Source names must stay byte-identical
    // to the published v19's, or existing installs get new source ids and detach
    // libraries. KSP generates the SourceFactory from these blocks.
    source {
        name = "NTK 만화"
        lang = "ko"
        baseUrl = "https://newtoki1.org"
        id = 7381471216199971485L
    }
    source {
        name = "NTK 웹툰"
        lang = "ko"
        baseUrl = "https://newtoki1.org"
        id = 2180431219753503027L
    }
}
