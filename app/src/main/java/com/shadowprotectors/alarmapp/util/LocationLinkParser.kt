package com.shadowprotectors.alarmapp.util

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class ParsedLocation(val latitude: Double, val longitude: Double, val label: String? = null)

object LocationLinkParser {
    private const val NUMBER = "[+-]?\\d{1,3}(?:\\.\\d+)?"
    private val pair = Regex("(?<![\\w.+-])($NUMBER)(?:\\s*,\\s*|\\s+)($NUMBER)(?![\\w.])")
    private val place = Regex("!3d($NUMBER)!4d($NUMBER)")
    private val url = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

    suspend fun parse(input: String): ParsedLocation? = withContext(Dispatchers.IO) {
        val text = input.trim()
        if (text.isEmpty() || text.length > 16_384) return@withContext null
        if (text.startsWith("geo:", ignoreCase = true)) {
            val query = text.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("q=") }?.substringAfter('=')
            return@withContext if (query != null) coordinates(Uri.decode(query))
                else coordinates(text.substringAfter(':').substringBefore('?'))
        }
        val link = url.find(text)?.value ?: text.takeIf {
            it.startsWith("maps.app.goo.gl/") || it.startsWith("goo.gl/maps/")
        }?.let { "https://$it" }
        if (link != null) {
            val uri = Uri.parse(link)
            if (isShortLink(uri)) return@withContext expandShortUrl(link)?.let(::parseFromUrl)
            return@withContext parseFromUrl(link)
        }
        coordinates(text)
    }

    private fun coordinates(text: String): ParsedLocation? = pair.find(text)?.let(::validated)

    private fun validated(match: MatchResult): ParsedLocation? {
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0)
            ParsedLocation(lat, lng) else null
    }

    private fun parseFromUrl(value: String): ParsedLocation? {
        val uri = Uri.parse(value)
        // A destination takes precedence over the viewport or route origin.
        for (key in listOf("destination", "daddr", "q", "query", "ll")) {
            val parameter = uri.getQueryParameter(key) ?: continue
            return coordinates(parameter)
        }
        val decoded = Uri.decode(value)
        place.findAll(decoded).lastOrNull()?.let { return validated(it) }
        return Regex("@($NUMBER),($NUMBER)(?![\\d.])").find(decoded)?.let(::validated)
    }

    private fun isShortLink(uri: Uri): Boolean = uri.host == "maps.app.goo.gl" ||
        (uri.host == "goo.gl" && uri.path.orEmpty().startsWith("/maps"))

    internal fun isAllowedExpansionUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (isShortLink(uri) || uri.host in setOf("google.com", "www.google.com", "maps.google.com", "www.google.co.in", "maps.google.co.in"))
    }

    private fun expandShortUrl(value: String): String? {
        var current = value
        repeat(5) {
            if (!isAllowedExpansionUrl(current)) return null
            if (parseFromUrl(current) != null) return current
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "WayPoint/1.1")
            }
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return current
                val next = connection.getHeaderField("Location") ?: return null
                current = URL(URL(current), next).toString()
            } catch (_: java.io.IOException) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return current.takeIf { isAllowedExpansionUrl(it) }
    }
}
