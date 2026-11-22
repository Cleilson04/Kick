package com.kick

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@CloudstreamPlugin
class KickPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KickProvider())
    }
}

class KickProvider : MainAPI() {

    override var mainUrl = "https://kick.com"
    override var name = "Kick"
    override var lang = "pt"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // Seções da tela inicial. Formato: "tipo|categoria" to "Título"
    // tipo = live | featured. Para trocar categorias, use o slug da URL: kick.com/category/<slug>
    override val mainPage = mainPageOf(
        "live|all" to "🔴 Ao vivo agora",
        "featured|all" to "⭐ Destaques",
        "live|just-chatting" to "Just Chatting",
        "live|irl" to "IRL",
        "live|grand-theft-auto-v" to "GTA V",
        "live|valorant" to "Valorant",
        "live|counter-strike-2" to "Counter-Strike 2",
    )

    companion object {
        private const val FEED_LANG = "pt"
        private const val PAGE_SIZE = 24
        private const val BLOCKED_MSG =
            "A Kick bloqueou o acesso (Cloudflare). Tente de novo em instantes ou troque de rede/VPN."
    }

    private val om = ObjectMapper()
    private val cloudflare by lazy { CloudflareKiller() }

    private var xsrf: String? = null
    private var sessionCookies: Map<String, String> = emptyMap()
    private var sessionTried = false

    // ------------------------------------------------------------------ HTTP

    private suspend fun ensureSession() {
        if (sessionTried) return
        sessionTried = true
        try {
            val res = app.get(
                mainUrl,
                headers = mapOf("Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"),
                interceptor = cloudflare
            )
            sessionCookies = res.cookies
            xsrf = res.cookies["XSRF-TOKEN"]
        } catch (e: Exception) {
            // segue sem sessão; leitura pública normalmente não precisa
        }
    }

