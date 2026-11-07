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
import kotlin.time.Duration.Companion.seconds

/**
 * Personal nhentai source for TachiHxT.
 *
 * Type a gallery number (e.g. 177013) or an nhentai link into the search box to open that work
 * directly. The bulk importer relies on this.
 */
@Source
abstract class HxTNHentai : KeiSource() {

    // Be gentle with the site: one request per second to nhentai.net itself.
    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 1.seconds) { it.host == "nhentai.net" }

    // ------------------------------------------------------------ Browse

    override suspend fun getPopularManga(page: Int): MangasPage = search("", page, SORT_VALUES.first())

    override suspend fun getLatestUpdates(page: Int): MangasPage = search("", page, "date")

    // ------------------------------------------------------------ Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmed = query.trim()

        ID_REGEX.matchEntire(trimmed)?.let { match ->
            return MangasPage(listOf(gallery(match.groupValues[1]).toSManga()), false)
        }

        val parts = mutableListOf<String>()
        if (trimmed.isNotEmpty()) parts += trimmed

        val sort = filters.firstInstanceOrNull<SortFilter>()?.let { SORT_VALUES[it.state] } ?: SORT_VALUES.first()

        filters.firstInstanceOrNull<LanguageFilter>()?.takeIf { it.state > 0 }?.let {
            parts += "language:${LANGUAGE_VALUES[it.state]}"
        }

        filters.filterIsInstance<TermFilter>().forEach { filter ->
            filter.state
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { term ->
                    parts += if (term.startsWith("-")) {
                        "-${filter.key}:\"${term.removePrefix("-").trim()}\""
                    } else {
                        "${filter.key}:\"$term\""
                    }
                }
        }

        return search(parts.joinToString(" "), page, sort)
    }

    // Pasting an nhentai link into search
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith("nhentai.net")) return null
        val id = LINK_ID_REGEX.find(url.encodedPath)?.groupValues?.get(1) ?: return null
        return gallery(id).toSManga()
    }

    private suspend fun search(query: String, page: Int, sort: String): MangasPage {
        val url = "$baseUrl/api/galleries/search".toHttpUrl().newBuilder()
            // "pages:>0" matches everything, used when there is no text or filter
            .addQueryParameter("query", query.ifBlank { "pages:>0" })
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sort)
            .build()

        return client.get(url, Fetch.XHR).parseAs<GalleryListDto>().toMangasPage(page)
    }

    private suspend fun gallery(id: String): GalleryDto = client.get("$baseUrl/api/gallery/$id", Fetch.XHR).parseAs<GalleryDto>()

    // ------------------------------------------------------------ Details, chapter, pages

    // details and the (single) chapter come from the same request
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = gallery(manga.url)
        return SMangaUpdate(dto.toSManga(), listOf(dto.toSChapter()))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = gallery(chapter.url).toPageList()

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

    companion object {
        private val ID_REGEX = Regex("""^(?:id:|#)?(\d{1,8})$""")
        private val LINK_ID_REGEX = Regex("""^/g/(\d+)""")

        private val SORT_LABELS = arrayOf(
            "Popular: all time",
            "Popular: this month",
            "Popular: this week",
            "Popular: today",
            "Recent",
        )
        private val SORT_VALUES = arrayOf("popular", "popular-month", "popular-week", "popular-today", "date")

        private val LANGUAGE_LABELS = arrayOf("Any", "English", "Japanese", "Chinese")
        private val LANGUAGE_VALUES = arrayOf("", "english", "japanese", "chinese")
    }
}
