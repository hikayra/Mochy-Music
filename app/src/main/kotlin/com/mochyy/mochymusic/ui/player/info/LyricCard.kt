package com.mochyy.mochymusic.ui.player.info

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
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
import android.graphics.SweepGradient
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
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.player.canvasMimeType
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.ui.utils.resize
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// lyric card: pick lines and get a card in the genius spirit
// eight looks all from the same renderer so preview and export match
// the fonts ship inside the app (assets/cardfonts) so the text looks the same on every phone
// background comes from inside the app (cover loops artist photos colours) no file picking

internal data class CardStyle(
    val name: String = "Genius",
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
    // 0 heavy grotesk 1 serif display 2 mono 3 tall condensed 4 bebas display
    val font: Int = 0,
    val marker: Boolean = false,
    // 0 white 1 the covers accent 2 genius yellow
    val markerColor: Int = 0,
    val panel: Boolean = false,
    // 0 nothing 1 sharp cover card 2 vinyl 3 header row with cover and names
    val coverMode: Int = 0,
    // 0 on the picture 1 paper 2 flat accent colour 3 near black with an accent glow
    val theme: Int = 0,
    val glow: Boolean = false,
    val upper: Boolean = false,
    val quote: Boolean = false,
    val footer: Boolean = true,
    val watermark: Boolean = true,
    // 0 plain ink 1 the covers accent
    val tint: Int = 0,
    // letter spacing in em
    val track: Float = 0f,
    // a short accent bar above the lyrics
    val rule: Boolean = false,
    // a small spaced out song line at the very top
    val tag: Boolean = false,
) {
    private fun f(x: Float) = String.format(Locale.US, "%.2f", x)

    fun describe() = "preset=$name ratio=${f(ratio)} blur=${f(blur)} dim=${f(dim)} saturation=${f(saturation)} zoom=${f(zoom)} " +
        "vignette=${f(vignette)} scrim=${f(scrim)} textScale=${f(textScale)} center=$center vAlign=$vAlign font=$font " +
        "marker=$marker markerColor=$markerColor panel=$panel coverMode=$coverMode theme=$theme glow=$glow upper=$upper " +
        "quote=$quote footer=$footer watermark=$watermark tint=$tint track=${f(track)} rule=$rule tag=$tag"
}

internal val CardPresets = listOf(
    // the genius look: cover soft and dark behind heavy white lines with the song at the foot
    CardStyle(name = "Genius", blur = 0.5f, dim = 0.42f, saturation = 1.1f, vignette = 0.3f, scrim = 0.55f, font = 0, textScale = 0.92f, track = -0.01f),
    // big tall caps pinned to the bottom with a small song line on top
    CardStyle(name = "Editorial", blur = 0.15f, dim = 0.2f, saturation = 1.05f, vignette = 0.2f, scrim = 0.92f, vAlign = 2, font = 3, upper = true, textScale = 1.15f, tag = true, footer = false),
    // frosted glass slab with the cover on top
    CardStyle(name = "Glass", blur = 1f, dim = 0.3f, saturation = 1.35f, vignette = 0f, center = true, panel = true, coverMode = 1, font = 0, textScale = 0.8f, footer = false),
    // the cover as a sleeve and a serif under it
    CardStyle(name = "Sleeve", blur = 1f, dim = 0.5f, saturation = 1.25f, vignette = 0.25f, coverMode = 1, center = true, vAlign = 2, font = 1, textScale = 0.78f),
    // near black with accent coloured display type
    CardStyle(name = "Midnight", theme = 3, tint = 1, font = 4, upper = true, textScale = 1.25f, rule = true, track = 0.02f),
    // warm paper and a serif
    CardStyle(name = "Paper", theme = 1, quote = true, font = 1, textScale = 0.9f),
    // flat accent colour and huge condensed caps
    CardStyle(name = "Poster", theme = 2, upper = true, font = 3, textScale = 1.2f, footer = true),
    // typewriter lines on black with a song line up top
    CardStyle(name = "Mono", theme = 3, font = 2, tag = true, textScale = 0.72f, rule = false, footer = false),
)

// accent is the loudest colour of the cover and solid is a colour picked as the whole background
internal class CardContent(
    val lines: List<String>,
    val title: String,
    val artist: String,
    val cover: Bitmap?,
    val accent: Int = 0xFFFFD166.toInt(),
    val solid: Int? = null,
)

