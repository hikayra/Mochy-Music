package com.mochyy.mochymusic.ui.player.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.screens.setup.onboarding.ProviderSquircle
import com.music.genius.PlayStats
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap

// what the platforms say about how often the song was played and a number only shows when a platform gave one
// the total adds youtube music and the music video and spotify
// apple music and tidal dont publish play counts so they have no row

private val Found = ConcurrentHashMap<String, Long>()

private fun norm(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

private suspend fun youtubeViews(videoId: String): Long? = YouTube.getMediaInfo(videoId).getOrNull()?.viewCount?.toLong()

// the official music video is the first video result marked as one that has the song title and one of its artists
private suspend fun musicVideoViews(song: MediaMetadata, artist: String): Long? {
    val title = norm(song.title)
    val lead = norm(artist)
    if (title.isEmpty()) return null
    val hit = YouTube.search("$artist ${song.title}", YouTube.SearchFilter.FILTER_VIDEO).getOrNull()?.items.orEmpty()
        .filterIsInstance<SongItem>()
        .firstOrNull { item ->
            item.id != song.id &&
                item.musicVideoType == "MUSIC_VIDEO_TYPE_OMV" &&
                norm(item.title).contains(title) &&
                item.artists.any { a -> norm(a.name).let { n -> n.isNotEmpty() && (n.contains(lead) || lead.contains(n)) } }
        } ?: return null
    return youtubeViews(hit.id)
}

private class StatRow(val key: String, val title: String, val note: String, val logo: @Composable () -> Unit)

internal fun compact(value: Long): String = when {
    value >= 1_000_000_000 -> String.format("%.2fB", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format("%.1fK", value / 1_000.0)
    else -> NumberFormat.getInstance().format(value)
}

@Composable
internal fun ListeningBlock(song: MediaMetadata, artist: String, spotifyId: String?) {
    val local = song.id.startsWith("LOCAL_")
    val results = remember(song.id) { mutableStateMapOf<String, Long>() }
    val finished = remember(song.id) { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(song.id, spotifyId) {
        val lead = artist.substringBefore(",").substringBefore("&").trim()
        coroutineScope {
            fun load(key: String, block: suspend () -> Long?) = launch {
                val cacheKey = "${song.id}|$key"
                val value = Found[cacheKey] ?: runCatching { block() }.getOrNull()?.also { Found[cacheKey] = it }
                if (value != null) results[key] = value
                finished[key] = true
            }
            if (!local) {
                load("ytm") { youtubeViews(song.id) }
                load("video") { musicVideoViews(song, lead) }
            }
            if (spotifyId != null) load("spotify") { PlayStats.spotifyStreams(spotifyId, song.title) }
        }
    }

    val rows = listOfNotNull(
        StatRow("ytm", "YouTube Music", "This track's plays") { ProviderSquircle(service = AccountService.YOUTUBE_MUSIC, size = 38.dp) }
            .takeIf { !local },
        StatRow("video", "YouTube", "Official music video") { ProviderSquircle(service = AccountService.YOUTUBE_MUSIC, size = 38.dp) }
            .takeIf { !local },
        StatRow("spotify", "Spotify", "Total streams") { ProviderSquircle(service = AccountService.SPOTIFY, size = 38.dp) }
            .takeIf { spotifyId != null },
    )
    fun waiting(row: StatRow) = finished[row.key] != true
    val shown = rows.filter { results[it.key] != null || waiting(it) }
    val counted = rows.mapNotNull { results[it.key] }
    val total = counted.sum()
    val anyWaiting = rows.any { waiting(it) }

    InfoDivider()
    InfoSectionTitle("More details")
    Spacer(Modifier.height(14.dp))
    if (shown.isEmpty()) {
        Text("No public play counts found for this song.", color = InfoMuted, fontSize = 14.sp)
        return
    }
    InfoCard {
        Text("Total listens", color = InfoMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (counted.isEmpty() && anyWaiting) {
            LoadingBar(width = 150.dp, height = 30.dp)
        } else if (counted.isEmpty()) {
            Text("—", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(compact(total), color = Color.White, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
            Text(
                "${NumberFormat.getInstance().format(total)} plays on ${counted.size} ${if (counted.size == 1) "platform" else "platforms"}" +
                    if (anyWaiting) " so far" else "",
                color = InfoMuted,
                fontSize = 13.sp,
            )
        }
        shown.forEach { row ->
            Spacer(Modifier.height(18.dp))
            val value = results[row.key]
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.logo()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(row.note, color = InfoMuted, fontSize = 12.sp)
                }
                if (value != null) {
                    Text(NumberFormat.getInstance().format(value), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                } else {
                    LoadingBar(width = 80.dp, height = 14.dp)
                }
            }
            // how much of the total this platform is
            if (value != null && total > 0) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth((value.toFloat() / total).coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
    }
}
