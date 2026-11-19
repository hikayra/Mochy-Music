package com.mochyy.mochymusic.ui.player.info

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.constants.GeniusTranslationBelowKey
import com.mochyy.mochymusic.constants.TranslateLanguageKey
import com.mochyy.mochymusic.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.mochyy.mochymusic.lyrics.GoogleTranslateEngine
import com.mochyy.mochymusic.lyrics.LyricsUtils
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.utils.rememberPreference
import com.music.genius.GeniusDetails
import com.music.genius.GeniusLyricPiece
import com.music.genius.GeniusNote
import com.music.genius.GeniusPages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// following the song and when each genius line is sung

internal object LineSync {
    private const val Match = 0.6

    private fun key(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()

    // how alike two lines are from 0 to 1 by the letter pairs they share so a word or two different still counts
    private fun dice(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
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

    // the time of every genius line and null for section titles and blank lines
    // the lines are matched to the timed lyrics in order (a chorus that comes back is matched to its own turn)
    // and a line that has no match gets a time between its neighbours so the follower never stops on one line while the song moves on
    // empty when too little lined up to trust
    fun align(genius: List<String>, timedRaw: List<Pair<Long, String>>): List<Long?> {
        val timed = timedRaw.sortedBy { it.first }
        val idx = genius.indices.filter { i -> val t = genius[i]; t.isNotBlank() && !t.startsWith("[") && key(t).length >= 2 }
        if (idx.size < 3 || timed.size < 3) return emptyList()
        val gk = idx.map { key(genius[it]) }
        val tk = timed.map { key(it.second) }
        val n = gk.size
        val m = tk.size
        val sim = Array(n) { i -> DoubleArray(m) { j -> dice(gk[i], tk[j]) } }
        // the longest run of matching lines in order and a better match is worth more
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            var best = maxOf(dp[i - 1][j], dp[i][j - 1])
            val s = sim[i - 1][j - 1]
            if (s >= Match) best = maxOf(best, dp[i - 1][j - 1] + s)
            dp[i][j] = best
        }
        val anchor = arrayOfNulls<Long>(n)
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val s = sim[i - 1][j - 1]
            if (s >= Match && Math.abs(dp[i][j] - (dp[i - 1][j - 1] + s)) < 1e-9) {
                anchor[i - 1] = timed[j - 1].first
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        val anchors = anchor.indices.filter { anchor[it] != null }
        if (anchors.size < 3 || anchors.size < n * 0.4) return emptyList()

        val first = anchors.first()
        val last = anchors.last()
        val average = ((anchor[last]!! - anchor[first]!!).toDouble() / (last - first)).coerceIn(1500.0, 8000.0)
        val times = LongArray(n)
        for (k in 0 until n) {
            val own = anchor[k]
            times[k] = when {
                own != null -> own
                else -> {
                    val before = anchors.lastOrNull { it < k }
                    val after = anchors.firstOrNull { it > k }
                    when {
                        before != null && after != null ->
                            anchor[before]!! + ((anchor[after]!! - anchor[before]!!) * (k - before).toDouble() / (after - before)).toLong()
                        before != null -> anchor[before]!! + ((k - before) * average).toLong()
                        after != null -> maxOf(0L, anchor[after]!! - ((after - k) * average).toLong())
                        else -> 0L
                    }
                }
            }
        }
        val out = arrayOfNulls<Long>(genius.size)
        idx.forEachIndexed { k, line -> out[line] = times[k] }
        return out.toList()
    }

    // the last line that has started by now
    fun current(times: List<Long?>, position: Long): Int {
        var found = -1
        for (k in times.indices) {
            val t = times[k] ?: continue
            if (t <= position) found = k else break
        }
        return found
    }
}

// the lyrics as sections

private class LyricSection(val label: String?, val singer: String?, val lines: List<Int>)

private fun splitLines(pieces: List<GeniusLyricPiece>): List<List<GeniusLyricPiece>> {
    val lines = mutableListOf(mutableListOf<GeniusLyricPiece>())
    for (piece in pieces) {
        piece.text.split("\n").forEachIndexed { i, part ->
            if (i > 0) lines += mutableListOf<GeniusLyricPiece>()
            if (part.isNotEmpty()) lines.last() += GeniusLyricPiece(part, piece.noteId)
        }
    }
    return lines
}

private fun List<GeniusLyricPiece>.plain() = joinToString("") { it.text }.trim()

// a title like verse 1 with a singer starts a section called verse 1 sung by that singer and lines before any title form a section without one
private fun sectionsOf(lines: List<List<GeniusLyricPiece>>): List<LyricSection> {
    val sections = mutableListOf<LyricSection>()
    var label: String? = null
    var singer: String? = null
    var current = mutableListOf<Int>()
    var open = false
    fun close() {
        if (current.any { lines[it].isNotEmpty() }) sections += LyricSection(label, singer, current.dropWhile { lines[it].isEmpty() }.dropLastWhile { lines[it].isEmpty() })
        current = mutableListOf()
    }
    lines.forEachIndexed { i, line ->
        val text = line.plain()
        if (text.startsWith("[") && text.endsWith("]")) {
            if (open || current.isNotEmpty()) close()
            val inner = text.removePrefix("[").removeSuffix("]")
            label = inner.substringBefore(":").trim().ifBlank { null }
            singer = inner.substringAfter(":", "").trim().ifBlank { null }
            open = true
        } else {
            current += i
        }
    }
    close()
    return sections
}

private fun copyLine(context: Context, text: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("lyrics", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun translateLines(lines: List<List<GeniusLyricPiece>>, target: String): List<String?> {
    val texts = lines.map { it.plain() }
    val send = texts.filter { it.isNotBlank() && !it.startsWith("[") }
    if (send.isEmpty()) return emptyList()
    // a failed translation ends the loader and leaves the lyrics as they are
    val out = GoogleTranslateEngine.translate(lines = send, targetLanguage = target, sourceLanguage = "auto") ?: return emptyList()
    var next = 0
    // a line already in the target language comes back identical so it is skipped and not shown twice
    return texts.map { t ->
        if (t.isBlank() || t.startsWith("[")) null else out.getOrNull(next++)?.takeUnless { it.trim().equals(t, ignoreCase = true) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GeniusLyricsBlock(song: MediaMetadata, artist: String, d: GeniusDetails, onOpenNote: (GeniusNote) -> Unit, onTranslate: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val (target) = rememberPreference(TranslateLanguageKey, defaultValue = "en")
    val (below) = rememberPreference(GeniusTranslationBelowKey, defaultValue = true)

    val lines = remember(d) { splitLines(d.lyrics) }
    val sections = remember(lines) { sectionsOf(lines) }
    // a note is found by its referent id (the api one) and by its annotation id (the page links)
    val notesById = remember(d) { buildMap<Long, GeniusNote> { d.notes.forEach { put(it.id, it); put(it.annotationId, it) } } }

    // the song being looked at is the one playing and only then is there a place to show
    val playing by playerConnection.mediaMetadata.collectAsState()
    val isCurrent = playing?.id == song.id
    val lyricsEntity by playerConnection.currentLyrics.collectAsState(initial = null)
    val progress = playerConnection.progressState.collectAsState()
    val offset = playerConnection.lyricsOffsetMs.collectAsState()

    // the app own timed lyrics first then lrclib when the app has none for this song
    val appTimed = remember(isCurrent, lyricsEntity?.lyrics) {
        val raw = lyricsEntity?.lyrics
        if (!isCurrent || raw.isNullOrBlank() || raw == LYRICS_NOT_FOUND) {
            emptyList<Pair<Long, String>>()
        } else {
            val parsed = LyricsUtils.parseLyricsCached(raw)
            if (parsed.isEmpty() || LyricsUtils.isUnsynced(parsed)) emptyList() else parsed.map { it.time to it.text }
        }
    }
    val timed by produceState(appTimed, isCurrent, appTimed, song.id) {
        value = appTimed
        if (isCurrent && appTimed.isEmpty()) value = GeniusPages.syncedLyrics(song.title, artist)
    }
    val times = remember(lines, timed) {
        if (timed.isEmpty()) emptyList<Long?>() else LineSync.align(lines.map { it.plain() }, timed)
    }
    val tracking = isCurrent && times.any { it != null }
    // read inside derivedStateOf so only a line change recomposes and not every tick of the position
    val current by remember(times, tracking) {
        derivedStateOf { if (tracking) LineSync.current(times, progress.value.position + offset.value) else -1 }
    }

    var follow by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(true) }
    // lyric card: lines being picked and the editor
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(listOf<Int>()) }
    var cardOpen by remember { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    val room = with(density) { 150.dp.toPx() }
    // brings the line in with room above and below it so it sits in the middle of the sheet and not at its edge
    suspend fun reveal() = requester.bringIntoView(Rect(0f, -room, 1f, room * 1.6f))
    LaunchedEffect(current, follow) {
        if (follow && current >= 0) {
            delay(80)
            reveal()
        }
    }

    val translated by produceState<List<String?>?>(null, GeniusTranslate.on, target, lines) {
        value = null
        if (GeniusTranslate.on) value = translateLines(lines, target)
    }
    val translating = GeniusTranslate.on && translated == null

    InfoDivider()
    InfoSectionTitle("Lyrics") {
        Spacer(Modifier.weight(1f))
        TranslateButton(GeniusTranslate.on, onTranslate)
    }
    if (d.notes.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text("${d.notes.size} notes · tap a painted line to read it", color = InfoMuted, fontSize = 13.sp)
    }
    if (selecting) {
        Spacer(Modifier.height(6.dp))
        Text("Tap up to 8 lines for the card", color = InfoMuted, fontSize = 13.sp)
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        if (tracking) {
            InfoPill(text = "Follow", filled = follow, onClick = { follow = !follow })
            InfoPill(text = "Now playing", filled = false, onClick = { scope.launch { reveal() } })
        }
        if (d.notes.isNotEmpty()) InfoPill(text = "Notes", filled = showNotes, onClick = { showNotes = !showNotes })
        InfoPill(text = "Copy", filled = false, onClick = { copyLine(context, lines.joinToString("\n") { it.plain() }) })
        InfoPill(text = if (selecting) "Cancel" else "Card", filled = selecting, onClick = { selecting = !selecting; picked = emptyList() })
        if (selecting && picked.isNotEmpty()) InfoPill(text = "Create (${picked.size})", filled = true, onClick = { cardOpen = true })
    }
    if (translating) {
        Spacer(Modifier.height(12.dp))
        LoadingBar(width = 160.dp, height = 12.dp)
    }
    Spacer(Modifier.height(14.dp))

    // any touch on the lyrics hands the scrolling back to the reader
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                follow = false
            }
        },
    ) {
        sections.forEach { section ->
            val noteCount = section.lines.flatMap { lines[it] }.mapNotNull { it.noteId?.let(notesById::get) }.distinct().size
            val hasCurrent = current in section.lines
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MenuRowSurface)
                    .then(if (hasCurrent) Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(22.dp)) else Modifier)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                if (section.label != null || section.singer != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text(
                            text = (section.label ?: "").uppercase(),
                            color = InfoMuted,
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        section.singer?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        if (noteCount > 0 && showNotes) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "$noteCount",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                section.lines.forEach { i ->
                    val line = lines[i]
                    if (line.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                    } else {
                        LyricLine(
                            line = line,
                            translation = translated?.getOrNull(i),
                            below = below,
                            tracking = tracking,
                            active = current == i,
                            showNotes = showNotes && !selecting,
                            selecting = selecting,
                            selected = i in picked,
                            onToggle = { picked = if (i in picked) picked - i else if (picked.size < 8) (picked + i).sorted() else picked },
                            notes = notesById,
                            onOpenNote = onOpenNote,
                            onCopy = { copyLine(context, line.plain()) },
                            modifier = if (current == i) Modifier.bringIntoViewRequester(requester) else Modifier,
                        )
                    }
                }
            }
        }
    }
    if (cardOpen) {
        LyricCardEditor(song = song, lines = picked.map { lines[it].plain() }, onDismiss = { cardOpen = false })
    }
}

// one lyric line with the noted bits painted (white with black letters) and tapping one opens its note
// while following the line being sung is bright and the rest soft. the weight is the same for every line
// because a bolder line is wider and wrapped differently and ran over its row. a long press copies the line
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricLine(
    line: List<GeniusLyricPiece>,
    translation: String?,
    below: Boolean,
    tracking: Boolean,
    active: Boolean,
    showNotes: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    notes: Map<Long, GeniusNote>,
    onOpenNote: (GeniusNote) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        targetValue = when {
            !tracking -> Color.White.copy(alpha = 0.92f)
            active -> Color.White
            else -> Color.White.copy(alpha = 0.45f)
        },
        animationSpec = tween(220),
        label = "lyricLine",
    )
    // replace means the translation takes the line place and otherwise it goes under it
    val replacing = translation.takeIf { !below }
    val under = translation.takeIf { below }
    fun noteLink(note: GeniusNote) = LinkAnnotation.Clickable("note ${note.id}", TextLinkStyles(style = NoteSpan)) { onOpenNote(note) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.16f)) else Modifier)
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { if (selecting) onToggle() }, onLongClick = onCopy)
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                if (replacing != null) {
                    val first = if (showNotes) line.firstNotNullOfOrNull { p -> p.noteId?.let(notes::get) } else null
                    if (first != null) withLink(noteLink(first)) { append(replacing) } else append(replacing)
                } else {
                    line.forEach { piece ->
                        val note = if (showNotes) piece.noteId?.let(notes::get) else null
                        if (note != null) withLink(noteLink(note)) { append(piece.text) } else append(piece.text)
                    }
                }
            },
            color = color,
            fontSize = 19.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (under != null) {
            Text(under, color = InfoMuted, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