    private fun apiHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Referer" to "$mainUrl/"
        )
        val token = xsrf
        if (token != null) {
            h["Authorization"] = "Bearer $token"
            h["X-XSRF-TOKEN"] = token
        }
        return h
    }

    private suspend fun getJson(url: String, params: Map<String, String> = emptyMap()): JsonNode {
        ensureSession()
        val res = app.get(
            url,
            headers = apiHeaders(),
            params = params,
            cookies = sessionCookies,
            interceptor = cloudflare
        )
        val body = res.text.trim()
        if (body.isEmpty() || body.startsWith("<")) throw ErrorLoadingException(BLOCKED_MSG)
        return try {
            om.readTree(body)
        } catch (e: Exception) {
            throw ErrorLoadingException("Resposta inesperada da Kick.")
        }
    }

    // ------------------------------------------------------------ Home / busca

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val kind = request.data.substringBefore('|')
        val category = request.data.substringAfter('|', "all")

        val url = if (kind == "featured") {
            "$mainUrl/stream/featured-livestreams/$FEED_LANG"
        } else {
            "$mainUrl/stream/livestreams/$FEED_LANG"
        }
        val params = mutableMapOf(
            "page" to page.toString(),
            "limit" to PAGE_SIZE.toString(),
            "sort" to "desc"
        )
        if (kind == "live" && category != "all") params["subcategory"] = category

        val list = rootArray(getJson(url, params))
        val items = list.mapNotNull { toCard(it) }.distinctBy { it.url }
        val hasNext = kind == "live" && list.size >= PAGE_SIZE
        return HomePageResponse(listOf(HomePageList(request.name, items, true)), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()
        var failure: Exception? = null

        // 1) Nome exato de canal (funciona mesmo se a busca do site falhar)
        val guess = q.lowercase(Locale.ROOT).replace('_', '-').replace(" ", "")
        if (Regex("^[a-z0-9][a-z0-9-]{2,24}$").matches(guess)) {
            try {
                val ch = getJson("$mainUrl/api/v2/channels/$guess")
                toCard(ch)?.let { results[it.url] = it }
            } catch (e: Exception) {
                failure = e
            }
        }

        // 2) Busca do site
        try {
            val root = getJson("$mainUrl/api/search", mapOf("searched_word" to q))
            rootArray(root, "channels").mapNotNull { toCard(it) }
                .forEach { if (!results.containsKey(it.url)) results[it.url] = it }
        } catch (e: Exception) {
            failure = e
        }

        if (results.isEmpty() && failure != null) throw failure
        return results.values.toList()
    }

    private fun toCard(node: JsonNode): SearchResponse? {
        // No feed, cada item é uma transmissão com o canal em "channel".
        // Na busca e no /channels/{slug}, o próprio item é o canal.
        val channel = node.path("channel").let { if (it.isObject) it else node }
        val slug = channel.text("slug") ?: return null
        val live = node.path("livestream")

        val username = channel.text("user", "username") ?: channel.text("username") ?: slug
        val viewers = node.intOrNull("viewer_count")
            ?: live.intOrNull("viewer_count")
            ?: node.intOrNull("viewers")
        val isLive = viewers != null || live.isObject ||
            node.flag("is_live") || node.flag("isLive") ||
            channel.flag("is_live") || channel.flag("isLive")
        val poster = node.image("thumbnail") ?: live.image("thumbnail") ?: avatarOf(channel)

        val label = when {
            viewers != null -> "$username · 🔴 ${compact(viewers)}"
            isLive -> "$username · 🔴"
            else -> username
        }
        return newMovieSearchResponse(label, "$mainUrl/$slug", TvType.Live) {
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------------ Canal

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugOf(url) ?: throw ErrorLoadingException("Link de canal inválido")
        val ch = getJson("$mainUrl/api/v2/channels/$slug")
        val channelSlug = ch.text("slug") ?: throw ErrorLoadingException("Canal não encontrado na Kick")

        val username = ch.text("user", "username") ?: channelSlug
        val live = ch.path("livestream")
        val isLive = live.isObject
        val liveTitle = live.text("session_title")
        val liveThumb = live.image("thumbnail")
        val viewers = live.intOrNull("viewer_count")
        val avatar = avatarOf(ch)
        val banner = ch.image("banner_image") ?: ch.image("offline_banner_image")
        val followers = ch.longOrNull("followers_count") ?: ch.longOrNull("followersCount")
        val bio = ch.text("user", "bio")
        val categories = live.path("categories").mapNotNull { it.text("name") }

        val vods = try {
            loadVods(channelSlug)
        } catch (e: Exception) {
            emptyList()
        }

        val episodes = ArrayList<Episode>()
        if (isLive || vods.isEmpty()) {
            episodes.add(
                newEpisode("$mainUrl/$channelSlug") {
                    this.name = if (isLive) {
                        "🔴 AO VIVO" + (liveTitle?.let { " — $it" } ?: "")
                    } else {
                        "⚫ Offline (tentar ao vivo)"
                    }
                    this.posterUrl = liveThumb ?: avatar
                    this.description = viewers?.let { "${compact(it)} assistindo agora" }
                }
            )
        }
        episodes.addAll(vods)

        val plot = buildString {
            if (isLive) {
                append("🔴 AO VIVO")
                if (viewers != null) append(" · ${compact(viewers)} assistindo")
                if (liveTitle != null) append("\n$liveTitle")
                append("\n\n")
            }
            if (followers != null) append("${compact(followers)} seguidores\n\n")
            if (bio != null) append(bio)
        }.trim()

        return newTvSeriesLoadResponse(username, "$mainUrl/$channelSlug", TvType.TvSeries, episodes) {
            this.posterUrl = avatar ?: liveThumb
            this.backgroundPosterUrl = liveThumb ?: banner
            this.plot = if (plot.isBlank()) null else plot
            this.tags = if (categories.isEmpty()) null else categories
        }
    }

    private suspend fun loadVods(slug: String): List<Episode> {
        val root = getJson("$mainUrl/api/v2/channels/$slug/videos")
        return rootArray(root).mapNotNull { v ->
            val uuid = v.text("video", "uuid") ?: v.text("uuid") ?: return@mapNotNull null
            val title = v.text("session_title")
                ?: v.text("livestream", "session_title")
                ?: v.text("title")
                ?: "VOD"
            val thumb = v.image("thumbnail") ?: v.image("livestream", "thumbnail") ?: v.image("thumb")
            val created = v.text("created_at") ?: v.text("livestream", "created_at") ?: v.text("start_time")
            val durationMs = v.longOrNull("duration") ?: v.longOrNull("livestream", "duration")
            val views = v.intOrNull("views")

            newEpisode("$mainUrl/$slug/videos/$uuid") {
                this.name = title
                this.posterUrl = thumb
                this.date = parseDate(created)
                this.runTime = durationMs?.let { (it / 60000).toInt() }
                this.description = views?.let { "${compact(it)} visualizações" }
            }
        }
    }

    // ----------------------------------------------------------------- Player

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vodId = Regex(
            "/videos?/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
        ).find(data)?.groupValues?.get(1)

        val playlist: String?
        val label: String
        if (vodId != null) {
            val v = getJson("$mainUrl/api/v1/video/$vodId")
            playlist = v.text("source") ?: v.text("video", "source")
            label = "Kick · VOD"
        } else {
            val slug = slugOf(data) ?: return false
            val ch = getJson("$mainUrl/api/v2/channels/$slug")
            playlist = ch.text("playback_url") ?: ch.text("livestream", "playback_url")
            label = "Kick · Ao vivo"
        }
        if (playlist.isNullOrBlank()) return false

        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = playlist,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    // ---------------------------------------------------------------- Helpers

    private fun slugOf(url: String): String? =
        url.substringAfter("kick.com/", url)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
            .substringBefore('/')
            .takeIf { it.isNotBlank() }

    private fun rootArray(root: JsonNode, vararg keys: String): List<JsonNode> {
        if (root.isArray) return root.toList()
        for (k in keys) {
            val n = root.path(k)
            if (n.isArray) return n.toList()
        }
        val data = root.path("data")
        if (data.isArray) return data.toList()
        val inner = data.path("data")
        if (inner.isArray) return inner.toList()
        return emptyList()
    }

    private fun avatarOf(channel: JsonNode): String? =
        channel.image("user", "profile_pic")
            ?: channel.image("user", "profilepic")
            ?: channel.image("profile_pic")
            ?: channel.image("profilepic")

    private fun compact(n: Number): String {
        val v = n.toDouble()
        return when {
            v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000)
            v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1_000)
            else -> n.toLong().toString()
        }
    }

    private fun parseDate(value: String?): Long? {
        if (value == null || value.length < 19) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(value.take(19).replace(' ', 'T'))?.time
        } catch (e: Exception) {
            null
        }
    }
}

