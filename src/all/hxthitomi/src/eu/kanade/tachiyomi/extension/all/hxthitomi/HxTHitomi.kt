package eu.kanade.tachiyomi.extension.all.hxthitomi

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
import keiyoushi.utils.jsonInstance
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Personal hitomi.la source for TachiHxT.
 *
 * hitomi.la has no normal API. Instead it publishes:
 *  - ".nozomi" files: binary lists of work numbers (newest first) for the home page, every tag,
 *    artist, group, series, character, type and language, plus "popular" versions of them;
 *  - "galleryblock" snippets: the little cards (title, cover) shown in those lists;
 *  - "galleries/<number>.js": everything about one work (tags, page file names/hashes);
 *  - "gg.js": a small script that decides which image server a page picture lives on;
 *  - a search index (a B-tree stored in two big files) used for title word search.
 *
 * Type a work number (or a hitomi.la link) into the search box to open that work directly.
 *
 * ALL web addresses used by this file are listed in the Api object at the bottom.
 */
@Source
abstract class HxTHitomi : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(8, 1.seconds) { it.host.startsWith("ltn.") }

    // ------------------------------------------------------------ Browse

    override suspend fun getLatestUpdates(page: Int): MangasPage = pagedList(listOf(Api.indexName("all")), page)

    override suspend fun getPopularManga(page: Int): MangasPage = pagedList(listOf("popular", "year-all.nozomi"), page)

    // ------------------------------------------------------------ Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmed = query.trim()

        ID_REGEX.matchEntire(trimmed)?.let { match ->
            return MangasPage(listOf(galleryInfo(match.groupValues[1]).toSManga()), false)
        }

        val sort = filters.firstInstanceOrNull<SortFilter>()?.let { SORT_VALUES[it.state] }.orEmpty()
        var language = filters.firstInstanceOrNull<LanguageFilter>()?.let { LANGUAGE_VALUES[it.state] } ?: "all"
        val type = filters.firstInstanceOrNull<TypeFilter>()?.let { TYPE_VALUES[it.state] }.orEmpty()

        val includes = mutableListOf<Term>()
        val excludes = mutableListOf<Term>()

        // The search box: words = title words, "namespace:value" = a tag-like term, "-" in front = exclude.
        // Only the known namespaces count; anything else (e.g. "Re:Zero") is treated as title words.
        // Text in [] () {} (circle, artist, language, translator group...) is not part of hitomi's titles,
        // so it is dropped from the words; if that leaves nothing, the full text is used.
        val withoutBrackets = trimmed.replace(BRACKETS_REGEX, " ").trim()
        val searchText = if (withoutBrackets.any { it.isLetterOrDigit() }) withoutBrackets else trimmed
        searchText.split(Regex("""\s+""")).filter { it.isNotEmpty() }.forEach { token ->
            val exclude = token.startsWith("-") && token.length > 1
            val text = token.removePrefix("-").replace('_', ' ').lowercase()
            val colon = text.indexOf(':')
            val ns = if (colon > 0) text.substring(0, colon) else null
            val value = if (colon > 0) text.substring(colon + 1) else text
            if (ns != null && ns in KNOWN_NAMESPACES) {
                if (ns == "language" && !exclude) {
                    language = value
                } else if (value.isNotEmpty()) {
                    (if (exclude) excludes else includes).add(Term(Kind.NAMESPACED, ns, value))
                }
            } else {
                // Title words: punctuation splits words, so "Mother-in-law!" becomes mother, in, law
                text.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.forEach { word ->
                    (if (exclude) excludes else includes).add(Term(Kind.TITLE, "", word))
                }
            }
        }

        // The filters: comma separated entries, "-" in front = exclude
        filters.filterIsInstance<TermFilter>().forEach { filter ->
            filter.state.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { raw ->
                val exclude = raw.startsWith("-")
                val value = raw.removePrefix("-").trim()
                if (value.isEmpty()) return@forEach
                val colon = value.indexOf(':')
                val term = if (filter.key == "tag" && colon < 0) {
                    Term(Kind.PLAIN_TAG, "tag", value)
                } else if (filter.key == "tag") {
                    Term(Kind.NAMESPACED, value.substring(0, colon), value.substring(colon + 1))
                } else {
                    Term(Kind.NAMESPACED, filter.key, value)
                }
                (if (exclude) excludes else includes).add(term)
            }
        }
        if (type.isNotEmpty()) includes += Term(Kind.NAMESPACED, "type", type)

        val tagLike = includes.filter { it.kind != Kind.TITLE }
        val titleWords = includes.filter { it.kind == Kind.TITLE }

        // Fast path: nothing to combine, so only one page of the list is downloaded
        if (titleWords.isEmpty() && excludes.isEmpty() && tagLike.size <= 1) {
            val prefix = if (sort.isEmpty()) emptyList() else listOf(sort)
            val segments = if (tagLike.isEmpty()) {
                if (sort.isEmpty()) listOf(Api.indexName(language)) else listOf("popular", "$sort-$language.nozomi")
            } else {
                termSegments(tagLike.first(), language, prefix.map { "popular" } + prefix)
            }
            return pagedList(segments, page)
        }

        // Combining several terms: whole lists are downloaded once and cached while paging
        val cacheKey = listOf(sort, language, includes, excludes).joinToString("|") { it.toString() }
        val all = if (cacheKey == lastSearchKey && lastSearchIds != null) {
            lastSearchIds!!
        } else {
            combine(sort, language, tagLike, titleWords, excludes).also {
                lastSearchKey = cacheKey
                lastSearchIds = it
            }
        }
        val from = (page - 1) * PAGE_SIZE
        if (from >= all.size) return MangasPage(emptyList(), false)
        val to = minOf(all.size, from + PAGE_SIZE)
        return MangasPage(blocks(all.slice(from until to)), to < all.size)
    }

    private var lastSearchKey: String? = null
    private var lastSearchIds: List<Int>? = null

    private suspend fun combine(
        sort: String,
        language: String,
        tagLike: List<Term>,
        titleWords: List<Term>,
        excludes: List<Term>,
    ): List<Int> {
        val sets = mutableListOf<IntArray>() // every work must be in all of these (sorted for lookups)
        var base: IntArray? = null

        if (sort.isNotEmpty()) {
            base = fetchNozomi(listOf("popular", "$sort-$language.nozomi"), null).ids
        }
        for (term in tagLike) {
            val list = fetchNozomi(termSegments(term, language), null).ids
            if (base == null) base = list else sets += list.sortedArray()
        }
        // Words the index does not know (typos, odd spellings) are ignored; if none are known, nothing matches.
        var knownWords = 0
        val wordLists = mutableListOf<IntArray>()
        for (word in titleWords.distinctBy { it.value }) {
            val list = searchTitleWord(word.value) ?: continue
            knownWords++
            wordLists += list
            if (base == null) base = list.sortedArrayDescending() else sets += list.sortedArray()
        }
        if (titleWords.isNotEmpty() && knownWords == 0) return emptyList()
        if (base == null) {
            base = fetchNozomi(listOf(Api.indexName(language)), null).ids
        } else if (sort.isEmpty() && tagLike.isEmpty() && language != "all") {
            // title words only: the language is applied through the language list
            sets += fetchNozomi(listOf(Api.indexName(language)), null).ids.sortedArray()
        }

        val removed = excludes.map { term ->
            if (term.kind == Kind.TITLE) {
                (searchTitleWord(term.value) ?: IntArray(0)).sortedArray()
            } else {
                fetchNozomi(termSegments(term, "all"), null).ids.sortedArray()
            }
        }

        val accept = { id: Int ->
            sets.all { java.util.Arrays.binarySearch(it, id) >= 0 } &&
                removed.none { java.util.Arrays.binarySearch(it, id) >= 0 }
        }
        val exact = base!!.filter(accept)

        // Nothing contains every word (a translated title, an odd spelling...): offer the works that
        // contain the most of them, as long as that is at least half. This is what lets migration find matches.
        if (exact.isEmpty() && sort.isEmpty() && tagLike.isEmpty() && knownWords >= 2) {
            return partialMatches(wordLists, accept)
        }
        return exact
    }

    private fun partialMatches(lists: List<IntArray>, accept: (Int) -> Boolean): List<Int> {
        val useful = lists.filter { it.size <= 150_000 } // very common words say nothing
        if (useful.size < 2) return emptyList()
        val counts = HashMap<Int, Int>()
        for (list in useful) for (id in list) counts[id] = (counts[id] ?: 0) + 1
        val best = counts.values.maxOrNull() ?: return emptyList()
        if (best * 2 < useful.size) return emptyList()
        return counts.filter { it.value == best && accept(it.key) }.keys.sortedDescending().take(100)
    }

    private enum class Kind { TITLE, NAMESPACED, PLAIN_TAG }

    private data class Term(val kind: Kind, val ns: String, val value: String)

    /** Where the .nozomi file for a term lives. */
    private suspend fun termSegments(term: Term, language: String, order: List<String> = emptyList()): List<String> {
        var ns = term.ns
        var value = term.value
        if (term.kind == Kind.PLAIN_TAG) {
            value = resolvePlainTag(term.value)
            ns = "tag"
        }
        val area = when (ns) {
            "female", "male" -> {
                value = "$ns:$value"
                "tag"
            }
            "parody" -> "series"
            "category" -> "type"
            else -> ns
        }
        return listOf(area) + order + listOf("$value-$language.nozomi")
    }

    // A tag typed without "female:"/"male:" is looked up in all three groups.
    private val plainTagCache = HashMap<String, String>()

    private suspend fun resolvePlainTag(name: String): String {
        plainTagCache[name]?.let { return it }
        val found = listOf("female:$name", "male:$name", name).firstOrNull { candidate ->
            try {
                fetchNozomi(listOf("tag", "$candidate-all.nozomi"), 0..3)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        } ?: name
        plainTagCache[name] = found
        return found
    }

    // Pasting a hitomi.la link into search
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith("hitomi.la")) return null
        val id = LINK_ID_REGEX.find(url.encodedPath)?.groupValues?.get(1) ?: return null
        return galleryInfo(id).toSManga()
    }

    // ------------------------------------------------------------ Lists

    private class NozomiPage(val ids: IntArray, val total: Long)

    /** Downloads (a part of) a .nozomi file: a list of 4-byte work numbers, newest first. */
    private suspend fun fetchNozomi(segments: List<String>, range: IntRange?): NozomiPage {
        val url = Api.LTN.toHttpUrl().newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
        val requestHeaders = headers.newBuilder().apply {
            if (range != null) set("Range", "bytes=${range.first}-${range.last}")
        }.build()
        return client.get(url, requestHeaders, Fetch.XHR).use { response ->
            val bytes = response.body.bytes()
            val buffer = ByteBuffer.wrap(bytes) // big-endian, as hitomi.la writes it
            val ids = IntArray(bytes.size / 4) { buffer.getInt(it * 4) }
            val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.div(4)
                ?: ids.size.toLong()
            NozomiPage(ids, total)
        }
    }

    private suspend fun pagedList(segments: List<String>, page: Int): MangasPage {
        val start = (page - 1) * PAGE_SIZE * 4
        val result = try {
            fetchNozomi(segments, start until start + PAGE_SIZE * 4)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (page > 1) return MangasPage(emptyList(), false) // past the end of the list
            throw e
        }
        return MangasPage(blocks(result.ids.toList()), page * PAGE_SIZE < result.total)
    }

    /** The cards shown in lists: one small snippet per work. */
    private suspend fun blocks(ids: List<Int>): List<SManga> = coroutineScope {
        ids.map { id ->
            async {
                try {
                    val html = client.get("${Api.LTN}/galleryblock/$id.html", Fetch.XHR).use { it.body.string() }
                    parseBlock(id, html)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SManga.create().apply {
                        url = id.toString()
                        title = "#$id"
                    }
                }
            }
        }.awaitAll()
    }

    private fun parseBlock(id: Int, html: String): SManga {
        val doc = Jsoup.parse(html)
        return SManga.create().apply {
            url = id.toString()
            title = doc.selectFirst("h1.lillie a")?.text()?.takeIf { it.isNotBlank() } ?: "#$id"
            thumbnail_url = doc.selectFirst(".dj-img1 img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("//")) "https:$it" else it }

            // Language and artist are on the card; they let the app's migration pick the right language.
            val language = doc.selectFirst("a[href^=/index-]")?.attr("href")
                ?.removePrefix("/index-")?.removeSuffix(".html")?.takeIf { it.isNotBlank() }
            val artists = doc.select(".artist-list a").map { it.text().trim() }.filter { it.isNotEmpty() }
            artist = artists.joinToString().ifEmpty { null }
            genre = buildList {
                language?.let { add("language: $it") }
                artists.forEach { add("artist: $it") }
            }.joinToString().ifEmpty { null }
        }
    }

    // ------------------------------------------------------------ Title word search (B-tree index)

    private var indexVersion: Pair<Long, String>? = null

    private suspend fun galleriesIndexVersion(): String {
        indexVersion?.let { if (System.currentTimeMillis() - it.first < 10 * 60_000L) return it.second }
        val version = client.get(
            "${Api.LTN}/galleriesindex/version?_=${System.currentTimeMillis()}",
            Fetch.XHR,
            CacheControl.FORCE_NETWORK,
        ).use { it.body.string().trim() }
        indexVersion = System.currentTimeMillis() to version
        return version
    }

    private suspend fun fetchRange(url: String, from: Long, to: Long): ByteArray {
        val requestHeaders = headers.newBuilder().set("Range", "bytes=$from-$to").build()
        return client.get(url, requestHeaders, Fetch.XHR).use { it.body.bytes() }
    }

    private class Node(val keys: List<ByteArray>, val datas: List<Pair<Long, Int>>, val subnodes: List<Long>)

    private fun decodeNode(data: ByteArray): Node {
        val buffer = ByteBuffer.wrap(data)
        val keyCount = buffer.getInt()
        val keys = List(keyCount) {
            val size = buffer.getInt()
            require(size in 1..32) { "bad index node" }
            ByteArray(size).also { buffer.get(it) }
        }
        val dataCount = buffer.getInt()
        val datas = List(dataCount) {
            val offset = buffer.getLong()
            val length = buffer.getInt()
            offset to length
        }
        val subnodes = List(17) { buffer.getLong() }
        return Node(keys, datas, subnodes)
    }

    private fun compareKeys(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /** Every work number whose title contains this word, or null if the index has no such word. */
    private suspend fun searchTitleWord(word: String): IntArray? {
        val clean = word.replace("/", "").replace("#", "").lowercase()
        if (clean.isEmpty()) return null
        val key = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)
        val version = galleriesIndexVersion()
        val indexUrl = "${Api.LTN}/galleriesindex/galleries.$version.index"

        var address = 0L
        var found: Pair<Long, Int>? = null
        for (depth in 0 until 40) {
            val node = decodeNode(fetchRange(indexUrl, address, address + 463))
            if (node.keys.isEmpty()) break
            var i = 0
            var cmp = -1
            while (i < node.keys.size) {
                cmp = compareKeys(key, node.keys[i])
                if (cmp <= 0) break
                i++
            }
            if (cmp == 0 && i < node.keys.size) {
                found = node.datas[i]
                break
            }
            if (node.subnodes.all { it == 0L }) break
            val next = node.subnodes[i]
            if (next == 0L) break
            address = next
        }
        val (offset, length) = found ?: return null
        if (length <= 0 || length > 100_000_000) return null

        val data = fetchRange("${Api.LTN}/galleriesindex/galleries.$version.data", offset, offset + length - 1)
        val buffer = ByteBuffer.wrap(data)
        val count = buffer.getInt()
        if (count <= 0 || data.size != count * 4 + 4) return null
        return IntArray(count) { buffer.getInt() }
    }

    // ------------------------------------------------------------ Details, chapter, pages

    private suspend fun galleryInfo(id: String): JsonObject {
        val text = client.get("${Api.LTN}/galleries/$id.js", Fetch.SCRIPT).use { it.body.string() }
        val json = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
        return jsonInstance.parseToJsonElement(json).jsonObject
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val info = galleryInfo(manga.url)
        return SMangaUpdate(info.toSManga(), listOf(info.toSChapter()))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val info = galleryInfo(chapter.url)
        val gg = gg()
        return info.files().mapIndexed { index, file -> Page(index, imageUrl = gg.imageUrl(file.hash)) }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/galleries/${manga.url}.html"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/galleries/${chapter.url}.html"

    // gg.js decides which server a picture is on, and changes now and then, so it is re-read every few minutes.
    private class Gg(val base: String, val zeroCases: Set<Int>, val default: Int, val assigned: Int) {
        fun imageUrl(hash: String): String {
            val last = hash.takeLast(3)
            val g = (last.substring(2) + last.substring(0, 2)).toInt(16)
            val o = if (g in zeroCases) assigned else default
            return "https://w${1 + o}.${Api.DOMAIN}/$base$g/$hash.webp"
        }
    }

    private var ggCache: Pair<Long, Gg>? = null

    private suspend fun gg(): Gg {
        ggCache?.let { if (System.currentTimeMillis() - it.first < 5 * 60_000L) return it.second }
        val text = client.get("${Api.LTN}/gg.js", Fetch.SCRIPT, CacheControl.FORCE_NETWORK).use { it.body.string() }
        val base = Regex("""b:\s*'([^']*)'""").find(text)?.groupValues?.get(1) ?: error("gg.js: no base path")
        val cases = Regex("""case (\d+):""").findAll(text).map { it.groupValues[1].toInt() }.toSet()
        val default = Regex("""var o = (\d)""").find(text)?.groupValues?.get(1)?.toInt() ?: 1
        val assigned = Regex("""o = (\d);\s*break""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        return Gg(base, cases, default, assigned).also { ggCache = System.currentTimeMillis() to it }
    }

    // ------------------------------------------------------------ Filters

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        LanguageFilter(),
        TypeFilter(),
        Filter.Separator(),
        Filter.Header("Separate several entries with commas. Start one with - to exclude it."),
        Filter.Header("Tags: write female:big breasts or male:... (plain names are looked up automatically)."),
        TermFilter("Tags", "tag"),
        TermFilter("Artists", "artist"),
        TermFilter("Groups", "group"),
        TermFilter("Series", "series"),
        TermFilter("Characters", "character"),
    )

    private class SortFilter : Filter.Select<String>("Sort by", SORT_LABELS)

    private class LanguageFilter : Filter.Select<String>("Language", LANGUAGE_LABELS)

    private class TypeFilter : Filter.Select<String>("Type", TYPE_LABELS)

    private class TermFilter(name: String, val key: String) : Filter.Text(name)

    // ------------------------------------------------------------ Reading the work info

    private class FileInfo(val hash: String)

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

    private fun JsonObject.names(key: String, field: String): List<String> = (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.str(field)?.trim()?.takeIf { s -> s.isNotEmpty() } }
        ?: emptyList()

    private fun JsonObject.files(): List<FileInfo> = (this["files"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.str("hash")?.takeIf { h -> h.length >= 3 }?.let(::FileInfo) }
        ?: emptyList()

    private fun JsonObject.toSManga(): SManga {
        val info = this
        return SManga.create().apply {
            val id = info.str("id").orEmpty()
            url = id
            title = info.str("title")?.takeIf { it.isNotBlank() } ?: "Untitled #$id"

            info.files().firstOrNull()?.hash?.let { hash ->
                thumbnail_url = "${Api.TN}/webpbigtn/${hash.last()}/${hash.takeLast(3).take(2)}/$hash.webp"
            }

            val artists = info.names("artists", "artist")
            val groups = info.names("groups", "group")
            artist = artists.joinToString().ifEmpty { null }
            author = groups.ifEmpty { artists }.joinToString().ifEmpty { null }

            // Same style as the nhentai source so the library's tag search works across both:
            // plain tags stay plain, everything else gets a "type: " prefix.
            val genres = buildList {
                addAll(info.names("tags", "tag"))
                info.names("parodys", "parody").forEach { add("parody: $it") }
                info.names("characters", "character").forEach { add("character: $it") }
                artists.forEach { add("artist: $it") }
                groups.forEach { add("group: $it") }
                info.str("language")?.takeIf { it.isNotBlank() }?.let { add("language: $it") }
                info.str("type")?.takeIf { it.isNotBlank() }?.let { add("category: $it") }
            }.distinct()
            genre = genres.joinToString().ifEmpty { null }

            description = buildString {
                append("#").append(id)
                append("\nPages: ").append(info.files().size)
                info.str("type")?.let { append("\nType: ").append(it) }
                info.str("language")?.let { append("\nLanguage: ").append(it) }
                info.str("japanese_title")?.takeIf { it.isNotBlank() }?.let { append("\nJapanese: ").append(it) }
            }
            status = SManga.COMPLETED
        }
    }

    private fun JsonObject.toSChapter(): SChapter {
        val info = this
        return SChapter.create().apply {
            url = info.str("id").orEmpty()
            name = "Chapter"
            chapter_number = 1f
            date_upload = parseDate(info.str("date"))
        }
    }

    private fun parseDate(text: String?): Long = try {
        text?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ssX", Locale.US).parse(it)?.time } ?: 0L
    } catch (_: Exception) {
        0L
    }

    /**
     * Every web address this source uses lives here. If hitomi.la changes something, this is the only
     * place to edit. All of it was taken from the site's own scripts and requests.
     */
    private object Api {
        const val DOMAIN = "gold-usergeneratedcontent.net"
        const val LTN = "https://ltn.$DOMAIN" // lists, work info, gg.js, search index
        const val TN = "https://tn.$DOMAIN" // thumbnails

        // home page list = index-all.nozomi, one language = index-<language>.nozomi
        fun indexName(language: String) = "index-$language.nozomi"
    }

    companion object {
        private const val PAGE_SIZE = 25

        private val BRACKETS_REGEX = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*}""")

        private val KNOWN_NAMESPACES = setOf(
            "female", "male", "tag", "artist", "group", "series", "parody", "character", "type", "category", "language",
        )

        private val ID_REGEX = Regex("""^(?:id:|#)?(\d{1,9})$""")
        private val LINK_ID_REGEX = Regex("""(\d+)\.html$""")

        private val SORT_LABELS = arrayOf("Recent", "Popular: today", "Popular: this week", "Popular: this month", "Popular: this year")
        private val SORT_VALUES = arrayOf("", "today", "week", "month", "year")

        private val LANGUAGE_LABELS = arrayOf(
            "All", "Japanese", "English", "Chinese", "Korean", "Spanish", "French", "German", "Russian",
            "Italian", "Portuguese", "Thai", "Vietnamese", "Indonesian", "Polish",
        )
        private val LANGUAGE_VALUES = arrayOf(
            "all", "japanese", "english", "chinese", "korean", "spanish", "french", "german", "russian",
            "italian", "portuguese", "thai", "vietnamese", "indonesian", "polish",
        )

        private val TYPE_LABELS = arrayOf("Any", "Doujinshi", "Manga", "Artist CG", "Game CG", "Image set", "Anime")
        private val TYPE_VALUES = arrayOf("", "doujinshi", "manga", "artistcg", "gamecg", "imageset", "anime")
    }
}
