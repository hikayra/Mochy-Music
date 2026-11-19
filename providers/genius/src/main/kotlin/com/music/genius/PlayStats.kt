package com.music.genius

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// spotify has no public play numbers so they come off kworb.net
// the track page first then the artist song list as a check because one song can have several spotify ids
// (single and album and deluxe) and each id only counts its own plays. the biggest number for the same title wins
// apple music and tidal dont publish play counts anywhere so there is nothing to read for them
object PlayStats {
    private const val Agent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun String.digits(): Long? = filter(Char::isDigit).takeIf { it.isNotEmpty() }?.toLongOrNull()

    private fun norm(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    // the title without feat parts so a song matches itself on the list
    private fun base(title: String) = title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim()

    // the biggest number in a table row and only cells made of digits and commas count so dates and names are skipped
    private fun Element.biggest(): Long? = select("td,th").mapNotNull { cell ->
        val t = cell.text().trim()
        if (t.isNotEmpty() && t.any(Char::isDigit) && t.all { it.isDigit() || it == ',' || it.isWhitespace() }) t.digits() else null
    }.maxOrNull()

    private fun fromArtistPage(artistId: String, title: String): Long? {
        val want = norm(base(title))
        if (want.isEmpty()) return null
        val doc = Jsoup.connect("https://kworb.net/spotify/artist/${artistId}_songs.html").userAgent(Agent).timeout(12_000).get()
        return doc.select("tr").mapNotNull { row ->
            val name = row.selectFirst("td")?.text().orEmpty()
            if (norm(base(name)) == want) row.biggest() else null
        }.maxOrNull()
    }

    suspend fun spotifyStreams(trackId: String, title: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val page = Jsoup.connect("https://kworb.net/spotify/track/$trackId.html").userAgent(Agent).timeout(12_000).get()
            val rows = page.select("tr")
            // the row that says total and if the page has none the biggest number on it is the best guess
            val own = rows.filter { it.selectFirst("td,th")?.text()?.trim()?.startsWith("Total", ignoreCase = true) == true }
                .mapNotNull { it.biggest() }.maxOrNull()
                ?: rows.mapNotNull { it.biggest() }.maxOrNull()
            val artistId = page.select("a[href]").firstNotNullOfOrNull { Regex("artist/([A-Za-z0-9]{22})").find(it.attr("href"))?.groupValues?.get(1) }
            val other = artistId?.let { runCatching { fromArtistPage(it, title) }.getOrNull() }
            listOfNotNull(own, other).maxOrNull()
        }.getOrNull()
    }
}
