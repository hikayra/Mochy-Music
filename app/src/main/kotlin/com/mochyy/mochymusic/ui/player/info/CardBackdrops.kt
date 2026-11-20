package com.mochyy.mochymusic.ui.player.info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.mochyy.mochymusic.applecanvas.AppleMusicCanvasProvider
import com.mochyy.mochymusic.artistvideo.ArtistVideoCanvasProvider
import com.mochyy.mochymusic.canvas.AppleMusicArtistBackgroundProvider
import com.mochyy.mochymusic.canvas.MonochromeApiCanvas
import com.mochyy.mochymusic.canvas.SpotifyCanvasProvider
import com.mochyy.mochymusic.canvas.appleArtworkUrl
import com.mochyy.mochymusic.constants.SpotifyCanvasKey
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.player.canvasMimeType
import com.mochyy.mochymusic.ui.player.normalizeCanvasArtistName
import com.mochyy.mochymusic.ui.player.normalizeCanvasSongTitle
import com.mochyy.mochymusic.ui.utils.resize
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.get
import com.music.genius.ArtistPhotos
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

// what the card can sit on all of it found inside the app so nothing has to be picked from files
internal sealed interface Backdrop {
    val id: String
    val label: String

    data object Cover : Backdrop {
        override val id = "cover"
        override val label = "Cover"
    }

    // a flat colour from the cover (the card paints it as a soft gradient)
    data class Colour(val argb: Int, override val label: String) : Backdrop {
        override val id get() = "colour-$argb"
    }

    data class Photo(val url: String, val thumb: String, val source: String) : Backdrop {
        override val id get() = "photo-" + url.substringBefore('?')
        override val label get() = "Artist photo · $source"
    }

    // a looping video the poster is a still of it to show while the clip isnt ready yet
    data class Motion(val url: String, val poster: String?, val source: String, val kind: String) : Backdrop {
        override val id get() = "motion-" + url.substringBefore('?')
        override val label get() = "$kind · $source"
    }
}

// the order the picker shows them in: cover first then loops then photos then the colours
internal fun Backdrop.rank(): Int = when (this) {
    Backdrop.Cover -> 0
    is Backdrop.Motion -> 1
    is Backdrop.Photo -> 2
    is Backdrop.Colour -> 3
}

internal suspend fun loadCardBitmap(context: Context, data: Any?, size: Int): Bitmap? {
    if (data == null) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            // kept in memory and not on the gpu so the software canvas can draw it
            val request = ImageRequest.Builder(context).data(data).size(size).allowHardware(false).build()
            context.imageLoader.execute(request).image?.toBitmap()
        }.getOrNull()
    }
}

// tiny fingerprint of a picture: 64 bits saying which pixel is brighter than its right neigbour
// the same photo at another size or another host gets almost the same bits
internal fun pictureHash(src: Bitmap): Long {
    val small = Bitmap.createScaledBitmap(src, 9, 8, true)
    fun lum(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
    var bits = 0L
    var i = 0
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            if (lum(small.getPixel(x, y)) > lum(small.getPixel(x + 1, y))) bits = bits or (1L shl i)
            i++
        }
    }
    return bits
}

// 5 colours from the cover first is the dominant one and the rest are what stands out
internal object CardPalette {
    fun of(src: Bitmap): List<Int> {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        // [count r g b] per bucket colours go by hue and greys by how bright they are
        val bins = HashMap<Int, LongArray>()
        val hsv = FloatArray(3)
        for (p in px) {
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.14f) continue
            val key = if (hsv[1] < 0.18f) 100 + (hsv[2] * 2.99f).toInt() else (hsv[0] / 30f).toInt().coerceIn(0, 11)
            val bin = bins.getOrPut(key) { LongArray(4) }
            bin[0]++
            bin[1] += Color.red(p).toLong()
            bin[2] += Color.green(p).toLong()
            bin[3] += Color.blue(p).toLong()
        }
        val found = bins.values.map { bin ->
            val n = bin[0].toInt().coerceAtLeast(1)
            val avg = Color.rgb((bin[1] / n).toInt(), (bin[2] / n).toInt(), (bin[3] / n).toInt())
            Color.colorToHSV(avg, hsv)
            // colourful buckets count for more than the same amount of grey
            avg to bin[0] * (0.4f + hsv[1])
        }.sortedByDescending { it.second }.map { it.first }
        return found.take(4).ifEmpty { listOf(Color.rgb(43, 43, 51)) }
    }

    // the most colourful one made bright enough to glow on a dark card
    fun accent(colours: List<Int>): Int {
        val hsv = FloatArray(3)
        val best = colours.maxByOrNull { Color.colorToHSV(it, hsv); hsv[1] * (0.5f + hsv[2]) } ?: return Color.rgb(255, 209, 102)
        Color.colorToHSV(best, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceAtLeast(0.88f)
        return Color.HSVToColor(hsv)
    }
}

// a few seconds of a looping video made into a normal mp4 on the phone the canvas loops come as hls and phones cant pick frames out of that
// so media3 reads it (it alredy does that for the share export) and writes a short h264 copy that a frame picker can open
internal object CardClips {
    suspend fun fetch(context: Context, url: String, maxMs: Long = 8_000L): File? {
        val dir = File(context.cacheDir, "cards/clips").apply { mkdirs() }
        val name = "c" + url.hashCode().toUInt() + ".mp4"
        val file = File(dir, name)
        if (file.length() > 1024) return file
        // the oldest clips go so the cache never piles up
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(6)?.forEach { it.delete() }
        val part = File(dir, "t$name")
        part.delete()
        return withContext(Dispatchers.Main) {
            val ok = runCatching { convert(context, url, part, maxMs) }.getOrDefault(false)
            if (ok && part.length() > 1024 && part.renameTo(file)) file else { part.delete(); null }
        }
    }

