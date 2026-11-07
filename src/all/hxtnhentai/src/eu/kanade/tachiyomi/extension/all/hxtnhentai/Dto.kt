package eu.kanade.tachiyomi.extension.all.hxtnhentai

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// If nhentai changes its image hosts, edit these two and rebuild.
private const val IMAGE_HOST = "https://i.nhentai.net"
private const val THUMB_HOST = "https://t.nhentai.net"

@Serializable
class GalleryListDto(
    private val result: List<GalleryDto>,
    @SerialName("num_pages") private val numPages: Int,
) {
    fun toMangasPage(page: Int) = MangasPage(result.map { it.toSManga() }, page < numPages)
}

// id and media_id are read as primitives so both numbers and strings are accepted.
@Serializable
class GalleryDto(
    private val id: JsonPrimitive,
    @SerialName("media_id") private val mediaId: JsonPrimitive,
    @SerialName("title") private val names: TitleDto,
    private val images: ImagesDto,
    private val tags: List<TagDto> = emptyList(),
    @SerialName("num_pages") private val numPages: Int = 0,
    @SerialName("num_favorites") private val numFavorites: Int = 0,
    @SerialName("upload_date") private val uploadDate: Long = 0L,
) {
    private val galleryId get() = id.content

    fun toSManga() = SManga.create().apply {
        url = galleryId
        title = names.display().ifBlank { "Untitled #$galleryId" }
        thumbnail_url = "$THUMB_HOST/galleries/${mediaId.content}/cover.${extensionOf(images.cover?.type)}"

        val artists = namesOf("artist")
        val groups = namesOf("group")
        artist = artists.joinToString().ifEmpty { null }
        author = groups.ifEmpty { artists }.joinToString().ifEmpty { null }

        genre = genres().joinToString().ifEmpty { null }
        description = buildString {
            append("#").append(galleryId)
            append("\nPages: ").append(numPages)
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

    fun toPageList(): List<Page> {
        val media = mediaId.content
        return images.pages.mapIndexed { index, image ->
            Page(index, imageUrl = "$IMAGE_HOST/galleries/$media/${index + 1}.${extensionOf(image.type)}")
        }
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

    private fun extensionOf(type: String?) = when (type) {
        "p" -> "png"
        "g" -> "gif"
        "w" -> "webp"
        "a" -> "avif"
        else -> "jpg"
    }
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
class ImagesDto(
    val pages: List<ImageDto> = emptyList(),
    val cover: ImageDto? = null,
)

@Serializable
class ImageDto(
    @SerialName("t") val type: String = "j",
)

@Serializable
class TagDto(
    val type: String = "",
    val name: String = "",
)
