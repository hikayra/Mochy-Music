package com.mochyy.mochymusic.ui.player.info

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.Color as AColor

// lyric card: pick lines and get a card like the ones genius makes
// the background can be the cover or any image gif or video (spotify canvas and apple animated cover have no public api
// so those are saved by the user and picked as a file). a video can run as it is or give one still frame
// preview and export use the same renderer so what you see is what you get

internal data class CardStyle(
    val name: String = "Classic",
    val ratio: Float = 1f,
    val blur: Float = 0.35f,
    val dim: Float = 0.45f,
    val saturation: Float = 1f,
    val zoom: Float = 1f,
    val vignette: Float = 0.3f,
    val scrim: Float = 0f,
    val textScale: Float = 1f,
    val center: Boolean = false,
    // 0 top 1 middle 2 bottom
    val vAlign: Int = 1,
    // 0 sans 1 serif 2 mono
    val font: Int = 0,
    val marker: Boolean = false,
    val panel: Boolean = false,
    val coverCard: Boolean = false,
    val footer: Boolean = true,
    val watermark: Boolean = true,
) {
    private fun f(x: Float) = String.format(Locale.US, "%.2f", x)

    // short text of every value so a liked look can be sent back as it is
    fun describe() = "preset=$name ratio=${f(ratio)} blur=${f(blur)} dim=${f(dim)} saturation=${f(saturation)} zoom=${f(zoom)} " +
        "vignette=${f(vignette)} scrim=${f(scrim)} textScale=${f(textScale)} center=$center vAlign=$vAlign font=$font " +
        "marker=$marker panel=$panel coverCard=$coverCard footer=$footer watermark=$watermark"
}

internal val CardPresets = listOf(
    CardStyle(name = "Classic", blur = 0.35f, dim = 0.45f, saturation = 1.05f, vignette = 0.3f),
    CardStyle(name = "Frosted", blur = 0.9f, dim = 0.22f, saturation = 1.3f, vignette = 0f, center = true, panel = true, textScale = 0.9f),
    CardStyle(name = "Cinema", blur = 0f, dim = 0.12f, vignette = 0.45f, scrim = 0.85f, vAlign = 2, textScale = 0.8f),
    CardStyle(name = "Poster", blur = 1f, dim = 0.35f, saturation = 1.2f, vignette = 0.2f, coverCard = true, center = true, vAlign = 2, textScale = 0.75f),
    CardStyle(name = "Marker", blur = 0.5f, dim = 0.4f, vignette = 0.25f, marker = true),
    CardStyle(name = "Mono", blur = 0.2f, dim = 0.55f, saturation = 0f, vignette = 0.5f, center = true, font = 1),
    CardStyle(name = "Glow", blur = 1f, dim = 0.15f, saturation = 1.8f, vignette = 0f, center = true, textScale = 1.1f),
)

internal class CardContent(val lines: List<String>, val title: String, val artist: String, val cover: Bitmap?)

// everything on the card that doesnt move: shade and text and footer. the glass panel is cut out of it later
internal class CardOverlay(val bitmap: Bitmap, val panel: RectF?, val panelRadius: Float)

// ---- the renderer ----

internal object CardRender {
    fun size(s: CardStyle, width: Int): Pair<Int, Int> = (width and 1.inv()) to ((width / s.ratio).toInt() and 1.inv())

