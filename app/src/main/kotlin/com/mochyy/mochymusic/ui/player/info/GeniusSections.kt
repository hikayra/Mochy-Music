package com.mochyy.mochymusic.ui.player.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.music.genius.GeniusAlbum
import com.music.genius.GeniusDetails
import com.music.genius.GeniusNote
import com.music.genius.GeniusPage
import com.music.genius.GeniusPages

// the genius part of about song with lyrics and their notes and the album and how often it was played and credits
// and related songs and media. the hosts (the player sheet and the page from a song menu) pass what opens a note and the language list
@Composable
internal fun GeniusSections(song: MediaMetadata, spotifyId: String?, onOpenNote: (GeniusNote) -> Unit, onTranslate: () -> Unit) {
    val artist = remember(song.id) { song.artists.joinToString(", ") { it.name } }
    var attempt by remember { mutableIntStateOf(0) }
    // null while loading
    val page by produceState<GeniusPage?>(null, song.title, artist, attempt) {
        value = null
        value = GeniusPages.load(song.title, artist)
    }
    when (val p = page) {
        null -> GeniusLoading()
        GeniusPage.Failed -> {
            InfoDivider()
            InfoSectionTitle("Lyrics")
            Spacer(Modifier.height(12.dp))
            Text("Couldn't reach Genius.", color = InfoMuted, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            InfoPill(text = "Try again", filled = true, onClick = { attempt++ })
            // the plays dont need genius
            ListeningBlock(song, artist, spotifyId)
        }
        GeniusPage.Missing -> ListeningBlock(song, artist, spotifyId)
        is GeniusPage.Found -> GeniusFound(song, artist, spotifyId, p.details, onOpenNote, onTranslate)
    }
}

// the same grey bars the rest of the sheet shows while something is on its way
@Composable
private fun GeniusLoading() {
    InfoDivider()
    InfoSectionTitle("Lyrics")
    Spacer(Modifier.height(16.dp))
    listOf(240, 180, 220, 200, 160, 230, 190).forEach { w ->
        LoadingBar(width = w.dp, height = 16.dp)
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun GeniusFound(
    song: MediaMetadata,
    artist: String,
    spotifyId: String?,
    d: GeniusDetails,
    onOpenNote: (GeniusNote) -> Unit,
    onTranslate: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    if (d.lyrics.any { it.text.isNotBlank() }) {
        GeniusLyricsBlock(song, artist, d, onOpenNote, onTranslate)
    } else if (d.notes.isNotEmpty()) {
        // no lyrics so the notes on their own
        InfoDivider()
        InfoSectionTitle("Notes on the lyrics")
        Spacer(Modifier.height(14.dp))
        d.notes.forEach { note ->
            InfoCard(Modifier.clickable(interactionSource = null, indication = RemoteFocus) { onOpenNote(note) }) {
                Text(note.lyric, color = InfoMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(note.note, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    d.albumInfo?.let { AlbumBlock(it) }

    ListeningBlock(song, artist, spotifyId)

    if (d.credits.isNotEmpty()) {
        InfoDivider()
        InfoSectionTitle("Credits")
        Spacer(Modifier.height(8.dp))
        d.credits.forEach { InfoBullet(it.role, it.names.joinToString(", ")) }
    }

    if (d.relations.isNotEmpty()) {
        InfoDivider()
        InfoSectionTitle("Related songs")
        Spacer(Modifier.height(10.dp))
        d.relations.forEachIndexed { i, relation ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Text(relation.kind, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            relation.songs.forEach { Text(it, color = InfoMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
    }

    if (d.media.isNotEmpty()) {
        InfoDivider()
        InfoSectionTitle("Videos and pictures")
        Spacer(Modifier.height(14.dp))
        d.media.forEach { media ->
            MediaCard(media.thumb ?: media.url, media.isVideo) { runCatching { uriHandler.openUri(media.url) } }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (d.pageUrl.isNotBlank()) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.about_song_provided_by, "genius"),
            color = InfoMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = null, indication = RemoteFocus) { runCatching { uriHandler.openUri(d.pageUrl) } },
        )
    }
}

// the album the way genius shows it with cover and name and artist and date and what it is about and the tracklist with this song marked
@Composable
private fun AlbumBlock(album: GeniusAlbum) {
    var open by remember(album.name) { mutableStateOf(false) }
    InfoDivider()
    InfoSectionTitle("Album")
    Spacer(Modifier.height(14.dp))
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            album.coverUrl?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(RoundedCornerShape(18.dp)))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(album.name, color = Color.White, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                if (album.artist.isNotBlank()) Text(album.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                Text(
                    listOfNotNull(album.releaseDate, "${album.tracks.size} tracks").joinToString(" · "),
                    color = InfoMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        album.about?.let { about ->
            Spacer(Modifier.height(14.dp))
            Text(about, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp, maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
            if (about.length > 220) {
                Spacer(Modifier.height(8.dp))
                InfoPill(text = if (open) "Show less" else "Read more", filled = false, onClick = { open = !open })
            }
        }
        Spacer(Modifier.height(10.dp))
        album.tracks.forEach { track ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(track.number?.toString() ?: "", color = InfoMuted, fontSize = 14.sp, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        color = if (track.current) Color.White else Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = if (track.current) FontWeight.Bold else FontWeight.Medium,
                    )
                    if (track.artist.isNotBlank() && !track.artist.equals(album.artist, ignoreCase = true)) {
                        Text(track.artist, color = InfoMuted, fontSize = 12.sp)
                    }
                }
                if (track.current) {
                    Text(
                        "This song",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
