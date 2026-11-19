package com.mochyy.mochymusic.ui.player.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.GeniusTranslationBelowKey
import com.mochyy.mochymusic.constants.TranslateLanguageKey
import com.mochyy.mochymusic.lyrics.GoogleTranslateEngine
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.player.menu.LanguagePickerSheet
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.player.menu.MenuSheetSurface
import com.mochyy.mochymusic.ui.player.menu.MenuSurface
import com.mochyy.mochymusic.ui.player.menu.rememberTranslationTargets
import com.mochyy.mochymusic.utils.rememberPreference
import com.music.genius.GeniusBlock
import com.music.genius.GeniusNote

// translation on or off for lyrics and notes and story and one switch for the whole genius part so it stays on when a note opens
internal object GeniusTranslate {
    var on by mutableStateOf(false)
}

// a noted bit in the opposite colours with white paint and black letters like a highlighter on the dark sheet
internal val NoteSpan = SpanStyle(color = Color.Black, background = Color.White)

@Composable
internal fun TranslateButton(on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (on) 0.24f else 0.08f))
            .clickable(interactionSource = null, indication = RemoteFocus, onClick = onClick),
    ) {
        Icon(painterResource(R.drawable.translate), contentDescription = "Translate", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

// a picture or a video thumbnail and tapping opens it outside the app
@Composable
internal fun MediaCard(url: String, video: Boolean, onOpen: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .background(MenuRowSurface)
            .clickable(interactionSource = null, indication = RemoteFocus, onClick = onOpen),
    ) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (video) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFFF0033)),
            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp)) }
        }
    }
}

// translation of a note lines and text blocks
private class NoteTranslation(val lines: List<String>, val blocks: Map<Int, String>)

private fun GeniusBlock.plain(): String? = when (this) {
    is GeniusBlock.Paragraph -> text
    is GeniusBlock.Quote -> text
    else -> null
}

private suspend fun translateNote(note: GeniusNote, target: String): NoteTranslation {
    val lines = note.lyric.split("\n").filter { it.isNotBlank() }
    val texts = note.blocks.withIndex().mapNotNull { (i, block) -> block.plain()?.let { i to it } }
    val out = GoogleTranslateEngine.translate(lines = lines + texts.map { it.second }, targetLanguage = target, sourceLanguage = "auto")
        ?: return NoteTranslation(emptyList(), emptyMap())
    return NoteTranslation(
        lines = out.take(lines.size),
        blocks = texts.mapIndexedNotNull { n, (i, _) -> out.getOrNull(lines.size + n)?.let { i to it } }.toMap(),
    )
}