    // tranformer wants the main thread and 720 high is plenty for a card
    private suspend fun convert(context: Context, url: String, out: File, maxMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(canvasMimeType(url))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(maxMs).build())
            .build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(720))))
            .build()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                if (cont.isActive) cont.resume(false)
            }
        }
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(listener)
            .build()
        transformer.start(edited, out.path)
        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
    }
}

// goes through everything the app knows about the song and its artist and hands each backdrop over as it turns up
// the same picture from two places is only shown once (same address first and then same looks)
internal object CardBackdropFinder {
    suspend fun discover(context: Context, song: MediaMetadata, coverHash: Long?, emit: (Backdrop) -> Unit) = coroutineScope {
        val lock = Any()
        val seenUrls = HashSet<String>()
        val seenLooks = ArrayList<Long>()
        if (coverHash != null) seenLooks += coverHash

        val send: suspend (Backdrop) -> Unit = { backdrop -> withContext(Dispatchers.Main) { emit(backdrop) } }

        suspend fun photo(url: String, thumb: String, source: String) {
            if (!synchronized(lock) { seenUrls.add(ArtistPhotos.sameKey(url)) }) return
            // a small copy is looked at to see if it is the cover or something that was already found one that doesnt load is hidden too
            val small = loadCardBitmap(context, thumb, 96) ?: loadCardBitmap(context, url, 96) ?: return
            val look = pictureHash(small)
            val twin = synchronized(lock) {
                val same = seenLooks.any { java.lang.Long.bitCount(it xor look) <= 6 }
                if (!same) seenLooks += look
                same
            }
            if (!twin) send(Backdrop.Photo(url, thumb, source))
        }

        suspend fun motion(url: String, poster: String?, source: String, kind: String) {
            if (!synchronized(lock) { seenUrls.add(ArtistPhotos.sameKey(url)) }) return
            send(Backdrop.Motion(url, poster, source, kind))
        }

        val artists = song.artists.map { it.name }.filter { it.isNotBlank() }
        val lead = artists.firstOrNull().orEmpty()
        val title = normalizeCanvasSongTitle(song.title)
        val leadName = normalizeCanvasArtistName(artists.joinToString(", "))
        val duration = song.duration.takeIf { it > 0 }

        // the songs own animated cover from every canvas source so one can be picked and not just the first that answers
        if (title.length >= 2 && leadName.length >= 2) {
            val spotifyOn = context.dataStore.get(SpotifyCanvasKey, false)
            for (source in listOf("Apple Music", "Tidal", "Spotify")) {
                launch(Dispatchers.IO) {
                    val canvas = runCatching {
                        when (source) {
                            "Apple Music" -> AppleMusicCanvasProvider.getBySongArtist(title, leadName, null, "us", duration)
                            "Tidal" -> MonochromeApiCanvas.getBySongArtist(title, leadName, null, duration)
                            else -> if (spotifyOn) SpotifyCanvasProvider.getBySongArtist(context, title, leadName, duration) else null
                        }
                    }.getOrNull()
                    val url = canvas?.preferredAnimationUrl?.takeIf { it.isNotBlank() } ?: return@launch
                    motion(url, canvas?.static, source, "Animated cover")
                }
            }
        }

        // the artists own loop and banner from apple music and the artist video source
        if (lead.isNotBlank()) {
            launch(Dispatchers.IO) {
                val editorial = runCatching { AppleMusicArtistBackgroundProvider.getArtistEditorial(lead) }.getOrNull() ?: return@launch
                val poster = editorial.previewFrameUrl?.let { appleArtworkUrl(it, 720, 720) }
                (editorial.motionSquareUrl ?: editorial.motionVideoUrl)?.let { motion(it, poster, "Apple Music", "Artist loop") }
                editorial.motionVideoUrl?.takeIf { it != editorial.motionSquareUrl }?.let { motion(it, poster, "Apple Music", "Artist loop (wide)") }
                editorial.bannerUrl?.let { photo(appleArtworkUrl(it, 1800, 1000), appleArtworkUrl(it, 480, 270), "Apple Music") }
            }
            launch(Dispatchers.IO) {
                val found = runCatching { ArtistVideoCanvasProvider.getBySongArtist(song = title.ifBlank { song.title }, artist = lead) }.getOrNull() ?: return@launch
                val url = found.preferredAnimationUrl?.takeIf { it.isNotBlank() } ?: return@launch
                motion(url, found.static, "Artist video", "Artist loop")
            }
        }

        launch(Dispatchers.IO) {
            for (artist in song.artists.take(3)) {
                val id = artist.id ?: continue
                val thumb = runCatching { YouTube.artist(id).getOrNull()?.artist?.thumbnail }.getOrNull() ?: continue
                photo(thumb.resize(1080, 1080), thumb.resize(300, 300), if (song.artists.size > 1) "YouTube Music · ${artist.name}" else "YouTube Music")
            }
        }

        for ((index, name) in artists.take(2).withIndex()) {
            launch(Dispatchers.IO) {
                ArtistPhotos.find(song.title, name) { found ->
                    for (p in found) photo(p.url, p.thumb, if (index > 0) "${p.source} · $name" else p.source)
                }
            }
        }
    }
}