// everything on the card that doesnt move: shade and text and footer the glass panel is cut out of it later
// bgcolor is set when the card paints its own flat background and then the picture isnt used at all
// glowcolor is a soft light in the corner of the dark theme
internal class CardOverlay(
    val bitmap: Bitmap,
    val panel: RectF?,
    val panelRadius: Float,
    val bgColor: Int?,
    val glowColor: Int? = null,
)

// the card fonts live in assets/cardfonts and fall back to the system font when a file is missing
internal object CardFonts {
    private var assets: AssetManager? = null
    private val cache = HashMap<Int, Typeface>()
    private val files = listOf(
        "ArchivoBlack-Regular.ttf",
        "DMSerifDisplay-Regular.ttf",
        "SpaceMono-Bold.ttf",
        "Anton-Regular.ttf",
        "BebasNeue-Regular.ttf",
    )

    fun init(context: Context) {
        if (assets == null) assets = context.applicationContext.assets
    }

    fun face(id: Int): Typeface = synchronized(cache) {
        cache.getOrPut(id) {
            val file = files.getOrNull(id)
            val loaded = file?.let { runCatching { Typeface.createFromAsset(assets, "cardfonts/$it") }.getOrNull() }
            loaded ?: Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    // line height per font because the tall ones want to sit tight
    fun leading(id: Int): Float = when (id) {
        0 -> 1.06f
        1 -> 1.14f
        2 -> 1.32f
        3 -> 1.0f
        4 -> 0.98f
        else -> 1.1f
    }
}

// ---- the renderer ----

internal object CardRender {
    fun size(s: CardStyle, width: Int): Pair<Int, Int> = (width and 1.inv()) to ((width / s.ratio).toInt() and 1.inv())

    private fun luma(c: Int) = (AColor.red(c) * 0.299 + AColor.green(c) * 0.587 + AColor.blue(c) * 0.114) / 255.0

    private fun withAlpha(c: Int, a: Int) = AColor.argb(a, AColor.red(c), AColor.green(c), AColor.blue(c))

    private fun roundedClip(cv: Canvas, r: RectF, radius: Float) {
        cv.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
    }

    private fun drawCover(cv: Canvas, cover: Bitmap, r: RectF, radius: Float) {
        cv.save()
        roundedClip(cv, r, radius)
        cv.drawBitmap(cover, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
        cv.restore()
    }

    private fun text(u: Float, size: Float, face: Typeface, color: Int, shadow: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size * u
        this.color = color
        if (shadow) setShadowLayer(u * 0.8f, 0f, u * 0.3f, AColor.argb(100, 0, 0, 0))
    }

    // the flat colour a style paints by itself or null when the picture is the background
    fun backgroundOf(s: CardStyle, c: CardContent): Int? = when {
        s.theme == 1 -> 0xFFF3ECDD.toInt()
        s.theme == 2 -> c.accent
        s.theme == 3 -> 0xFF0D0D0F.toInt()
        else -> c.solid
    }

    fun overlay(s: CardStyle, c: CardContent, w: Int, h: Int): CardOverlay {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val u = w / 100f
        val pad = 8f * u
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val bg = backgroundOf(s, c)
        val light = bg != null && luma(bg) > 0.62
        val ink = if (light) 0xFF15140F.toInt() else AColor.WHITE
        val soft = withAlpha(ink, if (light) 150 else 175)
        val onPicture = bg == null
        // the colour the lyrics are written in
        val voice = when {
            s.marker -> AColor.BLACK
            s.tint == 1 && !light -> c.accent
            else -> ink
        }

        if (!light && s.vignette > 0f) {
            p.shader = RadialGradient(
                w / 2f, h / 2f, maxOf(w, h) * 0.75f,
                intArrayOf(0, AColor.argb((s.vignette * 210).toInt(), 0, 0, 0)), floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP,
            )
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }
        if (!light && s.scrim > 0f) {
            p.shader = LinearGradient(0f, h * 0.25f, 0f, h.toFloat(), 0, AColor.argb((s.scrim * 235).toInt(), 0, 0, 0), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }

        val thumb = 9.5f * u
        val footerH = if (s.footer) thumb else if (s.watermark) 3f * u else 0f
        val bottom = h - pad - footerH - (if (footerH > 0f) 4f * u else 0f)
        var top = pad

        // small spaced out song line at the very top
        if (s.tag) {
            val t = text(u, 2.8f, Typeface.create("sans-serif-medium", Typeface.BOLD), if (light) soft else withAlpha(ink, 205), false)
            t.letterSpacing = 0.14f
            val label = "${c.title}  ·  ${c.artist}".uppercase()
            cv.drawText(TextUtils.ellipsize(label, t, w - 2 * pad, TextUtils.TruncateAt.END).toString(), pad, top + 3f * u, t)
            top += 9f * u
        }

        // header row: small cover and the song and the artist the way genius opens its cards
        if (s.coverMode == 3) {
            val th = 11f * u
            var x = pad
            c.cover?.let {
                drawCover(cv, it, RectF(x, top, x + th, top + th), 2f * u)
                x += th + 3.2f * u
            }
            val maxW = w - x - pad
            val title = text(u, 4.2f, Typeface.create("sans-serif", Typeface.BOLD), ink, onPicture)
            val sub = text(u, 3.4f, Typeface.create("sans-serif-medium", Typeface.NORMAL), soft, false)
            cv.drawText(TextUtils.ellipsize(c.title, title, maxW, TextUtils.TruncateAt.END).toString(), x, top + th * 0.46f, title)
            cv.drawText(TextUtils.ellipsize(c.artist, sub, maxW, TextUtils.TruncateAt.END).toString(), x, top + th * 0.9f, sub)
            top += th + 7f * u
        }

        if (s.coverMode == 1 && c.cover != null) {
            val sz = w * 0.36f
            val left = (w - sz) / 2f
            val r = RectF(left, top + 2f * u, left + sz, top + 2f * u + sz)
            p.color = AColor.BLACK
            p.setShadowLayer(5f * u, 0f, 2.4f * u, AColor.argb(130, 0, 0, 0))
            cv.drawRoundRect(r, 3f * u, 3f * u, p)
            p.clearShadowLayer()
            drawCover(cv, c.cover, r, 3f * u)
            top = r.bottom + 5f * u
        }

        if (s.coverMode == 2) {
            val d = w * 0.44f
            val cx = w / 2f
            val cy = top + 2f * u + d / 2f
            p.color = 0xFF0E0E10.toInt()
            p.setShadowLayer(4f * u, 0f, 2f * u, AColor.argb(130, 0, 0, 0))
            cv.drawCircle(cx, cy, d / 2f, p)
            p.clearShadowLayer()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.22f * u
            p.color = AColor.argb(26, 255, 255, 255)
            var rad = d * 0.27f
            while (rad < d / 2f - 0.6f * u) {
                cv.drawCircle(cx, cy, rad, p)
                rad += 0.95f * u
            }
            p.style = Paint.Style.FILL
            p.shader = SweepGradient(cx, cy, intArrayOf(0, AColor.argb(46, 255, 255, 255), 0, AColor.argb(46, 255, 255, 255), 0), null)
            cv.drawCircle(cx, cy, d / 2f, p)
            p.shader = null
            val label = d * 0.2f
            if (c.cover != null) {
                cv.save()
                cv.clipPath(Path().apply { addCircle(cx, cy, label, Path.Direction.CW) })
                cv.drawBitmap(c.cover, null, RectF(cx - label, cy - label, cx + label, cy + label), Paint(Paint.FILTER_BITMAP_FLAG))
                cv.restore()
            }
            p.color = 0xFF0B0B0C.toInt()
            cv.drawCircle(cx, cy, d * 0.024f, p)
            top = cy + d / 2f + 6f * u
        }

        // short accent bar above the lyrics
        if (s.rule) {
            p.color = c.accent
            val x = if (s.center) (w - 12f * u) / 2f else pad
            cv.drawRoundRect(RectF(x, top, x + 12f * u, top + 1.1f * u), 0.55f * u, 0.55f * u, p)
            top += 6f * u
        }

        if (s.quote) {
            val q = text(u, 26f, CardFonts.face(1), if (light) withAlpha(ink, 90) else c.accent, false)
            if (s.center) q.textAlign = Paint.Align.CENTER
            cv.drawText("“", if (s.center) w / 2f else pad, top + 17f * u, q)
            top += 12f * u
        }

        val raw = c.lines.joinToString("\n")
        val body = if (s.upper) raw.uppercase() else raw
        val boxPad = if (s.panel) 5f * u else 0f
        val markerPad = if (s.marker) 1.6f * u else 0f
        val availW = (w - 2 * pad - 2 * boxPad - 2 * markerPad).toInt().coerceAtLeast(10)
        val regionH = (bottom - top - 2 * boxPad).coerceAtLeast(10f)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = CardFonts.face(s.font)
            color = voice
            letterSpacing = s.track
            when {
                s.glow -> setShadowLayer(u * 3.6f, 0f, 0f, c.accent)
                onPicture && !s.marker -> setShadowLayer(u * 1.2f, 0f, u * 0.4f, AColor.argb(110, 0, 0, 0))
            }
        }
        val leading = CardFonts.leading(s.font) + if (s.marker) 0.14f else 0f
        fun build(sz: Float): StaticLayout {
            tp.textSize = sz
            return StaticLayout.Builder.obtain(body, 0, body.length, tp, availW)
                .setAlignment(if (s.center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, leading)
                .setIncludePad(false)
                .build()
        }
        // the tall fonts are narrow so they start bigger
        var size = w * 0.085f * s.textScale * (if (s.font >= 3) 1.3f else 1f)
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
            p.color = when (s.markerColor) {
                1 -> c.accent
                2 -> 0xFFFFFF64.toInt()
                else -> AColor.WHITE
            }
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
        if (s.glow) layout.draw(cv)
        cv.restore()

        if (s.footer) {
            val y = h - pad - thumb
            var x = pad
            c.cover?.let {
                drawCover(cv, it, RectF(x, y, x + thumb, y + thumb), 2f * u)
                x += thumb + 3f * u
            }
            val maxW = w - x - pad - (if (s.watermark) 24f * u else 0f)
            val title = text(u, 3.9f, Typeface.create("sans-serif", Typeface.BOLD), ink, onPicture)
            val sub = text(u, 3.2f, Typeface.create("sans-serif-medium", Typeface.NORMAL), soft, false)
            cv.drawText(TextUtils.ellipsize(c.title, title, maxW, TextUtils.TruncateAt.END).toString(), x, y + thumb * 0.45f, title)
            cv.drawText(TextUtils.ellipsize(c.artist, sub, maxW, TextUtils.TruncateAt.END).toString(), x, y + thumb * 0.88f, sub)
        }
        if (s.watermark) {
            val wm = text(u, 2.8f, Typeface.create("sans-serif-medium", Typeface.NORMAL), withAlpha(ink, 135), false)
            wm.textAlign = Paint.Align.RIGHT
            cv.drawText("mochy music", w - pad, h - pad - (if (s.footer) thumb * 0.12f else 0f), wm)
        }
        return CardOverlay(bmp, panel, panelRadius, bg, if (s.theme == 3) c.accent else null)
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
        val flat = ov.bgColor
        if (flat != null) {
            // paper stays flat and the rest slant into a deeper shade of themselves
            val deep = if (s.theme == 1) flat else AColor.rgb((AColor.red(flat) * 0.6f).toInt(), (AColor.green(flat) * 0.6f).toInt(), (AColor.blue(flat) * 0.6f).toInt())
            val g = Paint().apply { shader = LinearGradient(0f, 0f, w * 0.45f, h.toFloat(), flat, deep, Shader.TileMode.CLAMP) }
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
            // the dark theme gets a soft light of the accent in its top corner
            ov.glowColor?.let { gc ->
                val gp = Paint().apply {
                    shader = RadialGradient(w * 0.95f, h * 0.02f, maxOf(w, h) * 0.85f, AColor.argb(95, AColor.red(gc), AColor.green(gc), AColor.blue(gc)), 0, Shader.TileMode.CLAMP)
                }
                cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gp)
            }
        } else if (raw == null) {
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
        if (flat == null && s.dim > 0f) cv.drawColor(AColor.argb((s.dim * 255).toInt(), 0, 0, 0))
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
// laid out like the edit player: styles swipe sideways and backgrounds sit on a strip below

private fun inter(size: TextUnit, weight: FontWeight = FontWeight.SemiBold) = TextStyle(
    fontFamily = InterFontFamily,
    fontSize = size,
    fontWeight = weight,
    letterSpacing = (-0.05).em,
)

@Composable
private fun CardLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text.uppercase(), color = InfoMuted, letterSpacing = 1.2.sp, style = inter(10.sp, FontWeight.Bold))
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
            Text(label, color = Color.White, style = inter(14.sp, FontWeight.Medium))
            Spacer(Modifier.weight(1f))
            Text(text, color = InfoMuted, style = inter(13.sp, FontWeight.Medium))
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
private fun ActionButton(text: String, icon: Int, filled: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = if (filled) Color.Black else Color.White
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (filled) Color.White.copy(alpha = if (enabled) 0.92f else 0.4f) else Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = ink, style = inter(14.sp, FontWeight.Bold))
    }
}

private fun Backdrop.tile(): String = when (this) {
    Backdrop.Cover -> "Cover"
    is Backdrop.Colour -> "Colour"
    is Backdrop.Photo -> source.substringBefore(" ·")
    is Backdrop.Motion -> source.substringBefore(" ·")
}

// a small muted loop that really plays inside the tile
// the video is stretched by the texture view so it is scaled back to its own shape and the tile crops it
@Composable
private fun LoopPreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember { TextureView(context) }
    DisposableEffect(url) {
        val player = ExoPlayer.Builder(context).build()
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setVideoTextureView(view)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                view.scaleX = if (aspect >= 1f) aspect else 1f
                view.scaleY = if (aspect >= 1f) 1f else 1f / aspect
            }
        })
        player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(canvasMimeType(url)).build())
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@Composable
private fun BackdropTile(item: Backdrop, selected: Boolean, coverUrl: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
        Box(
            Modifier
                .size(84.dp)
                .clip(shape)
                .background(MenuRowSurface)
                .border(if (selected) 2.dp else 1.dp, Color.White.copy(alpha = if (selected) 0.95f else 0.14f), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        ) {
            when (item) {
                Backdrop.Cover -> AsyncImage(model = coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                is Backdrop.Colour -> Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(item.argb), lerp(Color(item.argb), Color.Black, 0.5f)))),
                )
                is Backdrop.Photo -> AsyncImage(model = item.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                is Backdrop.Motion -> {
                    // the poster shows until the first video frame arrives
                    AsyncImage(model = item.poster ?: coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    LoopPreview(item.url, Modifier.fillMaxSize())
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("LOOP", color = Color.White, style = inter(8.sp, FontWeight.Bold))
                    }
                }
            }
        }
        Text(
            item.tile(),
            color = if (selected) Color.White else InfoMuted,
            style = inter(10.sp, FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

// the card stays on the song it was opened for even when the player moves on to the next one
@Composable
internal fun LyricCardEditor(song: MediaMetadata, lines: List<String>, onDismiss: () -> Unit) {
    val pinnedSong = remember { song }
    val pinnedLines = remember { lines }
    CardEditorBody(pinnedSong, pinnedLines, onDismiss)
}

@Composable
private fun CardEditorBody(song: MediaMetadata, lines: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    CardFonts.init(context)
    val artist = remember(song.id) { song.artists.joinToString(", ") { it.name } }

    var style by remember { mutableStateOf(CardPresets[0]) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var colours by remember { mutableStateOf<List<Int>>(emptyList()) }
    val found = remember { mutableStateListOf<Backdrop>() }
    var searching by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Backdrop>(Backdrop.Cover) }
    var source by remember { mutableStateOf<BgSource?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var useStill by remember { mutableStateOf(false) }
    var stillFrac by remember { mutableFloatStateOf(0.1f) }
    var startFrac by remember { mutableFloatStateOf(0f) }
    var clipSeconds by remember { mutableFloatStateOf(5f) }
    var kind by remember { mutableIntStateOf(2) }
    var fps by remember { mutableIntStateOf(50) }
    var exportWidth by remember { mutableIntStateOf(720) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var previewRaw by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }

    // the cover first and then everything else the app can find for this song and its artists
    LaunchedEffect(song.id) {
        val bmp = withContext(Dispatchers.IO) { loadCover(song.thumbnailUrl) }
        cover = bmp
        val hash = bmp?.let { withContext(Dispatchers.Default) { runCatching { pictureHash(it) }.getOrNull() } }
        if (bmp != null) colours = withContext(Dispatchers.Default) { runCatching { CardPalette.of(bmp) }.getOrDefault(emptyList()) }
        searching = true
        runCatching {
            CardBackdropFinder.discover(context.applicationContext, song, hash) { item -> if (found.none { it.id == item.id }) found.add(item) }
        }
        searching = false
    }
    DisposableEffect(Unit) {
        onDispose { source?.close() }
    }

    // the picked background turned into something the renderer reads frames from
    LaunchedEffect(picked, cover) {
        val choice = picked
        fun swap(next: BgSource?) {
            val old = source
            source = next
            if (old !== next) old?.close()
        }
        preparing = true
        hint = null
        useStill = false
        startFrac = 0f
        when (choice) {
            Backdrop.Cover -> swap(cover?.let { StillBg(it) })
            is Backdrop.Colour -> swap(null)
            is Backdrop.Photo -> {
                val bmp = loadCardBitmap(context, choice.url, 1600)
                if (bmp == null) hint = "Couldn't load that photo"
                swap((bmp ?: cover)?.let { StillBg(it) })
            }
            is Backdrop.Motion -> {
                // the poster shows at once and the loop takes over when its clip is ready
                val poster = (choice.poster?.let { loadCardBitmap(context, it, 1080) }) ?: cover
                swap(poster?.let { StillBg(it) })
                val file = CardClips.fetch(context, choice.url)
                val video = file?.let { f -> withContext(Dispatchers.IO) { runCatching { VideoBg(context, Uri.fromFile(f)) }.getOrNull() } }
                if (video != null && video.durationMs > 0L) {
                    swap(video)
                    kind = 2
                } else {
                    video?.close()
                    hint = "Couldn't load that loop, showing a still"
                }
            }
        }
        preparing = false
    }

    val solid = (picked as? Backdrop.Colour)?.argb
    val accent = remember(colours) { CardPalette.accent(colours) }
    val content = remember(lines, song.id, cover, accent, solid) { CardContent(lines, song.title, artist, cover, accent, solid) }
    val flat = style.theme != 0 || solid != null

    val bg = source
    val dur = bg?.durationMs ?: 0L
    val animated = dur > 0L && !flat
    val lenMs = minOf((clipSeconds * 1000).toLong(), dur)
    val startMs = ((dur - lenMs) * startFrac).toLong()
    val stillMs = (dur * stillFrac).toLong()
    val effKind = if (animated && !useStill) kind else 0

    // the raw background frames for the preview: low quality and about 10 per second
    LaunchedEffect(bg, flat, useStill, stillMs, startMs, lenMs) {
        delay(200)
        val src = bg
        if (src == null || flat) {
            previewRaw = emptyList()
            return@LaunchedEffect
        }
        previewRaw = withContext(Dispatchers.IO) {
            if (src.durationMs <= 0L) {
                listOfNotNull(runCatching { src.frame(0, 480) }.getOrNull())
            } else if (useStill) {
                listOfNotNull(runCatching { src.frame(stillMs, 480) }.getOrNull())
            } else {
                val n = (lenMs / 100L).toInt().coerceIn(1, 60)
                (0 until n).mapNotNull { runCatching { src.frame(startMs + it * lenMs / n, 480) }.getOrNull() }
            }
        }
    }
    LaunchedEffect(previewRaw) {
        tick = 0
        while (isActive && previewRaw.size > 1) {
            delay(100)
            tick++
        }
    }

    val pager = rememberPagerState(pageCount = { CardPresets.size })
    // a swipe that comes to rest picks that look and the sliders start from it again
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { style = CardPresets[it].copy(ratio = style.ratio) }
    }
    val pw = 480

    fun run(share: Boolean) {
        if (progress != null) return
        scope.launch {
            progress = 0f
            hint = null
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    CardExport.render(context, style, content, if (flat) null else bg, useStill, stillMs, startMs, lenMs, effKind, fps, exportWidth) { progress = it }
                }
            }
            progress = null
            result.onSuccess { out ->
                val uri = withContext(Dispatchers.IO) { CardExport.save(context, out) }
                if (uri == null) hint = "Couldn't save the file" else if (share) CardExport.share(context, uri, out.mime) else hint = "Saved to your gallery"
            }.onFailure { hint = "Failed: ${it.message}" }
        }
    }

    val strip = remember(found.size, colours) {
        (listOf<Backdrop>(Backdrop.Cover) + found.sortedBy { it.rank() } + colours.map { Backdrop.Colour(it, "Colour") }).distinctBy { it.id }
    }
    val previewH = (LocalConfiguration.current.screenHeightDp * 0.42f).dp

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0C)).statusBarsPadding().navigationBarsPadding()) {
            // top bar like the edit player
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 22.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onDismiss),
                ) {
                    Icon(painterResource(R.drawable.arrow_back_ios), contentDescription = "Back", tint = Color.White, modifier = Modifier.size(16.dp).offset(x = 2.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Lyric card", color = Color.White, style = inter(16.sp, FontWeight.Bold))
                    Text("Swipe the styles, pick a background", color = Color.White.copy(alpha = 0.6f), style = inter(11.sp, FontWeight.Medium))
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = 44.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier.fillMaxWidth().height(previewH),
                ) { page ->
                    val here = pager.settledPage == page
                    val pageStyle = if (here) style else CardPresets[page].copy(ratio = style.ratio)
                    val ph = (pw / pageStyle.ratio).toInt() and 1.inv()
                    val ov = remember(pageStyle, content) { CardRender.overlay(pageStyle, content, pw, ph) }
                    val pageFlat = pageStyle.theme != 0 || solid != null
                    val raw = if (pageFlat || previewRaw.isEmpty()) null else previewRaw[(if (here) tick else 0) % previewRaw.size]
                    val shown by produceState<Bitmap?>(null, ov, raw) {
                        value = withContext(Dispatchers.Default) { CardRender.frame(pageStyle, raw, ov, pw, ph) }
                    }
                    val away = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    BoxWithConstraints(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = 1f - 0.08f * away
                            scaleY = 1f - 0.08f * away
                            alpha = 1f - 0.45f * away
                        },
                    ) {
                        val wDp = minOf(maxWidth, maxHeight * pageStyle.ratio)
                        val frame = Modifier.width(wDp).aspectRatio(pageStyle.ratio).clip(RoundedCornerShape(16.dp))
                        val bmp = shown
                        if (bmp != null) Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = frame) else Box(frame.background(MenuRowSurface))
                    }
                }

                // style number and name sliding like the edit players
                AnimatedContent(
                    targetState = pager.currentPage,
                    transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
                    label = "cardStyleName",
                    modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 14.dp),
                ) { index ->
                    Column {
                        Text("STYLE ${index + 1} OF ${CardPresets.size}", color = Color.White.copy(alpha = 0.6f), style = inter(10.sp, FontWeight.SemiBold))
                        Text(CardPresets[index].name, color = Color.White, style = inter(20.sp, FontWeight.Bold))
                    }
                }

                // backgrounds found inside the app they turn up one by one while the search runs
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 10.dp)) {
                    Text("Background", color = Color.White, style = inter(14.sp, FontWeight.Bold))
                    Spacer(Modifier.weight(1f))
                    val status = when {
                        preparing && picked is Backdrop.Motion -> "Preparing the loop"
                        searching -> "Looking for more"
                        else -> "${strip.size} options"
                    }
                    Text(status, color = InfoMuted, style = inter(11.sp, FontWeight.Medium))
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 26.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(strip, key = { it.id }) { item ->
                        BackdropTile(item, selected = item.id == picked.id, coverUrl = song.thumbnailUrl?.resize(300, 300), onClick = { picked = item })
                    }
                }
                if (style.theme != 0) {
                    Text("This style paints its own background", color = InfoMuted, style = inter(12.sp, FontWeight.Medium), modifier = Modifier.padding(start = 26.dp, top = 10.dp))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(horizontal = 26.dp, vertical = 18.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { more = !more }
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                ) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.tune), null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(if (more) "Fewer options" else "More options", color = Color.White, style = inter(14.sp, FontWeight.SemiBold))
                }

                if (more) {
                    Column(Modifier.padding(horizontal = 26.dp)) {
                        if (animated) {
                            CardLabel("Motion")
                            ChipRow {
                                InfoPill(text = "Moving", filled = !useStill, onClick = { useStill = false })
                                InfoPill(text = "Still frame", filled = useStill, onClick = { useStill = true })
                            }
                            if (useStill) {
                                CardSlider("Frame", stillFrac, 0f..1f, "${String.format(Locale.US, "%.1f", stillFrac * dur / 1000f)} s") { stillFrac = it }
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

                        CardLabel("Cover")
                        ChipRow {
                            listOf("None", "Card", "Vinyl", "Header").forEachIndexed { i, name ->
                                InfoPill(text = name, filled = style.coverMode == i, onClick = { style = style.copy(coverMode = i) })
                            }
                        }

                        CardLabel("Text")
                        ChipRow {
                            InfoPill(text = "Left", filled = !style.center, onClick = { style = style.copy(center = false) })
                            InfoPill(text = "Center", filled = style.center, onClick = { style = style.copy(center = true) })
                            InfoPill(text = "Top", filled = style.vAlign == 0, onClick = { style = style.copy(vAlign = 0) })
                            InfoPill(text = "Middle", filled = style.vAlign == 1, onClick = { style = style.copy(vAlign = 1) })
                            InfoPill(text = "Bottom", filled = style.vAlign == 2, onClick = { style = style.copy(vAlign = 2) })
                        }
                        ChipRow {
                            listOf("Heavy", "Serif", "Mono", "Tall", "Display").forEachIndexed { i, name ->
                                InfoPill(text = name, filled = style.font == i, onClick = { style = style.copy(font = i) })
                            }
                        }
                        CardSlider("Size", style.textScale, 0.5f..1.6f, "${(style.textScale * 100).toInt()}") { style = style.copy(textScale = it) }
                        CardSlider("Spacing", style.track, -0.04f..0.2f, "${(style.track * 100).toInt()}") { style = style.copy(track = it) }
                        ChipRow {
                            InfoPill(text = "Marker", filled = style.marker, onClick = { style = style.copy(marker = !style.marker) })
                            InfoPill(text = "Glow", filled = style.glow, onClick = { style = style.copy(glow = !style.glow) })
                            InfoPill(text = "Caps", filled = style.upper, onClick = { style = style.copy(upper = !style.upper) })
                            InfoPill(text = "Quote mark", filled = style.quote, onClick = { style = style.copy(quote = !style.quote) })
                            InfoPill(text = "Glass", filled = style.panel, onClick = { style = style.copy(panel = !style.panel) })
                        }
                        ChipRow {
                            InfoPill(text = "Accent text", filled = style.tint == 1, onClick = { style = style.copy(tint = if (style.tint == 1) 0 else 1) })
                            InfoPill(text = "Top line", filled = style.tag, onClick = { style = style.copy(tag = !style.tag) })
                            InfoPill(text = "Accent bar", filled = style.rule, onClick = { style = style.copy(rule = !style.rule) })
                        }
                        if (style.marker) {
                            ChipRow {
                                listOf("White marker", "Accent marker", "Yellow marker").forEachIndexed { i, name ->
                                    InfoPill(text = name, filled = style.markerColor == i, onClick = { style = style.copy(markerColor = i) })
                                }
                            }
                        }
                        ChipRow {
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
                                InfoPill(text = "MP4", filled = kind == 2, onClick = { kind = 2 })
                                InfoPill(text = "GIF", filled = kind == 1, onClick = { kind = 1 })
                                InfoPill(text = "PNG still", filled = kind == 0, onClick = { kind = 0 })
                            }
                            if (effKind != 0) {
                                ChipRow {
                                    listOf(25, 30, 50, 60).forEach { v -> InfoPill(text = "$v fps", filled = fps == v, onClick = { fps = v }) }
                                }
                            }
                        } else {
                            Text("PNG", color = Color.White, style = inter(14.sp, FontWeight.Medium))
                        }
                        ChipRow {
                            listOf(540, 720, 1080).forEach { v -> InfoPill(text = "${v}px", filled = exportWidth == v, onClick = { exportWidth = v }) }
                        }
                        if (effKind == 1) {
                            Text("A gif has 256 colours and gets big at 50 fps. MP4 is smaller and much sharper.", color = InfoMuted, style = inter(12.sp, FontWeight.Medium), modifier = Modifier.padding(top = 4.dp))
                        }
                        ChipRow {
                            InfoPill(text = "Copy settings", filled = false, onClick = {
                                runCatching {
                                    val text = style.describe() + " backdrop=${picked.id} export=${listOf("png", "gif", "mp4")[effKind]} fps=$fps width=$exportWidth clip=${clipSeconds.roundToInt()}s"
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("card", text))
                                    hint = "Settings copied"
                                }
                            })
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                val note = progress?.let { "Rendering ${(it * 100).toInt()}%" } ?: hint
                if (note != null) Text(note, color = Color.White.copy(alpha = 0.75f), style = inter(12.sp, FontWeight.Medium), modifier = Modifier.padding(bottom = 8.dp, start = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Save", R.drawable.download, filled = true, enabled = progress == null, modifier = Modifier.weight(1f)) { run(false) }
                    ActionButton("Share", R.drawable.share, filled = false, enabled = progress == null, modifier = Modifier.weight(1f)) { run(true) }
                }
            }
        }
    }
}
