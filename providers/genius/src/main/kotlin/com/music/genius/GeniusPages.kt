package com.music.genius

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// a piece of a note text and its url when its a link
data class GeniusTextPart(val text: String, val url: String?)

// what a note is made of text quotes images videos
sealed interface GeniusBlock {
    data class Paragraph(val parts: List<GeniusTextPart>) : GeniusBlock {
        val text: String get() = parts.joinToString("") { it.text }.trim()
    }
    data class Quote(val text: String) : GeniusBlock
    data class Image(val url: String, val caption: String?) : GeniusBlock
    data class Video(val url: String, val id: String) : GeniusBlock
}

// a genius note on a lyric line
data class GeniusNote(
    val id: Long,
    // genius links a noted bit by its annotation id on the page and by its referent id in the api so both find the note
    val annotationId: Long,
    val lyric: String,
    val note: String,
    val blocks: List<GeniusBlock>,
)

data class GeniusTrack(val number: Int?, val title: String, val artist: String, val current: Boolean)

data class GeniusAlbum(
    val name: String,
    val artist: String,
    val coverUrl: String?,
    val releaseDate: String?,
    val about: String?,
    val tracks: List<GeniusTrack>,
)

// piece of the lyrics the note id is set when genius has a note on it
data class GeniusLyricPiece(val text: String, val noteId: Long?)

data class GeniusCredit(val role: String, val names: List<String>)

// samples covered by and so on with the songs on the other side
data class GeniusRelation(val kind: String, val songs: List<String>)

data class GeniusLink(val label: String, val url: String)

// picture or video of the song and the thumb is the list preview
data class GeniusMedia(val label: String, val url: String, val thumb: String?, val isVideo: Boolean)

// everything on a songs genius page and missing stuff stays null or empty
data class GeniusDetails(
    val title: String,
    val artist: String,
    val featured: List<String>,
    val about: String?,
    val album: String?,
    val coverUrl: String?,
    val albumInfo: GeniusAlbum?,
    val releaseDate: String?,
    val recordedAt: String?,
    val writers: List<String>,
    val producers: List<String>,
    val genres: List<String>,
    val credits: List<GeniusCredit>,
    val relations: List<GeniusRelation>,
    val lyrics: List<GeniusLyricPiece>,
    val notes: List<GeniusNote>,
    val media: List<GeniusMedia>,
    val noteCount: Int?,
    val pageUrl: String,
    val links: List<GeniusLink>,
)

sealed interface GeniusPage {
    data class Found(val details: GeniusDetails) : GeniusPage
    // genius has nothing for this song
    data object Missing : GeniusPage
    // request failed (offline or blocked) a retry might work
    data object Failed : GeniusPage
}

// full song page and the same keyless web api genius uses for the short facts
object GeniusPages {
    private const val BrowserAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val cache = ConcurrentHashMap<String, GeniusDetails>()

    private val syncedCache = ConcurrentHashMap<String, List<Pair<Long, String>>>()

