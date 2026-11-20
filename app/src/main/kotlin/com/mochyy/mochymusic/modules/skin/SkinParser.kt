// turns a manifest's players, font, glass, nav bar, pages and menu rows into a skin, every value checked and kept in range so a typo can't break anything

package com.mochyy.mochymusic.modules.skin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import java.io.File

internal object SkinParser {

    /** null when the manifest brings none of these, or none of them made sense */
    fun parse(
        moduleId: String,
        moduleName: String,
        player: JsonElement?,
        font: JsonElement?,
        pages: JsonElement?,
        files: File?,
        base: String?,
        actions: JsonElement? = null,
        glass: JsonElement? = null,
        players: JsonElement? = null,
        bar: JsonElement? = null,
        mini: JsonElement? = null,
    ): ModuleSkin? {
        val parsedPlayers = SkinPlayerParser.players(player, players, moduleName, fromZip = files != null)
        // a font has to be a file on the phone, so only a zip can bring one
        val parsedFont = (font as? JsonObject)?.let(::font)?.takeIf { files != null }
        val parsedPages = (pages as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> page(o, fromZip = files != null) } }.distinctBy { it.id }.take(MaxPages)
        val parsedActions = (actions as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::action) }.take(MaxActions)
        // a shader is code the gpu runs, so it only comes as a file in a zip, the same as a font
        val parsedGlass = (glass as? JsonObject)?.let(::glass)?.takeIf { files != null }
        val parsedBar = (bar as? JsonObject)?.let { SkinBarParser.bar(it, fromZip = files != null) }
        val parsedMini = (mini as? JsonObject)?.let { SkinPlayerParser.mini(it, moduleName, fromZip = files != null) }
        if (parsedPlayers.isEmpty() && parsedFont == null && parsedPages.isEmpty() && parsedActions.isEmpty() && parsedGlass == null && parsedBar == null && parsedMini == null) return null
        return ModuleSkin(moduleId, moduleName, parsedPlayers, parsedFont, parsedPages, files, base, parsedActions, parsedGlass, parsedBar, parsedMini)
    }

    // ---- the bottom bar's glass -----------------------------------------------------------------

    private fun glass(o: JsonObject): SkinGlass? {
        val shader = o.string("shader")?.takeIf { name -> ShaderKinds.any { name.endsWith(it, ignoreCase = true) } } ?: return null
        val change = glassChange(o)
        // a glass that draws its own edge can turn off mochy's hairline and shadow, or the bars get two edges
        return SkinGlass(shader, change.blurDp ?: 6f, change.marginDp ?: 16f, change.uniforms, outline = o.bool("outline") ?: true, shadow = o.bool("shadow") ?: true)
    }

    /** a blur, a margin and uniforms, as the glass and a setting's option both write them; mochy's own uniforms can't be set this way */
    fun glassChange(o: JsonObject): GlassChange = GlassChange(
        blurDp = o.float("blur")?.coerceIn(0f, 40f),
        marginDp = o.float("margin")?.coerceIn(0f, 64f),
        uniforms = shaderUniforms(o["uniforms"], MochyUniforms),
    )

    // "scope": "app" puts the font everywhere; left out, it stays on the module's player
    private fun font(o: JsonObject): SkinFont? = o.string("regular")?.let {
        SkinFont(regular = it, bold = o.string("bold"), wholeApp = o.string("scope")?.lowercase() == "app")
    }

    // ---- pages --------------------------------------------------------------------------------

    private fun page(o: JsonObject, fromZip: Boolean): SkinPage? {
        val id = o.string("id")?.lowercase()?.takeIf { PageId.matches(it) } ?: return null
        val blocks = pageBlocks(o["blocks"], fromZip, nested = false)
        if (blocks.isEmpty()) return null
        return SkinPage(
            id = id,
            title = o.string("title")?.take(60) ?: id,
            blocks = blocks,
            inSettings = o.bool("inSettings") ?: o.bool("settings") ?: false,
            subtitle = o.string("subtitle")?.take(80),
            icon = o.string("icon"),
            background = (o["background"] as? JsonObject)?.let { pageBackground(it, fromZip) },
            replaces = replaces(o["replaces"] ?: o["takeover"]),
            condition = o.string("when")?.let(SkinAnimationParser::condition),
            size = SkinSize.of(o),
            header = o.bool("header") ?: true,
        )
    }

    // "album" or ["local_playlist", "online_playlist"]: the names of mochy's pages this one opens in place of
    private fun replaces(element: JsonElement?): List<String> {
        val list = (element as? JsonArray)?.toList() ?: listOfNotNull(element)
        return list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { name -> PageName.matches(name) } }.distinct().take(MaxReplaces)
    }

    private fun pageBlocks(element: JsonElement?, fromZip: Boolean, nested: Boolean): List<PageBlock> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> pageBlock(o, fromZip, nested) } }.take(MaxPageBlocks)

    // { "type": "shader", "shader": "page.agsl", "fallback": ["#101020", "#000000"] } or an image or colours
    private fun pageBackground(o: JsonObject, fromZip: Boolean): PageBackground? {
        val colors = pageColors(o)
        return when (o.string("type")?.lowercase()) {
            "image" -> o.string("image")?.let {
                PageBackground.Picture(it, (o.float("blur") ?: 0f).coerceIn(0f, 60f), (o.float("dim") ?: 0.35f).coerceIn(0f, 0.9f))
            }
            "shader" -> pageShader(o, fromZip)?.let { PageBackground.Shader(it, colors.ifEmpty { listOf(SkinColor.Fixed(Color.Black)) }) }
                ?: colors.takeIf { it.isNotEmpty() }?.let(PageBackground::Fill)
            else -> colors.takeIf { it.isNotEmpty() }?.let(PageBackground::Fill)
        }
    }

    // "colors": [...] or one "color" (a shader's "fallback" reads the same way)
    private fun pageColors(o: JsonObject): List<SkinColor> {
        val list = (o["colors"] as? JsonArray) ?: (o["fallback"] as? JsonArray)
        return list?.mapNotNull { skinColor((it as? JsonPrimitive)?.content) }?.take(6)
            ?: listOfNotNull(skinColor(o.string("color") ?: o.string("fallback")))
    }

    // a shader is code the gpu runs, so it only comes as a file in a zip
    private fun pageShader(o: JsonObject, fromZip: Boolean): SkinShaderSource? {
        if (!fromZip) return null
        val file = o.string("shader")?.takeIf { name -> ShaderKinds.any { name.endsWith(it, ignoreCase = true) } } ?: return null
        return SkinShaderSource(file, shaderUniforms(o["uniforms"], PageUniforms))
    }

    // any block can carry a when that hides it while one of the module settings says no
    private fun pageBlock(o: JsonObject, fromZip: Boolean, nested: Boolean): PageBlock? {
        val block = pageBlockBody(o, fromZip, nested) ?: return null
        val condition = o.string("when")?.let(SkinAnimationParser::condition) ?: return block
        return PageBlock.When(condition, block)
    }

    private fun pageBlockBody(o: JsonObject, fromZip: Boolean, nested: Boolean): PageBlock? = when (val type = o.string("type")?.lowercase()) {
        "heading" -> o.string("text")?.take(120)?.let(PageBlock::Heading)
        "text" -> o.string("text")?.take(2000)?.let(PageBlock::Paragraph)
        "image" -> o.string("image")?.let {
            PageBlock.Picture(it, (o.float("height") ?: 160f).coerceIn(40f, 400f).dp, (o.float("corner") ?: 16f).coerceIn(0f, 80f).dp)
        }
        // only a web link: a page can send someone to a site, never into another app's scheme
        "button" -> {
            val link = o.string("link")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val text = o.string("text")?.take(60)
            if (link != null && text != null) PageBlock.Button(text, link) else null
        }
        "divider" -> PageBlock.Divider
        "space" -> PageBlock.Space((o.float("size") ?: 16f).coerceIn(0f, 120f).dp)
        "song" -> o.string("id")?.let { PageBlock.Song(it, o.string("title")?.take(80) ?: it, o.string("artist")?.take(80)) }
        "playlist" -> o.string("id")?.let { PageBlock.Playlist(it, o.string("title")?.take(80) ?: it, o.string("subtitle")?.take(80)) }
        "album" -> o.string("id")?.let { PageBlock.Album(it, o.string("title")?.take(80) ?: it, o.string("subtitle")?.take(80)) }
        "artist" -> o.string("id")?.let { PageBlock.Artist(it, o.string("title")?.take(80) ?: it) }
        "search" -> o.string("query")?.take(120)?.let { PageBlock.Search(it, o.string("title")?.take(80) ?: it) }
        "setting" -> o.string("key")?.take(60)?.let(PageBlock::Setting)
        // a card holds plain blocks, never another card
        "card", "box", "panel" -> if (nested) null else pageBlocks(o["blocks"], fromZip, nested = true).takeIf { it.isNotEmpty() }?.let { inside ->
            val glass = when (val g = o["glass"]) {
                is JsonPrimitive -> if (g.content.equals("true", ignoreCase = true)) DefaultCardGlass else g.content.toFloatOrNull()
                is JsonObject -> g.float("blur") ?: DefaultCardGlass
                else -> null
            }?.coerceIn(0f, 60f)
            PageBlock.Card(
                blocks = inside,
                colors = pageColors(o),
                glass = glass,
                corner = (o.float("corner") ?: 22f).coerceIn(0f, 80f).dp,
                padding = (o.float("padding") ?: 16f).coerceIn(0f, 60f).dp,
            )
        }
        "shader" -> pageShader(o, fromZip)?.let {
            PageBlock.Shader(
                program = it,
                height = (o.float("height") ?: 180f).coerceIn(24f, 800f).dp,
                corner = (o.float("corner") ?: 22f).coerceIn(0f, 120f).dp,
                fallback = skinColor(o.string("fallback")),
            )
        }
        "cover" -> PageBlock.Cover(
            height = (o.float("height") ?: 240f).coerceIn(40f, 600f).dp,
            corner = (o.float("corner") ?: 20f).coerceIn(0f, 300f).dp,
            circle = o.string("shape")?.lowercase() == "circle",
            source = when (o.string("source")?.lowercase()) {
                "banner" -> PageCoverSource.Banner
                "logo" -> PageCoverSource.Logo
                else -> PageCoverSource.Cover
            },
        )
        "albums" -> shelf(o, null, PageItemKind.Album, circle = false)
        "artists" -> shelf(o, null, PageItemKind.Artist, circle = true)
        "shelf", "section" -> shelf(o, o.string("section") ?: o.string("title"), null, circle = false)
        "bio", "about" -> PageBlock.Bio((o.float("lines")?.toInt() ?: 6).coerceIn(1, 40))
        // a row holds plain blocks and never another row
        "row", "columns" -> pageBlocks(o["blocks"], fromZip, nested).filter { it.unwrap() !is PageBlock.Row }.takeIf { it.isNotEmpty() }?.let { inside ->
            PageBlock.Row(inside, (o.float("gap") ?: 10f).coerceIn(0f, 40f).dp)
        }
        "songs", "tracks" -> PageBlock.Songs(
            limit = (o.float("limit")?.toInt() ?: MaxSongs).coerceIn(1, MaxSongs),
            numbers = o.bool("numbers") ?: false,
            covers = o.bool("covers") ?: true,
            corner = (o.float("corner") ?: 8f).coerceIn(0f, 30f).dp,
        )
        "play", "shuffle", "original", "use", "follow", "subscribe", "like", "radio", "share" -> control(o, type)
        "control" -> o.string("action")?.lowercase()?.let { control(o, it) }
        "player", "preview" -> PageBlock.Player(
            height = (o.float("height") ?: 420f).coerceIn(160f, 720f).dp,
            corner = (o.float("corner") ?: 28f).coerceIn(0f, 60f).dp,
        )
        "styles", "substyles" -> PageBlock.Styles
        else -> {
            // a block type nobody knows is dropped so it gets a log line to help whoever writes the module
            Timber.w("module page block type not known: %s", type)
            null
        }
    }

    // albums and artists pick their items by kind and a shelf picks a section by its title
    private fun shelf(o: JsonObject, title: String?, kind: PageItemKind?, circle: Boolean): PageBlock? {
        if (title == null && kind == null) return null
        return PageBlock.Shelf(
            title = title?.take(60),
            kind = kind,
            limit = (o.float("limit")?.toInt() ?: MaxShelf).coerceIn(1, MaxShelf),
            corner = (o.float("corner") ?: if (circle) 0f else 12f).coerceIn(0f, 80f).dp,
            circle = circle || o.string("shape")?.lowercase() == "circle",
            size = (o.float("size") ?: 120f).coerceIn(64f, 260f).dp,
        )
    }

    // a button that plays, shuffles, opens mochy's own page or sets the module's player, in the page's words or mochy's
    private fun control(o: JsonObject, action: String): PageBlock? {
        val kind = when (action) {
            "play" -> PageControl.Play
            "shuffle" -> PageControl.Shuffle
            "original", "mochy" -> PageControl.Original
            "use", "useplayer", "setplayer" -> PageControl.UsePlayer
            "follow", "subscribe" -> PageControl.Follow
            "like", "heart" -> PageControl.Like
            "radio" -> PageControl.Radio
            "share" -> PageControl.Share
            else -> return null
        }
        // play and follow start out filled since they are the main button of a page
        val main = kind == PageControl.Play || kind == PageControl.Follow
        return PageBlock.Control(o.string("text")?.take(40).orEmpty(), kind, filled = o.bool("filled") ?: main)
    }

    // ---- the player's menu ----------------------------------------------------------------------

    // a link only to the web, a page by its id, or a search; the song's {title}, {artist}, {album} and {id} are filled in when it's tapped
    private fun action(o: JsonObject): SkinAction? {
        val title = o.string("title")?.take(40) ?: return null
        return actionTarget(o)?.let { SkinAction(title, o.string("icon"), it) }
    }

    /** a "link" (only to the web, never another app's scheme), a "page" by its id, or a "search" */
    fun actionTarget(o: JsonObject): ActionTarget? =
        o.string("link")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(ActionTarget::Link)
            ?: o.string("page")?.lowercase()?.takeIf { PageId.matches(it) }?.let(ActionTarget::Page)
            ?: o.string("search")?.take(120)?.let(ActionTarget::Search)

    private val PageId = Regex("[a-z0-9_-]{1,40}")
    // a page's name as a route writes it, with no arguments: album, local_playlist, library/songs
    private val PageName = Regex("[a-z0-9_/]{1,60}")
    private const val MaxReplaces = 8
    private const val MaxSongs = 500
    private const val MaxShelf = 60
    private const val MaxPages = 12
    private const val MaxPageBlocks = 80
    private const val MaxActions = 6
    private val ShaderKinds = listOf(".agsl", ".sksl")
    private const val DefaultCardGlass = 24f
    // what mochy hands every page shader itself
    val PageUniforms = setOf("size", "time", "density", "touch", "pressed", "accent")
    // what mochy hands every glass shader itself
    val MochyUniforms = setOf("content", "size", "margin", "radius", "density", "quality", "chrome", "accent", "kind", "pill", "pillShown", "pillLift")
}
