package eu.kanade.tachiyomi.extension.ko.ntk

import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

internal const val NTK_READER_HEADER = "X-NTK-WebView"

internal class NtkReaderInterceptor : Interceptor {

    private val webViewPermit = Semaphore(1, true)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(NTK_READER_HEADER) == null) {
            return chain.proceed(request)
        }

        val targetRequest = request.newBuilder()
            .removeHeader(NTK_READER_HEADER)
            .build()
        val timeout = if (targetRequest.url.encodedPath.startsWith("/manhwa/")) {
            90.seconds
        } else {
            45.seconds
        }

        val deadlineNanos = acquireWebViewPermit(chain.call(), timeout)
        val payload = try {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw timeoutException(timeout)

            runWebViewBlocking<String>(
                call = chain.call(),
                timeout = remainingNanos.nanoseconds,
            ) {
                targetRequest.header("User-Agent")?.let { userAgent = it }
                // The hidden challenge observes image requests before allowing the reader API.
                blockImages = false

                jsBridge(BRIDGE_NAME) { resolve(it.validatedCapturePayload()) }
                val evaluateCaptureOnTrustedPage: (String) -> Unit = { loadedUrl ->
                    val url = loadedUrl.toHttpUrlOrNull()
                    if (
                        url == null ||
                        !url.isHttps ||
                        url.host != targetRequest.url.host ||
                        url.port != targetRequest.url.port
                    ) {
                        reject(IOException("Reader navigated to an untrusted page"))
                    } else {
                        evaluateJs(CAPTURE_SCRIPT)
                    }
                }
                onPageStarted(evaluateCaptureOnTrustedPage)
                onPageFinished(evaluateCaptureOnTrustedPage)
                onReceivedError { webRequest, error ->
                    if (webRequest.isForMainFrame) {
                        reject(
                            IOException(
                                "Reader page load failed (${error.errorCode}): ${webRequest.url}",
                            ),
                        )
                    }
                }

                val extraHeaders = buildMap {
                    targetRequest.header("Referer")?.let { put("Referer", it) }
                    targetRequest.header("Accept-Language")?.let { put("Accept-Language", it) }
                }
                loadUrl(targetRequest.url.toString(), extraHeaders)
            }
        } catch (error: WebViewTimeoutException) {
            throw timeoutException(timeout)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            // Mihon runs page-list calls through OkHttp's async path, which rethrows
            // anything but an IOException on its dispatcher thread and takes the app
            // down with it (e.g. RenderProcessGoneException).
            throw IOException("Reader WebView failed: ${error.message}", error)
        } finally {
            webViewPermit.release()
        }

        return Response.Builder()
            .request(targetRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(payload.toResponseBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun acquireWebViewPermit(call: Call, timeout: Duration): Long {
        val deadlineNanos = System.nanoTime() + timeout.inWholeNanoseconds
        while (true) {
            if (call.isCanceled()) throw IOException("Canceled")
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw timeoutException(timeout)

            val waitNanos = minOf(remainingNanos, PERMIT_POLL_INTERVAL.inWholeNanoseconds)
            val acquired = try {
                webViewPermit.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the reader WebView", error)
            }
            if (acquired) return deadlineNanos
        }
    }

    private fun timeoutException(timeout: Duration) = IOException("Timed out waiting for the reader WebView after $timeout")

    private fun String.validatedCapturePayload(): String {
        if (length > MAX_CAPTURE_PAYLOAD_LENGTH) {
            throw IOException("Reader returned an oversized image list")
        }
        return try {
            parseAs<CapturePayload>().validatedJson()
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Reader returned an invalid image list", error)
        }
    }

    @Serializable
    private class CapturePayload(
        private val images: List<CaptureImage>? = null,
        private val error: String? = null,
    ) {
        fun validatedJson(): String {
            if (error != null) {
                throw IOException(
                    error.take(MAX_ERROR_LENGTH).ifBlank { "Reader failed to load images" },
                )
            }

            val rawImages = images ?: throw IOException("Reader returned an invalid image list")
            if (rawImages.isEmpty()) {
                throw IOException("Reader returned an empty image list")
            }
            if (rawImages.size > MAX_IMAGE_COUNT) {
                throw IOException("Reader returned too many images")
            }

            val validated = rawImages.map(CaptureImage::validated).sortedBy(CaptureImage::page)
            if (validated.map(CaptureImage::page).distinct().size != validated.size) {
                throw IOException("Reader returned duplicate page numbers")
            }

            return CapturePayload(images = validated).toJsonString()
        }
    }

    @Serializable
    private class CaptureImage(
        val page: Int,
        private val src: String,
    ) {
        fun validated(): CaptureImage {
            if (page <= 0 || src.length > MAX_IMAGE_URL_LENGTH) {
                throw IOException("Reader returned invalid image metadata")
            }
            val url = src.toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
                ?: throw IOException("Reader returned an invalid image URL")
            return CaptureImage(page, url.newBuilder().fragment(null).build().toString())
        }
    }

    private companion object {
        const val BRIDGE_NAME = "NtkImageBridge"
        const val MAX_CAPTURE_PAYLOAD_LENGTH = 2_000_000
        const val MAX_IMAGE_COUNT = 2_000
        const val MAX_IMAGE_URL_LENGTH = 8_192
        const val MAX_ERROR_LENGTH = 200
        val PERMIT_POLL_INTERVAL = 250.milliseconds
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        val CAPTURE_SCRIPT =
            """
            (() => {
                if (window.__ntkImageCapture) return;
                window.__ntkImageCapture = true;
                window.__ntkImageDone = false;

                const post = (value) => {
                    if (window.__ntkImageDone) return;
                    window.__ntkImageDone = true;
                    clearInterval(window.__ntkImagePoll);
                    clearTimeout(window.__ntkImageSettle);
                    if (window.__ntkImageObserver) window.__ntkImageObserver.disconnect();
                    window.NtkImageBridge.post(JSON.stringify(value));
                };

                try {
                    const getCookie = (name) => {
                        try {
                            const m = document.cookie.match(new RegExp('(?:^|;\\s*)' + name + '=([^;]*)'));
                            return m ? decodeURIComponent(m[1]) : '';
                        } catch (_) {
                            return '';
                        }
                    };
                    const setCookie = (name, val) => {
                        const secure = location.protocol === 'https:' ? '; Secure' : '';
                        document.cookie = name + '=' + encodeURIComponent(val) + '; Path=/; Max-Age=31536000; SameSite=Lax' + secure;
                    };
                    const randomHex = (len) => {
                        const b = new Uint8Array(len);
                        (window.crypto || window.msCrypto).getRandomValues(b);
                        let s = '';
                        for (let i = 0; i < b.length; i++) s += ('0' + b[i].toString(16)).slice(-2);
                        return s;
                    };
                    if (!/^[a-fA-F0-9]{32}${'$'}/.test(getCookie('ntk_pid'))) {
                        setCookie('ntk_pid', randomHex(16));
                    }
                    if (!/^[a-fA-F0-9]{16,64}${'$'}/.test(getCookie('ntk_fp'))) {
                        setCookie('ntk_fp', randomHex(16));
                    }
                } catch (_) {}

                const captured = new Map();

                const readExpectedPages = () => {
                    const data = document.getElementById("theme-viewer-data");
                    if (data) {
                        try {
                            const images = JSON.parse(data.textContent || "").images;
                            if (Array.isArray(images)) {
                                const pages = images.map((image) => Number(image && image.page));
                                const uniquePages = new Set(pages);
                                if (
                                    pages.length > 0 &&
                                    pages.every((page) => Number.isInteger(page) && page > 0) &&
                                    uniquePages.size === pages.length
                                ) {
                                    return { known: true, pages: Array.from(uniquePages).sort((a, b) => a - b) };
                                }
                            }
                        } catch (_) {}
                    }

                    const slots = Array.from(document.querySelectorAll(
                        "[data-theme-viewer-images] [data-theme-page]",
                    ));
                    if (!slots.length) return { known: false, pages: [] };
                    const pages = slots
                        .map((slot) => Number(slot.dataset.themePage))
                        .filter((page) => Number.isInteger(page) && page > 0);
                    return {
                        known: pages.length === slots.length && new Set(pages).size === pages.length,
                        pages: Array.from(new Set(pages)).sort((a, b) => a - b),
                    };
                };

                const tryFinish = () => {
                    const expected = readExpectedPages();
                    if (expected.known && expected.pages.length > 0 && expected.pages.every((p) => captured.has(p))) {
                        post({ images: expected.pages.map((p) => captured.get(p)) });
                        return true;
                    }
                    return false;
                };

                const scheduleLegacyFinish = () => {
                    clearTimeout(window.__ntkImageSettle);
                    window.__ntkImageSettle = setTimeout(() => {
                        const expected = readExpectedPages();
                        if (expected.known && expected.pages.length > 0) {
                            const validList = expected.pages.filter((p) => captured.has(p)).map((p) => captured.get(p));
                            if (validList.length > 0) {
                                post({ images: validList });
                                return;
                            }
                        }
                        const images = Array.from(captured.values()).sort((a, b) => a.page - b.page);
                        if (images.length) post({ images });
                    }, 800);
                };

                try {
                    const origJson = Response.prototype.json;
                    Response.prototype.json = function() {
                        return origJson.apply(this, arguments).then((data) => {
                            try {
                                if (data && Array.isArray(data.images) && data.images.length > 0) {
                                    let changed = false;
                                    data.images.forEach((item) => {
                                        const page = Number(item && item.page);
                                        const src = item && item.src;
                                        if (Number.isInteger(page) && page > 0 && typeof src === 'string' && src.startsWith('https://')) {
                                            if (!captured.has(page) || captured.get(page).src !== src) {
                                                captured.set(page, { page, src });
                                                changed = true;
                                            }
                                        }
                                    });
                                    if (changed) {
                                        if (!tryFinish()) scheduleLegacyFinish();
                                    }
                                }
                            } catch (_) {}
                            return data;
                        });
                    };
                } catch (_) {}

                try {
                    const desc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
                    if (desc && desc.set) {
                        Object.defineProperty(HTMLImageElement.prototype, 'src', {
                            set: function(val) {
                                try {
                                    const slot = this.closest ? this.closest('[data-theme-page]') : null;
                                    const page = Number(slot && slot.dataset.themePage);
                                    if (Number.isInteger(page) && page > 0 && typeof val === 'string' && val.startsWith('https://')) {
                                        if (!captured.has(page) || captured.get(page).src !== val) {
                                            captured.set(page, { page, src: val });
                                            if (!tryFinish()) scheduleLegacyFinish();
                                        }
                                    }
                                } catch (_) {}
                                return desc.set.call(this, val);
                            },
                            get: function() {
                                return desc.get.call(this);
                            }
                        });
                    }
                } catch (_) {}

                const poll = () => {
                    if (!window.__ntkImageObserver && document.documentElement) {
                        window.__ntkImageObserver = new MutationObserver(poll);
                        window.__ntkImageObserver.observe(document.documentElement, {
                            childList: true,
                            subtree: true,
                            attributes: true,
                            attributeFilter: ["class", "src"],
                        });
                    }
                    const nodes = document.querySelectorAll(
                        "[data-theme-viewer-images] img[src], " +
                        ".theme-viewer-images img[src], " +
                        ".vw-imgs img[src], #toon_img img[src], #toon_content_imgs img[src]",
                    );
                    let changed = false;
                    nodes.forEach((image, index) => {
                        const slot = image.closest("[data-theme-page]");
                        const matches = (image.alt || "").match(/[0-9]+/g);
                        const page = Number(slot && slot.dataset.themePage) ||
                            (matches ? Number(matches[matches.length - 1]) : index + 1);
                        if (!Number.isInteger(page) || page <= 0) return;

                        let url;
                        try {
                            url = new URL(image.currentSrc || image.src, document.baseURI);
                        } catch (_) {
                            return;
                        }
                        if (url.protocol !== "https:") return;

                        const previous = captured.get(page);
                        if (!previous || previous.src !== url.href) {
                            captured.set(page, { page, src: url.href });
                            changed = true;
                        }
                    });

                    if (tryFinish()) return;

                    const expected = readExpectedPages();
                    if (expected.known) {
                        const failedPages = new Set(
                            Array.from(document.querySelectorAll(
                                "[data-theme-viewer-images] [data-theme-page].is-error, " +
                                "[data-theme-viewer-images] [data-theme-page] .is-error",
                            ))
                                .map((error) => error.closest("[data-theme-page]"))
                                .map((slot) => Number(slot && slot.dataset.themePage))
                                .filter((page) => Number.isInteger(page) && page > 0),
                        );
                        if (expected.pages.every((page) => failedPages.has(page)) && captured.size === 0) {
                            post({ error: "Reader failed to load images" });
                            return;
                        }
                        if (failedPages.size > 0 && captured.size > 0) {
                            scheduleLegacyFinish();
                            return;
                        }
                    }

                    if (
                        !captured.size &&
                        document.querySelector(".theme-viewer-images .is-error, .vw-imgs .is-error")
                    ) {
                        post({ error: "Reader failed to load image metadata" });
                        return;
                    }
                    if (changed) scheduleLegacyFinish();
                };

                window.__ntkImagePoll = setInterval(poll, 250);
                poll();
            })();
            """.trimIndent()
    }
}
