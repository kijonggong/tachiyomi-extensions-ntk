package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Ntk : KeiSource() {

    // KSP generates subclasses from build.gradle.kts source blocks, so the
    // per-source paths have to be derived from the injected id.
    private val sectionPath by lazy { if (id == MANHWA_ID) MANHWA_PATH else WEBTOON_PATH }

    private val siteHost by lazy { baseUrl.toHttpUrl().host }

    // Only 만화 has a dedicated "최신" page; 웹툰 reuses its listing sorted by update.
    private val updatesPath by lazy { if (id == MANHWA_ID) "$MANHWA_PATH/updates" else null }

    // Limit the site itself only. Thumbnails come from aws-cdn1.site and
    // image-comic.pstatic.net, and throttling those stalls list scrolling.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2) { it.host == siteHost }
        .addInterceptor(NtkReaderInterceptor())

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36",
        )
        add(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        )
        add("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
    }

    private val chapterDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.KOREA)

    override suspend fun getPopularManga(page: Int): MangasPage = listingParse(client.get(listingUrl(page, "as_view")).asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (updatesPath == null) {
            return listingParse(client.get(listingUrl(page, "as_update")).asJsoup())
        }

        val url = "$baseUrl$updatesPath".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return updatesParse(client.get(url).asJsoup())
    }

    private fun listingUrl(page: Int, sort: String): HttpUrl = "$baseUrl$sectionPath".toHttpUrl().newBuilder()
        .addQueryParameter("sst", sort)
        .addQueryParameter("sod", "desc")
        .addQueryParameter("page", page.toString())
        .build()

    private fun listingParse(document: Document): MangasPage {
        val itemSelector = "#webtoon-list-all > li:has(a[href^=\"$sectionPath/\"])"
        val mangas = document.select(itemSelector).map { element ->
            val link = element.selectFirst("a[href^=\"$sectionPath/\"]")
                ?: throw Exception("작품 주소를 찾을 수 없습니다.")
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = element.selectFirst("span.title")?.text()
                    ?: throw Exception("제목을 찾을 수 없습니다.")
                thumbnail_url = element.selectFirst("img.theme-thumb-img")?.attrOrNull("abs:src")
                genre = element.attrOrNull("data-genre")
                    ?.split(",")
                    ?.joinToString { it.trim() }
                status = if (element.selectFirst(".theme-completed-badge") != null) {
                    SManga.COMPLETED
                } else {
                    SManga.ONGOING
                }
            }
        }
        return MangasPage(mangas, hasNextPage(document))
    }

    private fun updatesParse(document: Document): MangasPage {
        val mangas = document.select(".theme-update-webzine .post-list").map { element ->
            val link = element.selectFirst("a.theme-update-all-link")
                ?: throw Exception("작품 주소를 찾을 수 없습니다.")
            val tags = element.select(".theme-update-tag-text")
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = element.selectFirst(".theme-update-subject-title")?.text()
                    ?: throw Exception("제목을 찾을 수 없습니다.")
                thumbnail_url = element.selectFirst("img.theme-thumb-img")?.attrOrNull("abs:src")
                author = tags.getOrNull(0)?.textOrNull()
                genre = tags.getOrNull(1)?.textOrNull()
                    ?.split(",")
                    ?.joinToString { it.trim() }
            }
        }
        return MangasPage(mangas, hasNextPage(document))
    }

    private fun hasNextPage(document: Document): Boolean {
        val currentPage = document.selectFirst(".pagination li.active, .pg_current")
            ?.text()
            ?.toIntOrNull()
            ?: document.selectFirst("link[rel=canonical][href]")
                ?.absUrl("href")
                ?.toHttpUrlOrNull()
                ?.queryParameter("page")
                ?.toIntOrNull()
            ?: 1
        return document.select(".pagination a[href], a.pg_page[href]").any { link ->
            link.absUrl("href").toHttpUrlOrNull()
                ?.queryParameter("page")
                ?.toIntOrNull() == currentPage + 1
        }
    }

    private fun hasNextEpisodePage(document: Document, currentPage: Int): Boolean {
        val linkedPages = document.select("a.pg_page[href]").mapNotNull { link ->
            link.absUrl("href").toHttpUrlOrNull()
                ?.queryParameter("epage")
                ?.toIntOrNull()
        }
        // The pager only links a window of page numbers, so requiring the exact next number would
        // end the loop early on a page whose own number falls outside that window.
        // Do not fall back to the row count: the site clamps a past-the-end `epage` to the last
        // page, so a full page of rows does not prove another page exists.
        return linkedPages.any { it > currentPage }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = listingParse(client.get(searchUrl(page, query, filters)).asJsoup())

    private fun searchUrl(page: Int, query: String, filters: FilterList): HttpUrl {
        val builder = "$baseUrl$sectionPath".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            builder.addQueryParameter("stx", query)
                .addQueryParameter("kind", sectionPath.removePrefix("/"))
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
                    .addQueryParameter(
                        "yoil",
                        filters.firstInstance<DayFilter>().value,
                    )
                    .addQueryParameter(
                        "plat",
                        filters.firstInstance<PlatformFilter>().value,
                    )
            } else {
                builder.addQueryParameter(
                    "pub",
                    filters.firstInstance<StatusFilter>().value,
                )
            }

            builder.addQueryParameter(
                "jaum",
                filters.firstInstance<InitialFilter>().value,
            )

            if (genre.isNotEmpty()) {
                builder.addQueryParameter("tag", genre)
            }
        }

        return builder.build()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        // Only work pages resolve here. Everything else under the section - episode URLs
        // (/manhwa/{work}/{episode}, which deeplinks and shared chapter links match), the
        // /manhwa/updates listing and the section root - must return null so the app reports
        // a missing title instead of failing while the entry loads.
        val segments = url.pathSegments.filter(String::isNotEmpty)
        if (segments.size != 2 || "/${segments.first()}" != sectionPath) return null

        val targetUrl = url.newBuilder()
            .host(siteHost)
            .removeAllQueryParameters("epage")
            .build()
        val document = client.get(targetUrl).asJsoup()
        // An unknown id still renders a titled page ("작품을 찾을 수 없습니다") without a chapter
        // list, so require the chapter list that only a real work page has.
        if (document.selectFirst(DETAIL_TITLE_SELECTOR) == null ||
            document.selectFirst(CHAPTER_ROW_SELECTOR) == null
        ) {
            return null
        }
        return mangaDetailsParse(document).apply {
            setUrlWithoutDomain(targetUrl.toString())
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and chapters come from the same page, so fetch once and
        // return both regardless of the flags.
        // A URL saved before epage was stripped can still carry it, and the loop
        // below only works from page 1.
        val mangaUrl = getMangaUrl(manga).toHttpUrl().newBuilder()
            .removeAllQueryParameters("epage")
            .build()
        var document = client.get(mangaUrl).asJsoup()
        val details = mangaDetailsParse(document)

        // The chapter list caps at 100 rows per page and paginates via `epage`,
        // a different query key from the listing pages' `page`.
        val chapterList = chapterListParse(document).toMutableList()
        var epage = 1
        while (epage < MAX_CHAPTER_PAGES && hasNextEpisodePage(document, epage)) {
            epage++
            document = client.get(mangaUrl.newBuilder().setQueryParameter("epage", epage.toString()).build()).asJsoup()
            chapterList += chapterListParse(document)
        }

        return SMangaUpdate(details, chapterList.groupVolumeChapters())
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(DETAIL_TITLE_SELECTOR)?.text()
            ?: throw Exception("제목을 찾을 수 없습니다.")
        author = document
            .selectFirst(".theme-detail-info-row:first-child .theme-detail-info-value")
            ?.textOrNull()
        description = document.selectFirst(".theme-detail-description")?.textOrNull()
        thumbnail_url = document.selectFirst(".view-title .col-sm-4 img")?.attrOrNull("abs:src")
        genre = document
            .selectFirst(".theme-detail-info-row:nth-child(2) .theme-detail-info-value")
            ?.textOrNull()
            ?.replace("#", "")
            ?.takeIf(String::isNotEmpty)
        status = document
            .selectFirst(".theme-detail-info-row:nth-child(3) .theme-detail-info-value")
            ?.textOrNull()
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

    private fun chapterListParse(document: Document): List<SChapter> = document
        .select(CHAPTER_ROW_SELECTOR)
        .map { row ->
            val link = row.selectFirst("a.item-subject")
                ?: throw Exception("회차 주소를 찾을 수 없습니다.")
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.ownText()
                // Let Mihon recognize episode parts such as 25-1화 and titled episodes
                // such as "5화. 부제". Volumes and specials must stay unnumbered so
                // e.g. 77권 cannot collide with 77화.
                chapter_number = if (isEpisode(name)) -1F else -2F
                date_upload = chapterDateFormat.tryParseDate(
                    row.selectFirst("div.wr-date")?.textOrNull(),
                    KOREA_ZONE,
                )
            }
        }

    private fun List<SChapter>.groupVolumeChapters(): List<SChapter> {
        if (sectionPath != MANHWA_PATH) {
            return this
        }

        val (episodes, otherChapters) = partition { isEpisode(it.name) }
        val (volumes, specials) = otherChapters.partition { isVolume(it.name) }
        // The site interleaves numbered episodes and collected volumes.
        return specials + volumes + episodes
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val requestHeaders = headers.newBuilder()
            .set(NTK_READER_HEADER, "1")
            .build()
        return client.get(getChapterUrl(chapter), requestHeaders)
            .parseAs<NtkImagesResponse>()
            .toPages()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("키워드 검색 시 필터는 적용되지 않습니다."),
            SortFilter(),
        )
        if (sectionPath == WEBTOON_PATH) {
            filters += CategoryFilter()
            filters += GenreFilter(WEBTOON_GENRE_OPTIONS)
            filters += DayFilter()
            filters += PlatformFilter()
        } else {
            filters += StatusFilter()
            filters += GenreFilter(MANHWA_GENRE_OPTIONS)
        }
        filters += InitialFilter()
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

    private class InitialFilter : SelectFilter("초성", INITIAL_OPTIONS)

    private class DayFilter : SelectFilter("요일", DAY_OPTIONS)

    private class PlatformFilter : SelectFilter("플랫폼", PLATFORM_OPTIONS)

    companion object {
        private const val WEBTOON_PATH = "/webtoon"
        private const val MANHWA_PATH = "/manhwa"

        private val KOREA_ZONE = ZoneId.of("Asia/Seoul")

        // Episodes are numbered like "1194화" or "73-2화" and may carry a subtitle
        // ("5화. 부제"). The lookahead keeps 화/권 from matching inside a word.
        private val EPISODE_REGEX = Regex("""\d+(?:\.\d+)?\s*화(?!\p{L})""")
        private val VOLUME_REGEX = Regex("""\d+(?:\.\d+)?\s*권(?!\p{L})""")

        private fun isVolume(name: String) = VOLUME_REGEX.containsMatchIn(name)

        // A volume that mentions episodes ("77권 (300화~305화)") stays a volume.
        private fun isEpisode(name: String) = !isVolume(name) && EPISODE_REGEX.containsMatchIn(name)

        // Must match build.gradle.kts. Pinned so updates keep users' libraries.
        private const val MANHWA_ID = 7381471216199971485L

        private const val DETAIL_TITLE_SELECTOR = ".theme-detail-title-line"
        private const val CHAPTER_ROW_SELECTOR = "div.serial-list ul.list-body > li.list-item"

        // The site's chapter list paginates via `epage`, a different query key from the listing
        // pages' `page`. The pager window means the loop needs its own bound.
        private const val MAX_CHAPTER_PAGES = 200

        private val SORT_OPTIONS = arrayOf(
            "최신순" to "as_update",
            "신작순" to "as_new",
            "북마크순" to "as_bookmark",
            "조회순" to "as_view",
            "평점순" to "as_rating",
            "화수순" to "as_episode",
        )

        // Matches the site's listing filter buttons (s-jaum / s-yoil / s-plat).
        private val INITIAL_OPTIONS = arrayOf(
            "전체" to "",
            "ㄱ" to "ㄱ",
            "ㄴ" to "ㄴ",
            "ㄷ" to "ㄷ",
            "ㄹ" to "ㄹ",
            "ㅁ" to "ㅁ",
            "ㅂ" to "ㅂ",
            "ㅅ" to "ㅅ",
            "ㅇ" to "ㅇ",
            "ㅈ" to "ㅈ",
            "ㅊ" to "ㅊ",
            "ㅋ" to "ㅋ",
            "ㅌ" to "ㅌ",
            "ㅍ" to "ㅍ",
            "ㅎ" to "ㅎ",
            "a-z" to "a-z",
            "0-9" to "0-9",
        )

        private val DAY_OPTIONS = arrayOf(
            "전체" to "",
            "월" to "월",
            "화" to "화",
            "수" to "수",
            "목" to "목",
            "금" to "금",
            "토" to "토",
            "일" to "일",
            "열흘" to "열흘",
        )

        // Platform ids the site's own filter omits but the listing still honours.
        private val PLATFORM_OPTIONS = arrayOf(
            "전체" to "",
            "네이버" to "1",
            "다음" to "2",
            "카카오" to "3",
            "레진" to "4",
            "투믹스" to "5",
            "탑툰" to "6",
            "코미카" to "7",
            "배틀코믹스" to "8",
            "코믹GT" to "9",
            "케이툰" to "10",
            "애니툰" to "11",
            "폭스툰" to "12",
            "피너툰" to "13",
            "봄툰" to "14",
            "코미코" to "15",
            "무툰" to "16",
            "리디북스" to "18",
            "기타" to "99",
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
