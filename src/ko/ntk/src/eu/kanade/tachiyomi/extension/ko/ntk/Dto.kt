package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

private const val DEAD_CDN_HOST = "aws-cdn1.site"
private const val FALLBACK_CDN_HOST = "aws-cdn9.site"

@Serializable
class NtkImagesResponse(
    private val images: List<NtkImage>,
) {
    fun toPages(): List<Page> = images
        .sortedBy(NtkImage::page)
        .mapIndexed { index, image ->
            Page(
                index = index,
                imageUrl = image.src.toDownloadUrl(),
            )
        }

    private fun String.toDownloadUrl(): String {
        val url = toHttpUrlOrNull() ?: throw IOException("Invalid image URL: $this")
        // aws-cdn1.site has an expired SSL certificate. Fallback to active CDN if encountered.
        if (url.host == DEAD_CDN_HOST) {
            return url.newBuilder()
                .host(FALLBACK_CDN_HOST)
                .build()
                .toString()
        }
        return this
    }
}

@Serializable
class NtkImage(
    val page: Int,
    val src: String,
)