    private fun faceOf(font: Int): Typeface = when (font) {
        1 -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        2 -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        else -> Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun roundedClip(cv: Canvas, r: RectF, radius: Float) {
        cv.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
    }

    fun overlay(s: CardStyle, c: CardContent, w: Int, h: Int): CardOverlay {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val u = w / 100f
        val pad = 8f * u
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        if (s.vignette > 0f) {
            p.shader = RadialGradient(
                w / 2f, h / 2f, maxOf(w, h) * 0.75f,
                intArrayOf(0, AColor.argb((s.vignette * 210).toInt(), 0, 0, 0)), floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP,
            )
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }
        if (s.scrim > 0f) {
            p.shader = LinearGradient(0f, h * 0.3f, 0f, h.toFloat(), 0, AColor.argb((s.scrim * 235).toInt(), 0, 0, 0), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }

        val thumb = 9.5f * u
        val footerH = if (s.footer) thumb else if (s.watermark) 3f * u else 0f
        val bottom = h - pad - footerH - (if (footerH > 0f) 4f * u else 0f)
        var top = pad

        // the cover as a sharp card above the text
        if (s.coverCard && c.cover != null) {
            val sz = w * 0.36f
            val left = (w - sz) / 2f
            val r = RectF(left, top + 2f * u, left + sz, top + 2f * u + sz)
            p.color = AColor.BLACK
            p.setShadowLayer(4f * u, 0f, 2f * u, AColor.argb(120, 0, 0, 0))
            cv.drawRoundRect(r, 3f * u, 3f * u, p)
            p.clearShadowLayer()
            cv.save()
            roundedClip(cv, r, 3f * u)
            cv.drawBitmap(c.cover, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
            cv.restore()
            top = r.bottom + 5f * u
        }

        // the lyric text as big as fits
        val text = c.lines.joinToString("\n")
        val boxPad = if (s.panel) 5f * u else 0f
        val markerPad = if (s.marker) 1.6f * u else 0f
        val availW = (w - 2 * pad - 2 * boxPad - 2 * markerPad).toInt().coerceAtLeast(10)
        val regionH = (bottom - top - 2 * boxPad).coerceAtLeast(10f)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = faceOf(s.font)
            color = if (s.marker) AColor.BLACK else AColor.WHITE
            if (!s.marker) setShadowLayer(u * 1.2f, 0f, u * 0.4f, AColor.argb(110, 0, 0, 0))
        }
        fun build(sz: Float): StaticLayout {
            tp.textSize = sz
            return StaticLayout.Builder.obtain(text, 0, text.length, tp, availW)
                .setAlignment(if (s.center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, if (s.marker) 1.22f else 1.12f)
                .setIncludePad(false)
                .build()
        }
        var size = w * 0.085f * s.textScale
        var layout = build(size)
        while (layout.height > regionH && size > w * 0.028f) {
            size *= 0.93f
            layout = build(size)
        }
        val blockH = layout.height + 2 * boxPad
        val y0 = when (s.vAlign) {
            0 -> top
            2 -> bottom - blockH
            else -> top + (bottom - top - blockH) / 2f
        }.coerceAtLeast(top)
        val tx = pad + boxPad + markerPad
        val ty = y0 + boxPad

        var panel: RectF? = null
        val panelRadius = 5f * u
        if (s.panel) {
            panel = RectF(pad, y0, w - pad, y0 + blockH)
            p.color = AColor.argb(38, 255, 255, 255)
            cv.drawRoundRect(panel, panelRadius, panelRadius, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.4f * u
            p.color = AColor.argb(70, 255, 255, 255)
            cv.drawRoundRect(panel, panelRadius, panelRadius, p)
            p.style = Paint.Style.FILL
        }
        if (s.marker) {
            p.color = AColor.WHITE
            val inset = size * 0.04f
            for (i in 0 until layout.lineCount) {
                val l = tx + layout.getLineLeft(i) - markerPad
                val r = tx + layout.getLineRight(i) + markerPad
                if (r - l < 2f) continue
                cv.drawRoundRect(RectF(l, ty + layout.getLineTop(i) + inset, r, ty + layout.getLineBottom(i) - inset), 1.2f * u, 1.2f * u, p)
            }
        }
        cv.save()
        cv.translate(tx, ty)
        layout.draw(cv)
        cv.restore()

        // footer: small cover and title and artist
        if (s.footer) {
            val y = h - pad - thumb
            var x = pad
            c.cover?.let {
                val r = RectF(x, y, x + thumb, y + thumb)
                cv.save()
                roundedClip(cv, r, 2f * u)
                cv.drawBitmap(it, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
                cv.restore()
                x += thumb + 3f * u
            }
            val maxW = w - x - pad - (if (s.watermark) 24f * u else 0f)
            val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textSize = 3.9f * u
                color = AColor.WHITE
                setShadowLayer(u * 0.8f, 0f, u * 0.3f, AColor.argb(100, 0, 0, 0))
            }
            val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = 3.2f * u
                color = AColor.argb(175, 255, 255, 255)
            }
            cv.drawText(TextUtils.ellipsize(c.title, title, maxW, TextUtils.TruncateAt.END).toString(), x, y + thumb * 0.45f, title)
            cv.drawText(TextUtils.ellipsize(c.artist, sub, maxW, TextUtils.TruncateAt.END).toString(), x, y + thumb * 0.88f, sub)
        }
        if (s.watermark) {
            val wm = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = 2.8f * u
                color = AColor.argb(135, 255, 255, 255)
                textAlign = Paint.Align.RIGHT
            }
            cv.drawText("mochy music", w - pad, h - pad - (if (s.footer) thumb * 0.12f else 0f), wm)
        }
        return CardOverlay(bmp, panel, panelRadius)
    }

    // a soft blur from shrinking the picture and growing it back in steps
    private fun blur(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.01f) return src
        val div = 1f + amount * 26f
        val tw = maxOf(6, (src.width / div).toInt())
        val th = maxOf(6, (src.height / div).toInt())
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        cur = Bitmap.createScaledBitmap(cur, tw, th, true)
        while (cur.width * 2 < src.width && cur.height * 2 < src.height) cur = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true)
        return Bitmap.createScaledBitmap(cur, src.width, src.height, true)
    }

