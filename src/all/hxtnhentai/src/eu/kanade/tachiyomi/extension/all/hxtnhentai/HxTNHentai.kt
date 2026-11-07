package eu.kanade.tachiyomi.extension.all.hxtnhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Personal nhentai source for TachiHxT, written for nhentai's /api/v2 interface.
 *
 * Type a gallery number (e.g. 694214) or an nhentai link into the search box to open that
 * work directly. The bulk importer relies on this.
 *
 * ALL web addresses used by this file are listed in the Api object at the bottom.
 * Everything in there was copied from requests seen in the browser.
 */
@Source
abstract class HxTNHentai : KeiSource() {

    // Be gentle with the site: one request per second to nhentai.net itself.
    // (Image/thumbnail servers are other hosts, so reading stays fast.)
    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 1.seconds) { it.host == "nhentai.net" }

    // ------------------------------------------------------------ Config (image servers)

    private var cachedConfig: ConfigDto? = null

    private suspend fun config(): ConfigDto {
        cachedConfig?.let { return it }
        val loaded = try {
            client.get("$baseUrl${Api.CONFIG}", Fetch.XHR).parseAs<ConfigDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ConfigDto() // fall back to the defaults in Api
        }
        cachedConfig = loaded
        return loaded
    }

    private suspend fun thumbHost() = config().thumbServers.firstOrNull() ?: Api.DEFAULT_THUMB_HOST

    private suspend fun imageHosts() = config().imageServers.ifEmpty { Api.DEFAULT_IMAGE_HOSTS }

    // ------------------------------------------------------------ Browse

    // Latest = the site's "New Uploads" list
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl${Api.LIST}".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url, Fetch.XHR).parseAs<GalleryListDto>().toMangasPage(page, thumbHost())
    }

    // Popular = all-time popular works, using the search with a query that matches everything
    // (the site's own "Popular Now" list only holds a handful of works and cannot be paged)
    override suspend fun getPopularManga(page: Int): MangasPage = search(MATCH_ALL, "popular", page)

    // ------------------------------------------------------------ Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmed = query.trim()

        // Direct lookup by gallery number
        ID_REGEX.matchEntire(trimmed)?.let { match ->
            return MangasPage(listOf(gallery(match.groupValues[1]).toSManga(thumbHost())), false)
        }

        val sort = filters.firstInstanceOrNull<SortFilter>()?.let { SORT_VALUES[it.state] } ?: SORT_VALUES.first()

        // Build the list of "type:name" terms from the filters
        val terms = mutableListOf<Pair<String, String>>() // key to value (value may start with "-")
        filters.filterIsInstance<TermFilter>().forEach { filter ->
            filter.state.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { terms += filter.key to it }
        }
        val language = filters.firstInstanceOrNull<LanguageFilter>()?.takeIf { it.state > 0 }
            ?.let { LANGUAGE_VALUES[it.state] }

        // Exactly one tag-like filter and nothing else: use the (verified) "tagged" list
        val single = terms.singleOrNull()
        if (trimmed.isEmpty() && language == null && single != null && !single.second.startsWith("-")) {
            val tagId = tagId(single.first, single.second)
            val url = "$baseUrl${Api.TAGGED}".toHttpUrl().newBuilder()
                .addQueryParameter("tag_id", tagId.toString())
                .addQueryParameter("sort", sort)
                .addQueryParameter("page", page.toString())
                .build()
            return client.get(url, Fetch.XHR).parseAs<GalleryListDto>().toMangasPage(page, thumbHost())
        }

        // Everything else: text search
        val parts = mutableListOf<String>()
        if (trimmed.isNotEmpty()) parts += trimmed
        if (language != null) parts += "language:$language"
        terms.forEach { (key, value) ->
            parts += if (value.startsWith("-")) {
                "-$key:\"${value.removePrefix("-").trim()}\""
            } else {
                "$key:\"$value\""
            }
        }

        return search(parts.joinToString(" ").ifBlank { MATCH_ALL }, sort, page)
    }

    // GET /api/v2/search?query=..&sort=..&page=..
    private suspend fun search(query: String, sort: String, page: Int): MangasPage {
        val url = "$baseUrl${Api.SEARCH}".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url, Fetch.XHR).parseAs<GalleryListDto>().toMangasPage(page, thumbHost())
    }

    // GET /api/v2/tags/{type}/{slug}  (verified for tags: /api/v2/tags/tag/nakadashi)
    private suspend fun tagId(type: String, name: String): Int {
        val slug = name.trim().lowercase().replace(Regex("""\s+"""), "-")
        return client.get("$baseUrl${Api.TAG_LOOKUP}$type/$slug", Fetch.XHR).parseAs<TagLookupDto>().id
    }

    // Pasting an nhentai link into search
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith("nhentai.net")) return null
        val id = LINK_ID_REGEX.find(url.encodedPath)?.groupValues?.get(1) ?: return null
        return gallery(id).toSManga(thumbHost())
    }

    private suspend fun gallery(id: String): GalleryDto = client.get("$baseUrl${Api.GALLERY}$id", Fetch.XHR).parseAs<GalleryDto>()

    // ------------------------------------------------------------ Details, chapter, pages

    // details and the (single) chapter come from the same request
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = gallery(manga.url)
        return SMangaUpdate(dto.toSManga(thumbHost()), listOf(dto.toSChapter()))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = gallery(chapter.url).toPageList(imageHosts())

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/g/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/g/${chapter.url}/"

    // ------------------------------------------------------------ Filters

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        LanguageFilter(),
        Filter.Separator(),
        Filter.Header("Separate several entries with commas. Start one with - to exclude it."),
        TermFilter("Tags", "tag"),
        TermFilter("Artists", "artist"),
        TermFilter("Groups", "group"),
        TermFilter("Parodies", "parody"),
        TermFilter("Characters", "character"),
    )

    private class SortFilter : Filter.Select<String>("Sort by", SORT_LABELS)

    private class LanguageFilter : Filter.Select<String>("Language", LANGUAGE_LABELS)

    private class TermFilter(name: String, val key: String) : Filter.Text(name)

    /**
     * Every web address this source uses lives here. If nhentai changes something, this is
     * the only place to edit.
     */
    private object Api {
        // VERIFIED in the browser:
        const val CONFIG = "/api/v2/config"
        const val GALLERY = "/api/v2/galleries/" // + id   (?include=related,suggestions,comments is optional)
        const val TAG_LOOKUP = "/api/v2/tags/" // + type + "/" + slug

        // Verified: /api/v2/galleries/tagged?tag_id=..&sort=date|popular-today|popular-week|popular&page=..
        const val TAGGED = "/api/v2/galleries/tagged"

        // Verified: /api/v2/galleries?page=..  (the "New Uploads" list)
        const val LIST = "/api/v2/galleries"

        // Verified: /api/v2/search?query=..&sort=date|popular-today|popular-week|popular&page=..
        const val SEARCH = "/api/v2/search"

        // Used if /api/v2/config cannot be read:
        const val DEFAULT_THUMB_HOST = "https://t1.nhentai.net"
        val DEFAULT_IMAGE_HOSTS = listOf(
            "https://i1.nhentai.net",
            "https://i2.nhentai.net",
            "https://i3.nhentai.net",
            "https://i4.nhentai.net",
        )
    }

    companion object {
        // A search that matches every work (used for the all-time Popular tab). If the site rejects
        // it, this is the one place to change.
        private const val MATCH_ALL = "pages:>0"

        private val ID_REGEX = Regex("""^(?:id:|#)?(\d{1,8})$""")
        private val LINK_ID_REGEX = Regex("""^/g/(\d+)""")

        // Verified sort values (same names the website uses). The first entry is the default.
        private val SORT_LABELS = arrayOf(
            "Recent",
            "Popular: today",
            "Popular: this week",
            "Popular: all time",
        )
        private val SORT_VALUES = arrayOf("date", "popular-today", "popular-week", "popular")

        private val LANGUAGE_LABELS = arrayOf("Any", "English", "Japanese", "Chinese")
        private val LANGUAGE_VALUES = arrayOf("", "english", "japanese", "chinese")
    }
}