// a single note with the lines it is about painted like on the lyrics and then the note with its links and quotes and pictures and videos
// no author names are shown
@Composable
internal fun ColumnScope.GeniusNoteContent(note: GeniusNote, onTranslate: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val (target) = rememberPreference(TranslateLanguageKey, defaultValue = "en")
    // true means the translation goes under the original and false means it takes the original place
    val (below) = rememberPreference(GeniusTranslationBelowKey, defaultValue = true)
    val translation by produceState<NoteTranslation?>(null, GeniusTranslate.on, target, note) {
        value = null
        if (GeniusTranslate.on) value = translateNote(note, target)
    }
    val waiting = GeniusTranslate.on && translation == null
    val translated = translation
    val replace = !below && translated != null
    val lyricLines = note.lyric.split("\n").filter { it.isNotBlank() }

    InfoSectionTitle("Genius note") {
        Spacer(Modifier.weight(1f))
        TranslateButton(GeniusTranslate.on, onTranslate)
    }
    Spacer(Modifier.height(16.dp))

    // the lines the note is about and painted
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MenuRowSurface).padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        val shown = if (replace && translated!!.lines.isNotEmpty()) translated.lines else lyricLines
        shown.forEach { line ->
            Text(
                text = buildAnnotatedString { withStyle(NoteSpan) { append(line.trim()) } },
                fontSize = 18.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!replace) {
            translated?.lines?.forEach {
                Text(it, color = InfoMuted, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    if (waiting) {
        Spacer(Modifier.height(14.dp))
        LoadingBar(width = 200.dp, height = 12.dp)
    }

    note.blocks.forEachIndexed { i, block ->
        Spacer(Modifier.height(16.dp))
        val tr = translated?.blocks?.get(i)
        when (block) {
            is GeniusBlock.Paragraph -> Column {
                if (replace && tr != null) {
                    Text(tr, color = Color.White, fontSize = 16.sp, lineHeight = 24.sp)
                } else {
                    Text(
                        text = buildAnnotatedString {
                            block.parts.forEach { part ->
                                val link = part.url
                                if (link != null) {
                                    val style = TextLinkStyles(SpanStyle(color = Color.White, textDecoration = TextDecoration.Underline))
                                    withLink(LinkAnnotation.Url(link, style)) { append(part.text) }
                                } else {
                                    append(part.text)
                                }
                            }
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                    )
                    if (tr != null) Text(tr, color = InfoMuted, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            is GeniusBlock.Quote -> Column {
                Text(
                    text = if (replace && tr != null) tr else block.text,
                    color = Color.White,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontStyle = FontStyle.Italic,
                )
                if (!replace && tr != null) Text(tr, color = InfoMuted, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp))
            }
            is GeniusBlock.Image -> Column {
                MediaCard(block.url, video = false) { runCatching { uriHandler.openUri(block.url) } }
                block.caption?.let { Text(it, color = InfoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            is GeniusBlock.Video -> MediaCard("https://img.youtube.com/vi/${block.id}/hqdefault.jpg", video = true) {
                runCatching { uriHandler.openUri(block.url) }
            }
        }
        if (waiting && block.plain() != null) {
            Spacer(Modifier.height(8.dp))
            LoadingBar(width = 170.dp, height = 12.dp)
        }
    }
}

// the note in the song sheet own sheet (the player about song)
@Composable
internal fun GeniusNoteSheet(note: GeniusNote, onTranslate: () -> Unit, onDismiss: () -> Unit) {
    MenuSheetSurface(onDismiss = onDismiss, halfDetent = 0.72f, fullDetent = 0.94f) { _ ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding(),
        ) {
            InfoColumn(modifier = Modifier.widthIn(max = 600.dp)) { GeniusNoteContent(note, onTranslate) }
        }
    }
}

// the note as one more sheet of the app popup stack (the about page opened from a song menu)
internal fun showGeniusNote(sheets: PopupSheetState, note: GeniusNote) {
    sheets.show(surface = MenuSurface) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(),
        ) {
            InfoColumn(modifier = Modifier.widthIn(max = 600.dp)) { GeniusNoteContent(note) { showGeniusLanguages(sheets) } }
        }
    }
}

// language list of the lyrics screen with original on top while a translation is on
@Composable
internal fun GeniusLanguagePicker(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val (target, setTarget) = rememberPreference(TranslateLanguageKey, defaultValue = "en")
    val targets = rememberTranslationTargets()
    LanguagePickerSheet(
        title = "Translate to",
        selected = if (GeniusTranslate.on) target else "",
        options = if (GeniusTranslate.on) listOf("" to "Original") + targets else null,
        onPick = {
            if (it.isEmpty()) {
                GeniusTranslate.on = false
            } else {
                setTarget(it)
                GeniusTranslate.on = true
            }
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

internal fun showGeniusLanguages(sheets: PopupSheetState) {
    sheets.show(surface = MenuSurface) { GeniusLanguageList(onDone = { sheets.dismiss() }) }
}

@Composable
private fun GeniusLanguageList(onDone: () -> Unit) {
    val (target, setTarget) = rememberPreference(TranslateLanguageKey, defaultValue = "en")
    val targets = rememberTranslationTargets()
    var query by remember { mutableStateOf("") }
    val shown = remember(query, targets, GeniusTranslate.on) {
        (if (GeniusTranslate.on) listOf("" to "Original") else emptyList()) +
            targets.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) }
    }
    val selected = if (GeniusTranslate.on) target else ""
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp)) {
        InfoSheetTitle("Translate to")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.first.ifEmpty { "_original" } }) { (code, name) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(interactionSource = null, indication = RemoteFocus) {
                            if (code.isEmpty()) {
                                GeniusTranslate.on = false
                            } else {
                                setTarget(code)
                                GeniusTranslate.on = true
                            }
                            onDone()
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    Text(
                        name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = if (code == selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (code == selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
    }
}