    // the picture cropped from the middle to the card shape
    private fun fit(raw: Bitmap, w: Int, h: Int, zoom: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ta = w.toFloat() / h
        val ra = raw.width.toFloat() / raw.height
        val (cw, ch) = if (ra > ta) {
            val c = raw.height / zoom
            (c * ta) to c
        } else {
            val c = raw.width / zoom
            c to (c / ta)
        }
        val l = (raw.width - cw) / 2f
        val t = (raw.height - ch) / 2f
        Canvas(out).drawBitmap(raw, Rect(l.toInt(), t.toInt(), (l + cw).toInt(), (t + ch).toInt()), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun frame(s: CardStyle, raw: Bitmap?, ov: CardOverlay, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        if (raw == null) {
            val g = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2A2A30.toInt(), 0xFF0B0B0C.toInt(), Shader.TileMode.CLAMP) }
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        } else {
            val base = fit(raw, w, h, s.zoom)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (s.saturation != 1f) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(s.saturation) })
            cv.drawBitmap(blur(base, s.blur), 0f, 0f, paint)
            // the glass panel shows the same picture blurred harder
            ov.panel?.let { r ->
                cv.save()
                roundedClip(cv, r, ov.panelRadius)
                cv.drawBitmap(blur(base, 1f), 0f, 0f, paint)
                cv.restore()
            }
        }
        if (s.dim > 0f) cv.drawColor(AColor.argb((s.dim * 255).toInt(), 0, 0, 0))
        cv.drawBitmap(ov.bitmap, 0f, 0f, null)
        return out
    }
}

// ---- backgrounds ----

internal abstract class BgSource {
    // 0 for a still picture
    abstract val durationMs: Long
    abstract fun frame(ms: Long, maxW: Int): Bitmap?
    open fun close() {}
}

private fun Bitmap.limitWidth(maxW: Int): Bitmap =
    if (width <= maxW) this else Bitmap.createScaledBitmap(this, maxW, (height * maxW.toFloat() / width).toInt().coerceAtLeast(1), true)

internal class StillBg(private val bmp: Bitmap) : BgSource() {
    override val durationMs = 0L
    override fun frame(ms: Long, maxW: Int): Bitmap = bmp
}

internal class VideoBg(ctx: Context, uri: Uri) : BgSource() {
    private val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
    override val durationMs: Long = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    // the retriever is used by the preview and the export so one frame at a time
    @Synchronized
    override fun frame(ms: Long, maxW: Int): Bitmap? =
        r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.limitWidth(maxW)