// ------------------------------------------------------------- JSON helpers

private fun JsonNode.dig(vararg path: String): JsonNode {
    var n: JsonNode = this
    for (p in path) n = n.path(p)
    return n
}

private fun JsonNode.text(vararg path: String): String? {
    val n = dig(*path)
    if (n.isMissingNode || n.isNull || n.isContainerNode) return null
    return n.asText().takeIf { it.isNotBlank() }
}

private fun JsonNode.intOrNull(vararg path: String): Int? {
    val n = dig(*path)
    return when {
        n.isNumber -> n.asInt()
        n.isTextual -> n.asText().toIntOrNull()
        else -> null
    }
}

private fun JsonNode.longOrNull(vararg path: String): Long? {
    val n = dig(*path)
    return when {
        n.isNumber -> n.asLong()
        n.isTextual -> n.asText().toLongOrNull()
        else -> null
    }
}

private fun JsonNode.flag(vararg path: String): Boolean = dig(*path).asBoolean(false)

// A imagem pode vir como texto (URL) ou como objeto {src, url}
private fun JsonNode.image(vararg path: String): String? {
    val n = dig(*path)
    return when {
        n.isTextual -> n.asText().takeIf { it.isNotBlank() }
        n.isObject -> n.text("src") ?: n.text("url") ?: n.text("original")
        else -> null
    }
}
