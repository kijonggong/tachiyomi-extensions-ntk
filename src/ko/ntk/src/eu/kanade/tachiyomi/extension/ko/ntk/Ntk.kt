package eu.kanade.tachiyomi.extension.ko.ntk

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstance
import keiyoushi.utils.tryParse
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class Ntk(
    override val name: String,
    override val lang: String,
    // Pinned to the ids the published v19 shipped (md5 of "<name>/ko/1"). Letting
    // these regenerate would hand users new source ids and detach their libraries.
    override val id: Long,
) : HttpSource(),
    ConfigurableSource {

    // KSP generates the SourceFactory from build.gradle.kts and only ever passes
    // name/lang/id, so the per-source paths have to be derived rather than injected.
    private val sectionPath = if (id == MANHWA_ID) MANHWA_PATH else WEBTOON_PATH

    // Only 만화 has a dedicated "최신" page; 웹툰 reuses its listing sorted by update.
    private val updatesPath = if (id == MANHWA_ID) "$MANHWA_PATH/updates" else null

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences(SHARED_PREF_NAME, 0)
    }

    override val baseUrl: String
        get() = preferences.getString(DOMAIN_PREF, DOMAIN_DEFAULT)!!.trimEnd('/')

    override val client: OkHttpClient = network.client.newBuilder()
        // Korean ISPs can SNI-block the site's Cloudflare IPv6 route.
        .dns { hostname ->
            val all = Dns.SYSTEM.lookup(hostname)
            val (v4, v6) = all.partition { it is Inet4Address }
            v4 + v6
        }
        // Limit only the main site, not its image CDN.
        .rateLimit(2) { it.host == baseUrl.toHttpUrl().host }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36",
        )
        .add(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        )
        .add("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")

    private val chapterDateFormat by lazy {
        SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    }

    override fun popularMangaRequest(page: Int): Request = listingRequest(page, "as_view")

    override fun popularMangaParse(response: Response): MangasPage = listingParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        if (updatesPath == null) {
            return listingRequest(page, "as_update")
        }

        val url = "$baseUrl$updatesPath".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = if (updatesPath == null) {
        listingParse(response)
    } else {
        updatesParse(response)
    }

    private fun listingRequest(page: Int, sort: String): Request {
        val url = "$baseUrl$sectionPath".toHttpUrl().newBuilder()
            .addQueryParameter("sst", sort)
            .addQueryParameter("sod", "desc")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    private fun listingParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val itemSelector = "#webtoon-list-all > li:has(a[href^=\"$sectionPath/\"])"
        val mangas = document.select(itemSelector).map { element ->
            val link = element.selectFirst("a[href^=\"$sectionPath/\"]")
                ?: throw Exception("작품 주소를 찾을 수 없습니다.")
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = element.selectFirst("span.title")?.text()
                    ?: throw Exception("제목을 찾을 수 없습니다.")
                thumbnail_url = element.selectFirst("img.theme-thumb-img")?.attr("abs:src")
                genre = element.attr("data-genre")
                    .takeIf(String::isNotEmpty)
                    ?.split(",")
                    ?.joinToString { it.trim() }
                status = if (element.selectFirst(".theme-completed-badge") != null) {
                    SManga.COMPLETED
                } else {
                    SManga.ONGOING
                }
            }
        }
        return MangasPage(mangas, hasNextPage(document, response))
    }

    private fun updatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".theme-update-webzine .post-list").map { element ->
            val link = element.selectFirst("a.theme-update-all-link")
                ?: throw Exception("작품 주소를 찾을 수 없습니다.")
            val tags = element.select(".theme-update-tag-text")
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = element.selectFirst(".theme-update-subject-title")?.text()
                    ?: throw Exception("제목을 찾을 수 없습니다.")
                thumbnail_url = element.selectFirst("img.theme-thumb-img")?.attr("abs:src")
                author = tags.getOrNull(0)?.text()?.takeIf(String::isNotEmpty)
                genre = tags.getOrNull(1)?.text()
                    ?.takeIf(String::isNotEmpty)
                    ?.split(",")
                    ?.joinToString { it.trim() }
            }
        }
        return MangasPage(mangas, hasNextPage(document, response))
    }

    private fun hasNextPage(document: Document, response: Response): Boolean {
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return document.select(".pagination a[href], a.pg_page[href]").any { link ->
            link.absUrl("href").toHttpUrlOrNull()
                ?.queryParameter("page")
                ?.toIntOrNull() == currentPage + 1
        }
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val builder = "$baseUrl$sectionPath".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            builder.addQueryParameter("stx", query)
        } else {
            val sort = filters.firstInstance<SortFilter>().value
            val genre = filters.firstInstance<GenreFilter>().value
            builder.addQueryParameter("sst", sort)
                .addQueryParameter("sod", "desc")

            if (sectionPath == WEBTOON_PATH) {
                builder.addQueryParameter(
                    "toon",
                    filters.firstInstance<CategoryFilter>().value,
                )
            } else {
                builder.addQueryParameter(
                    "pub",
                    filters.firstInstance<StatusFilter>().value,
                )
            }

            if (genre.isNotEmpty()) {
                builder.addQueryParameter("tag", genre)
            }
        }

        return GET(builder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = listingParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".theme-detail-title-line")?.text()
                ?: throw Exception("제목을 찾을 수 없습니다.")
            author = document
                .selectFirst(".theme-detail-info-row:first-child .theme-detail-info-value")
                ?.text()
            description = document.selectFirst(".theme-detail-description")?.text()
            thumbnail_url = document.selectFirst(".view-title .col-sm-4 img")?.attr("abs:src")
            genre = document
                .selectFirst(".theme-detail-info-row:nth-child(2) .theme-detail-info-value")
                ?.text()
                ?.replace("#", "")
                ?.takeIf(String::isNotEmpty)
            status = document
                .selectFirst(".theme-detail-info-row:nth-child(3) .theme-detail-info-value")
                ?.text()
                .let {
                    when {
                        it == null -> SManga.UNKNOWN
                        it.contains("연재중") -> SManga.ONGOING
                        it.contains("완결") -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            initialized = true
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup()
        .select("div.serial-list ul.list-body > li.list-item")
        .map { row ->
            val link = row.selectFirst("a.item-subject")
                ?: throw Exception("회차 주소를 찾을 수 없습니다.")
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.ownText()
                chapter_number = row.selectFirst("div.wr-num")
                    ?.text()
                    ?.toFloatOrNull()
                    ?: -1F
                date_upload = chapterDateFormat.tryParse(
                    row.selectFirst("div.wr-date")?.text(),
                )
            }
        }

    // Reader images are injected client-side and do not exist in the downloaded HTML.
    override fun pageListParse(response: Response): List<Page> = throw Exception(
        "뉴토끼는 회차 이미지를 브라우저에서 동적으로 불러옵니다. " +
            "작품 화면에서 WebView 또는 브라우저로 읽어주세요.",
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("키워드 검색 시 필터는 적용되지 않습니다."),
            SortFilter(),
        )
        if (sectionPath == WEBTOON_PATH) {
            filters += CategoryFilter()
            filters += GenreFilter(WEBTOON_GENRE_OPTIONS)
        } else {
            filters += StatusFilter()
            filters += GenreFilter(MANHWA_GENRE_OPTIONS)
        }
        return FilterList(filters)
    }

    private open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
        default: Int = 0,
    ) : Filter.Select<String>(
        name,
        options.map { it.first }.toTypedArray(),
        default,
    ) {
        val value: String
            get() = options[state].second
    }

    private class SortFilter : SelectFilter("정렬", SORT_OPTIONS)

    private class CategoryFilter : SelectFilter("분류", CATEGORY_OPTIONS)

    private class StatusFilter : SelectFilter("발행구분", STATUS_OPTIONS, 1)

    private class GenreFilter(options: Array<Pair<String, String>>) : SelectFilter("장르", options)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "도메인"
            setDefaultValue(DOMAIN_DEFAULT)
            dialogTitle = "도메인"
            dialogMessage =
                "예: https://newtoki1.org\n주소 변경 시 공지된 newtoki숫자.org 도메인을 입력하세요."

            // Both sources must use the same domain preference.
            val current = preferences.getString(DOMAIN_PREF, DOMAIN_DEFAULT)!!
            text = current
            summary = buildDomainSummary(current)

            setOnPreferenceChangeListener { _, newValue ->
                val normalized = normalizeDomain(newValue as String)
                preferences.edit().putString(DOMAIN_PREF, normalized).apply()
                text = normalized
                summary = buildDomainSummary(normalized)
                false
            }
        }.also(screen::addPreference)
    }

    private fun buildDomainSummary(value: String): String = "현재: $value\n주소가 바뀌면 공지된 newtoki숫자.org 도메인을 입력한 뒤 " +
        "앱을 다시 시작하세요."

    private fun normalizeDomain(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return when {
            trimmed.isEmpty() -> DOMAIN_DEFAULT
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    companion object {
        private const val SHARED_PREF_NAME = "source_ntk_shared"
        private const val DOMAIN_PREF = "domain"
        private const val DOMAIN_DEFAULT = "https://newtoki1.org"
        private const val WEBTOON_PATH = "/webtoon"
        private const val MANHWA_PATH = "/manhwa"

        // Must match build.gradle.kts. Pinned so updates keep users' libraries.
        private const val MANHWA_ID = 7381471216199971485L

        private val SORT_OPTIONS = arrayOf(
            "최신순" to "as_update",
            "신작순" to "as_new",
            "북마크순" to "as_bookmark",
            "조회순" to "as_view",
            "평점순" to "as_rating",
            "화수순" to "as_episode",
        )

        private val CATEGORY_OPTIONS = arrayOf(
            "일반웹툰" to "일반웹툰",
            "성인웹툰" to "성인웹툰",
            "BL/GL" to "BL/GL",
            "완결웹툰" to "완결웹툰",
        )

        private val STATUS_OPTIONS = arrayOf(
            "전체" to "all",
            "연재중" to "ongoing",
            "완결" to "completed",
        )

        private val WEBTOON_GENRE_OPTIONS = arrayOf(
            "전체" to "",
            "판타지" to "판타지",
            "액션" to "액션",
            "개그" to "개그",
            "미스터리" to "미스터리",
            "로맨스" to "로맨스",
            "드라마" to "드라마",
            "무협" to "무협",
            "스포츠" to "스포츠",
            "일상" to "일상",
            "학원" to "학원",
            "성인" to "성인",
            "BLGL" to "BLGL",
            "한국" to "한국",
            "중국" to "중국",
        )

        private val MANHWA_GENRE_OPTIONS = arrayOf(
            "전체" to "",
            "17" to "17",
            "BL" to "BL",
            "SF" to "SF",
            "TS" to "TS",
            "개그" to "개그",
            "게임" to "게임",
            "도박" to "도박",
            "드라마" to "드라마",
            "라노벨" to "라노벨",
            "러브코미디" to "러브코미디",
            "먹방" to "먹방",
            "백합" to "백합",
            "보추" to "보추",
            "순정" to "순정",
            "스릴러" to "스릴러",
            "스포츠" to "스포츠",
            "시대" to "시대",
            "애니화" to "애니화",
            "액션" to "액션",
            "음악" to "음악",
            "이세계" to "이세계",
            "일상" to "일상",
            "전생" to "전생",
            "추리" to "추리",
            "판타지" to "판타지",
            "학원" to "학원",
            "호러" to "호러",
        )
    }
}