    override fun close() {
        runCatching { r.release() }
    }
}

internal class GifBg(ctx: Context, uri: Uri) : BgSource() {
    private val movie: Movie? = ctx.contentResolver.openInputStream(uri)?.use { Movie.decodeStream(it) }
    val ok get() = movie != null && movie.width() > 0
    override val durationMs: Long = (movie?.duration() ?: 0).toLong()

    @Synchronized
    override fun frame(ms: Long, maxW: Int): Bitmap? {
        val m = movie ?: return null
        val d = m.duration()
        m.setTime(if (d > 0) (ms % d).toInt() else 0)
        val b = Bitmap.createBitmap(m.width().coerceAtLeast(1), m.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(AColor.BLACK)
        m.draw(c, 0f, 0f)
        return b.limitWidth(maxW)
    }
}

private fun decodeSampled(ctx: Context, uri: Uri, max: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2
    return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

internal fun openSource(ctx: Context, uri: Uri): BgSource? {
    val type = ctx.contentResolver.getType(uri).orEmpty()
    return runCatching<BgSource?> {
        when {
            type == "image/gif" -> GifBg(ctx, uri).takeIf { it.ok && it.durationMs > 0 } ?: decodeSampled(ctx, uri, 2048)?.let { StillBg(it) }
            type.startsWith("video/") -> VideoBg(ctx, uri).takeIf { it.durationMs > 0 }
            type.startsWith("image/") -> decodeSampled(ctx, uri, 2048)?.let { StillBg(it) }
            else -> null
        }
    }.getOrNull()
}

// the cover in a big size
internal fun loadCover(url: String?): Bitmap? = runCatching<Bitmap?> {
    if (url.isNullOrBlank()) return@runCatching null
    val big = url.replace(Regex("=w\\d+-h\\d+.*$"), "=w1080-h1080-l90-rj")
    java.net.URL(big).openStream().use { BitmapFactory.decodeStream(it) }
}.getOrNull()

// ---- gif writer: one shared 256 colour palette with ordered dithering so frames stay steady while moving ----

internal class GifWriter(private val out: OutputStream, private val w: Int, private val h: Int, private val delayCs: Int) {
    private val px = IntArray(w * h)
    private val idx = ByteArray(w * h)
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    private fun short(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    init {
        out.write("GIF89a".toByteArray())
        short(w)
        short(h)
        out.write(0xF7)
        out.write(0)
        out.write(0)
        for (i in 0..255) {
            out.write(((i shr 5) and 7) * 255 / 7)
            out.write(((i shr 2) and 7) * 255 / 7)
            out.write((i and 3) * 255 / 3)
        }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    fun add(bmp: Bitmap) {
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px[i]
                val t = (bayer[(y and 3) * 4 + (x and 3)] - 7.5f) / 16f
                val r = (((p shr 16) and 255) + (t * 36f).toInt()).coerceIn(0, 255)
                val g = (((p shr 8) and 255) + (t * 36f).toInt()).coerceIn(0, 255)
                val b = ((p and 255) + (t * 85f).toInt()).coerceIn(0, 255)
                idx[i++] = ((((r * 7 + 127) / 255) shl 5) or (((g * 7 + 127) / 255) shl 2) or ((b * 3 + 127) / 255)).toByte()
            }
        }
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write(0x04)
        short(delayCs)
        out.write(0)
        out.write(0)
        out.write(0x2C)
        short(0)
        short(0)
        short(w)
        short(h)
        out.write(0)
        lzw(idx)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    // the usual gif lzw with 8 bit codes
    private fun lzw(pixels: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode
        val eoi = clear + 1
        var nextCode = eoi + 1
        var codeSize = minCode + 1
        val block = ByteArray(255)
        var blockLen = 0
        var cur = 0
        var curBits = 0
        fun flushBlock() {
            if (blockLen > 0) {
                out.write(blockLen)
                out.write(block, 0, blockLen)
                blockLen = 0
            }
        }
        fun put(code: Int) {
            cur = cur or (code shl curBits)
            curBits += codeSize
            while (curBits >= 8) {
                block[blockLen++] = (cur and 0xFF).toByte()
                cur = cur ushr 8
                curBits -= 8
                if (blockLen == 255) flushBlock()
            }
        }
        val size = 8209
        val keys = IntArray(size) { -1 }
        val vals = IntArray(size)
        fun bump() {
            if (nextCode > (1 shl codeSize) - 1 && codeSize < 12) codeSize++
        }
        put(clear)
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val c = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var hsh = ((key.toLong() * 2654435761L) and 0x7fffffffL).toInt() % size
            while (keys[hsh] != -1 && keys[hsh] != key) hsh = (hsh + 1) % size
            if (keys[hsh] == key) {
                prefix = vals[hsh]
            } else {
                put(prefix)
                bump()
                if (nextCode < 4096) {
                    keys[hsh] = key
                    vals[hsh] = nextCode++
                } else {
                    put(clear)
                    keys.fill(-1)
                    nextCode = eoi + 1
                    codeSize = minCode + 1
                }
                prefix = c
            }
        }
        put(prefix)
        bump()
        put(eoi)
        if (curBits > 0) {
            block[blockLen++] = (cur and 0xFF).toByte()
            if (blockLen == 255) flushBlock()
        }
        flushBlock()
        out.write(0)
    }
}

// ---- mp4 writer: frames go in through the encoder surface and the times are set by frame count ----

internal object Mp4Writer {
    fun encode(file: File, w: Int, h: Int, fps: Int, frames: Int, bitrate: Int, next: (Int) -> Bitmap, progress: (Float) -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        var count = 0
        val info = MediaCodec.BufferInfo()

        fun drain(end: Boolean) {
            if (end) codec.signalEndOfInputStream()
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 10_000)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) return
                } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (i >= 0) {
                    val buf = codec.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started && buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        info.presentationTimeUs = count * 1_000_000L / fps
                        count++
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }

        try {
            for (k in 0 until frames) {
                val bmp = next(k)
                val canvas = if (Build.VERSION.SDK_INT >= 26) surface.lockHardwareCanvas() else surface.lockCanvas(null)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                surface.unlockCanvasAndPost(canvas)
                drain(false)
                progress((k + 1f) / frames)
            }
            drain(true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
            surface.release()
        }
    }
}

// ---- export and saving ----

internal class CardFile(val file: File, val mime: String, val name: String)

internal object CardExport {
    // kind 0 is a still png and 1 is a gif and 2 is an mp4
    suspend fun render(
        ctx: Context, s: CardStyle, content: CardContent, bg: BgSource?, useStill: Boolean,
        stillMs: Long, startMs: Long, lenMs: Long, kind: Int, fps: Int, width: Int, onProgress: (Float) -> Unit,
    ): CardFile {
        val (w, h) = CardRender.size(s, width)
        val ov = CardRender.overlay(s, content, w, h)
        val stamp = System.currentTimeMillis()
        val dir = File(ctx.cacheDir, "cards").apply { mkdirs() }
        val animated = kind != 0 && bg != null && bg.durationMs > 0L && !useStill
        if (!animated) {
            val bmp = CardRender.frame(s, bg?.frame(if (useStill) stillMs else 0L, w), ov, w, h)
            val f = File(dir, "mochy-card-$stamp.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            onProgress(1f)
            return CardFile(f, "image/png", f.name)
        }
        val source = bg ?: error("no background")
        if (kind == 1) {
            // gif times are whole hundredths so 50 fps is the fastest steady speed
            val gifFps = minOf(fps, 50)
            val delay = (100f / gifFps).roundToInt().coerceAtLeast(2)
            val frames = ((lenMs / 1000f) * gifFps).toInt().coerceAtLeast(2)
            val f = File(dir, "mochy-card-$stamp.gif")
            f.outputStream().buffered(1 shl 16).use { os ->
                val gif = GifWriter(os, w, h, delay)
                for (k in 0 until frames) {
                    currentCoroutineContext().ensureActive()
                    gif.add(CardRender.frame(s, source.frame(startMs + k * 1000L / gifFps, w), ov, w, h))
                    onProgress((k + 1f) / frames)
                }
                gif.finish()
            }
            return CardFile(f, "image/gif", f.name)
        }
        val frames = ((lenMs / 1000f) * fps).toInt().coerceAtLeast(2)
        val f = File(dir, "mochy-card-$stamp.mp4")
        Mp4Writer.encode(
            file = f, w = w, h = h, fps = fps, frames = frames, bitrate = (w * h * fps * 0.15f).toInt(),
            next = { k -> CardRender.frame(s, source.frame(startMs + k * 1000L / fps, w), ov, w, h) },
            progress = onProgress,
        )
        return CardFile(f, "video/mp4", f.name)
    }

    fun save(ctx: Context, out: CardFile): Uri? = runCatching<Uri?> {
        val video = out.mime.startsWith("video/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, out.name)
            put(MediaStore.MediaColumns.MIME_TYPE, out.mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Mochy" else "Pictures/Mochy")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { o -> out.file.inputStream().use { it.copyTo(o) } }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        uri
    }.getOrNull()

    fun share(ctx: Context, uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---- the editor ----

@Composable
private fun CardLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text.uppercase(), color = InfoMuted, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), content = content)
}

@Composable
private fun CardSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(text, color = InfoMuted, fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
        )
    }
}

@Composable
internal fun LyricCardEditor(song: MediaMetadata, lines: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val artist = remember(song.id) { song.artists.joinToString(", ") { it.name } }

    var style by remember { mutableStateOf(CardPresets[0]) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var picked by remember { mutableStateOf<BgSource?>(null) }
    var useStill by remember { mutableStateOf(false) }
    var stillFrac by remember { mutableFloatStateOf(0.1f) }
    var startFrac by remember { mutableFloatStateOf(0f) }
    var clipSeconds by remember { mutableFloatStateOf(5f) }
    var kind by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(50) }
    var exportWidth by remember { mutableIntStateOf(720) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var previewRaw by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(song.id) { cover = withContext(Dispatchers.IO) { loadCover(song.thumbnailUrl) } }
    DisposableEffect(picked) {
        val p = picked
        onDispose { p?.close() }
    }

    val bg = remember(picked, cover) { picked ?: cover?.let { StillBg(it) } }
    val dur = bg?.durationMs ?: 0L
    val animated = dur > 0L
    val lenMs = minOf((clipSeconds * 1000).toLong(), dur)
    val startMs = ((dur - lenMs) * startFrac).toLong()
    val stillMs = (dur * stillFrac).toLong()
    val effKind = if (animated && !useStill) kind else 0
    val content = remember(lines, song.id, cover) { CardContent(lines, song.title, artist, cover) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val src = withContext(Dispatchers.IO) { openSource(context, uri) }
            if (src == null) {
                hint = "Couldn't open that file"
            } else {
                picked = src
                useStill = false
                startFrac = 0f
                kind = if (src.durationMs > 0L) 1 else 0
            }
        }
    }

    // the raw background frames for the preview: low quality and about 10 per second
    LaunchedEffect(bg, useStill, stillMs, startMs, lenMs) {
        delay(220)
        val source = bg
        if (source == null) {
            previewRaw = emptyList()
            return@LaunchedEffect
        }
        previewRaw = withContext(Dispatchers.IO) {
            if (source.durationMs <= 0L) {
                listOfNotNull(source.frame(0, 480))
            } else if (useStill) {
                listOfNotNull(source.frame(stillMs, 480))
            } else {
                val n = (lenMs / 100L).toInt().coerceIn(1, 60)
                (0 until n).mapNotNull { source.frame(startMs + it * lenMs / n, 480) }
            }
        }
    }

    val pw = 480
    val ph = (pw / style.ratio).toInt() and 1.inv()
    val overlay = remember(style, content) { CardRender.overlay(style, content, pw, ph) }
    LaunchedEffect(previewRaw, overlay) {
        if (previewRaw.isEmpty()) {
            shown = withContext(Dispatchers.Default) { CardRender.frame(style, null, overlay, pw, ph) }
            return@LaunchedEffect
        }
        var i = 0
        while (isActive) {
            val raw = previewRaw[i % previewRaw.size]
            shown = withContext(Dispatchers.Default) { CardRender.frame(style, raw, overlay, pw, ph) }
            if (previewRaw.size == 1) break
            delay(100)
            i++
        }
    }

    fun run(share: Boolean) {
        if (progress != null) return
        scope.launch {
            progress = 0f
            hint = null
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    CardExport.render(context, style, content, bg, useStill, stillMs, startMs, lenMs, effKind, fps, exportWidth) { progress = it }
                }
            }
            progress = null
            result.onSuccess { out ->
                val uri = withContext(Dispatchers.IO) { CardExport.save(context, out) }
                if (uri == null) hint = "Couldn't save the file" else if (share) CardExport.share(context, uri, out.mime) else hint = "Saved to your gallery"
            }.onFailure { hint = "Failed: ${it.message}" }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0C)).statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                InfoPill(text = "Close", filled = false, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                Text("Lyric card", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(56.dp))
            }

            // the preview
            val screenH = LocalConfiguration.current.screenHeightDp.dp
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                val wDp = minOf(maxWidth, screenH * 0.40f * style.ratio)
                val frame = Modifier.width(wDp).aspectRatio(style.ratio).clip(RoundedCornerShape(16.dp))
                val bmp = shown
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = frame)
                } else {
                    Box(frame.background(MenuRowSurface))
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text("The preview is lower quality and about 10 fps. The export is full quality.", color = InfoMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))

                CardLabel("Presets")
                ChipRow {
                    CardPresets.forEach { p -> InfoPill(text = p.name, filled = style.name == p.name, onClick = { style = p.copy(ratio = style.ratio) }) }
                }

                CardLabel("Background")
                ChipRow {
                    InfoPill(text = "Cover", filled = picked == null, onClick = { picked = null; useStill = false; kind = 0 })
                    InfoPill(text = if (picked == null) "Pick file" else "Another file", filled = picked != null, onClick = { picker.launch(arrayOf("image/*", "video/*")) })
                }
                Text("Image or gif or a video: spotify canvas or apple animated cover or a clip saved from anywhere", color = InfoMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (animated) {
                    Spacer(Modifier.height(8.dp))
                    ChipRow {
                        InfoPill(text = "Moving", filled = !useStill, onClick = { useStill = false })
                        InfoPill(text = "Still frame", filled = useStill, onClick = { useStill = true })
                    }
                    if (useStill) {
                        CardSlider("Frame", stillFrac, 0f..1f, "${(stillFrac * dur / 1000f).let { String.format(Locale.US, "%.1f", it) }} s") { stillFrac = it }
                    } else {
                        CardSlider("Length", clipSeconds, 2f..10f, "${clipSeconds.roundToInt()} s") { clipSeconds = it }
                        if (dur - lenMs > 500L) {
                            CardSlider("Start", startFrac, 0f..1f, "${String.format(Locale.US, "%.1f", startMs / 1000f)} s") { startFrac = it }
                        }
                    }
                }

                CardLabel("Look")
                CardSlider("Blur", style.blur, 0f..1f, "${(style.blur * 100).toInt()}") { style = style.copy(blur = it) }
                CardSlider("Dim", style.dim, 0f..0.9f, "${(style.dim * 100).toInt()}") { style = style.copy(dim = it) }
                CardSlider("Colour", style.saturation, 0f..2f, "${(style.saturation * 100).toInt()}") { style = style.copy(saturation = it) }
                CardSlider("Zoom", style.zoom, 1f..2f, "${String.format(Locale.US, "%.2f", style.zoom)}x") { style = style.copy(zoom = it) }
                CardSlider("Vignette", style.vignette, 0f..1f, "${(style.vignette * 100).toInt()}") { style = style.copy(vignette = it) }
                CardSlider("Bottom shade", style.scrim, 0f..1f, "${(style.scrim * 100).toInt()}") { style = style.copy(scrim = it) }

                CardLabel("Text")
                ChipRow {
                    InfoPill(text = "Left", filled = !style.center, onClick = { style = style.copy(center = false) })
                    InfoPill(text = "Center", filled = style.center, onClick = { style = style.copy(center = true) })
                    InfoPill(text = "Top", filled = style.vAlign == 0, onClick = { style = style.copy(vAlign = 0) })
                    InfoPill(text = "Middle", filled = style.vAlign == 1, onClick = { style = style.copy(vAlign = 1) })
                    InfoPill(text = "Bottom", filled = style.vAlign == 2, onClick = { style = style.copy(vAlign = 2) })
                }
                ChipRow {
                    InfoPill(text = "Sans", filled = style.font == 0, onClick = { style = style.copy(font = 0) })
                    InfoPill(text = "Serif", filled = style.font == 1, onClick = { style = style.copy(font = 1) })
                    InfoPill(text = "Mono", filled = style.font == 2, onClick = { style = style.copy(font = 2) })
                }
                CardSlider("Size", style.textScale, 0.5f..1.6f, "${(style.textScale * 100).toInt()}") { style = style.copy(textScale = it) }
                ChipRow {
                    InfoPill(text = "Marker", filled = style.marker, onClick = { style = style.copy(marker = !style.marker) })
                    InfoPill(text = "Glass", filled = style.panel, onClick = { style = style.copy(panel = !style.panel) })
                    InfoPill(text = "Cover card", filled = style.coverCard, onClick = { style = style.copy(coverCard = !style.coverCard) })
                    InfoPill(text = "Footer", filled = style.footer, onClick = { style = style.copy(footer = !style.footer) })
                    InfoPill(text = "Mochy tag", filled = style.watermark, onClick = { style = style.copy(watermark = !style.watermark) })
                }

                CardLabel("Shape")
                ChipRow {
                    listOf("1:1" to 1f, "4:5" to 0.8f, "9:16" to 0.5625f).forEach { (name, r) ->
                        InfoPill(text = name, filled = style.ratio == r, onClick = { style = style.copy(ratio = r) })
                    }
                }

                CardLabel("Export")
                if (animated && !useStill) {
                    ChipRow {
                        InfoPill(text = "GIF", filled = kind == 1, onClick = { kind = 1 })
                        InfoPill(text = "MP4", filled = kind == 2, onClick = { kind = 2 })
                        InfoPill(text = "PNG still", filled = kind == 0, onClick = { kind = 0 })
                    }
                    if (effKind != 0) {
                        ChipRow {
                            listOf(25, 30, 50, 60).forEach { v -> InfoPill(text = "$v fps", filled = fps == v, onClick = { fps = v }) }
                        }
                    }
                } else {
                    Text("PNG", color = Color.White, fontSize = 14.sp)
                }
                ChipRow {
                    listOf(540, 720, 1080).forEach { v -> InfoPill(text = "${v}px", filled = exportWidth == v, onClick = { exportWidth = v }) }
                }
                if (effKind == 1) {
                    Text("A gif has 256 colours and gets big at 50 fps. MP4 is smaller and much sharper.", color = InfoMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(Modifier.height(16.dp))
                ChipRow {
                    InfoPill(text = "Save", filled = true, onClick = { run(false) })
                    InfoPill(text = "Share", filled = false, onClick = { run(true) })
                    InfoPill(text = "Copy settings", filled = false, onClick = {
                        runCatching {
                            val text = style.describe() + " export=${listOf("png", "gif", "mp4")[effKind]} fps=$fps width=$exportWidth clip=${clipSeconds.roundToInt()}s"
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("card", text))
                            hint = "Settings copied"
                        }
                    })
                }
                progress?.let { Text("Rendering ${(it * 100).toInt()}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
                hint?.let { Text(it, color = InfoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