    // timed lines of the song (ms to text) from lrclib for following the lyrics when the app has none of its own
    suspend fun syncedLyrics(title: String, artist: String): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val key = "${title.lowercase()}|${artist.lowercase()}"
        syncedCache[key]?.let { return@withContext it }
        val lead = artist.substringBefore(",").substringBefore("&").trim()
        val lines = runCatching {
            fun fetch(url: String) = Jsoup.connect(url).ignoreContentType(true).userAgent("Mochy Music").timeout(10_000).execute().body()
            fun JsonObject.synced() = (this["syncedLyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val t = URLEncoder.encode(title, "UTF-8")
            val a = URLEncoder.encode(lead, "UTF-8")
            val raw = runCatching { (Json.parseToJsonElement(fetch("https://lrclib.net/api/get?track_name=$t&artist_name=$a")) as? JsonObject)?.synced() }.getOrNull()
                ?: (Json.parseToJsonElement(fetch("https://lrclib.net/api/search?track_name=$t&artist_name=$a")) as? JsonArray)
                    .orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.synced() }
            val stamp = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]\\s*(.*)$")
            raw.orEmpty().lines().mapNotNull { line ->
                val m = stamp.find(line.trim()) ?: return@mapNotNull null
                val (min, sec, frac, text) = m.destructured
                val ms = min.toLong() * 60_000 + sec.toLong() * 1000 + (frac.takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L)
                if (text.isBlank()) null else ms to text
            }
        }.getOrDefault(emptyList())
        if (lines.isNotEmpty()) syncedCache[key] = lines
        lines
    }

    // whatever load already fetched and no new request
    fun peek(title: String, artist: String): GeniusDetails? = cache["${title.lowercase()}|${artist.lowercase()}"]

    // title and channel of a youtube video for the embed card
    suspend fun videoInfo(id: String): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            with(Genius) {
                get("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$id&format=json")?.let { it.string("title") to it.string("author_name") }
            }
        }.getOrNull()
    }

    suspend fun load(title: String, artist: String): GeniusPage = withContext(Dispatchers.IO) {
        val key = "${title.lowercase()}|${artist.lowercase()}"
        cache[key]?.let { return@withContext GeniusPage.Found(it) }
        runCatching { read(title, artist) }
            .onFailure { Timber.tag(Genius.Tag).w(it, "couldn't read the page of $title by $artist") }
            .getOrDefault(GeniusPage.Failed)
            .also {
                // a song that should have notes but came back without any is not kept so the next open tries again
                if (it is GeniusPage.Found && !((it.details.noteCount ?: 0) > 0 && it.details.notes.isEmpty())) cache[key] = it.details
            }
    }

    // the title without feat parts and version tails
    private fun cleanTitle(t: String) = t.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), "").trim().ifBlank { t }

    // a request that gets a second try
    private fun <T> retry(block: () -> T?): T? {
        repeat(2) { block()?.let { return it } }
        return null
    }

    private fun read(title: String, artist: String): GeniusPage = with(Genius) {
        val leadArtist = artist.substringBefore(",").substringBefore("&").trim()
        val short = cleanTitle(title)
        // the full title first then the cleaned one then the title alone so a feat tail cant hide the song
        val queries = listOf("$leadArtist $title", "$leadArtist $short", short).distinct()
        var best: JsonObject? = null
        var reached = false
        for (q in queries) {
            val search = retry { get("${Genius.Api}/search/multi?per_page=5&q=${URLEncoder.encode(q, "UTF-8")}") } ?: continue
            reached = true
            val hits = (search.obj("response")?.get("sections") as? JsonArray).orEmpty()
                .filter { (it as? JsonObject)?.string("type") == "song" }
                .flatMap { ((it as JsonObject)["hits"] as? JsonArray).orEmpty() }
                .mapNotNull { (it as? JsonObject)?.obj("result") }
            best = hits.map { it to maxOf(score(it, title, leadArtist), score(it, short, leadArtist)) }
                .filter { it.second >= Genius.GoodMatch }
                .maxByOrNull { it.second }?.first
            if (best != null) break
        }
        val match = best ?: return if (reached) GeniusPage.Missing else GeniusPage.Failed
        val id = (match["id"] as? JsonPrimitive)?.longOrNull ?: return GeniusPage.Missing
        val song = retry { get("${Genius.Api}/songs/$id?text_format=plain")?.obj("response")?.obj("song") } ?: return GeniusPage.Failed
        // notes and lyrics are separate calls and the song still shows without them
        val referents = runCatching { readReferents(id) }.getOrNull().orEmpty()
        val notes = parseNotes(referents)
        var lyrics = emptyList<GeniusLyricPiece>()
        // the lyrics page fails once in a while so it gets a second try
        for (attempt in 1..2) {
            lyrics = runCatching { readLyrics(song.string("url").orEmpty()) }
                .onFailure { Timber.tag(Genius.Tag).w(it, "couldn't read the lyrics page") }
                .getOrDefault(emptyList())
            if (lyrics.any { it.text.isNotBlank() }) break
        }
        if (lyrics.none { it.text.isNotBlank() }) {
            // genius page didnt load (blocked or changed) so the full lyrics come from lrclib
            // the noted lines are found in them so every line shows and the noted ones get marked
            val plain = runCatching {
                readFallbackLyrics(title, leadArtist)
                    ?: readFallbackLyrics(short, leadArtist)
                    ?: readFallbackLyrics(song.string("title").orEmpty(), song.obj("primary_artist")?.string("name").orEmpty())
            }.onFailure { Timber.tag(Genius.Tag).w(it, "couldn't read the fallback lyrics") }.getOrNull()
            if (!plain.isNullOrBlank()) lyrics = tidy(listOf(GeniusLyricPiece(plain, null)))
        }
        // whatever note the page didnt mark (ids that dont line up or a changed page) is found by its text
        lyrics = attachMissingNotes(lyrics, notes)
        val album = runCatching { readAlbum(song, id) }.getOrNull()
        GeniusPage.Found(readDetails(song, notes, lyrics, album))
    }

    // every page of the song notes and genius gives at most 50 at a time
    private fun readReferents(songId: Long): List<JsonElement> = with(Genius) {
        val all = mutableListOf<JsonElement>()
        val seen = mutableSetOf<Long>()
        for (page in 1..12) {
            val response = retry { get("${Genius.Api}/referents?song_id=$songId&text_format=html,plain&per_page=50&page=$page")?.obj("response") } ?: break
            val batch = (response["referents"] as? JsonArray).orEmpty()
            val fresh = batch.filter { (it as? JsonObject)?.number("id")?.let(seen::add) == true }
            if (fresh.isEmpty()) break
            all += fresh
            val next = (response["next_page"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (next == null && batch.size < 50) break
        }
        all
    }

    // the album page of genius with cover and artist and date and about and the tracklist where current marks this song
    private fun readAlbum(song: JsonObject, songId: Long): GeniusAlbum? = with(Genius) {
        val brief = song.obj("album") ?: return null
        val albumId = (brief["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val full = runCatching { get("${Genius.Api}/albums/$albumId?text_format=plain")?.obj("response")?.obj("album") }.getOrNull() ?: brief
        val tracks = (get("${Genius.Api}/albums/$albumId/tracks?per_page=50")?.obj("response")?.get("tracks") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.mapNotNull { t ->
                val track = t.obj("song") ?: return@mapNotNull null
                GeniusTrack(
                    number = (t["number"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                    title = track.string("title") ?: return@mapNotNull null,
                    artist = track.obj("primary_artist")?.string("name").orEmpty(),
                    current = (track["id"] as? JsonPrimitive)?.longOrNull == songId,
                )
            }
        if (tracks.isEmpty()) return null
        GeniusAlbum(
            name = full.string("name") ?: brief.string("name").orEmpty(),
            artist = full.obj("artist")?.string("name").orEmpty(),
            coverUrl = full.string("cover_art_url") ?: brief.string("cover_art_url"),
            releaseDate = full.string("release_date_for_display") ?: full.string("release_date"),
            about = full.obj("description")?.string("plain")?.takeIf { it.isNotBlank() && it != "?" },
            tracks = tracks,
        )
    }

    // lyrics only exist on the web page and noted bits are links so each line keeps its note
    private fun readLyrics(pageUrl: String): List<GeniusLyricPiece> {
        if (pageUrl.isBlank()) return emptyList()
        val doc = Jsoup.connect(pageUrl).userAgent(BrowserAgent).timeout(12_000).get()
        val pieces = mutableListOf<GeniusLyricPiece>()
        val containers = doc.select("div[data-lyrics-container=true]").ifEmpty { doc.select("div[class^=Lyrics__Container]") }
        containers.forEachIndexed { i, container ->
            if (i > 0) pieces += GeniusLyricPiece("\n", null)
            walk(container, null, pieces)
        }
        return tidy(pieces)
    }

    // full plain lyrics from lrclib and null if it has nothing
    private fun readFallbackLyrics(title: String, artist: String): String? {
        if (title.isBlank()) return null
        fun fetch(url: String) = Jsoup.connect(url).ignoreContentType(true).userAgent("Mochy Music").timeout(10_000).execute().body()
        fun JsonObject.lyricsText(): String? {
            (this["plainLyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            // only a synced one exists so the timestamps get stripped
            return (this["syncedLyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?.lines()?.joinToString("\n") { it.replace(Regex("^\\[[^\\]]*\\]\\s*"), "") }
        }
        val t = URLEncoder.encode(title, "UTF-8")
        val a = URLEncoder.encode(artist, "UTF-8")
        runCatching { (Json.parseToJsonElement(fetch("https://lrclib.net/api/get?track_name=$t&artist_name=$a")) as? JsonObject)?.lyricsText() }
            .getOrNull()?.let { return it }
        val found = Json.parseToJsonElement(fetch("https://lrclib.net/api/search?track_name=$t&artist_name=$a")) as? JsonArray
        return found.orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.lyricsText() }
    }

    // how alike two letter strings are from 0 to 1 by the letter pairs they share
    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        if (a == b) return 1.0
        val grams = HashMap<String, Int>()
        for (i in 0 until a.length - 1) grams.merge(a.substring(i, i + 2), 1, Int::plus)
        var hit = 0
        for (i in 0 until b.length - 1) {
            val g = b.substring(i, i + 2)
            val left = grams[g] ?: 0
            if (left > 0) {
                grams[g] = left - 1
                hit++
            }
        }
        return 2.0 * hit / ((a.length - 1) + (b.length - 1))
    }

    // notes the page didnt mark are found in the lyrics by their letters only so spacing and punctuation dont matter
    // and only on text no other note has taken. what still has no place is matched by likeness to whole lines
    // because lrclib words some lines a little different from genius
    private fun attachMissingNotes(pieces: List<GeniusLyricPiece>, notes: List<GeniusNote>): List<GeniusLyricPiece> {
        if (pieces.isEmpty() || notes.isEmpty()) return pieces
        val present = pieces.mapNotNull { it.noteId }.toSet()
        val missing = notes.filter { it.id !in present && it.annotationId !in present }
        if (missing.isEmpty()) return pieces
        val text = pieces.joinToString("") { it.text }
        val owner = arrayOfNulls<Long>(text.length)
        var at = 0
        for (p in pieces) {
            for (k in at until at + p.text.length) owner[k] = p.noteId
            at += p.text.length
        }
        val letters = StringBuilder()
        val origin = ArrayList<Int>()
        text.forEachIndexed { i, c -> if (c.isLetterOrDigit()) { letters.append(c.lowercaseChar()); origin += i } }
        val leftover = mutableListOf<GeniusNote>()
        for (note in missing) {
            val f = note.lyric.filter { it.isLetterOrDigit() }.lowercase()
            if (f.length < 3) continue
            var from = 0
            var placed = false
            while (true) {
                val found = letters.indexOf(f, from)
                if (found < 0) break
                val first = origin[found]
                val last = origin[found + f.length - 1]
                if ((first..last).none { owner[it] != null }) {
                    for (k in first..last) owner[k] = note.id
                    placed = true
                    break
                }
                from = found + 1
            }
            if (!placed) leftover += note
        }
        if (leftover.isNotEmpty()) {
            // line starts and ends and the letters of each line
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            var pos = 0
            for (line in text.split("\n")) {
                starts += pos
                ends += pos + line.length
                pos += line.length + 1
            }
            val lineLetters = starts.indices.map { k -> text.substring(starts[k], ends[k]).filter { it.isLetterOrDigit() }.lowercase() }
            val isHeader = starts.indices.map { k -> text.substring(starts[k], ends[k]).trim().startsWith("[") }
            for (note in leftover) {
                val f = note.lyric.filter { it.isLetterOrDigit() }.lowercase()
                if (f.length < 8) continue
                var bestScore = 0.0
                var bestFrom = -1
                var bestTo = -1
                for (s in starts.indices) {
                    if (isHeader[s] || lineLetters[s].isEmpty()) continue
                    val window = StringBuilder()
                    var e = s
                    while (e < starts.size && e - s < 10 && !isHeader[e]) {
                        window.append(lineLetters[e])
                        if (window.length > f.length * 1.5) break
                        if (window.length >= f.length * 0.6 && lineLetters[e].isNotEmpty()) {
                            val sc = dice(f, window.toString())
                            if (sc > bestScore && (starts[s] until ends[e]).none { owner[it] != null }) {
                                bestScore = sc
                                bestFrom = s
                                bestTo = e
                            }
                        }
                        e++
                    }
                }
                if (bestScore >= 0.72 && bestFrom >= 0) {
                    for (k in starts[bestFrom] until ends[bestTo]) owner[k] = note.id
                }
            }
        }
        val out = mutableListOf<GeniusLyricPiece>()
        var start = 0
        for (i in 1..text.length) {
            if (i == text.length || owner[i] != owner[start]) {
                out += GeniusLyricPiece(text.substring(start, i), owner[start])
                start = i
            }
        }
        return out
    }

    private fun walk(node: Node, noteId: Long?, out: MutableList<GeniusLyricPiece>) {
        when (node) {
            is TextNode -> if (node.wholeText.isNotEmpty()) out += GeniusLyricPiece(node.wholeText, noteId)
            is Element -> {
                if (node.hasAttr("data-exclude-from-selection")) return
                if (node.tagName() == "br") {
                    out += GeniusLyricPiece("\n", null)
                    return
                }
                // noted bits link to /12345/artist-song/lyric (the annotation id) and carry the referent id as data-id
                val own = if (node.tagName() == "a") {
                    node.attr("data-id").toLongOrNull()
                        ?: Regex("(?:^|/)(\\d+)(?:/|#|$)").find(node.attr("href"))?.groupValues?.get(1)?.toLongOrNull()
                } else null
                node.childNodes().forEach { walk(it, own ?: noteId, out) }
            }
        }
    }

    // merge neighbours with the same note and keep one blank line at most and trim the ends
    private fun tidy(raw: List<GeniusLyricPiece>): List<GeniusLyricPiece> {
        val merged = mutableListOf<GeniusLyricPiece>()
        for (p in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.noteId == p.noteId) merged[merged.lastIndex] = last.copy(text = last.text + p.text) else merged += p
        }
        val cleaned = merged.map { it.copy(text = it.text.replace(Regex("\\n{3,}"), "\n\n")) }.toMutableList()
        if (cleaned.isNotEmpty()) cleaned[0] = cleaned[0].copy(text = cleaned[0].text.trimStart())
        if (cleaned.isNotEmpty()) cleaned[cleaned.lastIndex] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
        return cleaned.filter { it.text.isNotEmpty() }
    }

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    // the api referents (a bit of lyrics) with their first annotation (the note) as notes
    private fun parseNotes(referents: List<JsonElement>): List<GeniusNote> = with(Genius) {
        referents.mapNotNull { it as? JsonObject }.mapNotNull { ref ->
            val id = ref.number("id") ?: return@mapNotNull null
            val lyric = ref.string("fragment") ?: return@mapNotNull null
            val annotation = (ref["annotations"] as? JsonArray).orEmpty().firstOrNull() as? JsonObject ?: return@mapNotNull null
            val body = annotation.obj("body")
            val note = body?.string("plain") ?: return@mapNotNull null
            val blocks = body.string("html")?.let { parseNote(it) }.orEmpty()
                .ifEmpty { listOf(GeniusBlock.Paragraph(listOf(GeniusTextPart(note, null)))) }
            GeniusNote(
                id = id,
                annotationId = annotation.number("id") ?: id,
                lyric = lyric,
                note = note,
                blocks = blocks,
            )
        }
    }

    private fun readDetails(song: JsonObject, notes: List<GeniusNote>, lyrics: List<GeniusLyricPiece>, albumInfo: GeniusAlbum?): GeniusDetails = with(Genius) {
        fun names(array: Any?) = (array as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") }

        val credits = (song["custom_performances"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { p ->
            val role = p.string("label") ?: return@mapNotNull null
            names(p["artists"]).takeIf { it.isNotEmpty() }?.let { GeniusCredit(role, it) }
        }
        val relations = (song["song_relationships"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { r ->
            val songs = (r["songs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .mapNotNull { it.string("full_title") ?: it.string("title") }
            if (songs.isEmpty()) null else GeniusRelation(relationLabel(r.string("relationship_type") ?: r.string("type").orEmpty()), songs.take(12))
        }.filter { it.kind != "Tracklist" }

        val mediaItems = (song["media"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val videos = mediaItems.filter { it.string("type") == "video" || it.string("provider") == "youtube" }.mapNotNull { m ->
            val url = m.string("url") ?: return@mapNotNull null
            val videoId = Regex("(?:v=|youtu\\.be/|embed/)([\\w-]{11})").find(url)?.groupValues?.get(1)
            GeniusMedia("Video", url, videoId?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }, true)
        }
        fun picture(label: String, url: String?) = url?.takeIf { !it.contains("default_avatar") && !it.contains("default_cover") }
            ?.let { GeniusMedia(label, it, it, false) }
        val pictures = listOfNotNull(
            picture("Header", song.string("header_image_url")),
            picture("Cover", song.string("song_art_image_url")),
            picture("Album", song.obj("album")?.string("cover_art_url")),
            picture("Artist", song.obj("primary_artist")?.string("image_url")),
        )
        val links = mediaItems.filter { it.string("type") != "video" && it.string("provider") != "youtube" }.mapNotNull { m ->
            val url = m.string("url") ?: return@mapNotNull null
            GeniusLink(m.string("provider")?.replaceFirstChar(Char::titlecase) ?: "Link", url)
        }.distinctBy { it.url }

        GeniusDetails(
            title = song.string("title").orEmpty(),
            artist = song.obj("primary_artist")?.string("name").orEmpty(),
            featured = names(song["featured_artists"]),
            about = song.obj("description")?.string("plain")?.takeIf { it != "?" },
            album = song.obj("album")?.string("name"),
            coverUrl = song.string("song_art_image_url"),
            albumInfo = albumInfo,
            releaseDate = song.string("release_date"),
            recordedAt = song.string("recording_location"),
            writers = names(song["writer_artists"]),
            producers = names(song["producer_artists"]),
            genres = (song["tags"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { (it["primary"] as? JsonPrimitive)?.contentOrNull == "true" }.mapNotNull { it.string("name") },
            credits = credits,
            relations = relations,
            lyrics = lyrics,
            notes = notes,
            media = (videos + pictures).distinctBy { it.url },
            noteCount = song.number("annotation_count")?.toInt(),
            pageUrl = song.string("url").orEmpty(),
            links = links,
        )
    }

    private fun youtubeId(url: String): String? = Regex("(?:v=|youtu\\.be/|embed/)([\\w-]{11})").find(url)?.groupValues?.get(1)

    // note html to text with links and quotes and images and videos in order
    private fun parseNote(html: String): List<GeniusBlock> {
        val blocks = mutableListOf<GeniusBlock>()
        val parts = mutableListOf<GeniusTextPart>()
        val doc = Jsoup.parseBodyFragment(html, "https://genius.com")
        // an embedded video shows once and a link to the same video stays a normal link
        val embedded = doc.select("iframe").mapNotNull { youtubeId(it.attr("src")) }.toSet()
        val shown = mutableSetOf<String>()
        fun flush() {
            if (parts.any { it.text.isNotBlank() }) blocks += GeniusBlock.Paragraph(parts.toList())
            parts.clear()
        }
        fun walk(node: Node, link: String?) {
            when (node) {
                is TextNode -> if (node.text().isNotEmpty()) parts += GeniusTextPart(node.text(), link)
                is Element -> when (node.tagName()) {
                    "br" -> parts += GeniusTextPart("\n", null)
                    "img" -> {
                        flush()
                        node.absUrl("src").takeIf { it.isNotBlank() }?.let { blocks += GeniusBlock.Image(it, node.attr("alt").takeIf { a -> a.isNotBlank() }) }
                    }
                    "iframe" -> {
                        flush()
                        val src = node.attr("src")
                        youtubeId(src)?.let { if (shown.add(it)) blocks += GeniusBlock.Video("https://www.youtube.com/watch?v=$it", it) }
                    }
                    "blockquote" -> {
                        flush()
                        node.text().takeIf { it.isNotBlank() }?.let { blocks += GeniusBlock.Quote(it) }
                    }
                    "a" -> {
                        val href = node.absUrl("href")
                        val video = youtubeId(href)?.takeIf { it !in embedded && shown.add(it) }
                        if (video != null) {
                            flush()
                            blocks += GeniusBlock.Video("https://www.youtube.com/watch?v=$video", video)
                        } else {
                            node.childNodes().forEach { walk(it, href.takeIf { h -> h.isNotBlank() } ?: link) }
                        }
                    }
                    "p", "div", "li", "ul", "ol", "h1", "h2", "h3" -> {
                        flush()
                        node.childNodes().forEach { walk(it, link) }
                        flush()
                    }
                    else -> node.childNodes().forEach { walk(it, link) }
                }
            }
        }
        doc.body().childNodes().forEach { walk(it, null) }
        flush()
        return blocks
    }

    private fun relationLabel(type: String): String = when (type) {
        "samples" -> "Samples"
        "sampled_in" -> "Sampled in"
        "interpolates" -> "Interpolates"
        "interpolated_by" -> "Interpolated by"
        "cover_of" -> "Cover of"
        "covered_by" -> "Covered by"
        "remix_of" -> "Remix of"
        "remixed_by" -> "Remixed by"
        "live_version_of" -> "Live version of"
        "performed_live_as" -> "Performed live as"
        "translations" -> "Translations"
        else -> type.replace('_', ' ').replaceFirstChar(Char::titlecase).ifBlank { "Related" }
    }
}
