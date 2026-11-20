package com.music.genius

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

// a picture of an artist and where it came from the thumb is the small one for lists
data class ArtistPhoto(val url: String, val thumb: String, val source: String)

// artist pictures from every free place that has them and none needs a key
// deezer and theaudiodb and wikipedia and the genius page each one reports on its own the moment it has something
// so the picker fills up while the slow ones are still looking
object ArtistPhotos {
    private const val Agent = "Mochy (https://github.com/realidkroo/Mochy-Music)"

    private fun norm(text: String) = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun JsonObject.o(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.a(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun fetch(url: String): JsonObject? = runCatching {
        val body = Jsoup.connect(url).ignoreContentType(true).userAgent(Agent).timeout(10_000).execute().body()
        Json.parseToJsonElement(body) as? JsonObject
    }.getOrNull()

    // the same picture from the same host is one picture whatever its query says
    fun sameKey(url: String): String = url.substringBefore('?').lowercase(Locale.ROOT)

    private fun deezer(artist: String): List<ArtistPhoto> {
        val q = URLEncoder.encode(artist, "UTF-8")
        val hits = fetch("https://api.deezer.com/search/artist?limit=4&q=$q")?.a("data").orEmpty().mapNotNull { it as? JsonObject }
        // only the artist with the very same name or it could be somebody else
        val hit = hits.firstOrNull { norm(it.s("name").orEmpty()) == norm(artist) } ?: return emptyList()
        val big = hit.s("picture_xl") ?: hit.s("picture_big") ?: return emptyList()
        // an artist without a picture gets a grey placehloder with an empty hash in its path
        if (big.contains("/images/artist//")) return emptyList()
        return listOf(ArtistPhoto(big, hit.s("picture_medium") ?: big, "Deezer"))
    }

    private fun audioDb(artist: String): List<ArtistPhoto> {
        val q = URLEncoder.encode(artist, "UTF-8")
        // the free test key changed over the years so the new one is tried first and then the old one
        val hit = listOf("123", "2").firstNotNullOfOrNull { key ->
            fetch("https://www.theaudiodb.com/api/v1/json/$key/search.php?s=$q")?.a("artists").orEmpty().firstOrNull() as? JsonObject
        } ?: return emptyList()
        if (norm(hit.s("strArtist").orEmpty()) != norm(artist)) return emptyList()
        return listOf("strArtistThumb", "strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistFanart4", "strArtistWideThumb")
            .mapNotNull { key -> hit.s(key)?.takeIf { it.startsWith("http") } }
            .map { ArtistPhoto(it, "$it/medium", "TheAudioDB") }
    }

    private fun wikipedia(artist: String): List<ArtistPhoto> {
        val q = URLEncoder.encode(artist, "UTF-8")
        val pages = fetch(
            "https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&generator=search&gsrlimit=4&gsrsearch=$q" +
                "&prop=pageimages%7Cdescription&piprop=original%7Cthumbnail&pithumbsize=400",
        )?.o("query")?.o("pages") ?: return emptyList()
        val music = Regex("singer|rapper|band|musician|duo|group|songwriter|producer|composer|vocalist|\\bdj\\b", RegexOption.IGNORE_CASE)
        val hit = pages.values.mapNotNull { it as? JsonObject }
            .sortedBy { (it["index"] as? JsonPrimitive)?.intOrNull ?: 99 }
            .firstOrNull { page ->
                // the title has to be the artist and its short description has to say music so a film or a place with the same name is skipped
                norm(page.s("title").orEmpty().substringBefore("(")) == norm(artist) && music.containsMatchIn(page.s("description").orEmpty())
            } ?: return emptyList()
        val original = hit.o("original")?.s("source") ?: return emptyList()
        if (original.endsWith(".svg", ignoreCase = true)) return emptyList()
        return listOf(ArtistPhoto(original, hit.o("thumbnail")?.s("source") ?: original, "Wikipedia"))
    }

    // the genius page of the song already has the artist and the header picture
    private suspend fun genius(title: String, artist: String): List<ArtistPhoto> {
        val details = (GeniusPages.load(title, artist) as? GeniusPage.Found)?.details ?: return emptyList()
        return details.media
            .filter { !it.isVideo && (it.label == "Artist" || it.label == "Header") }
            .map { ArtistPhoto(it.url, it.thumb ?: it.url, "Genius") }
    }

    // onfound gets called once per source and with whatever that source had
    suspend fun find(title: String, artist: String, onFound: suspend (List<ArtistPhoto>) -> Unit) = withContext(Dispatchers.IO) {
        val lead = artist.substringBefore(",").substringBefore("&").trim()
        if (lead.isBlank()) return@withContext
        coroutineScope {
            launch { runCatching { deezer(lead) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { onFound(it) } }
            launch { runCatching { audioDb(lead) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { onFound(it) } }
            launch { runCatching { wikipedia(lead) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { onFound(it) } }
            launch { runCatching { genius(title, artist) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { onFound(it) } }
        }
    }
}
