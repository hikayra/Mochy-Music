package com.mochyy.mochymusic.ui.player.info

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.AboutSongSourceKey
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.modules.ModuleMetadata
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.player.menu.MenuSurface
import com.mochyy.mochymusic.ui.player.menu.SongHeader
import com.mochyy.mochymusic.utils.rememberPreference

// about song for any song and not only the one playing. opened from a song long press menu as a sheet of the
// app popup stack. the player own sheet (AboutSongSheet) shows the same things
fun showAboutSong(sheets: PopupSheetState, song: MediaMetadata, onOpenArtist: (String) -> Unit) {
    sheets.show(surface = MenuSurface) { AboutSongPage(sheets, song, onOpenArtist) }
}

@Composable
private fun AboutSongPage(sheets: PopupSheetState, song: MediaMetadata, onOpenArtist: (String) -> Unit) {
    val database = LocalDatabase.current
    val context = LocalContext.current
    val about by remember(song.id) { SongAboutLoader.load(song, database, context.applicationContext) }
        .collectAsState(initial = SongAbout(artist = song.artists.joinToString(", ") { it.name }))
    val (sourceId, setSourceId) = rememberPreference(AboutSongSourceKey, AboutSource.Musixmatch.id)
    val source = AboutSource.entries.firstOrNull { it.id == sourceId } ?: AboutSource.Musixmatch
    // no source list sheet here so the chip goes on to the next source
    val choices = AboutSource.entries.filter { it != AboutSource.Modules || ModuleMetadata.sources.isNotEmpty() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(),
    ) {
        InfoColumn(modifier = Modifier.widthIn(max = 600.dp)) {
            InfoSheetTitle(stringResource(R.string.ps_details_about_song))
            Spacer(Modifier.height(16.dp))
            SongHeader(song)
            InfoDivider()
            GeneralInformation(about)
            InfoDivider()
            InfoSectionTitle(stringResource(R.string.pc_about)) {
                SourceChip(source) { setSourceId(choices[(choices.indexOf(source) + 1) % choices.size].id) }
            }
            Spacer(Modifier.height(12.dp))
            AboutCard(about, source, onSwitch = { setSourceId(it.id) })
            GeniusSections(
                song = song,
                spotifyId = about.musixmatch?.spotifyId ?: about.genius?.spotifyId,
                onOpenNote = { showGeniusNote(sheets, it) },
                onTranslate = { showGeniusLanguages(sheets) },
            )
            if (about.artists.isNotEmpty()) {
                InfoDivider()
                InfoSectionTitle(stringResource(R.string.pc_about_by))
                Spacer(Modifier.height(14.dp))
                ArtistRow(about.artists) { artistId ->
                    sheets.dismissAll()
                    onOpenArtist(artistId)
                }
            }
            if (about.links.isNotEmpty()) {
                InfoDivider()
                InfoSectionTitle(stringResource(R.string.pc_about_also_on))
                Spacer(Modifier.height(14.dp))
                StoreRow(about.links)
            }
        }
    }
}
