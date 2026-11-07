package eu.kanade.tachiyomi.extension.all.hxtnhentai

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// GET /api/v2/config
@Serializable
class ConfigDto(
    @SerialName("image_servers") val imageServers: List<String> = emptyList(),
    @SerialName("thumb_servers") val thumbServers: List<String> = emptyList(),
)

// GET /api/v2/tags/{type}/{slug}
@Serializable
class TagLookupDto(
    val id: Int,
)

// ---------------------------------------------------------------- lists

// A page of works (browse / search / tagged). Entries only carry tag NUMBERS, not names;
// the full tags are fetched when a work is opened or added to the library.
@Serializable
class GalleryListDto(
    private val result: List<GalleryListItemDto>,
    @SerialName("num_pages") private val numPages: Int,
) {
    fun toMangasPage(page: Int, thumbHost: String) = MangasPage(result.map { it.toSManga(thumbHost) }, page < numPages)
}

// id is read as a primitive so both numbers and strings are accepted.
@Serializable
class GalleryListItemDto(
    private val id: JsonPrimitive,
    @SerialName("english_title") private val englishTitle: String? = null,
    @SerialName("japanese_title") private val japaneseTitle: String? = null,
    private val thumbnail: String? = null,
) {
    fun toSManga(thumbHost: String) = SManga.create().apply {
        url = id.content
        title = listOf(englishTitle, japaneseTitle).firstOrNull { !it.isNullOrBlank() } ?: "#${id.content}"
        thumbnail_url = thumbnail?.let { "$thumbHost/$it" }
    }
}

// ---------------------------------------------------------------- one work

// GET /api/v2/galleries/{id}
@Serializable
class GalleryDto(
    private val id: JsonPrimitive,
    @SerialName("title") private val names: TitleDto,
    private val cover: PathDto? = null,
    private val tags: List<TagDto> = emptyList(),
    private val pages: List<PathDto> = emptyList(),
    @SerialName("num_pages") private val numPages: Int = 0,
    @SerialName("num_favorites") private val numFavorites: Int = 0,
    @SerialName("upload_date") private val uploadDate: Long = 0L,
) {
    private val galleryId get() = id.content

    fun toSManga(thumbHost: String) = SManga.create().apply {
        url = galleryId
        title = names.display().ifBlank { "Untitled #$galleryId" }
        thumbnail_url = cover?.let { "$thumbHost/${it.path}" }

        val artists = namesOf("artist")
        val groups = namesOf("group")
        artist = artists.joinToString().ifEmpty { null }
        author = groups.ifEmpty { artists }.joinToString().ifEmpty { null }

        genre = genres().joinToString().ifEmpty { null }
        description = buildString {
            append("#").append(galleryId)
            append("\nPages: ").append(if (pages.isNotEmpty()) pages.size else numPages)
            append("\nFavorites: ").append(numFavorites)
            names.english?.takeIf { it.isNotBlank() }?.let { append("\nEnglish: ").append(it) }
            names.japanese?.takeIf { it.isNotBlank() }?.let { append("\nJapanese: ").append(it) }
        }
        status = SManga.COMPLETED
    }

    // a doujinshi is a single chapter
    fun toSChapter() = SChapter.create().apply {
        url = galleryId
        name = "Chapter"
        chapter_number = 1f
        date_upload = uploadDate * 1000L
    }

    // Image paths come ready-made from the API (e.g. "galleries/4298545/1.webp");
    // the image servers rotate between the hosts listed in /api/v2/config.
    fun toPageList(imageHosts: List<String>): List<Page> = pages.mapIndexed { index, page ->
        Page(index, imageUrl = "${imageHosts[index % imageHosts.size]}/${page.path}")
    }

    private fun namesOf(type: String) = tags
        .filter { it.type == type }
        .map { it.name.trim() }
        .filter { it.isNotEmpty() }

    // Plain tags stay as they are; everything else gets a "type: " prefix so the
    // library's tag filter can find artists, groups, parodies, characters, etc.
    private fun genres(): List<String> = tags
        .mapNotNull { tag ->
            val value = tag.name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when (tag.type) {
                "tag" -> value
                "artist", "group", "parody", "character", "language", "category" -> "${tag.type}: $value"
                else -> null
            }
        }
        .distinct()
}

@Serializable
class TitleDto(
    val english: String? = null,
    val japanese: String? = null,
    private val pretty: String? = null,
) {
    fun display() = listOf(pretty, english, japanese).firstOrNull { !it.isNullOrBlank() }.orEmpty()
}

@Serializable
class PathDto(
    val path: String,
)

@Serializable
class TagDto(
    val type: String = "",
    val name: String = "",
)
